package dev.opencode.android.core.data.composer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * History and stash, the two pieces of composer state the server knows nothing about.
 *
 * Both are pure lists with a cursor, and both are the kind of state that is only correct if the
 * *position* is right as well as the value: walking back into history and forward again has to
 * return to the sentence that was being written, and popping an empty stash has to be a no-op
 * rather than an error.
 */
class ComposerMemoryTest {

    private val history = listOf("first", "second", "third")

    @Test
    fun `a sent prompt goes to the front of the history`() {
        assertEquals(listOf("new", "first", "second", "third"), PromptHistory.record(history, "new"))
    }

    @Test
    fun `a prompt that is already in the history moves rather than duplicates`() {
        assertEquals(listOf("second", "first", "third"), PromptHistory.record(history, "second"))
    }

    @Test
    fun `a blank prompt is not history`() {
        assertEquals(history, PromptHistory.record(history, "   "))
        assertEquals(history, PromptHistory.record(history, ""))
    }

    @Test
    fun `the history is capped and the oldest goes`() {
        val long = List(60) { "p$it" }
        val recorded = PromptHistory.record(long, "newest")
        assertEquals(PromptHistory.LIMIT, recorded.size)
        assertEquals("newest", recorded.first())
        // The list keeps the first 50 of the 61, so the oldest kept is the one 49 prompts back.
        assertEquals("p48", recorded.last())
    }

    @Test
    fun `a prompt is stored trimmed`() {
        assertEquals(listOf("hello"), PromptHistory.record(emptyList(), "  hello  "))
    }

    @Test
    fun `the first position is the live draft`() {
        assertEquals("typing", HistoryCursor(draft = "typing").text(history))
    }

    @Test
    fun `each step back shows the next older prompt`() {
        // The history is newest first, so the first step back is the most recent past prompt.
        var cursor = HistoryCursor(draft = "typing")
        cursor = PromptHistory.older(history, cursor)
        assertEquals("first", cursor.text(history))
        cursor = PromptHistory.older(history, cursor)
        assertEquals("second", cursor.text(history))
        cursor = PromptHistory.older(history, cursor)
        assertEquals("third", cursor.text(history))
    }

    @Test
    fun `walking past the oldest stays at the oldest`() {
        var cursor = HistoryCursor(draft = "typing")
        repeat(5) { cursor = PromptHistory.older(history, cursor) }
        assertEquals(3, cursor.index)
        assertEquals("third", cursor.text(history))
    }

    @Test
    fun `the live text is remembered while the cursor is away from it`() {
        val away = PromptHistory.older(history, HistoryCursor(draft = "half a sentence"))
        assertEquals("half a sentence", away.draft)
    }

    @Test
    fun `walking forward returns to the sentence that was being written`() {
        val away = PromptHistory.older(history, PromptHistory.older(history, HistoryCursor(draft = "half a sentence")))
        assertEquals("second", away.text(history))
        assertEquals("half a sentence", away.draft)
        val back = PromptHistory.newer(history, PromptHistory.newer(history, away))
        assertEquals("half a sentence", back.text(history))
        assertEquals(0, back.index)
    }

    @Test
    fun `walking forward past the draft stays at the draft`() {
        val cursor = PromptHistory.newer(history, HistoryCursor(draft = "x"))
        assertEquals(0, cursor.index)
        assertEquals("x", cursor.text(history))
    }

    @Test
    fun `an empty history cannot be walked into`() {
        val cursor = PromptHistory.older(emptyList(), HistoryCursor(draft = "x"))
        assertEquals(0, cursor.index)
        assertEquals("x", cursor.text(history))
    }

    // --- stash -----------------------------------------------------------------------------

    private fun entry(id: String, text: String) = StashEntry(id, text, created = 0L)

    @Test
    fun `a stashed prompt goes to the front`() {
        val stashed = Stash.push(listOf(entry("1", "a")), entry("2", "b"))
        assertEquals(listOf("b", "a"), stashed.map { it.text })
    }

    @Test
    fun `the same text is not stashed twice`() {
        val stashed = Stash.push(listOf(entry("1", "a")), entry("2", "a"))
        assertEquals(listOf("a"), stashed.map { it.text })
        assertEquals("2", stashed.single().id)
    }

    @Test
    fun `a blank prompt is not stashed`() {
        val stashed = Stash.push(listOf(entry("1", "a")), entry("2", "  "))
        assertEquals(1, stashed.size)
    }

    @Test
    fun `popping takes the most recent and leaves the rest`() {
        val (rest, popped) = Stash.pop(listOf(entry("2", "b"), entry("1", "a")))
        assertEquals("b", popped?.text)
        assertEquals(listOf("a"), rest.map { it.text })
    }

    @Test
    fun `popping an empty stash changes nothing and returns nothing`() {
        val (rest, popped) = Stash.pop(emptyList())
        assertEquals(emptyList<StashEntry>(), rest)
        assertNull(popped)
    }

    @Test
    fun `a single stashed prompt survives being popped from`() {
        val (rest, popped) = Stash.pop(listOf(entry("1", "a")))
        assertEquals(emptyList<StashEntry>(), rest)
        assertEquals("a", popped?.text)
    }

    @Test
    fun `an entry can be dropped by id`() {
        val stashed = Stash.drop(listOf(entry("2", "b"), entry("1", "a")), "1")
        assertEquals(listOf("b"), stashed.map { it.text })
    }

    @Test
    fun `dropping an id that is not there changes nothing`() {
        val entries = listOf(entry("1", "a"))
        assertEquals(entries, Stash.drop(entries, "nope"))
    }

    @Test
    fun `the stash is capped and the oldest goes`() {
        val long = List(Stash.LIMIT) { entry("$it", "p$it") }
        val stashed = Stash.push(long, entry("new", "newest"))
        assertEquals(Stash.LIMIT, stashed.size)
        assertEquals("newest", stashed.first().text)
        // The list is newest first, so the oldest is the last entry and it is the one dropped.
        assertTrue(stashed.none { it.text == "p19" })
        assertEquals("p18", stashed.last().text)
    }
}
