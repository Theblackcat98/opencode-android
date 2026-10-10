package dev.opencode.android.feature.insights

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
import java.util.concurrent.ConcurrentHashMap

/**
 * A real [ServerApi] and [ServerDataSet] over a [MockWebServer], for the usage view model.
 *
 * **A fake server, not a fake interface.** The claim these tests make is about the query the dashboard sends
 * (`project=<id>` or none) and about which answer comes back for it, and a mocked `ServerApi` would agree with
 * whatever the test was written against.
 *
 * **The dispatcher answers only what it is told to.** A route the app calls that the test did not register comes
 * back as `404`, so a test cannot pass by calling something it never wired.
 */
class InsightsServer {
    val server: MockWebServer = MockWebServer().apply { start() }
    private val baseUrl: String get() = server.url("/").toString().trimEnd('/')
    val api: ServerApi = ServerApiFactory(OkHttpClient()).createForReads(baseUrl)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** The read model over [api], which is what the view model follows. */
    val set: ServerDataSet = ServerDataSet(
        serverId = "srv",
        api = api,
        scope = scope,
        cache = NoCache,
        schema = ConfigSchema(JsonObject(emptyMap())),
    )

    private val recorded = mutableListOf<RecordedRequest>()
    private val replies = ConcurrentHashMap<String, (RecordedRequest) -> String>()

    /** Requests the app made, in order, as `METHOD path?query`. */
    val requests: List<String>
        get() = synchronized(recorded) {
            recorded.map { "${it.method} ${it.url.encodedPath}?${it.url.query.orEmpty()}" }
        }

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                synchronized(recorded) { recorded += request }
                val reply = replies["${request.method} ${request.url.encodedPath}"]
                    ?: return MockResponse.Builder().code(NOT_FOUND).build()
                return MockResponse.Builder()
                    .code(OK)
                    .addHeader("Content-Type", "application/json")
                    .body(reply(request))
                    .build()
            }
        }
    }

    /** Answers one method and path with a fixed body. */
    fun answer(key: String, body: String) {
        replies[key] = { body }
    }

    /** Answers one method and path with a body worked out from the request, such as one that depends on its query. */
    fun answer(key: String, reply: (RecordedRequest) -> String) {
        replies[key] = reply
    }

    fun close() {
        scope.cancel()
        server.close()
    }

    private companion object {
        const val OK = 200
        const val NOT_FOUND = 404
    }
}

/** A cache that remembers nothing: these tests are about the network path and not the disk one. */
internal object NoCache : ReadCacheStore {
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
