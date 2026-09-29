package dev.opencode.android.core.data.insights

import dev.opencode.android.core.data.action.toActionError
import dev.opencode.android.core.data.capability.RouteAvailability
import dev.opencode.android.core.data.integrations.ActionFailure
import dev.opencode.android.core.network.ServerApi
import dev.opencode.android.core.network.SseMessage
import dev.opencode.android.core.network.SseParser
import java.io.BufferedReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.coroutines.coroutineContext

/**
 * One entry of a session's durable log.
 *
 * **The entry's own shape is not in the spec.** `GET /api/experimental/session/{id}/log` declares
 * its `data` as a JSON *string* with no schema behind it, so [fields] keeps the parsed object
 * without claiming what any key means. A viewer that assumed `event` and `seq` existed would show
 * blanks on the first server that spells them differently; one that reads only what is there cannot.
 */
data class SessionLogEntry(
    /** The `id:` the frame carried, which is what a caller sends back as `after`. */
    val id: String?,
    /** The `event:` the frame carried. [SYNCED] is how a replay says it has caught up. */
    val event: String?,
    /** The entry itself, as the server sent it. */
    val fields: JsonObject,
) {
    /**
     * The sequence number, when the entry carries a plain numeric one.
     *
     * Read rather than assumed, because this is what a caller passes as `after` to resume: sending a
     * name where the server wants a number would silently replay the whole log again.
     */
    val seq: Long?
        get() = (fields["seq"] as? JsonPrimitive)?.content?.toLongOrNull()

    /** True for the frame that ends a replay, whether it arrives as a name or as a field. */
    val isSynced: Boolean get() = event == SYNCED || fields.containsKey(SYNCED)

    companion object {
        /** The frame that ends a replay. */
        const val SYNCED = "log.synced"

        /**
         * Parses one SSE frame.
         *
         * The payload is a JSON *string* per the spec and a bare object in practice often enough that
         * both are accepted; a payload that is neither becomes an empty object rather than failing
         * the whole stream, because one unreadable frame must not cost the caller the rest of a
         * replay.
         */
        fun parse(payload: String, id: String? = null, event: String? = null): SessionLogEntry {
            val parsed = runCatching { Json.parseToJsonElement(payload) }.getOrNull()
            val obj = when (parsed) {
                is JsonObject -> parsed
                is JsonPrimitive -> runCatching { Json.parseToJsonElement(parsed.content) }.getOrNull() as? JsonObject
                else -> null
            }
            return SessionLogEntry(id = id, event = event, fields = obj ?: JsonObject(emptyMap()))
        }
    }
}

/**
 * Reads the durable session log (features doc §4.2, `session.log`).
 *
 * **This is the only route that can tell the app what it missed.** The global stream at
 * `GET /api/event` is live-only: whatever arrived while the phone was in a tunnel is gone, and a
 * reconnect says only *that* something was. `after=<seq>` replays from a sequence number and
 * `follow=true` keeps the stream open, and the replay ends with `log.synced {seq}`.
 *
 * **The reader is a cold [Flow], and cancelling the collection closes the socket.** The loop checks
 * for cancellation before every read rather than only between entries, because a `follow` stream
 * that has gone quiet would otherwise sit in a read that nothing can interrupt — a "stop watching"
 * button that does not stop anything is worse than no button. Callers still pass a timeout; this
 * only means the timeout is not the only way out.
 *
 * **The stream ending is not a failure.** A `follow` log ends when the server ends it, and
 * `log.synced` ends a replay. Neither throws, and a caller that wants to follow again starts
 * another read from [SessionLogEntry.seq].
 */
class SessionLogClient(
    private val api: ServerApi,
    private val surface: InsightsSurface? = null,
) {

    /**
     * The log of one session, from [after] onwards, optionally staying open.
     *
     * @param after a value from an earlier [SessionLogEntry.id] or [SessionLogEntry.seq], or `null`
     *   to start at the beginning of the log.
     * @param follow whether to keep the stream open after the replay catches up.
     */
    fun read(sessionID: String, after: String? = null, follow: Boolean = false): Flow<SessionLogEntry> = flow {
        val body = try {
            api.readSessionLog(sessionID, after, if (follow) "true" else "false")
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            val actionError = error.toActionError()
            surface?.recordLogFailure(actionError)
            throw ActionFailure(actionError)
        }
        surface?.recordLogAvailability(RouteAvailability.Present)

        body.use { stream ->
            val reader: BufferedReader = SseParser.reader(stream.byteStream())
            val parser = SseParser(keepFrameFields = true)
            while (true) {
                // Checked before the read, so cancelling the collector closes the socket even when the
                // server has gone quiet.
                coroutineContext.ensureActive()
                val line = reader.readLine() ?: break
                val frame = parser.parseLine(line) ?: continue
                if (frame is SseMessage.Data) {
                    val entry = SessionLogEntry.parse(frame.payload, frame.id, frame.event)
                    if (entry.isSynced) break
                    emit(entry)
                }
            }
        }
    }
}
