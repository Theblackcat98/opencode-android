package dev.opencode.android.core.network

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

/** One message parsed out of an SSE stream. */
sealed interface SseMessage {
    /**
     * A complete event, with its `data:` lines joined by newlines as the spec requires.
     *
     * [id] and [event] are the frame's own fields, which only a parser built with
     * [SseParser.keepFrameFields] retains. The global event stream does not need them — its frames
     * carry everything in `data` — and the session log does, because its `log.synced` end-of-replay
     * frame is named by `event:`.
     */
    data class Data(val payload: String, val id: String? = null, val event: String? = null) : SseMessage

    /** A `: heartbeat` comment, which the client counts as activity for the idle watchdog. */
    data object Heartbeat : SseMessage

    /** Any other comment, such as the `:`-prefixed keep-alives a proxy may add. */
    data class Comment(val text: String) : SseMessage
}

/**
 * Line-oriented SSE parser for the OpenCode event stream (features doc §36).
 *
 * The stream carries `data: <json>` frames and `: heartbeat` comments every 15 s, and a frame ends
 * at a blank line. Comment lines are reported immediately rather than buffered, because the idle
 * watchdog must see a heartbeat before the frame's blank line arrives.
 *
 * **One parser for both streams.** The global stream and the durable session log are the same wire
 * format, and the only difference is that the log names its end-of-replay frame in `event:`. Rather
 * than a second parser that could disagree about blank lines and heartbeats, [keepFrameFields]
 * turns that one field on.
 */
class SseParser(private val keepFrameFields: Boolean = false) {

    private val dataBuffer = StringBuilder()
    private var frameId: String? = null
    private var frameEvent: String? = null

    /**
     * Feeds one line and returns a message when the line completed one.
     *
     * Returns `null` for a buffered `data:` line, for an ignored field, and for a blank line that
     * carries nothing.
     */
    fun parseLine(line: String): SseMessage? {
        val text = line.trimEnd('\r')

        if (text.isEmpty()) {
            return dispatch()
        }

        if (text.startsWith(COMMENT_PREFIX)) {
            val comment = text.removePrefix(COMMENT_PREFIX).trim()
            return if (comment.equals(HEARTBEAT, ignoreCase = true)) {
                SseMessage.Heartbeat
            } else {
                SseMessage.Comment(comment)
            }
        }

        val separator = text.indexOf(':')
        val field = if (separator == -1) text else text.substring(0, separator)
        val rawValue = if (separator == -1) "" else text.substring(separator + 1)
        val value = if (rawValue.startsWith(" ")) rawValue.substring(1) else rawValue

        if (field != FIELD_DATA) {
            when (field) {
                FIELD_ID -> if (keepFrameFields) frameId = value
                FIELD_EVENT -> if (keepFrameFields) frameEvent = value
                // `retry:` and anything unknown carry nothing this client needs.
            }
            return null
        }

        if (dataBuffer.isNotEmpty()) {
            dataBuffer.append('\n')
        }
        dataBuffer.append(value)
        return null
    }

    /**
     * Dispatches a frame that the stream ended without a trailing blank line, so the last event of
     * a connection that is cut mid-frame is not lost.
     */
    fun flush(): SseMessage? = dispatch()

    private fun dispatch(): SseMessage? {
        if (dataBuffer.isEmpty()) {
            // A frame that carried only an `event:` name and no data still ends the replay, so the
            // name has to survive an empty payload.
            if (keepFrameFields && frameEvent != null) {
                val name = frameEvent
                reset()
                return SseMessage.Data(payload = "", event = name)
            }
            return null
        }
        val payload = dataBuffer.toString()
        val id = frameId
        val event = frameEvent
        reset()
        return SseMessage.Data(payload, id, event)
    }

    private fun reset() {
        dataBuffer.setLength(0)
        frameId = null
        frameEvent = null
    }

    companion object {
        private const val COMMENT_PREFIX = ":"
        private const val FIELD_DATA = "data"
        private const val FIELD_ID = "id"
        private const val FIELD_EVENT = "event"
        private const val HEARTBEAT = "heartbeat"

        /** Reads the stream as UTF-8 regardless of any charset the server declares. */
        fun reader(inputStream: InputStream): BufferedReader =
            BufferedReader(InputStreamReader(inputStream, StandardCharsets.UTF_8))
    }
}
