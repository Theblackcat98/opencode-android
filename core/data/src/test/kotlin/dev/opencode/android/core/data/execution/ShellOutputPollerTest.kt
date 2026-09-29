package dev.opencode.android.core.data.execution

import dev.opencode.android.core.model.ShellOutput
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Streaming a command's output by byte cursor (features doc §30).
 *
 * **The tests are about the cursor, because the cursor is the only state that can be got wrong.** A
 * poller that advances it locally skips output; one that never advances it asks for the same page
 * forever; one that stops on the first empty page stops before a build that has not finished printing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ShellOutputPollerTest {

    @Test
    fun `pages are appended and the cursor is the server's`() {
        val poller = ShellOutputPoller()
        poller.accept(page(output = "one ", cursor = 4, size = 9))
        poller.accept(page(output = "two", cursor = 7, size = 9))
        val state = poller.state()
        assertEquals("one two", state.text)
        assertEquals(7L, state.cursor)
        assertEquals(9L, state.size)
    }

    @Test
    fun `a page behind the cursor is appended but does not rewind it`() {
        // A server answering a stale request. Dropping the text would lose output the server chose to
        // send; rewinding the cursor would make the next poll re-read everything after it.
        val poller = ShellOutputPoller()
        poller.accept(page(output = "aaaa", cursor = 4, size = 8))
        poller.accept(page(output = "bb", cursor = 2, size = 8))
        assertEquals("aaaabb", poller.state().text)
        assertEquals(4L, poller.state().cursor)
    }

    @Test
    fun `the buffer keeps the tail and says the page was truncated`() {
        val poller = ShellOutputPoller(maxChars = 8)
        poller.accept(page(output = "0123456789", cursor = 10, size = 10, truncated = true))
        assertEquals("23456789", poller.state().text)
        assertTrue(poller.state().truncated)
    }

    @Test
    fun `more exists while the cursor is short of the size`() {
        val poller = ShellOutputPoller()
        poller.accept(page(output = "abc", cursor = 3, size = 100))
        assertTrue(poller.hasMore())
        poller.accept(page(output = "def", cursor = 6, size = 6))
        assertFalse(poller.hasMore())
    }

    @Test
    fun `a truncated page means more exists even at the size`() {
        val poller = ShellOutputPoller()
        poller.accept(page(output = "abc", cursor = 3, size = 3, truncated = true))
        assertTrue(poller.hasMore())
    }

    @Test
    fun `an exited command is never polled again`() {
        val poller = ShellOutputPoller()
        poller.accept(page(output = "abc", cursor = 3, size = 100))
        poller.exit("killed", null)
        assertFalse(poller.hasMore())
        assertEquals("killed", poller.state().status)
    }

    @Test
    fun `the exit code is recorded`() {
        val poller = ShellOutputPoller()
        poller.exit("exited", 2)
        assertEquals(2, poller.state().exitCode)
        assertTrue(poller.state().exited)
    }

    @Test
    fun `reset forgets the cursor so a reused panel cannot ask from the old position`() {
        val poller = ShellOutputPoller()
        poller.accept(page(output = "abc", cursor = 3, size = 3))
        poller.reset()
        assertEquals(0L, poller.state().cursor)
        assertEquals("", poller.state().text)
    }

    @Test
    fun `the loop stops when the command is gone and keeps the last page on screen`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val poller = ShellOutputPoller(intervalMillis = 100)
        val asked = mutableListOf<Long>()

        poller.start(
            scope = scope,
            page = { cursor ->
                asked += cursor
                // The first page leaves the cursor short of the size, so the loop asks again; the
                // second ask is the one that finds the command gone.
                if (asked.size == 1) page(output = "building", cursor = 8, size = 12) else null
            },
            onState = {},
        )
        advanceTimeBy(1_000)

        assertEquals(listOf(0L, 8L), asked)
        // The command left `shell.list` when it exited, so its output route went with it. Dropping the
        // page on that `404` would be a worse lie than showing a command whose end was not read.
        assertEquals("building", poller.state().text)
        assertNull(poller.state().error)
    }

    @Test
    fun `the loop keeps polling while the size grows`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val poller = ShellOutputPoller(intervalMillis = 100)
        val asked = mutableListOf<Long>()
        var size = 3L

        poller.start(
            scope = scope,
            page = { cursor ->
                asked += cursor
                if (asked.size < 3) {
                    val text = "x".repeat(3)
                    size += 3
                    page(output = text, cursor = cursor + 3, size = size)
                } else {
                    page(output = "", cursor = size, size = size)
                }
            },
            onState = {},
        )
        advanceTimeBy(1_000)

        assertEquals(listOf(0L, 3L, 6L), asked)
    }

    private fun page(output: String, cursor: Long, size: Long, truncated: Boolean = false) = ShellOutput(
        output = output,
        cursor = cursor,
        size = size,
        truncated = truncated,
    )
}
