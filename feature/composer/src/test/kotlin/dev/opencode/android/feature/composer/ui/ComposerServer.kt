package dev.opencode.android.feature.composer.ui

import dev.opencode.android.core.data.config.ConfigSchema
import dev.opencode.android.core.data.server.ServerDataSet
import dev.opencode.android.core.database.cache.ReadCacheStore
import dev.opencode.android.core.model.AgentInfo
import dev.opencode.android.core.model.CommandInfo
import dev.opencode.android.core.model.DataResponse
import dev.opencode.android.core.model.FileSystemEntry
import dev.opencode.android.core.model.LocationPublicRef
import dev.opencode.android.core.model.LocationScoped
import dev.opencode.android.core.model.ModelInfo
import dev.opencode.android.core.model.ModelRef
import dev.opencode.android.core.model.Project
import dev.opencode.android.core.model.ReferenceInfo
import dev.opencode.android.core.model.SessionInfo
import dev.opencode.android.core.model.SessionMessage
import dev.opencode.android.core.model.SkillInfo
import dev.opencode.android.core.model.TokenUsage
import dev.opencode.android.core.model.event.Event
import dev.opencode.android.core.model.json.OpenCodeJson
import dev.opencode.android.core.network.ServerApiFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * A real [ServerDataSet] over a real [dev.opencode.android.core.network.ServerApi] over a
 * [MockWebServer], with the routes a session screen reads answering.
 *
 * **A fake server, not a fake interface.** The claim these tests make is about *when* a view model
 * follows a store, and the stores have to be the real ones for that to mean anything: a mocked
 * `models(directory)` would return whatever the test told it to at whatever moment the test asked, which
 * is exactly the thing being tested. Here a catalog is `Idle`, then `Loading`, then `Ready` the way it is
 * on a device, and a test moves it by answering the route and asking the set to load.
 *
 * Every route answers `200` with an empty list until a test says otherwise, so a session opens with
 * nothing in it, and [failing] makes one route answer `500`, which is what the app sees while the
 * server is unreachable or has not booted the directory yet.
 */
class ComposerServer(
    val directory: String = "/work",
    val sessionID: String = "ses_1",
) {
    private val server: MockWebServer = MockWebServer().apply { start() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val answers = ConcurrentHashMap<String, Answer>()
    private val sequence = AtomicInteger()

    @Volatile private var stopped = false

    private data class Answer(val status: Int, val body: String)

    private val recorded = CopyOnWriteArrayList<Call>()

    /** One request the composer made: the method and the path, which is what a wire assertion is about. */
    data class Call(val method: String, val path: String, val body: String)

    /** Every request that reached the server so far, oldest first. */
    val calls: List<Call> get() = recorded.toList()

    /**
     * Waits for a request to reach the server, which is a fact about another thread and not about virtual time.
     *
     * The failure names what was awaited and what the server did see, because a timeout on its own says nothing.
     */
    suspend fun awaitCall(what: String, matches: (Call) -> Boolean): Call =
        withContext(Dispatchers.Default) {
            withTimeoutOrNull(WAIT_MILLIS) {
                var found = calls.firstOrNull(matches)
                while (found == null) {
                    delay(POLL_MILLIS)
                    found = calls.firstOrNull(matches)
                }
                found
            }
        } ?: throw AssertionError("Timed out waiting for $what; the server saw ${calls.map { "${it.method} ${it.path}" }}")

    /** The read model of the server, wired to the routes below. */
    val set: ServerDataSet = ServerDataSet(
        serverId = SERVER_ID,
        api = ServerApiFactory(OkHttpClient()).createForReads(server.url("/").toString().trimEnd('/')),
        scope = scope,
        cache = NoCache,
        schema = ConfigSchema(JsonObject(emptyMap())),
    )

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                recorded += Call(request.method.orEmpty(), request.url.encodedPath, request.body?.utf8().orEmpty())
                val answer = answers[request.url.encodedPath] ?: Answer(404, "{}")
                return MockResponse.Builder()
                    .code(answer.status)
                    .addHeader("Content-Type", "application/json")
                    .body(answer.body)
                    .build()
            }
        }
        session()
        answer("/api/session/$sessionID/message", """{"data":[],"cursor":{}}""")
        models(emptyList())
        agents(emptyList())
        commands(emptyList())
        answerScoped("/api/skill", emptyList(), ListSerializer(SkillInfo.serializer()))
        answerScoped("/api/reference", emptyList(), ListSerializer(ReferenceInfo.serializer()))
        answer("/api/model/default", """{"location":{"directory":"$directory"},"data":null}""")
        projects(emptyList())
        listing(emptyList())
    }

    /** The session `session.get` returns, which is what tells the composer where the session lives. */
    fun session(model: ModelRef? = ModelRef("text", "fake")) {
        val info = SessionInfo(
            id = sessionID,
            projectID = "project-1",
            agent = "build",
            model = model,
            cost = 0.0,
            tokens = TokenUsage.Zero,
            time = SessionInfo.Time(created = 1L, updated = 2L),
            title = "A session",
            location = LocationPublicRef(directory),
        )
        answer(
            "/api/session/$sessionID",
            OpenCodeJson.encodeToString(DataResponse.serializer(SessionInfo.serializer()), DataResponse(info)),
        )
    }

    fun models(models: List<ModelInfo>) =
        answerScoped("/api/model", models, ListSerializer(ModelInfo.serializer()))

    fun agents(agents: List<AgentInfo>) =
        answerScoped("/api/agent", agents, ListSerializer(AgentInfo.serializer()))

    fun commands(commands: List<CommandInfo>) =
        answerScoped("/api/command", commands, ListSerializer(CommandInfo.serializer()))

    /** `fs.list`, which is what the new-session sheet's directory browser reads. */
    fun listing(entries: List<FileSystemEntry>) =
        answerScoped("/api/fs/list", entries, ListSerializer(FileSystemEntry.serializer()))

    fun projects(projects: List<Project>) = answer(
        "/api/project",
        OpenCodeJson.encodeToString(ListSerializer(Project.serializer()), projects),
    )

    /** The messages `message.list` returns, as the JSON the server sends: newest first, as the real route answers. */
    fun messages(json: String) = answer("/api/session/$sessionID/message", """{"data":$json,"cursor":{}}""")

    /** A user message the timeline will show, which is what an undo is aimed at. */
    fun userMessage(id: String, text: String, created: Long): String =
        """{"id":"$id","time":{"created":$created},"type":"user","text":"$text"}"""

    /** `session.compact` accepted: the inbox item the server enqueued for the compaction. */
    fun compactionAccepted() = answer(
        "/api/session/$sessionID/compact",
        """{"data":{"id":"msg_c1","sessionID":"$sessionID","type":"compaction","payload":{},"delivery":"steer"}}""",
    )

    /** `session.generate` answered: the side question's reply, which is a body rather than an event. */
    fun sideAnswer(text: String) = answer("/api/session/$sessionID/generate", """{"data":{"text":"$text"}}""")

    /** Makes [path] answer `204`, which is what a reply, a cancel or a clear answers with. */
    fun accepted(path: String) = answer(path, "", status = 204)

    /** Makes [path] answer `500`, the way a server that is unreachable or not yet ready does. */
    fun failing(path: String) = answer(path, """{"_tag":"unavailable","message":"not ready"}""", status = 500)

    /** Makes [path] refuse with [status] and [body], the server's own error as the wire carries it. */
    fun refusing(path: String, status: Int, body: String) = answer(path, body, status = status)

    /**
     * Stops listening, so the next request fails to connect the way a phone that lost the network does.
     *
     * Only the socket goes: the read model keeps running, so a test can look at what a failed call left behind.
     */
    fun goOffline() {
        if (!stopped) server.close()
        stopped = true
    }

    /**
     * An event as the stream delivers it, for [ServerDataSet.apply].
     *
     * [data] defaults to the session-scoped payload most events carry, and the durable envelope is the one
     * the recorded corpus has, so the event decodes the way a server's would.
     */
    fun event(type: String, data: String = """{"sessionID":"$sessionID"}"""): Event {
        val number = sequence.incrementAndGet()
        return Event.decode(
            """{"id":"evt_$number","type":"$type","created":$number,""" +
                """"durable":{"aggregateID":"$sessionID","seq":$number,"version":1},""" +
                """"location":{"directory":"$directory"},"data":$data}""",
        )
    }

    /** Stops answering, and stops everything the set has running. */
    fun close() {
        scope.cancel()
        goOffline()
    }

    private fun answer(path: String, body: String, status: Int = 200) {
        answers[path] = Answer(status, body)
    }

    private fun <T> answerScoped(path: String, value: T, serializer: KSerializer<T>) = answer(
        path,
        OpenCodeJson.encodeToString(
            LocationScoped.serializer(serializer),
            LocationScoped(LocationPublicRef(directory), value),
        ),
    )

    companion object {
        const val SERVER_ID = "server-1"
        private const val WAIT_MILLIS = 3_000L
        private const val POLL_MILLIS = 20L

        /** A model a location offers, enabled unless a test says otherwise. */
        fun model(id: String = "text", enabled: Boolean = true): ModelInfo = ModelInfo(
            id = id,
            modelID = id,
            providerID = "fake",
            name = "Fake $id",
            enabled = enabled,
            limit = ModelInfo.Limit(context = 200_000, output = 4_096),
        )

        fun agent(id: String = "build"): AgentInfo = AgentInfo(id = id, name = id, mode = AgentInfo.AgentMode.PRIMARY)
    }
}

/** A cache that remembers nothing, because these tests are about the network path and not the disk one. */
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
