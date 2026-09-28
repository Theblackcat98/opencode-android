package dev.opencode.android.core.data.server

import dev.opencode.android.core.model.event.Event
import dev.opencode.android.core.model.event.EventPayload
import dev.opencode.android.core.model.event.SessionCreated
import dev.opencode.android.core.model.event.SessionDeleted
import dev.opencode.android.core.model.event.SessionExecutionStarted
import dev.opencode.android.core.model.event.SessionExecutionSucceeded
import dev.opencode.android.core.model.event.SessionIdle
import dev.opencode.android.core.model.event.SessionRenamed
import dev.opencode.android.core.model.event.SessionStatusUpdated
import dev.opencode.android.core.model.event.SessionUsageUpdated
import dev.opencode.android.core.model.SessionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The session list over a real HTTP server: paging, filtering, the badges, and the live events.
 *
 * Every wait is bounded by `runTest`'s virtual clock and by an assertion on the requests the fake
 * server saw, so a store that silently stops calling it fails here instead of hanging.
 */
class SessionStoreTest {

    private lateinit var server: FakeServer
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        server = FakeServer()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.close()
    }

    /**
     * A store on a real dispatcher, because the API under test is a real HTTP call.
     *
     * The waits are therefore wall-clock and bounded, and every one of them fails with the
     * server's request log: a store that silently stops calling the server must produce a
     * readable failure, not a build that hangs.
     */
    private fun newStore(pageSize: Int = SessionStore.DEFAULT_PAGE_SIZE): SessionStore =
        SessionStore("server-1", server.api, scope, FakeReadCacheStore(), pageSize = pageSize)

    /** Waits until the server has seen no new request for [quietMillis]. */
    private suspend fun awaitQuiescent(quietMillis: Long = 250L) {
        withTimeout(QUIESCE_TIMEOUT_MILLIS) {
            var seen = -1
            while (true) {
                val now = server.requestCount()
                if (now == seen) {
                    delay(quietMillis)
                    if (server.requestCount() == now) return@withTimeout
                } else {
                    seen = now
                    delay(quietMillis)
                }
            }
        }
    }

    /**
     * Waits for a state the store derives asynchronously to settle.
     *
     * Bounded, and the failure carries the request log, which is the only way to tell "the store
     * did not apply the event" from "the store applied it and the flow has not caught up".
     */
    private suspend fun awaitState(reason: String, predicate: () -> Boolean) {
        try {
            withTimeout(STATE_TIMEOUT_MILLIS) {
                while (!predicate()) delay(10)
            }
        } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
            throw quiesceFailure(reason)
        }
    }

    private fun quiesceFailure(reason: String): AssertionError = AssertionError(
        "$reason\nrequests seen by the fake server:\n" +
            server.paths().joinToString("\n") { "  $it" }.ifEmpty { "  (none)" },
    )

    @Test
    fun `pages with a cursor and stops at the end`() = runBlocking {
        val store = newStore(pageSize = 2)
        server.sessions = (1..5).map { sessionFixture("ses_$it", title = "session $it", updated = it * 10L) }
        store.start()
        awaitQuiescent()

        assertEquals(listOf("ses_5", "ses_4"), store.visibleRows.value.map { it.id })
        assertTrue(store.paging.value.hasMore)

        store.loadMore()
        awaitQuiescent()
        assertEquals(listOf("ses_5", "ses_4", "ses_3", "ses_2"), store.visibleRows.value.map { it.id })

        store.loadMore()
        awaitQuiescent()
        assertEquals(5, store.visibleRows.value.size)
        assertFalse("the server has no more pages", store.paging.value.hasMore)

        // A further request must not be made once the cursor is exhausted.
        val before = server.paths().size
        store.loadMore()
        awaitQuiescent()
        assertEquals(before, server.paths().size)
    }

    @Test
    fun `rows are ordered by the server's update time, newest first`() = runBlocking {
        val store = newStore()
        server.sessions = listOf(
            sessionFixture("ses_old", title = "old", updated = 10L),
            sessionFixture("ses_new", title = "new", updated = 99L),
        )
        store.start()
        awaitQuiescent()
        assertEquals(listOf("ses_new", "ses_old"), store.visibleRows.value.map { it.id })
    }

    @Test
    fun `the roots only filter hides child sessions and counts them`() = runBlocking {
        val store = newStore()
        server.sessions = listOf(
            sessionFixture("ses_root", title = "root"),
            sessionFixture("ses_child", title = "child", parentID = "ses_root"),
            sessionFixture("ses_child2", title = "child two", parentID = "ses_root"),
        )
        store.start()
        awaitQuiescent()

        assertEquals(listOf("ses_root"), store.visibleRows.value.map { it.id })
        assertEquals(2, store.visibleRows.value.single().childCount)

        store.setFilter(SessionFilter.Everything)
        awaitQuiescent()
        assertEquals(setOf("ses_root", "ses_child", "ses_child2"), store.visibleRows.value.map { it.id }.toSet())
    }

    @Test
    fun `search and project filters reach the server`() = runBlocking {
        val store = newStore()
        server.sessions = listOf(
            sessionFixture("ses_a", title = "alpha", projectID = "p1"),
            sessionFixture("ses_b", title = "beta", projectID = "p2"),
        )
        store.setFilter(SessionFilter(search = "alph"))
        store.start()
        awaitQuiescent()
        assertEquals(listOf("ses_a"), store.visibleRows.value.map { it.id })

        store.setFilter(SessionFilter(projectId = "p2"))
        awaitQuiescent()
        assertEquals(listOf("ses_b"), store.visibleRows.value.map { it.id })
    }

    @Test
    fun `unread means the session went idle after the last view`() = runBlocking {
        val store = newStore()
        server.sessions = listOf(
            sessionFixture("ses_unread", title = "unread", idle = 200L, viewed = 100L),
            sessionFixture("ses_read", title = "read", idle = 200L, viewed = 300L),
            sessionFixture("ses_running_turn", title = "busy", idle = null),
        )
        store.start()
        awaitQuiescent()
        val rows = store.visibleRows.value.associateBy { it.id }
        assertTrue(rows.getValue("ses_unread").isUnread)
        assertFalse(rows.getValue("ses_read").isUnread)
        assertFalse(rows.getValue("ses_running_turn").isUnread)
    }

    @Test
    fun `a created session appears without a fetch and a deleted one disappears`() = runBlocking {
        val store = newStore()
        server.sessions = emptyList()
        store.start()
        awaitQuiescent()
        val before = server.paths().size

        store.apply(
            createdEvent(
                id = "ses_new",
                title = "made on the desktop",
                created = 5_000L,
            ),
        )
        awaitState("the created session never reached the list") { store.visibleRows.value.isNotEmpty() }
        assertEquals(listOf("ses_new"), store.visibleRows.value.map { it.id })
        assertEquals("made on the desktop", store.visibleRows.value.single().title)

        store.apply(Event.decode(deletedEventJson("ses_new")))
        awaitState("the deleted session never left the list") { store.visibleRows.value.isEmpty() }
        assertTrue("no fetch for a create or a delete", server.paths().size == before)
    }

    @Test
    fun `a rename updates the row in place`() = runBlocking {
        val store = newStore()
        server.sessions = listOf(sessionFixture("ses_a", title = "before", updated = 10L))
        store.start()
        awaitQuiescent()
        assertTrue(store.apply(Event.decode("""{"id":"evt_1","created":60,"type":"session.renamed","data":{"sessionID":"ses_a","title":"after"}}""")))
        awaitState("the renamed title never reached the list") {
            store.visibleRows.value.singleOrNull()?.title == "after"
        }
        assertEquals("after", store.visibleRows.value.single().title)
        assertEquals("an event must not move a session's clock backwards", 60L, store.visibleRows.value.single().updated)
        assertFalse("nothing changed, so nothing is reported", store.apply(Event.decode("""{"id":"evt_2","created":60,"type":"session.renamed","data":{"sessionID":"ses_a","title":"after"}}""")))
    }

    @Test
    fun `usage, cost and the running badge follow the events`() = runBlocking {
        val store = newStore()
        server.sessions = listOf(sessionFixture("ses_a"))
        store.start()
        awaitQuiescent()

        store.apply(Event.decode("""{"id":"evt_1","created":70,"type":"session.usage.updated","data":{"sessionID":"ses_a","cost":1.25,"tokens":{"input":10,"output":5,"reasoning":0,"cache":{"read":0,"write":0}}}}"""))
        awaitState("the usage update never reached the list") { store.visibleRows.value.single().cost == 1.25 }
        val row = store.visibleRows.value.single()
        assertEquals(1.25, row.cost, 0.0001)
        assertEquals(15L, row.tokens)

        store.apply(Event.decode("""{"id":"evt_2","created":71,"type":"session.execution.started","data":{"sessionID":"ses_a"}}"""))
        awaitState("the running badge never appeared") { store.visibleRows.value.single().isRunning }

        store.apply(Event.decode("""{"id":"evt_3","created":72,"type":"session.execution.succeeded","data":{"sessionID":"ses_a"}}"""))
        awaitState("the finished turn never settled on the row") {
            val row = store.visibleRows.value.single()
            !row.isRunning && row.session.outcome == dev.opencode.android.core.model.Outcome.Succeeded
        }
        val finished = store.visibleRows.value.single()
        assertFalse(finished.isRunning)
        assertEquals(dev.opencode.android.core.model.Outcome.Succeeded, finished.session.outcome)
    }

    @Test
    fun `a retry status becomes a retrying badge with its countdown`() = runBlocking {
        val store = newStore()
        server.sessions = listOf(sessionFixture("ses_a"))
        store.start()
        awaitQuiescent()
        store.apply(
            Event.decode(
                """
                {"id":"evt_1","created":80,"type":"session.status","data":{"sessionID":"ses_a","status":{"type":"retry","attempt":2,"message":"rate limited","next":999}}}
                """.trimIndent(),
            ),
        )
        awaitState("the retrying badge never appeared") { store.visibleRows.value.single().isRetrying }
        val row = store.visibleRows.value.single()
        assertTrue(row.isRetrying)
        val retrying = row.activity as SessionActivity.Retrying
        assertEquals(2, retrying.attempt)
        assertEquals(999L, retrying.next)
        assertEquals("rate limited", retrying.message)
    }

    @Test
    fun `the active session list overwrites the running flags on a resync`() = runBlocking {
        val store = newStore()
        server.sessions = listOf(sessionFixture("ses_a"), sessionFixture("ses_b"))
        store.start()
        awaitQuiescent()
        store.apply(Event.decode("""{"id":"evt_1","created":90,"type":"session.execution.started","data":{"sessionID":"ses_a"}}"""))
        awaitState("the running badge never appeared") { store.visibleRows.value.first { it.id == "ses_a" }.isRunning }

        server.running = mutableSetOf("ses_b")
        store.refreshActive()
        awaitQuiescent()
        awaitState("session.active was not applied") {
            store.visibleRows.value.first { it.id == "ses_b" }.isRunning &&
                !store.visibleRows.value.first { it.id == "ses_a" }.isRunning
        }

        val rows = store.visibleRows.value.associateBy { it.id }
        assertFalse("the server says ses_a is not running", rows.getValue("ses_a").isRunning)
        assertTrue(rows.getValue("ses_b").isRunning)
        assertTrue(server.paths().any { it.startsWith("/api/session/active") })
    }

    @Test
    fun `an idle message stamps the unread marker`() = runBlocking {
        val store = newStore()
        server.sessions = listOf(sessionFixture("ses_a", viewed = 10L))
        store.start()
        awaitQuiescent()
        assertFalse(store.visibleRows.value.single().isUnread)
        store.apply(Event.decode("""{"id":"evt_1","created":30,"type":"session.idle","data":{"sessionID":"ses_a"}}"""))
        awaitState("the idle marker never made the row unread") { store.visibleRows.value.single().isUnread }
    }

    @Test
    fun `an event for an unknown session is ignored rather than creating a row`() = runBlocking {
        val store = newStore()
        server.sessions = emptyList()
        store.start()
        awaitQuiescent()
        assertFalse(store.apply(Event.decode("""{"id":"evt_1","created":30,"type":"session.renamed","data":{"sessionID":"ses_ghost","title":"x"}}""")))
        assertTrue(store.visibleRows.value.isEmpty())
    }

    private fun createdEvent(id: String, title: String, created: Long): Event = Event.decode(
        """
        {"id":"evt_created","created":$created,"type":"session.created","data":{
          "sessionID":"$id","projectID":"project-1","location":{"directory":"/work"},"slug":"x",
          "title":"$title","version":"2.0.18"}}
        """.trimIndent(),
    )

    private fun deletedEventJson(id: String) =
        """{"id":"evt_deleted","created":${id.length},"type":"session.deleted","data":{"sessionID":"$id"}}"""
}

private const val QUIESCE_TIMEOUT_MILLIS = 10_000L
private const val STATE_TIMEOUT_MILLIS = 5_000L
