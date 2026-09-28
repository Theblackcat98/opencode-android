package dev.opencode.android.core.designsystem.markdown

/**
 * The Markdown a model writes, reduced to what a transcript actually contains.
 *
 * The grammar is deliberately small and total: an assistant answer is prose, lists, headings, code,
 * tables, links and emphasis, and a plugin can emit anything else. Anything this parser does not
 * recognise is rendered as the literal text it was, never dropped, because a transcript that
 * silently loses a line is worse than one that shows a stray asterisk.
 *
 * The parse is a pure function over a string, so it runs off the main thread (plan §5.4) and is
 * tested without a device.
 */
sealed interface MarkdownBlock {
    /** The style of a [Divider] block. */
    enum class DividerStyle {
        /** `---` */
        HORIZONTAL_RULE,

        /** A table separator, which is not drawn as a rule. */
        TABLE,
    }

    /** A run of paragraph lines. */
    data class Paragraph(val text: String) : MarkdownBlock

    data class Heading(val level: Int, val text: String) : MarkdownBlock

    data class BulletList(val items: List<MarkdownInline>) : MarkdownBlock

    data class NumberedList(val start: Int, val items: List<MarkdownInline>) : MarkdownBlock

    data class Code(val language: String?, val code: String) : MarkdownBlock

    data class Quote(val lines: List<MarkdownBlock>) : MarkdownBlock

    /** A table: a header row, its alignment, and the body rows. */
    data class Table(
        val header: List<MarkdownInline>,
        val alignment: List<ColumnAlignment>,
        val rows: List<List<MarkdownInline>>,
    ) : MarkdownBlock

    data class Divider(val style: DividerStyle) : MarkdownBlock

    /** Anything this grammar does not cover, shown as it was written. */
    data class Verbatim(val text: String) : MarkdownBlock
}

/** How a table column lines up. */
enum class ColumnAlignment {
    LEFT,
    CENTER,
    RIGHT,
}

/** Inline markup inside a block: text, emphasis, code and links. */
sealed interface MarkdownInline {
    data class Text(val text: String) : MarkdownInline

    data class Emphasis(val text: String, val strong: Boolean) : MarkdownInline

    /** `` `code` ``, which must not be re-interpreted. */
    data class Code(val text: String) : MarkdownInline

    data class Link(val text: String, val url: String) : MarkdownInline
}

/** Parses [source] into blocks. Never throws: unrecognised input becomes [MarkdownBlock.Verbatim]. */
fun parseMarkdown(source: String): List<MarkdownBlock> = MarkdownParser(source).parse()
