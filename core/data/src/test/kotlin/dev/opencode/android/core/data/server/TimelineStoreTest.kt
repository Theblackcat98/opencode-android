package dev.opencode.android.core.data.server

import dev.opencode.android.core.data.server.TimelineSelfCheck
import dev.opencode.android.core.data.timeline.TimelineDivergence
import dev.opencode.android.core.data.timeline.TimelineState
import dev.opencode.android.core.model.SessionMessage
import dev.opencode.android.core.model.event.Event
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The timeline store: cache first, the server's page second, and the event stream on top.
 *
 * The properties that matter are all about ordering and replacement, so the tests drive the store
 * the way the app does and check what a user would see, not what a method returned.
 */
class TimelineStoreTest {

    private val server = FakeServer()
    private val cache = FakeReadCacheStore()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val reported = mutableListOf<Pair<String, List<TimelineDivergence>>>()

    private val selfCheck = object : TimelineSelfCheck {
        override fun report(sessionID: String, divergences: List<TimelineDivergence>) {
            reported += sessionID to divergences
        }
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.close()
    }

    @Test
    fun `the server page arrives newest first and is held oldest first`() = runBlocking {
        server.messagePage = listOf(
            userMessage("msg_2", "second"),
            userMessage("msg_1", "first"),
        )
        val store = newStore()
        store.start()
        await("the timeline did not settle as expected") { store.state.value.messages.size == 2 }

        assertEquals(listOf("msg_1", "msg_2"), store.state.value.messages.map { it.id })
    }

    @Test
    fun `a cached window is shown before the server answers`() = runBlocking {
        cache.writeMessages("server-1", "ses_a", listOf(userMessage("msg_cached", "from the cache")))
        server.messagePage = listOf(userMessage("msg_fresh", "from the server"))
        val store = newStore()
        store.start()
        // The cache is populated synchronously before the request returns, so the window it holds
        // is what a user sees while offline.
        await("the cached window never appeared") { store.state.value.messages.any { it.id == "msg_cached" } }
        await("the server page never arrived") { store.state.value.messages.any { it.id == "msg_fresh" } }
        await("the server page never replaced the cache") {
            store.state.value.messages.size == 1 && store.state.value.messages[0].id == "msg_fresh"
        }
        assertEquals(
            "the server's page replaces the cache",
            listOf("msg_fresh"),
            store.state.value.messages.map {
                it.id
            },
        )
    }

    @Test
    fun `load older prepends the next page and stops when the cursor runs out`() = runBlocking {
        // The server answers newest first; the store holds oldest first.
        server.messagePage = listOf(userMessage("msg_4"), userMessage("msg_3"))
        server.messageCursorNext = "next"
        val store = newStore()
        store.start()
        await("the first page never arrived") { store.state.value.messages.size == 2 }
        await("the store never learned there is an older page") { store.paging.value.hasMore }

        server.messagePage = listOf(userMessage("msg_2"), userMessage("msg_1"))
        server.messageCursorNext = null
        store.loadMore()
        await("the older page was never prepended") { store.state.value.messages.size == 4 }

        assertEquals(listOf("msg_1", "msg_2", "msg_3", "msg_4"), store.state.value.messages.map { it.id })
        assertTrue("the server has no more pages", !store.paging.value.hasMore)
    }

    @Test
    fun `a page that overlaps what is held adds nothing`() = runBlocking {
        server.messagePage = listOf(userMessage("msg_3"), userMessage("msg_2"))
        server.messageCursorNext = "next"
        val store = newStore()
        store.start()
        await("the first page never arrived") { store.state.value.messages.size == 2 }
        await("the store never learned there is an older page") { store.paging.value.hasMore }

        server.messagePage = listOf(userMessage("msg_2"), userMessage("msg_1"))
        server.messageCursorNext = null
        store.loadMore()
        await("the timeline did not settle as expected") { store.state.value.messages.size == 3 }
        assertEquals(listOf("msg_1", "msg_2", "msg_3"), store.state.value.messages.map { it.id })
    }

    @Test
    fun `an event is folded onto the projection`() = runBlocking {
        server.messagePage = listOf(userMessage("msg_1", "hello"))
        val store = newStore()
        store.start()
        await("the first page never arrived") { store.state.value.messages.size == 1 }

        val applied = store.apply(
            Event.decode(
                """
                {"id":"evt_1","created":20,"type":"session.step.started","data":{
                  "sessionID":"ses_a","assistantMessageID":"msg_a","agent":"build",
                  "model":{"id":"m","providerID":"p"},"started":20}}
                """.trimIndent(),
            ),
        )
        assertTrue("a step must reach the timeline", applied)
        assertEquals(2, store.state.value.messages.size)
        assertTrue(store.state.value.activeAssistant != null)
    }

    @Test
    fun `an event for another session is ignored`() = runBlocking {
        server.messagePage = listOf(userMessage("msg_1"))
        val store = newStore()
        store.start()
        await("the timeline did not settle as expected") { store.state.value.messages.size == 1 }
        val before = store.state.value

        assertTrue(
            !store.apply(
                Event.decode(
                    """{"id":"evt_1","created":20,"type":"session.step.started","data":{"sessionID":"ses_other","assistantMessageID":"msg_a","agent":"b","model":{"id":"m","providerID":"p"},"started":20}}""",
                ),
            ),
        )
        assertEquals(before, store.state.value)
    }

    @Test
    fun `a finished turn triggers the self-check and a match reports nothing`() = runBlocking {
        // The server's final projection of this turn: the prompt and the idle marker the events
        // will add. The check must find nothing.
        server.messagePage = listOf(
            idleMessage("msg_idle", created = 30),
            userMessage("msg_1", "hello"),
        )
        val store = newStore()
        store.start()
        await("the first page never arrived") { store.state.value.messages.size == 2 }

        store.apply(
            Event.decode(
                """{"id":"evt_idle","created":30,"type":"session.execution.succeeded","data":{"sessionID":"ses_a"}}""",
            ),
        )
        await("the timeline did not settle as expected") {
            store.state.value.messages.any { it is SessionMessage.Idle }
        }
        delay(300)

        assertTrue("a converged timeline must report nothing, saw $reported", reported.isEmpty())
    }

    @Test
    fun `the self-check reports a divergence with the server projection`() = runBlocking {
        // The server projects a message the events never produced.
        server.messagePage = listOf(
            idleMessage("msg_idle", created = 30),
            userMessage("msg_1", "hello"),
        )
        val store = newStore()
        store.start()
        await("the first page never arrived") { store.state.value.messages.size == 2 }
        store.apply(
            Event.decode(
                """{"id":"evt_idle","created":30,"type":"session.execution.succeeded","data":{"sessionID":"ses_a"}}""",
            ),
        )
        await("the timeline did not settle as expected") {
            store.state.value.messages.any { it is SessionMessage.Idle }
        }

        // A divergence the reducer really did miss: the server knows a message this client never saw.
        server.messagePage = listOf(
            idleMessage("msg_idle", created = 30),
            userMessage("msg_1", "hello"),
            userMessage("msg_server_only"),
        )
        assertTrue(store.verifyAgainstServer().isNotEmpty())
        assertTrue("the finding must be reported, saw $reported", reported.isNotEmpty())
    }

    @Test
    fun `resync replaces the timeline rather than merging it`() = runBlocking {
        server.messagePage = listOf(userMessage("msg_3"), userMessage("msg_2"))
        val store = newStore()
        store.start()
        await("the first page never arrived") { store.state.value.messages.size == 2 }
        assertTrue(
            store.apply(
                Event.decode(
                    """{"id":"evt_synthetic","created":9,"type":"session.synthetic","data":{"sessionID":"ses_a","text":"local"}}""",
                ),
            ),
        )

        server.messagePage = listOf(userMessage("msg_3"), userMessage("msg_2"))
        store.resync()
        await("the timeline did not settle as expected") {
            store.state.value.messages.none { it is SessionMessage.Synthetic }
        }
        assertEquals(
            "a resync is the server's projection, not a merge with a guess",
            listOf("msg_2", "msg_3"),
            store.state.value.messages.map { it.id },
        )
    }

    @Test
    fun `a cleared timeline forgets the cursor`() = runBlocking {
        server.messagePage = listOf(userMessage("msg_1"))
        server.messageCursorNext = "next"
        val store = newStore()
        store.start()
        await("the first page never arrived") { store.state.value.messages.size == 1 }
        await("the store never learned there is an older page") { store.paging.value.hasMore }
        store.clear()
        assertEquals(TimelineState.Empty, store.state.value)
        assertTrue(!store.paging.value.hasMore)
    }

    /** An idle marker as the server projects it at the end of a turn. */
    private fun idleMessage(id: String, created: Long) = SessionMessage.Idle(
        id = id,
        time = SessionMessage.CreatedTime(created),
        outcome = dev.opencode.android.core.model.Outcome.Succeeded,
    )

    private fun newStore() = TimelineStore(
        serverId = "server-1",
        sessionID = "ses_a",
        api = server.api,
        scope = scope,
        cache = cache,
        selfCheck = selfCheck,
    )

    private suspend fun await(reason: String, condition: () -> Boolean) {
        try {
            withTimeout(5_000) {
                while (!condition()) delay(10)
            }
        } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("timed out: $reason\nrequests:\n${server.paths().joinToString("\n")}")
        }
    }
}
