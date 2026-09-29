package dev.opencode.android.core.data.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JSONC masking, and the property it exists for: **positions survive**.
 *
 * The schema publishes `allowComments: true` and `allowTrailingCommas: true`, so a valid
 * `opencode.jsonc` contains bytes plain JSON rejects, and the masking has to remove exactly those two
 * things. Every other byte has to stay where it was, because a diagnostic that reports line 4 for a
 * problem on line 40 of a file on a phone is a diagnostic the user cannot act on — and P6 found the
 * same class of bug in the file viewer, where the content type was asked before the bytes.
 */
class JsoncTest {

    @Test
    fun `masking keeps the length, the lines and the columns`() {
        val source = """
            {
              // the default model
              "model": "placeholder-provider/placeholder-model",

              /* a block
                 comment over two lines */
              "share": "manual",
            }
        """.trimIndent()

        val masked = Jsonc.mask(source)

        assertEquals("masking must not move a single byte", source.length, masked.length)
        assertEquals("masking must not remove a line", source.lines().size, masked.lines().size)
        // The exact answer, spelled out. An inequality would pass on a mask that also blanked a value,
        // which is the failure this whole class exists to make impossible: a user would be told their
        // model line was wrong when the comment above it was.
        val spaces = " ".repeat(30)
        val expected = listOf(
            "{",
            "  ${spaces.take("// the default model".length)}",
            """  "model": "placeholder-provider/placeholder-model",""",
            "",
            "  ${spaces.take("/* a block".length)}",
            "     ${spaces.take("comment over two lines */".length)}",
            // The trailing comma before the closing brace is masked too, which is why the line ends in
            // a space where the comma was.
            """  "share": "manual" """,
            "}",
        ).joinToString("\n")
        assertEquals(expected, masked)
        // And every value survived, which is the part that matters.
        assertTrue(masked.contains("""placeholder-provider/placeholder-model"""))
        assertTrue(masked.contains(""""manual""""))
    }

    @Test
    fun `a document with no comments is returned unchanged`() {
        // The baseline every "only the comment changed" claim rests on.
        val source = """{"a": 1, "b": ["x", "y"], "c": {"d": true}}"""
        assertEquals(source, Jsonc.mask(source))
    }

    @Test
    fun `a masked comment leaves the value after it untouched`() {
        // The comma is real — the next meaningful byte is a key, not a close — and the space before
        // the comment is real. Only the comment's own six characters are blanked.
        val source = "{ \"a\": 1, // note\n  \"b\": 2 }"
        val masked = Jsonc.mask(source)
        assertEquals("{ \"a\": 1, ${" ".repeat(7)}\n  \"b\": 2 }", masked)
        assertTrue(masked.contains("\"b\""))
    }

    @Test
    fun `a block comment keeps its line breaks so later lines do not move`() {
        val source = "{/* one\ntwo\nthree */\"a\":1}"
        val masked = Jsonc.mask(source)
        assertEquals(2, masked.count { it == '\n' })
        assertEquals(source.indexOf("\"a\""), masked.indexOf("\"a\""))
        // The two comment lines are blank rather than deleted, which is what "does not move" means.
        // Line one still has the `{` and line three the closing key, so only the middle line is blank.
        assertEquals(1, masked.lines().count { it.isBlank() })
    }

    @Test
    fun `a URL inside a string is not a comment`() {
        // Two `//` and a `/*`-looking pair in one line. A regex, or a scanner that did not track string
        // state, would blank the value and leave the document unreadable.
        val source = """{"url":"https://example.com/a/*b*/c","model":"x/y"}"""
        assertEquals(source, Jsonc.mask(source))
    }

    @Test
    fun `an escaped quote inside a string does not end it`() {
        val source = """{"note":"a \" b // c","model":"x/y"}"""
        assertEquals(source, Jsonc.mask(source))
    }

    @Test
    fun `a trailing comma before a close brace is removed`() {
        // The comma becomes a space, so the space that was already there is still there: the mask
        // never closes up a gap, which is what keeps the columns stable.
        assertEquals("""{"a":1  }""", Jsonc.mask("""{"a":1, }"""))
        assertEquals("""{"a":[1  ] }""", Jsonc.mask("""{"a":[1, ] }"""))
        assertEquals("""{"a":1 }""", Jsonc.mask("""{"a":1,}"""))
        assertEquals("""{ "a" : 1   }""", Jsonc.mask("""{ "a" : 1 , }"""))
    }

    @Test
    fun `a trailing comma followed by a comment is removed`() {
        // Both the comma and the comment go, and the newline between them survives, so the close brace
        // is still on its own line and a diagnostic below it does not move.
        val masked = Jsonc.mask("""{"a":1, // done
}""")
        assertEquals("""{"a":1  ${" ".repeat(7)}
}""", masked)
    }

    @Test
    fun `a comma that is not trailing is kept`() {
        val source = """{"a":1, "b":2}"""
        assertEquals(source, Jsonc.mask(source))
        // A comma after a close brace belongs to nothing and must survive: `{"a":1} ,` is unusual but
        // it is not a trailing comma, and blanking it would change the document's meaning.
        assertEquals("""{"a":1} ,""", Jsonc.mask("""{"a":1} ,"""))
        // Nor is a comma before a comment that is followed by a real member.
        val withComment = "{ \"a\": 1, // note\n  \"b\": 2 }"
        assertTrue(Jsonc.mask(withComment).contains(","))
    }

    @Test
    fun `an empty object and an empty array are left alone`() {
        assertEquals("{}", Jsonc.mask("{}"))
        assertEquals("[]", Jsonc.mask("[]"))
    }

    // ------------------------------------------------------------------------------ positions

    @Test
    fun `a position is one-based and points at the byte asked for`() {
        val text = "abc\ndefg\nhi"
        assertEquals(Jsonc.Position(1, 1, 0), Jsonc.positionOf(text, 0))
        assertEquals(Jsonc.Position(1, 3, 2), Jsonc.positionOf(text, 2))
        assertEquals(Jsonc.Position(2, 1, 4), Jsonc.positionOf(text, 4))
        assertEquals(Jsonc.Position(3, 1, 9), Jsonc.positionOf(text, 9))
        assertEquals(Jsonc.Position(3, 2, 10), Jsonc.positionOf(text, 10))
    }

    @Test
    fun `a position in a masked document matches the original file`() {
        // The whole reason masking is byte-for-byte: the same offset names the same place in both.
        val source = "{\n  // note\n  \"model\": \"x\",\n  \"broken\"\n}"
        val masked = Jsonc.mask(source)
        val offset = masked.indexOf("\"broken\"")
        assertEquals(Jsonc.positionOf(source, offset), Jsonc.positionOf(masked, offset))
        assertEquals(4, Jsonc.positionOf(source, offset).line)
    }

    @Test
    fun `a position past the end is the last position, not the first`() {
        val text = "ab"
        assertEquals(Jsonc.Position(1, 3, 2), Jsonc.positionOf(text, 99))
    }

    @Test
    fun `a token position is found or is absent`() {
        val text = "{\n  \"model\": \"x\"\n}"
        assertEquals(2, Jsonc.positionOfToken(text, "\"model\"")?.line)
        assertNull(Jsonc.positionOfToken(text, "\"missing\""))
    }

    @Test
    fun `blankness is decided on whitespace alone`() {
        assertTrue(Jsonc.isBlank(""))
        assertTrue(Jsonc.isBlank("   \n\t "))
        assertTrue(Jsonc.isBlank("// only a comment"))
        assertFalse(Jsonc.isBlank("{}"))
    }

    @Test
    fun `masking is idempotent, so a second pass changes nothing`() {
        val source = """
            {
              // note
              "a": [1, 2,],
              /* tail */
            }
        """.trimIndent()
        val once = Jsonc.mask(source)
        assertEquals(once, Jsonc.mask(once))
        assertNotEquals(source, once)
    }
}
