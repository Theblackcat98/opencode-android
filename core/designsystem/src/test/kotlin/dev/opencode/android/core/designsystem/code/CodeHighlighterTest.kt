package dev.opencode.android.core.designsystem.code

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The highlighter, over the property that matters: **the runs tile the line exactly**.
 *
 * A renderer draws one span per run, so a gap is missing text and an overlap is doubled text. Every
 * test here therefore checks the tiling as well as the kinds, and the corpus includes the inputs
 * that would break a naive scanner: an unterminated string, a block comment that never closes, a
 * line of only a marker, a very long line, a line of a language the app has no rules for, and an
 * empty line.
 */
class CodeHighlighterTest {

    private fun kindsOf(text: String, language: CodeLanguage): List<CodeTokenKind> =
        CodeHighlighter.highlight(text, language).tokens.map { it.kind }

    private fun assertTiles(text: String, language: CodeLanguage) {
        val line = CodeHighlighter.highlight(text, language)
        if (text.isEmpty()) {
            // An empty line is one empty run, which is a legal answer: a renderer drawing nothing
            // for it is correct, and one that assumed a run existed is not.
            assertEquals(1, line.tokens.size)
            assertEquals(0, line.tokens.single().start)
            assertEquals(0, line.tokens.single().end)
            return
        }
        var at = 0
        line.tokens.forEach { token ->
            assertEquals("a gap or overlap at $at in '$text' (runs=${line.tokens})", at, token.start)
            assertTrue("an empty run in '$text'", token.end > token.start)
            at = token.end
        }
        assertEquals("the runs do not reach the end of '$text'", text.length, at)
    }

    @Test
    fun `an empty line is one run, not none`() {
        val line = CodeHighlighter.highlight("", CodeLanguage.KOTLIN)

        // A renderer that iterates runs and draws nothing for an empty run is correct; one that
        // assumes a run exists is not.
        assertEquals(1, line.tokens.size)
        assertEquals(0, line.tokens.single().start)
        assertEquals(0, line.tokens.single().end)
    }

    @Test
    fun `a keyword, a string and a number are three different runs`() {
        val kinds = kindsOf("""val x = "a" + 1""", CodeLanguage.KOTLIN)

        assertTrue(CodeTokenKind.KEYWORD in kinds)
        assertTrue(CodeTokenKind.STRING in kinds)
        assertTrue(CodeTokenKind.NUMBER in kinds)
    }

    @Test
    fun `a line comment wins over everything after it`() {
        val kinds = kindsOf("val x = 1 // val y = \"not a string\" ", CodeLanguage.KOTLIN)

        // The quote inside the comment must not open a string that swallows the rest of the file,
        // which is the assertion that matters: there is no string run at all.
        assertEquals(CodeTokenKind.COMMENT, kinds.last())
        assertEquals(0, kinds.count { it == CodeTokenKind.STRING })
    }

    @Test
    fun `a block comment does not run past its close`() {
        val line = CodeHighlighter.highlight("/* a */ val /* b */ x = 1", CodeLanguage.CPP)

        assertEquals(2, line.tokens.count { it.kind == CodeTokenKind.COMMENT })
        assertTiles("/* a */ val /* b */ x = 1", CodeLanguage.CPP)
    }

    @Test
    fun `an unterminated block comment still tiles the line`() {
        val text = "val x = 1 /* never closed"
        assertTiles(text, CodeLanguage.CPP)
        assertEquals(CodeTokenKind.COMMENT, CodeHighlighter.highlight(text, CodeLanguage.CPP).tokens.last().kind)
    }

    @Test
    fun `an unterminated string still tiles the line`() {
        val text = """val x = "never closed"""
        assertTiles(text, CodeLanguage.KOTLIN)
        assertEquals(CodeTokenKind.STRING, kindsOf(text, CodeLanguage.KOTLIN).last())
    }

    @Test
    fun `a triple quoted literal is a string, and a fourth quote is not the end of it`() {
        val text = "val doc = \"\"\"a \"quote\" inside\"\"\""
        val line = CodeHighlighter.highlight(text, CodeLanguage.KOTLIN)

        assertTiles(text, CodeLanguage.KOTLIN)
        assertTrue(CodeTokenKind.STRING in line.tokens.map { it.kind })
    }

    @Test
    fun `a raw string carries its state to the next line and drops it at the close`() {
        val first = CodeHighlighter.highlight("val doc = \"\"\"", CodeLanguage.KOTLIN, 1)
        assertTrue("a triple quote opens a literal that is still open", first.carry.open)
        assertEquals("\"\"\"", first.carry.openStringDelimiter)

        val second = CodeHighlighter.highlight("still inside", CodeLanguage.KOTLIN, 2, first.carry)
        assertTrue(second.tokens.all { it.kind == CodeTokenKind.STRING })

        val third = CodeHighlighter.highlight("\"\"\" + 1", CodeLanguage.KOTLIN, 3, second.carry)
        assertFalse("the closing marker ends the literal", third.carry.open)
        assertTrue(CodeTokenKind.NUMBER in third.tokens.map { it.kind })
    }

    @Test
    fun `a diff viewer's line is highlighted with no carry at all`() {
        // The diff viewer's case: a hunk's first line has no context above it, so a literal opened
        // on the previous line must not colour this one.
        val line = CodeHighlighter.highlight("""val x = 1""", CodeLanguage.KOTLIN, 1, LineCarry.NONE)

        assertFalse(line.carry.open)
    }

    @Test
    fun `highlighting a whole file threads the carry and does not leak it out`() {
        val source = """
            fun main() {
                val doc = ""${'"'}
                    hello
                ""${'"'}
                println(doc)
            }
        """.trimIndent()
        val lines = CodeHighlighter.highlightAll(source, CodeLanguage.KOTLIN)

        assertEquals(source.lines().size, lines.size)
        // The closing `"""` really closed it: nothing after it is a string run any more.
        val last = lines.last()
        assertFalse(CodeTokenKind.STRING in last.tokens.map { it.kind })
    }

    @Test
    fun `an annotation is its own run`() {
        val kinds = kindsOf("@Composable fun Screen()", CodeLanguage.KOTLIN)

        assertEquals(CodeTokenKind.ANNOTATION, kinds.first())
    }

    @Test
    fun `a Python decorator is an annotation`() {
        assertEquals(CodeTokenKind.ANNOTATION, kindsOf("@app.route('/')", CodeLanguage.PYTHON).first())
    }

    @Test
    fun `a YAML key separator is a marker run`() {
        val kinds = kindsOf("name: opencode", CodeLanguage.YAML)

        assertEquals(listOf(CodeTokenKind.PLAIN, CodeTokenKind.MARKER, CodeTokenKind.PLAIN), kinds)
    }

    @Test
    fun `a patch body's marker is coloured and the rest is not`() {
        val kinds = kindsOf("+val x = 1", CodeLanguage.DIFF)

        // The patch rules have no keywords on purpose: a diff viewer's own +/- colouring already
        // says what changed, and a second language's keywords underneath it would be noise.
        assertEquals(listOf(CodeTokenKind.MARKER, CodeTokenKind.PLAIN), kinds)
    }

    @Test
    fun `a patch hunk header is one marker run`() {
        assertEquals(listOf(CodeTokenKind.MARKER), kindsOf("@@ -1,3 +1,4 @@", CodeLanguage.DIFF))
    }

    @Test
    fun `an unterminated hunk header is still one marker run`() {
        assertEquals(listOf(CodeTokenKind.MARKER), kindsOf("@@ -1,3 +1,4", CodeLanguage.DIFF))
    }

    @Test
    fun `a Markdown heading marker and a table pipe are markers`() {
        assertEquals(CodeTokenKind.MARKER, kindsOf("## Title", CodeLanguage.MARKDOWN).first())
        assertTrue(CodeTokenKind.MARKER in kindsOf("| a | b |", CodeLanguage.MARKDOWN))
    }

    @Test
    fun `an XML tag is an annotation and the text after it is not`() {
        val kinds = kindsOf("""<tag attr="v">text</tag>""", CodeLanguage.XML)

        assertEquals(CodeTokenKind.ANNOTATION, kinds.first())
        assertEquals(CodeTokenKind.ANNOTATION, kinds.last())
    }

    @Test
    fun `a JSON value is a string and its booleans are keywords`() {
        val kinds = kindsOf("""{"a": true, "b": 1}""", CodeLanguage.JSON)

        assertTrue(CodeTokenKind.STRING in kinds)
        assertTrue(CodeTokenKind.KEYWORD in kinds)
        assertTrue(CodeTokenKind.NUMBER in kinds)
    }

    @Test
    fun `a shebang is a marker and swallows the line`() {
        assertEquals(listOf(CodeTokenKind.MARKER), kindsOf("#!/bin/sh", CodeLanguage.SHELL))
    }

    @Test
    fun `a language with no rules is one plain run`() {
        val line = CodeHighlighter.highlight("val x = 1 // comment", CodeLanguage.PLAIN_TEXT)

        assertEquals(1, line.tokens.size)
        assertEquals(CodeTokenKind.PLAIN, line.tokens.single().kind)
    }

    @Test
    fun `every language tiles an ordinary line, an empty line and a bare marker`() {
        val samples = listOf("", "x", "  ", "\"", "'''", "\\", "+", "-", "@", "#", "a\"b", "\t\t")
        CodeLanguage.entries.forEach { language ->
            samples.forEach { text -> assertTiles(text, language) }
        }
    }

    @Test
    fun `a very long line is linear and tiles exactly`() {
        val text = "val x = \"" + "a".repeat(200_000) + "\""
        val started = System.nanoTime()
        val line = CodeHighlighter.highlight(text, CodeLanguage.KOTLIN)
        val millis = (System.nanoTime() - started) / 1_000_000

        assertTiles(text, CodeLanguage.KOTLIN)
        assertTrue("highlighting 200k characters took $millis ms", millis < 1_000)
        assertEquals(text.length, line.tokens.last().end)
    }

    @Test
    fun `token offsets are character offsets, so a tap maps to a run`() {
        val line = CodeHighlighter.highlight("""val x = "a"""", CodeLanguage.KOTLIN)

        val at = line.text.indexOf('"')
        val token = line.tokenAt(at)
        assertEquals(CodeTokenKind.STRING, token?.kind)
        assertEquals(at, token?.start)
    }

    @Test
    fun `a token past the end of the line is nothing rather than a crash`() {
        val line = CodeHighlighter.highlight("ab", CodeLanguage.KOTLIN)

        assertEquals(null, line.tokenAt(99))
    }

    @Test
    fun `a path names its language, and a file with no extension does not`() {
        assertEquals(CodeLanguage.KOTLIN, CodeLanguage.ofPath("src/main/kotlin/A.kt"))
        assertEquals(CodeLanguage.TYPESCRIPT, CodeLanguage.ofPath("web/app.tsx"))
        assertEquals(CodeLanguage.PYTHON, CodeLanguage.ofPath("tools/script.py"))
        assertEquals(CodeLanguage.PLAIN_TEXT, CodeLanguage.ofPath("Makefile"))
        assertEquals(CodeLanguage.PLAIN_TEXT, CodeLanguage.ofPath("README"))
    }

    @Test
    fun `a fenced code block's language tag is an alias`() {
        assertEquals(CodeLanguage.KOTLIN, CodeLanguage.ofTag("kotlin"))
        assertEquals(CodeLanguage.KOTLIN, CodeLanguage.ofTag("kt"))
        assertEquals(CodeLanguage.TYPESCRIPT, CodeLanguage.ofTag("TS"))
        assertEquals(CodeLanguage.SHELL, CodeLanguage.ofTag("shell"))
        assertEquals(CodeLanguage.PLAIN_TEXT, CodeLanguage.ofTag("wat"))
        assertEquals(CodeLanguage.PLAIN_TEXT, CodeLanguage.ofTag(null))
    }

    @Test
    fun `a keyword is matched exactly, because the language does`() {
        // Kotlin's `val` is a keyword and `VAL` is an identifier. Colouring an identifier as a
        // keyword is a quiet lie, so the lookup does not fold case.
        assertTrue(CodeTokenKind.KEYWORD in kindsOf("val x = 1", CodeLanguage.KOTLIN))
        assertFalse(CodeTokenKind.KEYWORD in kindsOf("VAL x = 1", CodeLanguage.KOTLIN))
    }

    @Test
    fun `SQL is the one language whose keywords fold case`() {
        assertTrue(CodeTokenKind.KEYWORD in kindsOf("select 1", CodeLanguage.SQL))
        assertTrue(CodeTokenKind.KEYWORD in kindsOf("SELECT 1", CodeLanguage.SQL))
    }
}
