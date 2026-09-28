package dev.opencode.android.feature.review

import dev.opencode.android.core.network.ServerApi
import dev.opencode.android.core.network.ServerApiFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient

/**
 * A real [ServerApi] over a [MockWebServer], with the Phase 6 routes wired.
 *
 * **A fake server, not a fake interface.** The claims these tests make are about wire shapes — the
 * `mode` and `base` of `vcs.diff`, the `sanitize` string of the export, the `files: true` of a stage
 * — and a mocked interface would agree with whatever the test was written against. Here a request
 * that the app would not send cannot pass.
 */
class ReviewServer(
    val directory: String = ReviewFixtures.DIRECTORY,
) {
    val server: MockWebServer = MockWebServer().apply { start() }
    val baseUrl: String get() = server.url("/").toString().trimEnd('/')
    val api: ServerApi = ServerApiFactory(OkHttpClient()).createForReads(baseUrl)

    private val recorded = mutableListOf<RecordedRequest>()
    private val bodiesSent = mutableListOf<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    /** The body of every response, keyed by `METHOD path`, and a status per key. */
    val bodies = mutableMapOf<String, String>()
    val statuses = mutableMapOf<String, Int>()

    /** Requests the app made, in order, so a test can assert on the query it sent. */
    val requests: List<String> get() = synchronized(recorded) { recorded.map { "${it.method} ${it.url.encodedPath}?${it.url.query}" } }

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
                return MockResponse.Builder()
                    .code(status)
                    .addHeader("Content-Type", "application/json")
                    .body(body)
                    .build()
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

    /**
     * The `Content-Type` of the last request whose path contains [fragment].
     *
     * `experimental.fs.write` answers `415` for `text/plain` and `200` for
     * `application/octet-stream` (verified live against 2.0.18), so the media type a write sends is
     * a property of the request rather than of a mock, and it has to be assertable.
     */
    fun lastMediaType(fragment: String): String? {
        var index = recorded.size - 1
        while (index >= 0) {
            if (recorded[index].url.encodedPath.contains(fragment)) {
                return recorded[index].headers["Content-Type"]
            }
            index--
        }
        return null
    }

    fun close() {
        server.close()
    }
}
