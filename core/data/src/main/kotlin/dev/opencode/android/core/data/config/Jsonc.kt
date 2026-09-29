package dev.opencode.android.core.data.config

/**
 * Turns a JSONC document into plain JSON **without moving a single character**, and can say where a
 * byte was.
 *
 * **Positions are the whole reason this exists.** `opencode.jsonc` is the file OpenCode reads, and
 * the schema it is validated against is published with `allowComments: true` and
 * `allowTrailingCommas: true` — so the bytes a user typed contain things JSON does not allow, and a
 * client that rejected them would reject a valid configuration. Stripping them by rewriting the
 * text shifts every offset after the first comment, which would put every diagnostic at the wrong
 * line: the user would be told line 4 when the problem is line 40, in a file they cannot fix from a
 * phone.
 *
 * **So the masking is byte-for-byte.** A comment becomes spaces, a trailing comma's following
 * whitespace becomes spaces, and every newline is kept. The masked text has exactly the original
 * length, every line has exactly the original line count, and column N of line M in the masked text
 * is column N of line M in the file. [positionOf] turns a parser offset into a line and column
 * without ever consulting the original text again.
 *
 * **A comment marker inside a string is not a comment.** `"url": "https://example.com"` has two `//`
 * in it, and a regex or a naive scan would blank the rest of the line — so this walks the text as a
 * JSON tokenizer does, tracking string state and escapes, which is the only thing that can tell the
 * two apart.
 */
object Jsonc {

    /**
     * Replaces comments and trailing commas with spaces, keeping length, lines and columns.
     *
     * @return the masked text, always the same length as [text].
     */
    fun mask(text: String): String {
        val out = CharArray(text.length) { text[it] }
        var index = 0
        val length = text.length
        while (index < length) {
            when {
                text[index] == '"' -> index = skipString(text, index)
                text[index] == '/' -> when {
                    index + 1 < length && text[index + 1] == '/' -> {
                        blankToEndOfLine(out, text, index)
                        index = endOfLine(text, index)
                    }

                    index + 1 < length && text[index + 1] == '*' -> {
                        val end = endOfBlockComment(text, index)
                        for (at in index until end) if (out[at] != '\n' && out[at] != '\r') out[at] = ' '
                        index = end
                    }

                    else -> index++
                }

                // A `}` or `]` can be followed by a comma; the comma can be followed by nothing, a
                // comment or whitespace and then the close. Only that last case is a trailing comma,
                // and the check runs to the next meaningful byte rather than to end of line, so
                // `{"a":1,}` and `{"a":1,\n}` are both caught and `{"a":1} ,` is not.
                text[index] == '}' || text[index] == ']' -> {
                    val comma = nextMeaningful(text, index + 1)
                    if (comma != null && text[comma] == ',') {
                        val after = nextMeaningful(text, comma + 1)
                        if (after != null && (text[after] == '}' || text[after] == ']')) {
                            out[comma] = ' '
                        }
                    }
                    index++
                }

                else -> index++
            }
        }
        return String(out)
    }

    /** Where a byte is, as a 1-based line and column. */
    data class Position(val line: Int, val column: Int, val offset: Int)

    /**
     * The 1-based position of [offset] in the text.
     *
     * An offset past the end is reported at the last position rather than clamped to zero, because
     * "unexpected end of input" is a real diagnostic and pointing past the last character is the
     * only honest place for it.
     */
    fun positionOf(text: String, offset: Int): Position {
        val bounded = offset.coerceIn(0, text.length)
        var line = 1
        var lineStart = 0
        for (index in 0 until bounded) {
            if (text[index] == '\n') {
                line++
                lineStart = index + 1
            }
        }
        return Position(line = line, column = bounded - lineStart + 1, offset = bounded)
    }

    /** The position of the first occurrence of [needle], or `null`. Used to turn a path into a line. */
    fun positionOfToken(text: String, needle: String): Position? {
        val at = text.indexOf(needle)
        return if (at < 0) null else positionOf(text, at)
    }

    /** Whether the text has anything other than whitespace in it. */
    fun isBlank(text: String): Boolean = text.isBlank()

    // ------------------------------------------------------------------------------ internals

    /**
     * The index just past the string literal that starts at [start].
     *
     * Returns the index of the closing quote plus one, or the end of the text for an unterminated
     * string. An unterminated string is left to the JSON parser to report — guessing where the user
     * meant the string to end would produce a diagnostic about the wrong byte.
     */
    private fun skipString(text: String, start: Int): Int {
        var index = start + 1
        while (index < text.length) {
            when (text[index]) {
                '\\' -> index += 2
                '"' -> return index + 1
                else -> index++
            }
        }
        return index
    }

    /** Blanks a line comment's bytes and returns the index of the newline that ends it. */
    private fun blankToEndOfLine(out: CharArray, text: String, start: Int) {
        var index = start
        while (index < text.length && text[index] != '\n') {
            out[index] = ' '
            index++
        }
    }

    private fun endOfLine(text: String, start: Int): Int {
        var index = start
        while (index < text.length && text[index] != '\n') index++
        return index
    }

    /**
     * The index just past the end of a block comment, or the end of the text.
     *
     * **A line break inside a block comment is kept**, which is what preserves the line count: a
     * multi-line comment must not make every later diagnostic report a lower line number.
     */
    private fun endOfBlockComment(text: String, start: Int): Int {
        var index = start + 2
        while (index + 1 < text.length) {
            if (text[index] == '*' && text[index + 1] == '/') return index + 2
            index++
        }
        return text.length
    }

    /** The next byte that is not whitespace or a masked comment, or `null` at the end. */
    private fun nextMeaningful(text: String, from: Int): Int? {
        var index = from
        while (index < text.length) {
            when {
                text[index].isWhitespace() -> index++
                text[index] == '/' && index + 1 < text.length && text[index + 1] == '/' -> index = endOfLine(text, index)
                text[index] == '/' && index + 1 < text.length && text[index + 1] == '*' ->
                    index = endOfBlockComment(text, index)

                else -> return index
            }
        }
        return null
    }
}
