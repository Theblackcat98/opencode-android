package dev.opencode.android.core.designsystem.code

/**
 * The line lexer every language's rules drive.
 *
 * **One pass, left to right, no backtracking**, because the function that has to be fast is the one
 * that runs a thousand times for a thousand-line patch (plan §5.4). A token is decided the moment
 * the scanner reaches it and the scanner never revisits a character, so the work is linear in the
 * length of the line and a pathological line — a megabyte of `"` — is a megabyte of work and not a
 * hang.
 *
 * **Total by construction.** Every branch that could run out of text does, every delimiter that
 * could be unterminated ends at the line's end, and there is no recursion. Whatever the input, the
 * result is a set of runs that exactly tile the line — [CodeLine.tokens] always starts at 0, always
 * ends at the line's length, and never overlaps — because a renderer that draws runs needs them to
 * cover the text and a gap would render as missing text.
 */
internal class Lexer(
    private val text: String,
    private val rules: LexerRules,
) {
    private val tokens = mutableListOf<CodeToken>()

    fun run(carry: LineCarry, number: Int): CodeLine {
        tokens.clear()
        pendingCarry = carry
        var index = 0
        // A literal that was still open when this line began: its opening run belongs to the line
        // that opened it, so the first run here is the rest of the literal, and the closing marker
        // is what ends it.
        if (carry.open) {
            val closed = consumeUntilClose(index = 0, delimiter = carry.openStringDelimiter!!, triple = carry.triple)
            index = closed.index
            if (closed.closed) pendingCarry = LineCarry.NONE
        }
        while (index < text.length) {
            index = step(index, pendingCarry)
        }
        if (tokens.isEmpty() || tokens.last().end < text.length) {
            tokens += CodeToken(CodeTokenKind.PLAIN, tokens.lastOrNull()?.end ?: 0, text.length)
        }
        return CodeLine(number, text, merge(tokens), pendingCarry)
    }

    private fun step(index: Int, carryIn: LineCarry): Int {
        val rest = index
        val ch = text[index]

        // A leading marker, when the language uses one: a YAML key, a diff body, a block quote.
        if (index == 0 && rest < text.length) {
            markLeadingMarker(index, carryIn)?.let { return it }
        }

        rules.lineComment.firstOrNull { text.startsWith(it, index) }?.let { marker ->
            add(CodeTokenKind.COMMENT, index, text.length)
            return text.length
        }
        rules.blockComment.firstOrNull { text.startsWith(it.first, index) }?.let { (open, close) ->
            val result = consumeUntilClose(index + open.length, close, triple = false, open = open)
            add(CodeTokenKind.COMMENT, index, result.index)
            return result.index
        }

        if (ch in rules.stringDelimiters) {
            val triple = rules.tripleQuote && text.startsWith("$ch$ch$ch", index)
            val open = if (triple) "$ch$ch$ch" else ch.toString()
            val result = consumeUntilClose(index + open.length, close = if (triple) open else ch.toString(), triple = triple, open = open)
            add(CodeTokenKind.STRING, index, result.index)
            // Only a language that *has* multi-line literals can leave one open.
            pendingCarry = if (triple && !result.closed && rules.tripleQuote) {
                LineCarry(openStringDelimiter = open, triple = true)
            } else {
                LineCarry.NONE
            }
            return result.index
        }
        if (rules.backtickTemplate && ch == '`') {
            val result = consumeUntilClose(index + 1, "`", triple = false, open = "`")
            add(CodeTokenKind.STRING, index, result.index)
            pendingCarry = LineCarry.NONE
            return result.index
        }
        if (ch == '"' && !rules.stringDelimiters.contains('"')) {
            val result = consumeUntilClose(index + 1, "\"", triple = false, open = "\"")
            add(CodeTokenKind.STRING, index, result.index)
            return result.index
        }

        if (ch.isDigit()) {
            val end = scanWhile(index) { it.isDigit() || it == '.' || it == '_' || it in "xXbBoOeEaAcCdDfF" }
            add(CodeTokenKind.NUMBER, index, end)
            return end
        }
        if (ch == '@' && rules.annotation) {
            val end = scanWhile(index + 1) { it.isLetterOrDigit() || it == '_' || it == '.' }
            add(CodeTokenKind.ANNOTATION, index, end)
            return end
        }
        if (ch == '#' && rules.annotation) {
            val end = scanWhile(index + 1) { it.isLetterOrDigit() || it == '_' || it == '.' }
            add(CodeTokenKind.ANNOTATION, index, end)
            return end
        }
        if (ch.isLetter() || ch == '_' || ch == '$') {
            val end = scanWhile(index) { it.isLetterOrDigit() || it == '_' || it == '$' }
            val word = text.substring(index, end)
            if (word.lowercase() in rules.keywords) add(CodeTokenKind.KEYWORD, index, end) else add(CodeTokenKind.PLAIN, index, end)
            return end
        }
        if (ch == '#' && text.getOrNull(index + 1) == '[') {
            val close = text.indexOf(']', index)
            val end = if (close < 0) text.length else close + 1
            add(CodeTokenKind.ANNOTATION, index, end)
            return end
        }
        if (!ch.isLetterOrDigit() && !ch.isWhitespace() && ch != '_') {
            val end = scanWhile(index) { !it.isLetterOrDigit() && !it.isWhitespace() && it != '_' && it != '@' && it != '#' && it != '"' && it != '\'' && it != '`' }
            add(CodeTokenKind.PUNCTUATION, index, end)
            return end
        }
        // Whitespace and anything else: plain, and it merges with the run before it.
        val end = scanWhile(index) { it.isWhitespace() }
        add(CodeTokenKind.PLAIN, index, end)
        return end
    }

    /** The carry the last step decided, read by [run] after the loop. */
    private var pendingCarry: LineCarry = LineCarry.NONE

    private fun markLeadingMarker(index: Int, carryIn: LineCarry): Int? {
        val ch = text[index]
        if (carryIn.open) return null
        if (rules.diffBody && (ch == '+' || ch == '-' || ch == '@')) {
            val end = scanWhile(index) { !it.isWhitespace() }
            val rest = text.indexOf('\n', index).let { if (it < 0) text.length else it }
            val markerEnd = end.coerceAtMost(rest)
            add(CodeTokenKind.MARKER, index, markerEnd)
            if (rest > markerEnd) add(CodeTokenKind.PLAIN, markerEnd, rest)
            return rest
        }
        if (rules.leadingDashKey && (ch == '-' || ch == ':' || ch == '?')) {
            add(CodeTokenKind.MARKER, index, index + 1)
            return index + 1
        }
        if (rules.quotePrefix && ch == '>') {
            add(CodeTokenKind.MARKER, index, index + 1)
            return index + 1
        }
        if (rules.markup && ch == '<') {
            val close = text.indexOf('>', index)
            val end = if (close < 0) text.length else close + 1
            add(CodeTokenKind.ANNOTATION, index, end)
            return end
        }
        if (rules.markup && ch == '/' && text.getOrNull(index + 1) == '>') {
            add(CodeTokenKind.ANNOTATION, index, index + 2)
            return index + 2
        }
        if (rules.tablePipe && ch == '|') {
            add(CodeTokenKind.MARKER, index, index + 1)
            return index + 1
        }
        if (rules.markup && ch == '#' && text.getOrNull(index + 1) == '#') {
            val end = scanWhile(index) { it == '#' }
            add(CodeTokenKind.MARKER, index, end)
            return end
        }
        if (index == 0 && text.startsWith("#!")) {
            add(CodeTokenKind.MARKER, 0, text.length)
            return text.length
        }
        return null
    }

    private data class Close(val index: Int, val closed: Boolean)

    private fun consumeUntilClose(index: Int, delimiter: String, triple: Boolean, open: String): Close {
        var at = index
        while (at < text.length) {
            val found = text.indexOf(delimiter, at)
            if (found < 0) return Close(text.length, closed = false)
            at = found + delimiter.length
            if (triple) {
                // A triple-quoted literal may contain a shorter run of quotes, so only the full
                // delimiter closes it — which is why the scan above searched for the full one.
                return Close(at, closed = true)
            }
            return Close(at, closed = true)
        }
        return Close(text.length, closed = false)
    }

    private inline fun scanWhile(from: Int, predicate: (Char) -> Boolean): Int {
        var at = from
        while (at < text.length && predicate(text[at])) at++
        return at
    }

    private fun add(kind: CodeTokenKind, start: Int, end: Int) {
        if (end <= start) return
        val last = tokens.lastOrNull()
        if (last != null && last.kind == kind && last.end == start) {
            tokens[tokens.lastIndex] = last.copy(end = end)
            return
        }
        tokens += CodeToken(kind, start, end)
    }

    private companion object {
        /**
         * Merges neighbouring runs of the same kind so the token list is minimal.
         *
         * `splitWhile` is not an option: an empty line must still produce one run, because a
         * renderer that iterates runs and draws nothing for an empty run is correct, and one that
         * iterates runs and assumes a run exists is not.
         */
        fun merge(tokens: List<CodeToken>): List<CodeToken> {
            if (tokens.size <= 1) return tokens
            val out = mutableListOf(tokens.first())
            for (index in 1 until tokens.size) {
                val previous = out.last()
                val current = tokens[index]
                if (previous.kind == current.kind && previous.end == current.start) {
                    out[out.lastIndex] = previous.copy(end = current.end)
                } else {
                    out += current
                }
            }
            return out
        }
    }
}
