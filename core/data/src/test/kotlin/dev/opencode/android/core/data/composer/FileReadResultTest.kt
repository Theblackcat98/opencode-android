package dev.opencode.android.core.data.composer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a [FileReadResult] says about its own size, and the bounded window a viewer draws from it.
 *
 * [FileReadResult.firstLines] exists so a file of two million empty lines or one two-megabyte line
 * cannot make the viewer build two million strings or lay out one enormous paragraph. It has to
 * agree with [FileReadResult.lines] on what a line *is*, because the two are read by the same
 * screen and a disagreement would be a line count that changes with the cap.
 */
class FileReadResultTest {

    private fun text(body: String, truncated: Boolean = false, totalBytes: Long? = null) = FileReadResult(
        path = "/work/a.txt",
        bytes = body.toByteArray(),
        kind = FileContentKind.TEXT,
        mime = "text/plain",
        text = body,
        truncated = truncated,
        totalBytes = totalBytes,
    )

    @Test
    fun `an unbounded window is exactly the lines`() {
        val shapes = listOf(
            "", "a", "a\n", "\n", "a\nb", "a\nb\n", "a\r\nb\r\n", "\n\n", "a\n\nb", "\r\n", "a\r", "line\n\n\n",
        )
        for (body in shapes) {
            val file = text(body)
            val window = file.firstLines(limit = Int.MAX_VALUE, maxLineChars = Int.MAX_VALUE)

            val shape = body.replace("\n", "\\n").replace("\r", "\\r")
            assertEquals("lines of \"$shape\"", file.lines, window.lines)
            assertEquals("count of \"$shape\"", file.lines.size, window.total)
            assertEquals(0, window.hidden)
        }
    }

    @Test
    fun `a window stops at the limit and counts the rest without building it`() {
        val file = text((1..10).joinToString("\n") { "line $it" } + "\n")

        val window = file.firstLines(limit = 3, maxLineChars = 100)

        assertEquals(listOf("line 1", "line 2", "line 3"), window.lines)
        assertEquals(10, window.total)
        assertEquals(7, window.hidden)
    }

    @Test
    fun `two million empty lines are counted and only the window is built`() {
        val file = text("\n".repeat(2_000_000))

        val window = file.firstLines(limit = 2_000, maxLineChars = 2_000)

        assertEquals(2_000, window.lines.size)
        assertEquals(2_000_000, window.total)
    }

    @Test
    fun `a line longer than the width is cut and the cut is visible on the line`() {
        val file = text("x".repeat(50) + "\n" + "y".repeat(20) + "\n" + "z".repeat(20) + "\r\n")

        val window = file.firstLines(limit = 10, maxLineChars = 20)

        assertEquals("x".repeat(20) + LineWindow.CUT_MARK, window.lines[0])
        assertEquals("a line exactly at the width is whole", "y".repeat(20), window.lines[1])
        assertEquals("the CR of a CRLF is not part of the width", "z".repeat(20), window.lines[2])
    }

    @Test
    fun `one enormous line is one short line`() {
        val file = text("m".repeat(2 * 1024 * 1024))

        val window = file.firstLines(limit = 2_000, maxLineChars = 2_000)

        assertEquals(1, window.total)
        assertEquals(2_000 + LineWindow.CUT_MARK.length, window.lines.single().length)
    }

    @Test
    fun `a file that is not text has no lines to window`() {
        val binary = FileReadResult("/work/a.bin", ByteArray(0), FileContentKind.BINARY, null)

        assertEquals(LineWindow(emptyList(), 0), binary.firstLines(limit = 10, maxLineChars = 10))
    }

    @Test
    fun `the size is the file's, and unknown says so instead of inventing one`() {
        val whole = text("hello")
        assertEquals(5L, whole.sizeBytes)
        assertTrue(whole.sizeKnown)

        val cut = text("hello", truncated = true, totalBytes = 600_000_000L)
        assertEquals("a cut file is its real size, not the size of what was held", 600_000_000L, cut.sizeBytes)
        assertTrue(cut.sizeKnown)

        val cutUnknown = text("hello", truncated = true, totalBytes = null)
        assertEquals("0 is unknown, as in an attachment draft", 0L, cutUnknown.sizeBytes)
        assertFalse(cutUnknown.sizeKnown)
    }

    @Test
    fun `two reads of the same bytes differ when one was cut`() {
        assertFalse(text("hello") == text("hello", truncated = true, totalBytes = 9L))
        assertEquals(text("hello"), text("hello"))
    }
}
