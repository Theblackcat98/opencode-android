package dev.opencode.android.core.network

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

/** One message parsed out of an SSE stream. */
sealed interface SseMessage {
    /** A complete event, with its `data:` lines joined by newlines as the spec requires. */
    data class Data(val payload: String) : SseMessage

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
 */
class SseParser {

    private val dataBuffer = StringBuilder()

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
        if (field != FIELD_DATA) {
            // `event:`, `id:`, `retry:` and unknown fields carry nothing this client needs.
            return null
        }

        val rawValue = if (separator == -1) "" else text.substring(separator + 1)
        val value = if (rawValue.startsWith(" ")) rawValue.substring(1) else rawValue
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
        if (dataBuffer.isEmpty()) return null
        val payload = dataBuffer.toString()
        dataBuffer.setLength(0)
        return SseMessage.Data(payload)
    }

    companion object {
        private const val COMMENT_PREFIX = ":"
        private const val FIELD_DATA = "data"
        private const val HEARTBEAT = "heartbeat"

        /** Reads the stream as UTF-8 regardless of any charset the server declares. */
        fun reader(inputStream: InputStream): BufferedReader =
            BufferedReader(InputStreamReader(inputStream, StandardCharsets.UTF_8))
    }
}
