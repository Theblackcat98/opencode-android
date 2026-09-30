package dev.opencode.android.core.data.execution

import dev.opencode.android.core.model.ShellOutput
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Streaming a command's output by byte cursor (features doc §30).
 *
 * **The tests are about the cursor and about when to stop.** A poller that advances the cursor locally
 * skips output; one that never advances it asks for the same page forever; and one that stops on the first
 * page that has caught up — which is what this used to do — stops before a build has printed anything,
 * which is the bug manual test F1 found. The loop tests drive it on the test scheduler's virtual clock, so
 * "it kept asking" and "it did not spin" are counts and timestamps rather than sleeps.
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
    fun `truncated is remembered, and a later page does not take it back`() {
        val poller = ShellOutputPoller()
        poller.accept(page(output = "abc", cursor = 3, size = 3, truncated = true))
        poller.accept(page(output = "", cursor = 3, size = 3, truncated = false))
        assertTrue(poller.state().truncated)
        // Nothing to fetch: `truncated` is a fact about the output, not a promise of another page.
        assertFalse(poller.hasMore())
    }

    @Test
    fun `an exited command with bytes still waiting has more to read`() {
        // The tail written just before the end is read *after* the exit is known. Treating an exit as "no
        // more pages" is how the last lines of a build went missing.
        val poller = ShellOutputPoller()
        poller.accept(page(output = "abc", cursor = 3, size = 100))
        poller.exit("killed", null)
        assertTrue(poller.hasMore())
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
        val poller = ShellOutputPoller(intervalMillis = 100)
        val asked = mutableListOf<Long>()

        poller.start(
            scope = backgroundScope,
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
        // The server no longer has the command, so its output route went with it. Dropping the page on
        // that `404` would be a worse lie than showing a command whose end was not read.
        assertEquals("building", poller.state().text)
        assertNull(poller.state().error)
        assertFalse(poller.isPolling)
    }

    @Test
    fun `an empty page from a running command is not the end`() = runTest {
        // The F1 case: the first poll runs before the command has printed anything, and answers the page
        // the real server answers for a cursor at the end of nothing.
        val poller = ShellOutputPoller(intervalMillis = 100)
        val asked = mutableListOf<Long>()

        poller.start(
            scope = backgroundScope,
            page = { cursor ->
                asked += cursor
                page(output = "", cursor = 0, size = 0)
            },
            onState = {},
        )
        advanceTimeBy(5_000)

        assertTrue("it kept asking: $asked", asked.size >= 10)
        assertTrue("always from the cursor the server gave", asked.all { it == 0L })
        assertTrue(poller.isPolling)
    }

    @Test
    fun `a page that has caught up with a running command is not the end either`() = runTest {
        val poller = ShellOutputPoller(intervalMillis = 100)
        val asked = mutableListOf<Long>()

        poller.start(
            scope = backgroundScope,
            page = { cursor ->
                asked += cursor
                // "one\n" is there at the first poll; nothing more ever comes while the loop is watched.
                if (cursor == 0L) page(output = "one\n", cursor = 4, size = 4) else page("", 4, 4)
            },
            onState = {},
        )
        advanceTimeBy(5_000)

        assertEquals("one\n", poller.state().text)
        assertTrue("it went on asking from 4: $asked", asked.count { it == 4L } >= 5)
        assertTrue(poller.isPolling)
    }

    @Test
    fun `pages that keep the cursor moving are read without a pause`() = runTest {
        // The server reads at most 64 KiB a page, so a command that printed a lot is several pages, and
        // waiting an interval between them would make a burst of output arrive at one page a second.
        val poller = ShellOutputPoller(intervalMillis = 1_000)
        val at = mutableListOf<Long>()

        poller.start(
            scope = backgroundScope,
            page = { cursor ->
                at += currentTime
                if (cursor < 30) page(output = "0123456789", cursor = cursor + 10, size = 30) else page("", 30, 30)
            },
            onState = {},
        )
        advanceTimeBy(10)

        assertEquals("three pages, one after another, and then the wait", listOf(0L, 0L, 0L), at)
        assertEquals(30L, poller.state().cursor)
    }

    @Test
    fun `an answer that moves nothing while the size is ahead is waited out, not spun on`() = runTest {
        // The server counts bytes when it receives them and writes them a moment later, so `cursor < size`
        // with an empty page is "ask again shortly". Asking again at once would be a tight request loop.
        val poller = ShellOutputPoller(intervalMillis = 100)
        val at = mutableListOf<Long>()

        poller.start(
            scope = backgroundScope,
            page = { _ ->
                at += currentTime
                page(output = "", cursor = 0, size = 50)
            },
            onState = {},
        )
        advanceTimeBy(1_000)

        assertTrue("a pause between every ask: $at", at.zipWithNext().all { (a, b) -> b - a >= 100 })
        assertTrue(at.size in 3..11)
    }

    @Test
    fun `a quiet command is asked about less often, and the first byte ends the quiet`() = runTest {
        val poller = ShellOutputPoller(intervalMillis = 100)
        val at = mutableListOf<Long>()
        var printed = false

        poller.start(
            scope = backgroundScope,
            page = { cursor ->
                at += currentTime
                if (printed && cursor == 0L) page("hi", 2, 2) else page("", cursor, cursor)
            },
            onState = {},
        )
        advanceTimeBy(2_000)
        val gaps = at.zipWithNext().map { (a, b) -> b - a }
        assertEquals("waits grow from 1x to 3x the interval and stay there", listOf(100L, 200L), gaps.take(2))
        assertTrue("and stay at 3x: $gaps", gaps.drop(2).all { it == 300L })

        printed = true
        advanceTimeBy(400)
        assertEquals("hi", poller.state().text)
    }

    @Test
    fun `the loop keeps polling while the size grows`() = runTest {
        val poller = ShellOutputPoller(intervalMillis = 100)
        val asked = mutableListOf<Long>()
        var size = 3L

        poller.start(
            scope = backgroundScope,
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

        assertEquals(listOf(0L, 3L, 6L), asked.take(3))
    }

    @Test
    fun `an exit the status read finds ends the loop after one more read`() = runTest {
        val poller = ShellOutputPoller(intervalMillis = 100)
        val asked = mutableListOf<Long>()
        var statusReads = 0
        var lastPrinted = false

        poller.start(
            scope = backgroundScope,
            page = { cursor ->
                asked += cursor
                // "two\n" is written between the read that found nothing and the read after the exit: it has
                // to be read, and it can only be read if the exit is learned before the last page.
                when {
                    cursor == 0L -> page("one\n", 4, 4)
                    lastPrinted -> page("two\n", 8, 8)
                    else -> page("", cursor, cursor)
                }
            },
            status = {
                statusReads++
                if (statusReads == 2) {
                    lastPrinted = true
                    ShellExit("exited", 0)
                } else {
                    null
                }
            },
            onState = {},
        )
        advanceTimeBy(10_000)

        assertEquals("one\ntwo\n", poller.state().text)
        assertTrue(poller.state().exited)
        assertEquals(0, poller.state().exitCode)
        assertFalse("the loop ended", poller.isPolling)
        assertEquals("the read after the exit is the last one", listOf(0L, 4L, 4L, 4L), asked)
        assertEquals(2, statusReads)
    }

    @Test
    fun `an exit reported from outside is followed by exactly one draining read`() = runTest {
        val poller = ShellOutputPoller(intervalMillis = 100)
        val asked = mutableListOf<Long>()
        poller.start(
            scope = backgroundScope,
            page = { cursor ->
                asked += cursor
                if (cursor == 0L) page("abc", 3, 3) else page("", cursor, cursor)
            },
            onState = {},
        )
        advanceTimeBy(1_000)
        val beforeExit = asked.size

        poller.exit("exited", 0)
        advanceTimeBy(5_000)

        assertEquals("one read after the exit, then it stopped", beforeExit + 1, asked.size)
        assertFalse(poller.isPolling)
    }

    @Test
    fun `an exited command whose bytes are still not there is given up on after a few reads`() = runTest {
        val poller = ShellOutputPoller(intervalMillis = 100)
        var asked = 0
        poller.exit("exited", 1)

        poller.start(
            scope = backgroundScope,
            page = { _ ->
                asked++
                page(output = "", cursor = 0, size = 10)
            },
            onState = {},
        )
        advanceTimeBy(10_000)

        assertEquals(ShellOutputPoller.FINAL_IDLE_LIMIT, asked)
        assertFalse(poller.isPolling)
    }

    @Test
    fun `an exit is read from the start when the command was already over`() = runTest {
        val poller = ShellOutputPoller(intervalMillis = 100)
        var asked = 0
        poller.exit("exited", 0)

        poller.start(
            scope = backgroundScope,
            page = { _ ->
                asked++
                page(output = "one\ntwo\nthree\n", cursor = 14, size = 14)
            },
            onState = {},
        )
        advanceTimeBy(10_000)

        assertEquals("one\ntwo\nthree\n", poller.state().text)
        assertEquals("a single read: the page caught up and the command had ended", 1, asked)
    }

    @Test
    fun `a failed read is retried and a working one clears the note`() = runTest {
        val poller = ShellOutputPoller(intervalMillis = 100)
        var failures = 2

        poller.start(
            scope = backgroundScope,
            page = { cursor ->
                if (failures-- > 0) throw IOException("connection reset")
                if (cursor == 0L) page("one\n", 4, 4) else page("", cursor, cursor)
            },
            onState = {},
        )
        advanceTimeBy(2_000)

        assertEquals("one\n", poller.state().text)
        assertNull(poller.state().error)
        assertTrue(poller.isPolling)
    }

    @Test
    fun `reads that keep failing stop the loop and say why`() = runTest {
        val poller = ShellOutputPoller(intervalMillis = 100)
        var asked = 0

        poller.start(
            scope = backgroundScope,
            page = { _ ->
                asked++
                throw IOException("connection reset")
            },
            onState = {},
        )
        advanceTimeBy(60_000)

        assertEquals(ShellOutputPoller.MAX_FAILURES, asked)
        assertEquals("connection reset", poller.state().error)
        assertFalse(poller.isPolling)
    }

    @Test
    fun `stopping ends the loop and a start again continues from the cursor`() = runTest {
        val poller = ShellOutputPoller(intervalMillis = 100)
        val asked = mutableListOf<Long>()
        val read: suspend (Long) -> ShellOutput? = { cursor ->
            asked += cursor
            if (cursor == 0L) page("one\n", 4, 4) else page("", cursor, cursor)
        }

        poller.start(scope = backgroundScope, page = read, onState = {})
        advanceTimeBy(300)
        poller.stop()
        val stoppedAt = asked.size
        advanceTimeBy(5_000)
        assertEquals("nothing is asked while stopped", stoppedAt, asked.size)

        poller.start(scope = backgroundScope, page = read, onState = {})
        advanceTimeBy(50)
        assertEquals("it resumed from 4, not from the beginning", 4L, asked.last())
        assertEquals("nothing was read twice", "one\n", poller.state().text)
    }

    private fun page(output: String, cursor: Long, size: Long, truncated: Boolean = false) = ShellOutput(
        output = output,
        cursor = cursor,
        size = size,
        truncated = truncated,
    )
}
