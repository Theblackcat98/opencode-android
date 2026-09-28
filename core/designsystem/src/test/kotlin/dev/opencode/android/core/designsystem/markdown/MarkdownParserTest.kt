package dev.opencode.android.core.designsystem.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Markdown grammar an assistant answer is written in.
 *
 * The cases that matter are the ones a real answer contains: an unterminated fence, a ragged table,
 * a lone emphasis marker. All of them must come out as text rather than as a lost line.
 */
class MarkdownParserTest {

    @Test
    fun `paragraphs split on blank lines and keep their line breaks`() {
        val blocks = parseMarkdown("first line\nsecond line\n\nsecond paragraph")
        assertEquals(2, blocks.size)
        assertEquals("first line\nsecond line", (blocks[0] as MarkdownBlock.Paragraph).text)
        assertEquals("second paragraph", (blocks[1] as MarkdownBlock.Paragraph).text)
    }

    @Test
    fun `headings, rules and quotes are recognised`() {
        val blocks = parseMarkdown("## Heading\n\n> quoted line\n\n---")
        assertEquals(MarkdownBlock.Heading(2, "Heading"), blocks[0])
        assertTrue(blocks[1] is MarkdownBlock.Quote)
        assertEquals(MarkdownBlock.Divider(MarkdownBlock.DividerStyle.HORIZONTAL_RULE), blocks[2])
    }

    @Test
    fun `a fenced code block keeps its language and body`() {
        val blocks = parseMarkdown("before\n\n```kotlin\nval x = 1\nval y = 2\n```\n\nafter")
        val code = blocks.first { it is MarkdownBlock.Code } as MarkdownBlock.Code
        assertEquals("kotlin", code.language)
        assertEquals("val x = 1\nval y = 2", code.code)
        assertEquals(3, blocks.size)
    }

    @Test
    fun `an unterminated fence swallows the rest instead of losing it`() {
        val code = parseMarkdown("```\nline one\nline two").single() as MarkdownBlock.Code
        assertEquals("line one\nline two", code.code)
    }

    @Test
    fun `bullets and numbered lists are grouped`() {
        val blocks = parseMarkdown("- one\n- two\n\n1. first\n2. second")
        val bullets = blocks[0] as MarkdownBlock.BulletList
        assertEquals(listOf("one", "two"), bullets.items.map { it.plainText() })
        val numbered = blocks[1] as MarkdownBlock.NumberedList
        assertEquals(1, numbered.start)
        assertEquals(listOf("first", "second"), numbered.items.map { it.plainText() })
    }

    @Test
    fun `a table keeps its columns, alignment and rows`() {
        val table = parseMarkdown(
            """
            | Tool | Cost |
            | :--- | ---: |
            | read | 0.10 |
            | shell | 0.20 |
            """.trimIndent(),
        ).single() as MarkdownBlock.Table

        assertEquals(listOf("Tool", "Cost"), table.header.map { it.plainText() })
        assertEquals(listOf(ColumnAlignment.LEFT, ColumnAlignment.RIGHT), table.alignment)
        assertEquals(2, table.rows.size)
        assertEquals("shell", table.rows[1][0].plainText())
    }

    @Test
    fun `a row with pipes but no separator stays text`() {
        val blocks = parseMarkdown("a | b\nnot a table")
        assertTrue(blocks.all { it is MarkdownBlock.Paragraph })
    }

    @Test
    fun `emphasis, inline code and links are parsed only when they close`() {
        val inlines = parseInline("a **bold** b *italic* c `code` d [text](https://x) e * f")
        assertEquals(
            listOf(
                MarkdownInline.Text("a "),
                MarkdownInline.Emphasis("bold", strong = true),
                MarkdownInline.Text(" b "),
                MarkdownInline.Emphasis("italic", strong = false),
                MarkdownInline.Text(" c "),
                MarkdownInline.Code("code"),
                MarkdownInline.Text(" d "),
                MarkdownInline.Link("text", "https://x"),
                MarkdownInline.Text(" e * f"),
            ),
            inlines,
        )
    }

    @Test
    fun `a stray marker never eats the rest of the line`() {
        assertEquals(listOf(MarkdownInline.Text("2 * 3 = 6")), parseInline("2 * 3 = 6"))
        assertEquals(listOf(MarkdownInline.Text("a ` b")), parseInline("a ` b"))
    }

    @Test
    fun `windows line endings and a trailing blank line are tolerated`() {
        val blocks = parseMarkdown("one\r\n\r\ntwo\r\n")
        assertEquals(2, blocks.size)
        assertEquals("one", (blocks[0] as MarkdownBlock.Paragraph).text)
        assertEquals("two", (blocks[1] as MarkdownBlock.Paragraph).text)
    }
}
