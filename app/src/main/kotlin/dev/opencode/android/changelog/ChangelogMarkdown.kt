package dev.opencode.android.changelog

/**
 * One block of the in-app changelog, after its markdown has been reduced to the three shapes the screen draws.
 *
 * A table, a fenced code block or an ordered list has no shape of its own. It falls through to [Paragraph], so its
 * words stay readable instead of being dropped.
 */
sealed interface ChangelogBlock {
    val text: String

    /** A `#`-style heading. [level] is the number of hashes, 1 to 6. */
    data class Heading(val level: Int, override val text: String) : ChangelogBlock

    /** A `-`, `*` or `+` list item. Lines indented under it are joined into [text]. */
    data class Bullet(override val text: String) : ChangelogBlock

    /** Running text. The changelog hard-wraps its prose, so consecutive lines are joined into one paragraph. */
    data class Paragraph(override val text: String) : ChangelogBlock
}

/**
 * Reads the changelog's markdown without a markdown library.
 *
 * The changelog is written by hand in a small, regular subset of markdown, so one line at a time is enough: headings,
 * bullets, and prose wrapped over several lines. Emphasis, code spans and links are flattened to their words. Anything
 * outside that subset is shown as text rather than hidden.
 */
object ChangelogMarkdown {

    /** The blocks of [markdown], in the order they appear. */
    fun parse(markdown: String): List<ChangelogBlock> {
        val blocks = mutableListOf<ChangelogBlock>()
        // True while the last block still takes continuation lines. A heading or a blank line closes it.
        var continues = false
        for (raw in markdown.lineSequence()) {
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
        return blocks.map { block -> block.mapText(::plainText) }
    }
}

/**
 * What one line of the changelog is, or `null` for a line that only separates blocks.
 *
 * The text is returned as written, without inline cleanup, so a wrapped line can be joined to the one before it first
 * and emphasis that spans a wrap is still removed.
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
 * Flattens the inline markup the changelog uses. Emphasis and code-span markers are removed, and a link keeps its
 * label and drops its target, because a changelog entry in the app has no repository to link into.
 */
internal fun plainText(text: String): String = text
    .replace(LINK) { it.groupValues[1] }
    .replace(BOLD) { it.groupValues[1] }
    .replace("`", "")

private fun ChangelogBlock.mapText(transform: (String) -> String): ChangelogBlock = when (this) {
    is ChangelogBlock.Heading -> copy(text = transform(text))
    is ChangelogBlock.Bullet -> copy(text = transform(text))
    is ChangelogBlock.Paragraph -> copy(text = transform(text))
}

private val THEMATIC_BREAK = Regex("""([-*_])(\s*\1){2,}""")
private val HEADING = Regex("""(#{1,6})\s+(.+)""")
private val BULLET = Regex("""[-*+]\s+(.+)""")
private val LINK = Regex("""\[([^\]]*)]\([^)]*\)""")
private val BOLD = Regex("""\*\*(.+?)\*\*""")
