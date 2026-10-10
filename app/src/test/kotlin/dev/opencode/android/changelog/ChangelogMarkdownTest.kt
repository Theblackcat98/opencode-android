package dev.opencode.android.changelog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What the in-app changelog reads from its markdown, and what it shows.
 *
 * The reader has no markdown library behind it, so each shape it recognises is pinned here, and the repository's own
 * changelog is parsed as a whole to show that nothing markup-shaped leaks onto the screen.
 */
class ChangelogMarkdownTest {

    @Test
    fun `a heading keeps its level and loses its hashes`() {
        assertEquals(ChangelogBlock.Heading(1, "Changelog"), classifyLine("# Changelog"))
        assertEquals(ChangelogBlock.Heading(3, "Added"), classifyLine("### Added"))
    }

    @Test
    fun `a hash with no space after it is text, not a heading`() {
        assertEquals(ChangelogBlock.Paragraph("#hashtag"), classifyLine("#hashtag"))
    }

    @Test
    fun `dash, star and plus items are bullets, indented or not`() {
        assertEquals(ChangelogBlock.Bullet("first"), classifyLine("- first"))
        assertEquals(ChangelogBlock.Bullet("second"), classifyLine("  * second"))
        assertEquals(ChangelogBlock.Bullet("third"), classifyLine("+ third"))
    }

    @Test
    fun `an ordered item is kept as text rather than given a bullet`() {
        assertEquals(ChangelogBlock.Paragraph("1. first"), classifyLine("1. first"))
    }

    @Test
    fun `a line that starts with bold is text, not a bullet`() {
        assertEquals(ChangelogBlock.Paragraph("**Scope.** Plain."), classifyLine("**Scope.** Plain."))
    }

    @Test
    fun `blank lines and thematic breaks separate blocks and are not blocks`() {
        assertNull(classifyLine(""))
        assertNull(classifyLine("   "))
        assertNull(classifyLine("---"))
        assertNull(classifyLine("* * *"))
    }

    @Test
    fun `hard-wrapped prose is joined into one paragraph, and a blank line starts the next`() {
        val blocks = ChangelogMarkdown.parse("The first line\nthe second line.\n\nNext paragraph.")
        assertEquals(
            listOf(
                ChangelogBlock.Paragraph("The first line the second line."),
                ChangelogBlock.Paragraph("Next paragraph."),
            ),
            blocks,
        )
    }

    @Test
    fun `lines indented under a bullet are joined into that bullet`() {
        val blocks = ChangelogMarkdown.parse("- Usage statistics\n  with the range and tool detail.\n- Next item")
        assertEquals(
            listOf(
                ChangelogBlock.Bullet("Usage statistics with the range and tool detail."),
                ChangelogBlock.Bullet("Next item"),
            ),
            blocks,
        )
    }

    @Test
    fun `a heading closes the block before it`() {
        val blocks = ChangelogMarkdown.parse("- an item\n## Fixed\nafter the heading")
        assertEquals(
            listOf(
                ChangelogBlock.Bullet("an item"),
                ChangelogBlock.Heading(2, "Fixed"),
                ChangelogBlock.Paragraph("after the heading"),
            ),
            blocks,
        )
    }

    @Test
    fun `inline markup is flattened to its words`() {
        assertEquals("not shippable", plainText("**not shippable**"))
        assertEquals("COVERAGE.md", plainText("`COVERAGE.md`"))
        assertEquals("manual test matrix", plainText("[manual test matrix](./MANUAL_TEST_MATRIX.md)"))
        assertEquals("a b", plainText("a `b`"))
    }

    @Test
    fun `emphasis that is split across a wrapped line is still flattened`() {
        val blocks = ChangelogMarkdown.parse("This is **not\nshippable** yet.")
        assertEquals(listOf(ChangelogBlock.Paragraph("This is not shippable yet.")), blocks)
    }

    @Test
    fun `the repository changelog parses to a title and no markup is left in any block`() {
        val file = File("../docs/CHANGELOG.md")
        assertTrue("docs/CHANGELOG.md is not at ${file.absolutePath}", file.isFile)

        val blocks = ChangelogMarkdown.parse(file.readText())

        assertEquals(ChangelogBlock.Heading(1, "Changelog"), blocks.first())
        assertTrue(blocks.any { it is ChangelogBlock.Bullet })
        blocks.forEach { block ->
            assertFalse("markup left in: ${block.text}", block.text.contains("`") || block.text.contains("**"))
            assertFalse("a link target left in: ${block.text}", block.text.contains("]("))
        }
    }
}
