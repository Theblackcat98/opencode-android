package dev.opencode.android.core.data.integration

import dev.opencode.android.core.data.server.ServerDataSet
import dev.opencode.android.core.data.timeline.TimelineConvergence
import dev.opencode.android.core.data.timeline.TimelineReducer
import dev.opencode.android.core.data.timeline.TimelineState
import dev.opencode.android.core.database.cache.ReadCacheStore
import dev.opencode.android.core.model.SessionInfo
import dev.opencode.android.core.model.SessionMessage
import dev.opencode.android.core.model.event.Event
import dev.opencode.android.core.network.EventStreamClient
import dev.opencode.android.core.network.ServerApi
import dev.opencode.android.core.network.ServerApiFactory
import dev.opencode.android.core.testing.integration.DevServerHarness
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * The Phase 2 read path against a real `opencode serve` 2.0.18.
 *
 * The unit tests prove the stores behave; this proves the endpoints, their parameters and their
 * envelopes are what this client thinks they are. A decode that only ever runs against fixtures from
 * one recording can drift from a server that has moved, and a parameter the fixtures never exercised
 * can be wrong in a way no amount of replaying catches.
 *
 * It also reproduces exit criterion "a turn driven elsewhere streams on the phone with identical
 * content" as far as a headless run can: a turn is driven over REST, the app's own event stream
 * client is attached while it runs, the events are folded through `TimelineReducer`, and the result is
 * compared with the projection the server then returns.
 *
 * Every wait is bounded by `withTimeout`, so a server that never reaches idle fails here with a
 * readable message instead of hanging the build.
 *
 * Skipped when no server is configured, so it never fails a plain unit-test run.
 */
class LiveReadPathIntegrationTest {

    private lateinit var scope: CoroutineScope
    private lateinit var api: ServerApi
    private lateinit var serverUrl: String
    private lateinit var password: String
    private lateinit var directory: String

    @Before
    fun setUp() {
        assumeTrue(
            "No dev server configured. Start one with 'scripts/dev-server.sh start'.",
            DevServerHarness.isAvailable,
        )
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        serverUrl = DevServerHarness.url!!.trimEnd('/')
        password = DevServerHarness.password!!
        directory = DevServerHarness.directory ?: "."
        // The factory takes a credential provider, exactly as the app's own factory is built: the
        // password goes through the same interceptor a paired token would.
        api = ServerApiFactory(
            okHttpClient = OkHttpClient(),
            credentialProvider = { password },
        ).createForReads(serverUrl)
    }

    @After
    fun tearDown() {
        if (::scope.isInitialized) scope.cancel()
    }

    @Test
    fun `location, projects, agents and models decode from the live server`() = runBlocking {
        val location = api.getLocation(directory)
        assertEquals(directory, location.directory)
        assertTrue("the location must name its project", location.project.id.isNotBlank())

        val projects = api.listProjects()
        assertTrue("the server must report the working directory's project", projects.isNotEmpty())
        assertTrue("a project has a canonical directory", projects.any { it.canonical.isNotBlank() })

        val agents = api.listAgents(directory).data
        assertTrue("the built-in agents must be listed", agents.any { it.id == "build" })
        assertTrue("an agent names itself", agents.all { it.name.isNotBlank() })

        val models = api.listModels(directory).data
        assertTrue("the model catalog must not be empty", models.isNotEmpty())
        assertTrue(
            "every model has a context limit, saw ${models.filter { it.limit.context <= 0 }.map { it.id }}",
            models.all { it.limit.context > 0 },
        )

        val default = api.getDefaultModel(directory).data
        assertNotNull("a server with a configured model reports a default", default)
        val chosen = default!!
        assertTrue(
            "the default ${chosen.providerID}/${chosen.id} must be in the catalog, " +
                "which holds ${models.take(3).map { "${it.providerID}/${it.id}" }}",
            models.any { it.id == chosen.id && it.providerID == chosen.providerID },
        )
    }

    @Test
    fun `the session list pages, filters and reports what is active`() = runBlocking {
        val sessionID = driveTurn("list")

        val firstPage = api.listSessions(limit = 5, order = "desc")
        assertTrue(firstPage.data.isNotEmpty())
        assertTrue("the new session must be in the first page", firstPage.data.any { it.id == sessionID })
        assertNotNull("a paged list answers with a cursor", firstPage.cursor)

        val byId = api.getSession(sessionID).data
        assertEquals(sessionID, byId.id)
        // The server resolves a location to the project that contains it, so a session created
        // inside the harness's working directory reports the checkout above it, not the directory
        // the request named. What the client has to get right is that it carries a directory at all.
        assertTrue("a session names its location", byId.location.directory.isNotBlank())

        assertTrue(
            "the directory filter must find the session it created",
            api.listSessions(directory = byId.location.directory, limit = 100).data.any { it.id == sessionID },
        )
        assertTrue(api.listSessions(projectID = byId.projectID, limit = 100).data.any { it.id == sessionID })
        assertTrue(
            "a title search must find the new session",
            api.listSessions(search = "list", limit = 100).data.any { it.id == sessionID },
        )
        assertTrue("a fresh session has no children", api.listSessions(parentID = sessionID).data.isEmpty())
        assertTrue("a finished turn is not active", sessionID !in api.listActiveSessions().data.running)
    }

    @Test
    fun `a turn streams and the replayed timeline converges with the projection`() = runBlocking {
        val client = EventStreamClient(
            baseUrl = serverUrl,
            credentialProvider = { password },
            okHttpClient = DevServerHarness.streamingClient(),
        )
        client.start(scope)
        withTimeout(STREAM_TIMEOUT_MILLIS) {
            while (client.state.value !is dev.opencode.android.core.network.ConnectionState.Connected) delay(50)
        }

        val seen = mutableListOf<Event>()
        val collector = scope.launch { client.events.toList(seen) }
        withTimeout(STREAM_TIMEOUT_MILLIS) { delay(200) }

        val sessionID = driveTurn("stream")
        withTimeout(TURN_TIMEOUT_MILLIS) {
            while (seen.none { it.sessionID() == sessionID && it.type == "session.execution.succeeded" }) delay(50)
        }
        collector.cancel()
        client.stop()

        val sessionEvents = seen.filter { it.sessionID() == sessionID }
        assertTrue(
            "the turn must have streamed its step and its text, saw ${sessionEvents.map(Event::type)}",
            sessionEvents.any { it.type == "session.step.ended" } &&
                sessionEvents.any { it.type == "session.text.delta" },
        )

        val reduced = sessionEvents.fold(TimelineState.Empty) { state, event ->
            TimelineReducer.reduce(state, event, sessionID)
        }
        val projected = api.listMessages(sessionID, limit = 100, order = "desc").data.asReversed()
        val divergences = TimelineConvergence.compare(reduced.messages, projected)
        assertEquals(
            "a turn driven over REST must converge with the projection: $divergences",
            emptyList<Any>(),
            divergences.toList(),
        )
    }

    @Test
    fun `the inbox and a single message answer for a live session`() = runBlocking {
        val sessionID = driveTurn("inbox")

        val inbox = api.listInbox(sessionID).data
        inbox.forEach { item ->
            assertEquals(sessionID, item.sessionID)
            assertTrue("an inbox item has an id", item.id.isNotBlank())
        }
        assertTrue(
            "a delivered prompt is no longer pending",
            inbox.none { it.id == "msg_inbox_$suffix" },
        )

        val messages = api.listMessages(sessionID, limit = 10, order = "desc").data
        assertTrue(messages.isNotEmpty())
        val prompt = messages.filterIsInstance<SessionMessage.User>().first()
        assertEquals(prompt, api.getMessage(sessionID, prompt.id).data)
    }

    @Test
    fun `a store set reaches the same state the API returns`() = runBlocking {
        val sessionID = driveTurn("stores")
        val set = ServerDataSet("live", api, scope, NoCache)
        set.start()

        val session = withTimeout(STORE_TIMEOUT_MILLIS) { set.sessions.loadSession(sessionID) }
        assertEquals(sessionID, session?.id)
        assertTrue("a real session has a location", session!!.location.directory.isNotBlank())

        val timeline = set.timeline(sessionID)
        timeline.start()
        withTimeout(STORE_TIMEOUT_MILLIS) {
            while (timeline.state.value.messages.isEmpty()) delay(50)
        }
        val projected = api.listMessages(sessionID, limit = 100, order = "desc").data.asReversed()
        val divergences = TimelineConvergence.compare(timeline.state.value.messages, projected)
        assertEquals("the store must hold the server's projection: $divergences", emptyList<Any>(), divergences.toList())
        assertTrue("the self-check must be silent on a converged timeline", set.runSelfCheck().isEmpty())

        set.timeline(sessionID).loadMore()
        set.closeTimeline(sessionID)
        set.clear()
    }

    /** Creates a session, prompts it, and returns its id once the turn has finished. */
    private suspend fun driveTurn(name: String): String {
        val sessionID = "ses_${name}_$suffix"
        // The title carries the suffix so the search below has something unique to find: `search`
        // matches the title, not the id.
        val created = post("/api/session", """{"id":"$sessionID","title":"$name $suffix"}""")
        assertTrue("could not create $sessionID: $created", created.contains(sessionID))
        val prompted = post(
            "/api/session/$sessionID/prompt",
            """{"id":"msg_${name}_$suffix","text":"Say hello in a few words."}""",
        )
        assertTrue("could not prompt $sessionID: $prompted", prompted.contains("msg_"))
        withTimeout(TURN_TIMEOUT_MILLIS) {
            while (api.listMessages(sessionID, limit = 5, order = "desc").data.none { it is SessionMessage.Idle }) {
                delay(200)
            }
        }
        return sessionID
    }

    private fun post(path: String, body: String): String {
        val url = DevServerHarness.request(path).url
        val request = Request.Builder()
            .url(url)
            .header("Authorization", Credentials.basic("opencode", password))
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        return DevServerHarness.client().newCall(request).execute().use { it.body.string() }
    }

    private fun Event.sessionID(): String? =
        (payload as? dev.opencode.android.core.model.event.EventPayload.SessionScoped)?.sessionID

    private val suffix: String get() = java.lang.Long.toString(java.lang.System.currentTimeMillis(), 36)

    private companion object {
        const val TURN_TIMEOUT_MILLIS = 180_000L
        const val STREAM_TIMEOUT_MILLIS = 60_000L
        const val STORE_TIMEOUT_MILLIS = 60_000L

        /** The integration test only needs the reads, so nothing is written to the cache. */
        val NoCache: ReadCacheStore = object : ReadCacheStore {
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
    }
}
