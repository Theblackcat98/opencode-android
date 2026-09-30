package dev.opencode.android.feature.sessions.ui

import dev.opencode.android.core.data.config.ConfigSchema
import dev.opencode.android.core.data.server.ServerDataSet
import dev.opencode.android.core.database.cache.ReadCacheStore
import dev.opencode.android.core.model.SessionInfo
import dev.opencode.android.core.model.SessionMessage
import dev.opencode.android.core.model.event.Event
import dev.opencode.android.core.network.ServerApiFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient

/**
 * A real [ServerDataSet] that is fed events and asked for nothing.
 *
 * The view models under test here follow the set's request and session stores, and those stores are
 * filled by events, so no request is ever made and the API is pointed at an address nothing listens on.
 * The set is the real one — a fake would answer at whatever moment the test asked, which is the thing
 * being tested — and the events are the ones the stream delivers.
 */
class DataSetFixtures {
    val set: ServerDataSet = ServerDataSet(
        serverId = "server-1",
        api = ServerApiFactory(OkHttpClient()).createForReads("http://127.0.0.1:1"),
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        cache = NoCache,
        schema = ConfigSchema(JsonObject(emptyMap())),
    )

    private var sequence = 0

    /** An event as the stream delivers it, in [directory], with the durable envelope the recorded corpus has. */
    fun event(type: String, data: String, sessionID: String = "ses_1", directory: String = "/work"): Event {
        val number = ++sequence
        return Event.decode(
            """{"id":"evt_$number","type":"$type","created":$number,""" +
                """"durable":{"aggregateID":"$sessionID","seq":$number,"version":1},""" +
                """"location":{"directory":"$directory"},"data":$data}""",
        )
    }

    fun sessionCreated(sessionID: String, parentID: String? = null, title: String? = null): Event {
        val parent = parentID?.let { ""","parentID":"$it"""" }.orEmpty()
        val name = title?.let { ""","title":"$it"""" }.orEmpty()
        return event(
            "session.created",
            """{"sessionID":"$sessionID","projectID":"project-1","location":{"directory":"/work"},""" +
                """"slug":"s","version":"1"$parent$name}""",
            sessionID = sessionID,
        )
    }
}

/** A cache that remembers nothing, because these tests are about the event path and not the disk one. */
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

/**
 * Waits until [predicate] holds, on the wall clock, so a state that never arrives fails and does not hang.
 * The failure names what was awaited and what the state was instead.
 */
suspend fun <T> StateFlow<T>.await(what: String, predicate: (T) -> Boolean): T =
    withContext(Dispatchers.Default) { withTimeoutOrNull(AWAIT_MILLIS) { first(predicate) } }
        ?: throw AssertionError("Timed out waiting for $what; the state stayed $value")

private const val AWAIT_MILLIS = 3_000L
