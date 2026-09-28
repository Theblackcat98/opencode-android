package dev.opencode.android.core.data.composer

/**
 * What the composer is currently in the middle of typing.
 *
 * The three triggers are the TUI's (features doc §38, "Composer"): `@` mentions, a leading `/`
 * command, and a leading `!` shell line. Deciding between them is the one piece of composer
 * behaviour that has to be right before anything is sent, because it decides **what the text will
 * mean** — a leading `/` is a command and not a message, and a leading `!` runs a shell and does
 * not talk to the agent at all.
 *
 * **Where a trigger is allowed matters more than which character it is.**
 *
 * - `/` and `!` are only triggers at the start of the input. The TUI decides these modes from the
 *   first non-blank character, and so does this: `run /usr/bin/tests` is a message about a path,
 *   not a command, and a phone keyboard makes a leading slash a plausible typo.
 * - `@` is a trigger at the start of a word. `foo@bar.com` is an address, not a mention, and the
 *   boundary check is what tells the two apart.
 *
 * A trigger that is not in a valid position is not a trigger at all: the text is a message.
 *
 * Pure, so every one of these cases is a unit test rather than something discovered by typing.
 */
enum class TriggerKind {
    /** An `@` mention: a file, a directory, a reference or an agent. */
    MENTION,

    /** A leading `/`: a server command, an MCP prompt or a client action. */
    COMMAND,

    /** A leading `!`: a shell command for `session.shell`. */
    SHELL,
}

/**
 * The trigger under the caret, and the range a completion would replace.
 *
 * [start] and [end] are **half-open** offsets into the text and always cover the whole token, not
 * just what is left of the caret: completing `@src/li` with the caret before the `i` replaces
 * `@src/li`, not `@src/`, so the completion does not leave the tail of the token behind. [query] is
 * only the part before the caret, because that is what the user has actually typed.
 *
 * For [TriggerKind.SHELL] the span covers the whole first line, because a shell command is the line
 * and not a prefix of it.
 */
data class TriggerSpan(
    val kind: TriggerKind,
    val start: Int,
    val end: Int,
    val query: String,
) {
    /** The text the span covers, which is what a completion replaces. */
    fun textIn(text: String): String = text.substring(start, end)

    companion object {
        const val AT = '@'
        const val SLASH = '/'
        const val BANG = '!'
    }
}

/**
 * Finds the trigger under the caret.
 *
 * [cursor] is an offset into [text] and is clamped, because a text field can report a selection
 * start past the end of the text it just handed over while a composition is in flight.
 */
fun detectTrigger(text: String, cursor: Int = text.length): TriggerSpan? {
    val at = cursor.coerceIn(0, text.length)
    val first = text.indexOfFirst { !it.isWhitespace() }
    if (first >= 0 && at > first) {
        when (text[first]) {
            TriggerSpan.BANG -> return shellSpan(text, first)
            TriggerSpan.SLASH -> return commandSpan(text, first, at)
            else -> Unit
        }
    }
    return mentionSpan(text, at)
}

/**
 * `!command`: the whole first line, from the `!` to the end of the line.
 *
 * The span deliberately runs past the caret, because a shell command is not completed — the text
 * after the caret is part of the same command and is what the server will run.
 */
private fun shellSpan(text: String, first: Int): TriggerSpan {
    val lineEnd = text.indexOf('\n', first).let { if (it < 0) text.length else it }
    return TriggerSpan(
        kind = TriggerKind.SHELL,
        start = first,
        end = lineEnd,
        query = text.substring(first + 1, lineEnd),
    )
}

/**
 * `/name`, while the caret is still inside the name.
 *
 * Once a space follows the name the palette closes: the user is typing the command's arguments,
 * which are free text (`$ARGUMENTS`, `$1..$n` in a command template, features doc §11) and have no
 * completion of their own.
 */
private fun commandSpan(text: String, first: Int, cursor: Int): TriggerSpan? {
    val nameStart = first + 1
    var end = nameStart
    while (end < text.length && !text[end].isWhitespace()) end++
    if (cursor < nameStart) return null
    if (cursor > end) return null
    return TriggerSpan(
        kind = TriggerKind.COMMAND,
        start = first,
        end = end,
        query = text.substring(nameStart, cursor),
    )
}

/**
 * `@name`, anywhere a word can start.
 *
 * The token is the run of non-whitespace around the caret, so the span covers what is before and
 * after it, and the trigger only counts when it opens the token at a word boundary.
 */
private fun mentionSpan(text: String, cursor: Int): TriggerSpan? {
    if (cursor <= 0) return null
    var start = cursor
    while (start > 0 && !text[start - 1].isWhitespace()) start--
    var end = cursor
    while (end < text.length && !text[end].isWhitespace()) end++
    val token = text.substring(start, end)
    if (token.isEmpty() || !token.all(::isMentionChar)) return null
    val at = token.indexOf(TriggerSpan.AT)
    if (at < 0) return null
    val trigger = start + at
    if (trigger > 0 && !text[trigger - 1].isWhitespace()) return null
    return TriggerSpan(
        kind = TriggerKind.MENTION,
        start = trigger,
        end = end,
        query = text.substring(trigger + 1, cursor),
    )
}

/**
 * Whether [char] can appear in a mention token.
 *
 * Deliberately narrow. A mention names a path, so it takes letters, digits, the path characters and
 * the `#` that introduces a line range — and nothing else: an `@` run containing a space, a quote
 * or a bracket is a mail address or a snippet of code, not something the server is being asked to
 * attach.
 */
internal fun isMentionChar(char: Char): Boolean =
    char.isLetterOrDigit() || char in "@/._-+~#:"
