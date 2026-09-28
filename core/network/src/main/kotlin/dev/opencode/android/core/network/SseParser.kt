package dev.opencode.android.core.network

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

/**
 * Parsed output from an SSE stream.
 */
sealed interface SseMessage {
    /** A complete data frame containing the concatenated `data:` lines. */
    data class Data(val payload: String) : SseMessage

    /** A heartbeat comment (`: heartbeat`). */
    data object Heartbeat : SseMessage

    /** Other comment or non-data line (for debugging/logging). */
    data class Comment(val text: String) : SseMessage
}

/**
 * SSE Parser that processes Server-Sent Events line by line according to the W3C SSE specification
 * and OpenCode V2 event stream format.
 *
 * Features:
 * - Accumulates multiple `data:` lines until a blank line is encountered.
 * - Detects `: heartbeat` comments (features doc §36) to keep connection alive.
 * - Handles `\r\n`, `\n`, and `\r` newlines.
 */
class SseParser {

    private val dataBuffer = StringBuilder()

    /**
     * Parses a single line from the SSE stream.
     * Returns an [SseMessage] if this line completed a message (e.g. blank line or heartbeat),
     * or `null` if the line was buffered or ignored.
     */
    fun parseLine(line: String): SseMessage? {
        val trimmedLine = line.trimEnd('\r')

        // Empty line dispatches the accumulated data buffer
        if (trimmedLine.isEmpty()) {
            if (dataBuffer.isNotEmpty()) {
                val payload = dataBuffer.toString()
                dataBuffer.setLength(0)
                return SseMessage.Data(payload)
            }
            return null
        }

        // Comment line (starts with ':')
        if (trimmedLine.startsWith(":")) {
            val commentContent = trimmedLine.substring(1).trim()
            return if (commentContent.equals("heartbeat", ignoreCase = true)) {
                SseMessage.Heartbeat
            } else {
                SseMessage.Comment(commentContent)
            }
        }

        // Field line
        val colonIndex = trimmedLine.indexOf(':')
        val fieldName: String
        val fieldValue: String
        if (colonIndex != -1) {
            fieldName = trimmedLine.substring(0, colonIndex)
            // If the value starts with a space, strip the first leading space per spec
            val rawValue = trimmedLine.substring(colonIndex + 1)
            fieldValue = if (rawValue.startsWith(" ")) rawValue.substring(1) else rawValue
        } else {
            fieldName = trimmedLine
            fieldValue = ""
        }

        if (fieldName == "data") {
            if (dataBuffer.isNotEmpty()) {
                dataBuffer.append('\n')
            }
            dataBuffer.append(fieldValue)
        }

        return null
    }

    /**
     * Resets any buffered data.
     */
    fun reset() {
        dataBuffer.setLength(0)
    }

    companion object {
        /**
         * Reads lines from an input stream using UTF-8 and parses them.
         */
        fun streamReader(inputStream: InputStream): BufferedReader {
            return BufferedReader(InputStreamReader(inputStream, StandardCharsets.UTF_8))
        }
    }
}
