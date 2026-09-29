package dev.opencode.android.core.data.composer

import dev.opencode.android.core.model.PromptMention

/**
 * The `#20-45` line range of a mention (features doc §6, `@file#20-45`).
 *
 * Line numbers are **one-based and inclusive**, which is what a person reading a stack trace
 * counts, and they become `?start=&end=` on the attachment URI. A range with only a start is
 * "from here to the end of the file", which is a range a person asks for and the server can honour.
 */
data class LineRange(val start: Int, val end: Int? = null) {
    /** The query string this range contributes, empty when there is nothing to ask for. */
    fun toQuery(): String = buildString {
        if (start > 0) {
            append("?start=").append(start)
            if (end != null) append("&end=").append(end)
        }
    }

    /** The `#20-45` spelling, which is what the composer's text carries. */
    fun toSuffix(): String = if (end != null) "#$start-$end" else "#$start"
}

/**
 * One `@` mention as the user typed it.
 *
 * [raw] is the whole token including the `@` and the range suffix; [target] is what the `@` names,
 * with the range stripped; [range] is the range or `null`. [start] and [end] are the half-open
 * offsets of [raw] in the prompt text, so [PromptMention] can be built from a token without
 * searching for it again.
 */
data class MentionToken(
    val start: Int,
    val end: Int,
    val raw: String,
    val target: String,
    val range: LineRange? = null,
) {
    /** The mention range the API carries, tying the attachment to the text the user typed. */
    fun toMention(): PromptMention = PromptMention(start = start, end = end, text = raw)
}

/**
 * Scans prompt text for the mentions it contains.
 *
 * This is the pass that decides what a prompt *carries*, so it is deliberately conservative: only a
 * token that starts with `@` at a word boundary and is made of path characters is a mention. Text
 * inside a code fence is not exempt — the composer is not a Markdown editor, and a fenced `@decorator`
 * is not something a user attaches a file for — which is recorded as a limitation rather than
 * pretended away.
 */
object MentionScanner {

    /** Every mention in [text], in the order it appears. */
    fun scan(text: String): List<MentionToken> {
        val tokens = mutableListOf<MentionToken>()
        var index = 0
        while (index < text.length) {
            if (text[index] != '@' || (index > 0 && !text[index - 1].isWhitespace())) {
                index++
                continue
            }
            var end = index + 1
            while (end < text.length && isMentionChar(text[end])) end++
            val raw = text.substring(index, end)
            val fragment = raw.substringAfter('#', "").takeIf { raw.contains('#') && it.isNotEmpty() }
            val range = fragment?.let(::parseRange)
            tokens += MentionToken(
                start = index,
                end = index + raw.length,
                raw = raw,
                // Only a fragment that *is* a range is taken off the name. A `#` that is not one stays
                // part of it, because `a#b.ts` is a legal file name and dropping the fragment would
                // attach a different file than the one named.
                target = if (range == null) raw.substring(1) else raw.substringBefore('#').substring(1),
                range = range,
            )
            index = end
        }
        return tokens
    }

    /**
     * Parses `20-45`, `20` and `20-` into a range.
     *
     * `null` for anything else, which is what makes a `#` in an ordinary file name inert. Line
     * numbers are one-based, so `0` and a negative number are not ranges either.
     */
    fun parseRange(text: String): LineRange? {
        val start = text.substringBefore('-').toIntOrNull() ?: return null
        if (start < 1) return null
        if (!text.contains('-')) return LineRange(start)
        val end = text.substringAfter('-').let { if (it.isEmpty()) null else it.toIntOrNull() } ?: return null
        if (end < start) return null
        return LineRange(start, end)
    }
}

/**
 * Turning a server path into the two spellings the client needs.
 *
 * The server hands out paths in its own spelling — absolute, or relative to the location — and both
 * have to become something else: a `file:` URI for the attachment and a short token for the text
 * field. Neither may be guessed, so both are pure functions of what the server said and the
 * location it said it in, and both are tested against both spellings.
 */
object ServerPath {

    /**
     * The `file:` URI the API takes (features doc §6).
     *
     * Absolute, with a line range as `?start=&end=`. The path is percent-encoded as an RFC 3986
     * path — everything outside the unreserved set except `/` and `:` — so a directory with a
     * space or a `#` in its name is one attachment rather than a truncated one plus a range.
     */
    fun toUri(path: String, location: String?, range: LineRange? = null): String {
        val absolute = resolve(location, path)
        return "file://" + encodePath(absolute) + (range?.toQuery() ?: "")
    }

    /**
     * The spelling to put in the text field.
     *
     * A path inside the session's own location is shown relative to it, because `@src/a.ts` is what
     * a person reads and what the TUI shows; anything else is left as the server spelled it, since
     * there is no shorter honest form for a file outside the project.
     */
    fun toMentionText(path: String, location: String?): String {
        if (location.isNullOrEmpty()) return path
        val base = location.trimEnd('/')
        if (base.isEmpty()) return path
        val prefix = "$base/"
        return if (path.startsWith(prefix)) path.removePrefix(prefix) else path
    }

    /**
     * Makes [path] absolute against [location].
     *
     * Absolute input is returned unchanged — the server's own spelling, not a normalized guess of
     * it, which is the rule the P2 directory browser also follows. A relative path is resolved
     * against the location with `.` and `..` handled, because a relative path has to become
     * absolute before it can be a `file:` URI, and the location is the only base the client has.
     */
    fun resolve(location: String?, path: String): String {
        if (path.startsWith("/")) return path
        if (location.isNullOrEmpty()) return "/$path"
        val out = ArrayDeque<String>()
        location.split('/').filter { it.isNotEmpty() }.forEach { out.addLast(it) }
        path.split('/').forEach { segment ->
            when (segment) {
                "", "." -> Unit
                ".." -> out.removeLastOrNull()
                else -> out.addLast(segment)
            }
        }
        return "/" + out.joinToString("/")
    }

    /** RFC 3986 path encoding, leaving the separators alone. */
    fun encodePath(path: String): String = buildString {
        path.toByteArray(Charsets.UTF_8).forEach { byte ->
            val c = byte.toInt().toChar()
            val unreserved = c == '-' || c == '.' || c == '_' || c == '~' || c == '/' || c == ':'
            if ((c.isLetterOrDigit() && c.code < 128) || unreserved) {
                append(c)
            } else {
                append('%').append("%02X".format(byte.toInt() and 0xFF))
            }
        }
    }

    /**
     * The last segment of a path, which is what a completion row shows next to the full path.
     *
     * A trailing `/` names a directory and its last non-empty segment is the directory, so the
     * trailing separator does not produce an empty label.
     */
    fun label(path: String): String {
        val trimmed = path.trimEnd('/')
        val name = trimmed.substringAfterLast('/')
        return name.ifEmpty { trimmed }
    }
}
