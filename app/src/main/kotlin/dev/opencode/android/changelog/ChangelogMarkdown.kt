package dev.opencode.android.changelog

import dev.opencode.android.core.model.SafeNavigationUrl

/**
 * One block of the in-app changelog, after its markdown has been reduced to the shapes the screen draws.
 *
 * A table or an ordered list has no shape of its own. It falls through to [Paragraph], so its
 * words stay readable instead of being dropped.
 */
sealed interface ChangelogBlock {
    /** The block's words, with inline markup flattened. What tests and accessibility see. */
    val text: String

    /**
     * The block's inline elements, in order, for rich rendering. Parsed from the text before the
     * markup was flattened, so bold, code spans and link targets survive here even though [text]
     * no longer shows them.
     */
    val inlines: List<ChangelogInline>

    /** A `#`-style heading. [level] is the number of hashes, 1 to 6. */
    data class Heading(
        val level: Int,
        override val text: String,
        override val inlines: List<ChangelogInline> = inlineElements(text),
    ) : ChangelogBlock

    /** A `-`, `*` or `+` list item. Lines indented under it are joined into [text]. */
    data class Bullet(
        override val text: String,
        override val inlines: List<ChangelogInline> = inlineElements(text),
    ) : ChangelogBlock

    /** Running text. The changelog hard-wraps its prose, so consecutive lines are joined into one paragraph. */
    data class Paragraph(
        override val text: String,
        override val inlines: List<ChangelogInline> = inlineElements(text),
    ) : ChangelogBlock

    /**
     * A fenced code block. Its text is kept verbatim — no inline markup is parsed inside it — so
     * indentation and backticks survive exactly as written.
     */
    data class Code(
        override val text: String,
        override val inlines: List<ChangelogInline> = listOf(ChangelogInline.Text(text)),
    ) : ChangelogBlock
}

/**
 * One inline element of a block, in document order.
 */
sealed interface ChangelogInline {
    /** The words, with no markup. */
    fun plainText(): String

    data class Text(val text: String) : ChangelogInline {
        override fun plainText(): String = text
    }

    data class Bold(val text: String) : ChangelogInline {
        // A bold span can wrap a code span in prose about markdown; the markers are not words.
        override fun plainText(): String = text.replace("`", "")
    }

    data class Code(val text: String) : ChangelogInline {
        // A code span about markdown itself can contain its markers (`**bold**`); they are shown
        // verbatim when rendered, but they are not words.
        override fun plainText(): String = text.replace("**", "").replace("`", "")
    }

    /**
     * `[label](target)`. [url] is the target when it is an absolute http(s) URL with a host, and
     * `null` otherwise — a relative link like `./COVERAGE.md` goes nowhere in the bundled
     * changelog, so it renders as its label with no link. [label] is itself parsed, because labels
     * like `` [`COVERAGE.md`](./COVERAGE.md) `` carry code spans.
     */
    data class Link(val label: List<ChangelogInline>, val url: String?) : ChangelogInline {
        override fun plainText(): String = label.joinToString("") { it.plainText() }
    }
}

/**
 * Reads the changelog's markdown without a markdown library.
 *
 * The changelog is written by hand in a small, regular subset of markdown, so one line at a time is enough: headings,
 * bullets, fenced code blocks, and prose wrapped over several lines. Reference definitions (`[label]: target`) are
 * link metadata for a renderer that resolves references; this one does not, so they are stripped rather than shown
 * as stray paragraphs. Anything outside that subset is shown as text rather than hidden.
 */
object ChangelogMarkdown {

    /** The blocks of [markdown], in the order they appear. */
    fun parse(markdown: String): List<ChangelogBlock> {
        val blocks = mutableListOf<ChangelogBlock>()
        // True while the last block still takes continuation lines. A heading or a blank line closes it.
        var continues = false
        // Non-null while inside a fenced code block; gathers lines verbatim until the closing fence.
        var fence: MutableList<String>? = null
        for (raw in markdown.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith(FENCE)) {
                val gathered = fence
                val rest = line.drop(FENCE.length)
                val closeAt = rest.indexOf(FENCE)
                if (gathered == null && closeAt >= 0) {
                    // A single-line fence: ```code``` opens and closes on the same line.
                    val code = rest.substring(0, closeAt)
                    if (code.isNotBlank()) blocks += ChangelogBlock.Code(code)
                } else {
                    fence = if (gathered == null) mutableListOf() else null
                    if (gathered != null && gathered.any(String::isNotBlank)) {
                        blocks += ChangelogBlock.Code(gathered.joinToString("\n"))
                    }
                }
                continues = false
                continue
            }
            val gathering = fence
            if (gathering != null) {
                gathering += raw
                continue
            }
            if (REFERENCE_DEFINITION.matches(line)) {
                continues = false
                continue
            }
            val block = classifyLine(raw)
            if (block == null) {
                continues = false
            } else if (continues && block is ChangelogBlock.Paragraph) {
                val last = blocks.lastIndex
                blocks[last] = blocks[last].mapText { "$it ${block.text}" }
            } else {
                blocks += block
                continues = block !is ChangelogBlock.Heading
            }
        }
        // An unclosed fence still emits what it gathered rather than dropping the text.
        fence?.let { if (it.any(String::isNotBlank)) blocks += ChangelogBlock.Code(it.joinToString("\n")) }
        return blocks.map { it.finalized() }
    }
}

/**
 * What one line of the changelog is, or `null` for a line that only separates blocks.
 *
 * The text is returned as written, without inline cleanup, so a wrapped line can be joined to the one before it first
 * and emphasis that spans a wrap is still resolved. Fences and reference definitions never reach here; [parse]
 * handles them before classifying.
 */
internal fun classifyLine(raw: String): ChangelogBlock? {
    val line = raw.trim()
    if (line.isEmpty() || THEMATIC_BREAK.matches(line)) return null
    HEADING.matchEntire(line)?.let { match ->
        return ChangelogBlock.Heading(level = match.groupValues[1].length, text = match.groupValues[2])
    }
    BULLET.matchEntire(line)?.let { match -> return ChangelogBlock.Bullet(match.groupValues[1]) }
    return ChangelogBlock.Paragraph(line)
}

/**
 * The inline elements of [text], in document order: bold spans, code spans, links, and the plain
 * text between them. A link target that is not an absolute http(s) URL keeps its label and drops
 * its link, because a changelog entry in the app has no repository to link into.
 */
internal fun inlineElements(text: String): List<ChangelogInline> {
    val inlines = mutableListOf<ChangelogInline>()
    var at = 0
    for (match in INLINE.findAll(text)) {
        if (match.range.first > at) {
            inlines += ChangelogInline.Text(text.substring(at, match.range.first))
        }
        val bold = match.groupValues[1]
        val code = match.groupValues[3]
        val label = match.groupValues[4]
        val target = match.groupValues[5]
        when {
            bold.isNotEmpty() -> inlines += ChangelogInline.Bold(bold)

            code.isNotEmpty() -> inlines += ChangelogInline.Code(code)

            else -> inlines += ChangelogInline.Link(
                label = inlineElements(label),
                url = SafeNavigationUrl.parse(target),
            )
        }
        at = match.range.last + 1
    }
    if (at < text.length) inlines += ChangelogInline.Text(text.substring(at))
    return inlines
}

/**
 * Flattens the inline markup of a raw string to its words. Kept for the tests; [parse] flattens
 * through [ChangelogInline.plainText] instead, so the two never disagree about what the words are.
 */
internal fun plainText(text: String): String =
    inlineElements(text).joinToString("") { it.plainText() }

/**
 * Rebuilds a block around transformed text. The inlines are re-parsed from the new text, which is
 * still raw at every call site, so a join never leaves stale spans behind.
 */
private fun ChangelogBlock.mapText(transform: (String) -> String): ChangelogBlock {
    val raw = transform(text)
    val parsed = inlineElements(raw)
    return when (this) {
        is ChangelogBlock.Heading -> copy(text = raw, inlines = parsed)
        is ChangelogBlock.Bullet -> copy(text = raw, inlines = parsed)
        is ChangelogBlock.Paragraph -> copy(text = raw, inlines = parsed)
        is ChangelogBlock.Code -> this
    }
}

/**
 * Strips a raw block down to its words. The inlines were parsed from the raw text while joining,
 * so they survive the flattening and the screen can still render them richly.
 */
private fun ChangelogBlock.finalized(): ChangelogBlock {
    if (this is ChangelogBlock.Code) return this
    val plain = inlines.joinToString("") { it.plainText() }
    return when (this) {
        is ChangelogBlock.Heading -> copy(text = plain)
        is ChangelogBlock.Bullet -> copy(text = plain)
        is ChangelogBlock.Paragraph -> copy(text = plain)
        is ChangelogBlock.Code -> this
    }
}

private const val FENCE = "```"
private val THEMATIC_BREAK = Regex("""([-*_])(\s*\1){2,}""")
private val HEADING = Regex("""(#{1,6})\s+(.+)""")
private val BULLET = Regex("""[-*+]\s+(.+)""")
private val REFERENCE_DEFINITION = Regex("""\[[^\]]+\]:(\s|$)""")
private val INLINE = Regex("""\*\*(.+?)\*\*|(`+)(.+?)\2|\[([^\]]*)]\(([^)]*)\)""")
