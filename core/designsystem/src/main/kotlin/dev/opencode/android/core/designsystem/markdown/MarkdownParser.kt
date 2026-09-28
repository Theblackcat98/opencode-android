package dev.opencode.android.core.designsystem.markdown

/**
 * A block parser for the subset of Markdown an assistant answer uses.
 *
 * It is a single pass over the lines with an explicit table state machine, because that is all the
 * grammar needs and because a hand-written scanner is far easier to keep total than a general
 * Markdown engine: an unterminated fence, a ragged table or a stray marker all end in
 * [MarkdownBlock.Verbatim] rather than in a dropped line.
 */
internal class MarkdownParser(private val source: String) {

    private val lines = source.replace("\r\n", "\n").replace('\r', '\n').split('\n')

    fun parse(): List<MarkdownBlock> {
        val blocks = mutableListOf<MarkdownBlock>()
        val paragraph = StringBuilder()
        var index = 0

        fun flushParagraph() {
            if (paragraph.isNotEmpty()) {
                blocks += MarkdownBlock.Paragraph(paragraph.toString())
                paragraph.setLength(0)
            }
        }

        while (index < lines.size) {
            val line = lines[index]
            when {
                line.isBlank() -> {
                    flushParagraph()
                    index++
                }

                isFence(line) -> {
                    flushParagraph()
                    val (language, body, next) = readFence(index)
                    blocks += MarkdownBlock.Code(language, body)
                    index = next
                }

                isHeading(line) -> {
                    flushParagraph()
                    val level = line.takeWhile { it == '#' }.length
                    blocks += MarkdownBlock.Heading(level.coerceIn(1, 6), line.drop(level).trim())
                    index++
                }

                line.trimEnd() == "---" || line.trimEnd() == "***" || line.trimEnd() == "___" -> {
                    flushParagraph()
                    blocks += MarkdownBlock.Divider(MarkdownBlock.DividerStyle.HORIZONTAL_RULE)
                    index++
                }

                isQuote(line) -> {
                    flushParagraph()
                    val (body, next) = readQuote(index)
                    blocks += MarkdownBlock.Quote(parseMarkdown(body))
                    index = next
                }

                isBullet(line) -> {
                    flushParagraph()
                    val (items, next) = readList(index, bullet = true)
                    blocks += MarkdownBlock.BulletList(items)
                    index = next
                }

                isNumbered(line) -> {
                    flushParagraph()
                    val start = line.takeWhile { it.isDigit() }.toIntOrNull() ?: 1
                    val (items, next) = readList(index, bullet = false)
                    blocks += MarkdownBlock.NumberedList(start, items)
                    index = next
                }

                isTableHeader(index) -> {
                    flushParagraph()
                    val (table, next) = readTable(index)
                    if (table == null) {
                        paragraph.appendLine(line)
                        index++
                    } else {
                        blocks += table
                        index = next
                    }
                }

                else -> {
                    if (paragraph.isNotEmpty()) paragraph.append('\n')
                    paragraph.append(line)
                    index++
                }
            }
        }
        flushParagraph()
        return blocks
    }

    private fun isFence(line: String): Boolean = line.trimStart().startsWith("```")

    private fun isHeading(line: String): Boolean =
        line.startsWith("#") && line.dropWhile { it == '#' }.startsWith(' ')

    private fun isQuote(line: String): Boolean = line.trimStart().startsWith(">")

    private fun isBullet(line: String): Boolean {
        val trimmed = line.trimStart()
        return (trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("+ ")) &&
            trimmed.length > 2
    }

    private fun isNumbered(line: String): Boolean {
        val trimmed = line.trimStart()
        val digits = trimmed.takeWhile { it.isDigit() }
        return digits.isNotEmpty() && trimmed.drop(digits.length).startsWith(". ")
    }

    /** A table header is a row of cells followed by a separator row. */
    private fun isTableHeader(index: Int): Boolean {
        val line = lines[index]
        val next = lines.getOrNull(index + 1) ?: return false
        return line.contains('|') && isTableSeparator(next)
    }

    private fun readFence(start: Int): Triple<String?, String, Int> {
        val opening = lines[start].trimStart()
        val language = opening.removePrefix("```").trim().takeIf { it.isNotEmpty() }
        val body = StringBuilder()
        var index = start + 1
        while (index < lines.size && !lines[index].trimStart().startsWith("```")) {
            if (body.isNotEmpty()) body.append('\n')
            body.append(lines[index])
            index++
        }
        // An unterminated fence swallows the rest of the answer, which is what a reader expects.
        return Triple(language, body.toString(), if (index < lines.size) index + 1 else index)
    }

    private fun readQuote(start: Int): Pair<String, Int> {
        val body = StringBuilder()
        var index = start
        while (index < lines.size && isQuote(lines[index])) {
            if (body.isNotEmpty()) body.append('\n')
            body.append(lines[index].trimStart().removePrefix(">").trimStart())
            index++
        }
        return body.toString() to index
    }

    private fun readList(start: Int, bullet: Boolean): Pair<List<MarkdownInline>, Int> {
        val items = mutableListOf<MarkdownInline>()
        var index = start
        while (index < lines.size) {
            val line = lines[index]
            val isItem = if (bullet) isBullet(line) else isNumbered(line)
            if (!isItem) break
            items += MarkdownInline.Text(stripMarker(line))
            index++
        }
        return items to index
    }

    private fun stripMarker(line: String): String {
        val trimmed = line.trimStart()
        return if (trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("+ ")) {
            trimmed.drop(2)
        } else {
            trimmed.dropWhile { it.isDigit() }.drop(2)
        }
    }

    private fun isTableSeparator(line: String): Boolean {
        val trimmed = line.trim()
        if (!trimmed.contains('-')) return false
        return trimmed.all { it == '|' || it == '-' || it == ':' || it == ' ' }
    }

    private fun readTable(start: Int): Pair<MarkdownBlock.Table?, Int> {
        val separator = lines.getOrNull(start + 1) ?: return null to start
        if (!isTableSeparator(separator)) return null to start
        val header = splitRow(lines[start])
        val alignment = splitRow(separator).map { cell ->
            val marker = cell.plainText()
            val left = marker.startsWith(':')
            val right = marker.endsWith(':')
            when {
                left && right -> ColumnAlignment.CENTER
                right -> ColumnAlignment.RIGHT
                else -> ColumnAlignment.LEFT
            }
        }
        val rows = mutableListOf<List<MarkdownInline>>()
        var index = start + 2
        while (index < lines.size && lines[index].contains('|') && lines[index].isNotBlank()) {
            rows += splitRow(lines[index])
            index++
        }
        return MarkdownBlock.Table(header, alignment, rows) to index
    }

    private fun splitRow(line: String): List<MarkdownInline> = line
        .trim()
        .removePrefix("|")
        .removeSuffix("|")
        .split('|')
        .map { MarkdownInline.Text(it.trim()) }
}

/** Removes a single set of emphasis, code and link markers from [text], for list items. */
fun MarkdownInline.plainText(): String = when (this) {
    is MarkdownInline.Text -> text
    is MarkdownInline.Emphasis -> text
    is MarkdownInline.Code -> text
    is MarkdownInline.Link -> text
}
