package dev.opencode.android.feature.execution

import dev.opencode.android.core.data.config.ConfigSchema
import dev.opencode.android.core.data.server.ServerDataSet
import dev.opencode.android.core.database.cache.ReadCacheStore
import dev.opencode.android.core.model.SessionInfo
import dev.opencode.android.core.model.SessionMessage
import dev.opencode.android.core.network.ServerApi
import dev.opencode.android.core.network.ServerApiFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.JsonObject
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A real [ServerApi] over a [MockWebServer], with the Phase 7 routes wired.
 *
 * **A fake server, not a fake interface.** Every claim [ExecutionOperationsTest] makes is about a wire
 * shape — the `location[directory]` every location-scoped route takes, the `cursor` that is a *string*,
 * the `force` on a `DELETE` with a body, the `x-opencode-ticket` header, the eleven experimental paths,
 * the bare array of `worktree.list` — and a mocked interface would agree with whatever the test was
 * written against. Here a request the app would not send cannot pass.
 */
class ExecutionServer(
    val directory: String = "/work/app",
) {
    val server: MockWebServer = MockWebServer().apply { start() }
    val baseUrl: String get() = server.url("/").toString().trimEnd('/')
    val api: ServerApi = ServerApiFactory(OkHttpClient()).createForReads(baseUrl)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * The read model over [api], for the view models that follow a set rather than call an api.
     *
     * The real one: a stand-in would hand a terminal list back at whatever moment the test asked, and the
     * moment the list catches up with the server is exactly what a terminal test is about.
     */
    val set: ServerDataSet = ServerDataSet(
        serverId = "srv",
        api = api,
        scope = scope,
        cache = NoCache,
        schema = ConfigSchema(JsonObject(emptyMap())),
    )

    /** What runs when a request arrives and before it is answered, keyed like [bodies]. */
    val beforeAnswer = mutableMapOf<String, () -> Unit>()

    /** The server's end of every WebSocket upgrade it accepted, so a test can speak as the terminal. */
    private val peers = mutableListOf<WebSocket>()

    private val recorded = mutableListOf<RecordedRequest>()
    private val bodiesSent = mutableListOf<String>()

    /** The body of every response, keyed by `METHOD path`, and a status per key. */
    val bodies = mutableMapOf<String, String>()
    val statuses = mutableMapOf<String, Int>()

    /** Requests the app made, in order, as `METHOD path?query`. */
    val requests: List<String>
        get() = synchronized(recorded) {
            recorded.map { "${it.method} ${it.url.encodedPath}?${it.url.query}" }
        }

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                synchronized(recorded) {
                    recorded += request
                    bodiesSent += request.body?.let { String(it.toByteArray(), Charsets.UTF_8) }.orEmpty()
                }
                val key = "${request.method} ${request.url.encodedPath}"
                // A PTY upgrade is accepted and left open: the request is recorded above, which is all
                // most tests need to know, and the peer is kept so one can send the terminal's frames.
                if (request.headers["Upgrade"].equals("websocket", ignoreCase = true)) {
                    return MockResponse.Builder().webSocketUpgrade(acceptor).build()
                }
                (beforeAnswer[key] ?: beforeAnswer[request.url.encodedPath])?.invoke()
                val body = bodies[key] ?: bodies[request.url.encodedPath] ?: "{}"
                val status = statuses[key] ?: statuses[request.url.encodedPath] ?: 200
                val builder = MockResponse.Builder().code(status)
                // A 204 carries no body, and OkHttp refuses one that says otherwise — so a fixture that
                // answers 204 with `{}` fails at the transport, not at the assertion, and says so.
                if (status == NO_BODY_STATUS) return builder.build()
                return builder.addHeader("Content-Type", "application/json").body(body).build()
            }
        }
    }

    fun answer(key: String, body: String, status: Int = 200) {
        bodies[key] = body
        statuses[key] = status
    }

    /** The last request whose path contains [fragment], which is how a query is asserted. */
    fun lastRequest(fragment: String): String? = requests.lastOrNull { it.contains(fragment) }

    /**
     * The last request *body* whose path contains [fragment], for the routes that take one.
     *
     * [method] narrows it to one verb: `/api/pty` is the path of the create and also the start of every
     * terminal's socket URL, and the socket's upgrade has no body to be mistaken for the create's.
     */
    fun lastBody(fragment: String, method: String? = null): String? = synchronized(recorded) {
        var index = recorded.size - 1
        while (index >= 0) {
            val request = recorded[index]
            if (request.url.encodedPath.contains(fragment) && (method == null || request.method == method)) {
                return bodiesSent[index]
            }
            index--
        }
        null
    }

    /** The value of a header on the last request whose path contains [fragment]. */
    fun lastHeader(fragment: String, name: String): String? {
        var index = recorded.size - 1
        while (index >= 0) {
            if (recorded[index].url.encodedPath.contains(fragment)) return recorded[index].headers[name]
            index--
        }
        return null
    }

    /** How many WebSocket upgrades the server has accepted. */
    val sockets: Int get() = synchronized(peers) { peers.size }

    /** Sends the control frame the server writes after a terminal's replay, to the newest socket. */
    fun sendCursor(cursor: Long) {
        val frame = byteArrayOf(0x00) + """{"cursor":$cursor}""".toByteArray()
        synchronized(peers) { peers.last() }.send(frame.toByteString())
    }

    /** Sends terminal output, as the text frame the server writes it in, to the newest socket. */
    fun sendOutput(text: String) {
        synchronized(peers) { peers.last() }.send(text)
    }

    private val acceptor = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            synchronized(peers) { peers += webSocket }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) = Unit
    }

    fun close() {
        // A socket left open makes `MockWebServer.close()` wait for a queue that never shuts down, which
        // hides the real failure behind a hang.
        synchronized(peers) { peers.forEach { it.close(1000, "test over") } }
        scope.cancel()
        server.close()
    }

    private companion object {
        const val NO_BODY_STATUS = 204
    }
}

/** The harnesses the operation tests use, so each test owns a server and closes it. */
abstract class ExecutionServerTest {

    private lateinit var harness: ExecutionServer

    protected val server: ExecutionServer
        get() = harness

    @Before
    fun startServer() {
        harness = ExecutionServer()
    }

    @After
    fun stopServer() {
        harness.close()
    }

    /**
     * Asserts that a request carried the location.
     *
     * Every location-scoped route takes `location[directory]` in its query, and a client that forgets
     * it is not answered with an error — it is answered about the *server's* working directory, which is
     * a different checkout and therefore a different set of commands. That is why this is asserted on
     * every one of them.
     */
    protected fun assertSentDirectory(request: String) {
        assertTrue("no location on $request", request.contains("location[directory]="))
    }
}

/** A cache that remembers nothing: these tests are about the network path and not the disk one. */
object NoCache : ReadCacheStore {
    override suspend fun readSessions(serverId: String, directory: String?, limit: Int): List<SessionInfo> =
        emptyList()

    override suspend fun writeSessions(serverId: String, directory: String?, sessions: List<SessionInfo>) = Unit

    override suspend fun readSession(serverId: String, sessionId: String): SessionInfo? = null

    override suspend fun writeSession(serverId: String, sessionId: String, session: SessionInfo) = Unit

    override suspend fun deleteSession(serverId: String, sessionId: String) = Unit

    override suspend fun readMessages(serverId: String, sessionId: String, limit: Int): List<SessionMessage> =
        emptyList()

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
