package dev.opencode.android.feature.review

import dev.opencode.android.core.data.config.ConfigSchema
import dev.opencode.android.core.data.server.ServerDataSet
import dev.opencode.android.core.database.cache.ReadCacheStore
import dev.opencode.android.core.model.LocationPublicRef
import dev.opencode.android.core.model.SessionInfo
import dev.opencode.android.core.model.SessionMessage
import dev.opencode.android.core.model.TokenUsage
import dev.opencode.android.core.model.json.OpenCodeJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** The history panel follows one session at a time, even while another session's timeline changes. */
@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModelTest {

    private val scheduler = TestCoroutineScheduler()
    private lateinit var server: ReviewServer
    private lateinit var scope: CoroutineScope
    private lateinit var set: ServerDataSet
    private lateinit var viewModel: HistoryViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
        server = ReviewServer()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        set = ServerDataSet(
            serverId = "server-1",
            api = server.api,
            scope = scope,
            cache = EmptyReadCache,
            schema = ConfigSchema(JsonObject(emptyMap())),
        )
        viewModel = HistoryViewModel(MutableStateFlow(set))
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `a switched-away session cannot publish later timeline updates or search results`() = runBlocking {
        server.answer("GET /api/session/ses_a", sessionResponse("ses_a"))
        server.answer("GET /api/session/ses_b", sessionResponse("ses_b"))
        server.answer("GET /api/session/ses_a/message", messagePage("msg_a", "Prompt from A"))
        server.answer("GET /api/session/ses_b/message", messagePage("msg_b", "Prompt from B"))

        val timelineA = set.timeline("ses_a")
        val timelineB = set.timeline("ses_b")
        timelineA.start()
        timelineB.start()
        settle("the two source timelines") {
            promptText(timelineA.state.value.messages) == "Prompt from A" &&
                promptText(timelineB.state.value.messages) == "Prompt from B"
        }

        viewModel.open("ses_a")
        settle("session A's history") {
            viewModel.state.value.prompts == listOf(HistoryPrompt("msg_a", "Prompt from A"))
        }
        viewModel.search("Prompt from A")
        settle("session A's search result") { viewModel.state.value.hits.singleOrNull()?.id == "msg_a" }

        viewModel.open("ses_b")
        settle("session B's history") {
            viewModel.state.value.prompts == listOf(HistoryPrompt("msg_b", "Prompt from B"))
        }
        assertEquals("switching sessions clears the previous search", "", viewModel.state.value.search)
        assertEquals(emptyList<HistoryHit>(), viewModel.state.value.hits)

        // A late resync is an emission from the old session's live StateFlow. It must not replace B's
        // prompt list, even though both timelines belong to the same server data set.
        server.answer("GET /api/session/ses_a/message", messagePage("msg_a2", "Late prompt from A"))
        timelineA.resync()
        settle("session A's late timeline update") {
            promptText(timelineA.state.value.messages) == "Late prompt from A"
        }
        scheduler.advanceUntilIdle()

        assertEquals(
            listOf(HistoryPrompt("msg_b", "Prompt from B")),
            viewModel.state.value.prompts,
        )
        assertEquals("the selected history remains session B", "ses_b", viewModel.state.value.sessionID)
    }

    private fun promptText(messages: List<SessionMessage>): String? =
        messages.filterIsInstance<SessionMessage.User>().singleOrNull()?.text

    private fun settle(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TIMEOUT_MILLIS * NANOS_PER_MILLI
        while (true) {
            scheduler.advanceUntilIdle()
            if (condition()) return
            if (System.nanoTime() > deadline) {
                throw AssertionError(
                    "Timed out after ${TIMEOUT_MILLIS}ms waiting for $what.\n" +
                        "History: ${viewModel.state.value}\nRequests: ${server.requests}",
                )
            }
            Thread.sleep(POLL_MILLIS)
        }
    }

    private fun sessionResponse(id: String): String {
        val session = SessionInfo(
            id = id,
            projectID = "project-1",
            cost = 0.0,
            tokens = TokenUsage.Zero,
            time = SessionInfo.Time(created = 1, updated = 2),
            location = LocationPublicRef(ReviewFixtures.DIRECTORY),
        )
        return """{"data":${OpenCodeJson.encodeToString(SessionInfo.serializer(), session)}}"""
    }

    private fun messagePage(id: String, text: String): String =
        """{"data":[{"id":"$id","type":"user","time":{"created":1},"text":"$text"}],"cursor":{"previous":null,"next":null}}"""

    private object EmptyReadCache : ReadCacheStore {
        override suspend fun readSessions(serverId: String, directory: String?, limit: Int): List<SessionInfo> = emptyList()

        override suspend fun writeSessions(serverId: String, directory: String?, sessions: List<SessionInfo>) = Unit

        override suspend fun readSession(serverId: String, sessionId: String): SessionInfo? = null

        override suspend fun writeSession(serverId: String, sessionId: String, session: SessionInfo) = Unit

        override suspend fun deleteSession(serverId: String, sessionId: String) = Unit

        override suspend fun readMessages(serverId: String, sessionId: String, limit: Int): List<SessionMessage> = emptyList()

        override suspend fun writeMessages(
            serverId: String,
            sessionId: String,
            messages: List<SessionMessage>,
            keep: Int,
        ) = Unit

        override suspend fun deleteMessages(serverId: String, sessionId: String) = Unit

        override suspend fun dropLocation(serverId: String, directory: String?) = Unit

        override suspend fun dropServer(serverId: String) = Unit
    }

    private companion object {
        const val TIMEOUT_MILLIS = 10_000L
        const val POLL_MILLIS = 5L
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
