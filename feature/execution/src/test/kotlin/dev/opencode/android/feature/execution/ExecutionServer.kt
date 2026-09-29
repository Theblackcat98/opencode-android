package dev.opencode.android.feature.execution

import dev.opencode.android.core.network.ServerApi
import dev.opencode.android.core.network.ServerApiFactory
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest

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

    /** The last request *body* whose path contains [fragment], for the routes that take one. */
    fun lastBody(fragment: String): String? = synchronized(bodiesSent) {
        var index = recorded.size - 1
        while (index >= 0) {
            if (recorded[index].url.encodedPath.contains(fragment)) return bodiesSent[index]
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

    fun close() {
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
