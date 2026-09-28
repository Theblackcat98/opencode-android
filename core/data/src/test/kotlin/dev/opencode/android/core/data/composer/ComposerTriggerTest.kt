package dev.opencode.android.core.data.composer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Where a trigger is allowed, and what range a completion replaces.
 *
 * The property that matters is the negative one: text that merely *contains* a trigger character is
 * a message, and the composer has to treat it as one. Every case below is a thing a person types
 * while typing something else, which is why they are tests and not examples.
 */
class ComposerTriggerTest {

    @Test
    fun `a mention at the start of the input opens a completion`() {
        val span = detectTrigger("@src")
        assertEquals(TriggerKind.MENTION, span?.kind)
        assertEquals("@src", span?.textIn("@src"))
        assertEquals("src", span?.query)
    }

    @Test
    fun `a mention after a space opens a completion`() {
        val text = "look at @src/a.ts"
        val span = detectTrigger(text)
        assertEquals(TriggerKind.MENTION, span?.kind)
        assertEquals("@src/a.ts", span?.textIn(text))
    }

    @Test
    fun `a bare at sign offers the whole list rather than nothing`() {
        val span = detectTrigger("@")
        assertEquals(TriggerKind.MENTION, span?.kind)
        assertEquals("", span?.query)
    }

    @Test
    fun `an address is not a mention`() {
        // The `@` is not at a word boundary, so nothing is being attached to a prompt.
        assertNull(detectTrigger("mail me at nick@example.com"))
        assertNull(detectTrigger("nick@example.com "))
    }

    @Test
    fun `a mention is only triggered by a token made of path characters`() {
        assertNull(detectTrigger("see @\"quoted\""))
        assertNull(detectTrigger("@/work/a b"))
    }

    @Test
    fun `the completion span covers the whole token, not only what is before the caret`() {
        // Caret after "@src/", the user is about to replace the entire token.
        val text = "@src/a.ts"
        val span = detectTrigger(text, cursor = 5)
        assertEquals(0, span?.start)
        assertEquals(text.length, span?.end)
        assertEquals("src/", span?.query)
    }

    @Test
    fun `a line range typed so far is kept in the query`() {
        val span = detectTrigger("@src/a.ts#20-")
        assertEquals(TriggerKind.MENTION, span?.kind)
        assertEquals("src/a.ts#20-", span?.query)
    }

    @Test
    fun `a leading slash is a command, and only a leading slash`() {
        val span = detectTrigger("/comp")
        assertEquals(TriggerKind.COMMAND, span?.kind)
        assertEquals("comp", span?.query)
    }

    @Test
    fun `a slash inside a message is not a command`() {
        assertNull(detectTrigger("run /usr/bin/tests"))
        assertNull(detectTrigger("try ./gradlew test"))
    }

    @Test
    fun `the command palette closes once an argument is being typed`() {
        // The name is finished and the caret is past the space, so there is nothing to complete.
        assertNull(detectTrigger("/compact now ", cursor = 13))
        assertNotNull(detectTrigger("/compact now", cursor = 8))
    }

    @Test
    fun `a caret on the other side of the command name is not completing it`() {
        assertNull(detectTrigger("/compact", cursor = 0))
    }

    @Test
    fun `a leading bang is a shell line and covers the whole line`() {
        val text = "!git status --short"
        val span = detectTrigger(text)
        assertEquals(TriggerKind.SHELL, span?.kind)
        assertEquals(text, span?.textIn(text))
        assertEquals("git status --short", span?.query)
    }

    @Test
    fun `a shell line stops at the end of its line`() {
        val text = "!ls -la\nand then this"
        val span = detectTrigger(text)
        assertEquals("!ls -la", span?.textIn(text))
    }

    @Test
    fun `a bang inside a message is not a shell line`() {
        assertNull(detectTrigger("what does ! mean"))
        assertNull(detectTrigger("wow!"))
    }

    @Test
    fun `leading whitespace does not stop a leading slash from being a command`() {
        val span = detectTrigger("  /models")
        assertEquals(TriggerKind.COMMAND, span?.kind)
        assertEquals(2, span?.start)
    }

    @Test
    fun `an empty composer has no trigger`() {
        assertNull(detectTrigger(""))
        assertNull(detectTrigger("   "))
        assertNull(detectTrigger("\n\n"))
    }

    @Test
    fun `a caret past the end of the text is clamped rather than throwing`() {
        val span = detectTrigger("@src", cursor = 99)
        assertEquals(TriggerKind.MENTION, span?.kind)
        assertEquals("src", span?.query)
    }

    @Test
    fun `a caret before the start of the text has no trigger`() {
        assertNull(detectTrigger("@src", cursor = -5))
    }

    @Test
    fun `a mention in a later line is found`() {
        val text = "first line\nsecond @do"
        val span = detectTrigger(text)
        assertEquals(TriggerKind.MENTION, span?.kind)
        assertEquals("@do", span?.textIn(text))
    }

    @Test
    fun `a second mention in the same line is the one under the caret`() {
        val text = "@a and @b"
        assertEquals("@b", detectTrigger(text, cursor = text.length)?.textIn(text))
        assertEquals("@a", detectTrigger(text, cursor = 1)?.textIn(text))
    }
}
