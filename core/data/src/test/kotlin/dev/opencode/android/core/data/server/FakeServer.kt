package dev.opencode.android.core.data.server

import dev.opencode.android.core.database.cache.ReadCacheStore
import dev.opencode.android.core.model.ActiveSessionMap
import dev.opencode.android.core.model.LocationInfo
import dev.opencode.android.core.model.LocationPublicRef
import dev.opencode.android.core.model.Paged
import dev.opencode.android.core.model.Project
import dev.opencode.android.core.model.SessionActive
import dev.opencode.android.core.model.SessionInfo
import dev.opencode.android.core.model.SessionMessage
import dev.opencode.android.core.model.TokenUsage
import dev.opencode.android.core.model.json.OpenCodeJson
import dev.opencode.android.core.network.ServerApi
import dev.opencode.android.core.network.ServerApiFactory
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient

/**
 * A real [ServerApi] over a [MockWebServer], so cursors, ordering and the location parameter are
 * exercised the way the app uses them instead of through a mock of the interface.
 */
class FakeServer(
    val serverId: String = "server-1",
    val directory: String = "/work",
) {
    val server: MockWebServer = MockWebServer().apply { start() }
    val baseUrl: String get() = server.url("/").toString().trimEnd('/')
    val api: ServerApi = ServerApiFactory(OkHttpClient()).createForReads(baseUrl)

    private val recorded = mutableListOf<RecordedRequest>()

    var running: MutableSet<String> = mutableSetOf()
    var projects: List<Project> = listOf(
        Project(
            id = "project-1",
            canonical = "/work",
            vcs = "git",
            time = Project.Time(created = 1, updated = 2, active = 3),
            sandboxes = emptyList(),
        ),
    )
    var sessions: List<SessionInfo> = emptyList()
    var messagePage: List<SessionMessage> = emptyList()
    var messageCursorNext: String? = null

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                synchronized(recorded) { recorded.add(request) }
                val path = request.url.encodedPath.let { path ->
                    if (request.url.querySize == 0) path else "$path?${request.url.query}"
                }
                val body = when {
                    path.startsWith("/api/session/active") -> dataResponse(runningMap())

                    path.endsWith("/message") || path.contains("/message?") -> encode(
                        Paged.serializer(SessionMessage.serializer()),
                        Paged(messagePage, Paged.Cursor(null, messageCursorNext)),
                    )

                    path.startsWith("/api/session?") -> sessionsPage(path)

                    path.matches(Regex("/api/session/ses_[^/?]+")) -> dataResponse(
                        encodeJson(
                            SessionInfo.serializer(),
                            sessions.first { it.id == path.substringAfterLast('/') },
                        ),
                    )

                    path.startsWith("/api/project") -> encode(
                        kotlinx.serialization.builtins.ListSerializer(Project.serializer()),
                        projects,
                    )

                    path.startsWith("/api/location") -> encode(
                        LocationInfo.serializer(),
                        LocationInfo(
                            directory = directory,
                            project = LocationInfo.Project("project-1", directory, directory),
                        ),
                    )

                    else -> return notFound()
                }
                return MockResponse.Builder()
                    .code(200)
                    .addHeader("Content-Type", "application/json")
                    .body(body)
                    .build()
            }
        }
    }

    /** The paths of every request the server has seen, in order. */
    fun paths(): List<String> = synchronized(recorded) { recorded.map { it.url.encodedPath } }

    fun requestCount(): Int = synchronized(recorded) { recorded.size }

    fun close() = server.close()

    private fun sessionsPage(path: String): String {
        val limit = query(path, "limit")?.toIntOrNull() ?: 50
        val offset = query(path, "cursor")?.toIntOrNull() ?: 0
        val directoryFilter = query(path, "directory")
        val projectFilter = query(path, "project")
        val search = query(path, "search")
        // `order=desc` is the server's newest-first ordering, which is what a cursor walk needs.
        val matching = sessions.filter { session ->
            (directoryFilter == null || session.location.directory == directoryFilter) &&
                (projectFilter == null || session.projectID == projectFilter) &&
                (search == null || session.title?.contains(search, ignoreCase = true) == true)
        }.sortedByDescending { it.time.updated }
        val page = matching.drop(offset).take(limit)
        val nextOffset = offset + page.size
        return encode(
            Paged.serializer(SessionInfo.serializer()),
            Paged(page, Paged.Cursor(null, if (nextOffset < matching.size) nextOffset.toString() else null)),
        )
    }

    private fun query(path: String, name: String): String? = path.substringAfter('?', "")
        .split('&')
        .firstOrNull { it.substringBefore('=') == name }
        ?.substringAfter('=', "")
        ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
        ?.takeIf { it.isNotEmpty() }

    private fun notFound() = MockResponse.Builder()
        .code(404)
        .addHeader("Content-Type", "application/json")
        .body("""{"_tag":"session.not_found","message":"no such session"}""")
        .build()
}

private fun <T> encode(serializer: KSerializer<T>, value: T): String = OpenCodeJson.encodeToString(serializer, value)

private fun <T> encodeJson(serializer: KSerializer<T>, value: T): JsonElement =
    OpenCodeJson.encodeToJsonElement(serializer, value)

private fun dataResponse(element: JsonElement): String = buildJsonObject { put("data", element) }.toString()

/** `session.active` only needs the `type`, so the map is built from the ids directly. */
private fun FakeServer.runningMap(): JsonElement = buildJsonObject {
    running.sorted().forEach { id ->
        put(
            id,
            OpenCodeJson.encodeToJsonElement(SessionActive.serializer(), SessionActive(SessionActive.RUNNING)),
        )
    }
}

/** A session the fake serves, in the shape a `session.list` page carries. */
fun sessionFixture(
    id: String,
    title: String? = null,
    parentID: String? = null,
    projectID: String = "project-1",
    directory: String = "/work",
    updated: Long = 1_000L,
    cost: Double = 0.0,
    agent: String? = null,
    idle: Long? = null,
    viewed: Long? = null,
    model: dev.opencode.android.core.model.ModelRef? = null,
): SessionInfo = SessionInfo(
    id = id,
    parentID = parentID,
    projectID = projectID,
    agent = agent,
    model = model,
    cost = cost,
    tokens = TokenUsage.Zero,
    time = SessionInfo.Time(created = 0L, updated = updated, idle = idle, viewed = viewed),
    title = title,
    location = LocationPublicRef(directory),
)

/** A user message, the cheapest message shape for store tests. */
fun userMessage(id: String, text: String = "hello", created: Long = 0L): SessionMessage = SessionMessage.User(
    id = id,
    time = SessionMessage.CreatedTime(created),
    text = text,
)

/** An in-memory [ReadCacheStore] that records what it was asked to keep. */
class FakeReadCacheStore : ReadCacheStore {
    private val sessionsByScope = mutableMapOf<String, MutableMap<String, SessionInfo>>()
    private val messagesBySession = mutableMapOf<String, MutableList<SessionMessage>>()

    var sessionWrites = 0
        private set
    var messageWrites = 0
        private set
    val droppedLocations = mutableListOf<String>()

    override suspend fun readSessions(
        serverId: String,
        directory: String?,
        limit: Int,
    ): List<SessionInfo> = sessionsByScope[key(serverId, directory)].orEmpty().values
        .sortedByDescending { it.time.updated }
        .take(limit)

    override suspend fun writeSessions(
        serverId: String,
        directory: String?,
        sessions: List<SessionInfo>,
    ) {
        sessionWrites++
        val scope = sessionsByScope.getOrPut(key(serverId, directory)) { mutableMapOf() }
        sessions.forEach { scope[it.id] = it }
    }

    override suspend fun readSession(serverId: String, sessionId: String): SessionInfo? =
        sessionsByScope.values.firstNotNullOfOrNull { it[sessionId] }

    override suspend fun writeSession(serverId: String, sessionId: String, session: SessionInfo) {
        sessionWrites++
        sessionsByScope.getOrPut(key(serverId, session.location.directory)) { mutableMapOf() }[sessionId] = session
    }

    override suspend fun deleteSession(serverId: String, sessionId: String) {
        sessionsByScope.values.forEach { it.remove(sessionId) }
        messagesBySession.remove(key(serverId, sessionId))
    }

    override suspend fun readMessages(
        serverId: String,
        sessionId: String,
        limit: Int,
    ): List<SessionMessage> = messagesBySession[key(serverId, sessionId)].orEmpty().takeLast(limit)

    override suspend fun writeMessages(
        serverId: String,
        sessionId: String,
        messages: List<SessionMessage>,
        keep: Int,
    ) {
        messageWrites++
        messagesBySession[key(serverId, sessionId)] = messages.takeLast(keep).toMutableList()
    }

    override suspend fun deleteMessages(serverId: String, sessionId: String) {
        messagesBySession.remove(key(serverId, sessionId))
    }

    override suspend fun dropLocation(serverId: String, directory: String?) {
        droppedLocations += directory.orEmpty()
        val scope = sessionsByScope.remove(key(serverId, directory)).orEmpty()
        scope.keys.forEach { messagesBySession.remove(key(serverId, it)) }
    }

    override suspend fun dropServer(serverId: String) {
        sessionsByScope.keys.filter { it.startsWith("$serverId:") }.forEach(sessionsByScope::remove)
        messagesBySession.keys.filter { it.startsWith("$serverId:") }.forEach(messagesBySession::remove)
    }

    private fun key(serverId: String, directory: String?): String = "$serverId:${directory.orEmpty()}"
}
