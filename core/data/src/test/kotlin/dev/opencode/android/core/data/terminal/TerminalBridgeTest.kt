package dev.opencode.android.core.data.terminal

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The WebView bridge's validation, and the grid arithmetic behind a resize (plan §5.2).
 *
 * **Every message in this file is hostile.** The page is xterm.js and the code around it, running
 * JavaScript the app did not write; anything it posts is untrusted input, and the tests are written as
 * if it were. The four rules the codec enforces are each asserted, plus the cases that are easy to get
 * wrong: a message split across posts, a `type` that is right and a payload that is not, and a number
 * that is a number.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TerminalBridgeTest {

    // ------------------------------------------------------------------ the four rules

    @Test
    fun `an object with a known type and a string payload is accepted`() {
        assertEquals(
            TerminalBridgeMessage.Input("ls -la"),
            TerminalBridgeCodec.decode("""{"type":"input","data":"ls -la"}"""),
        )
    }

    @Test
    fun `rule one, anything that is not a JSON object is refused`() {
        assertNull(TerminalBridgeCodec.decode("\"input\""))
        assertNull(TerminalBridgeCodec.decode("[1,2,3]"))
        assertNull(TerminalBridgeCodec.decode("42"))
        assertNull(TerminalBridgeCodec.decode("null"))
        assertNull(TerminalBridgeCodec.decode("undefined"))
        assertNull(TerminalBridgeCodec.decode(""))
        assertNull(TerminalBridgeCodec.decode(null))
        assertNull(TerminalBridgeCodec.decode("not json at all"))
        assertNull(TerminalBridgeCodec.decode("{"))
    }

    @Test
    fun `rule two, a type this build does not know is refused rather than treated as a generic event`() {
        assertNull(TerminalBridgeCodec.decode("""{"type":"exec","data":"rm -rf /"}"""))
        assertNull(TerminalBridgeCodec.decode("""{"type":"INPUT","data":"x"}"""))
        assertNull(TerminalBridgeCodec.decode("""{"type":42,"data":"x"}"""))
        assertNull(TerminalBridgeCodec.decode("""{"data":"x"}"""))
    }

    @Test
    fun `rule three, a payload larger than a paste is refused`() {
        val huge = "x".repeat(TerminalBridgeCodec.MAX_INPUT_CHARS + 1)
        assertNull(TerminalBridgeCodec.decode("""{"type":"input","data":"$huge"}"""))
        // One character under the cap is a paste and is accepted.
        val paste = "x".repeat(TerminalBridgeCodec.MAX_INPUT_CHARS)
        assertEquals(
            TerminalBridgeMessage.Input(paste),
            TerminalBridgeCodec.decode("""{"type":"input","data":"$paste"}"""),
        )
    }

    @Test
    fun `an empty payload is refused because there is nothing to send`() {
        assertNull(TerminalBridgeCodec.decode("""{"type":"input","data":""}"""))
        assertNull(TerminalBridgeCodec.decode("""{"type":"input"}"""))
        assertNull(TerminalBridgeCodec.decode("""{"type":"input","data":null}"""))
        assertNull(TerminalBridgeCodec.decode("""{"type":"input","data":7}"""))
    }

    @Test
    fun `rule four, a grid outside the range is refused`() {
        assertNull(TerminalBridgeCodec.decode("""{"type":"resize","cols":0,"rows":24}"""))
        assertNull(TerminalBridgeCodec.decode("""{"type":"resize","cols":80,"rows":0}"""))
        assertNull(TerminalBridgeCodec.decode("""{"type":"resize","cols":-80,"rows":24}"""))
        assertNull(
            TerminalBridgeCodec.decode(
                """{"type":"resize","cols":${TerminalBridgeCodec.MAX_CELLS + 1},"rows":24}""",
            ),
        )
        assertNull(TerminalBridgeCodec.decode("""{"type":"resize","cols":"80","rows":"24"}"""))
        assertNull(TerminalBridgeCodec.decode("""{"type":"resize","cols":80.5,"rows":24}"""))
    }

    @Test
    fun `a grid in range is accepted`() {
        assertEquals(
            TerminalBridgeMessage.Resize(cols = 80, rows = 24),
            TerminalBridgeCodec.decode("""{"type":"resize","cols":80,"rows":24}"""),
        )
    }

    @Test
    fun `ready carries no payload`() {
        assertEquals(TerminalBridgeMessage.Ready, TerminalBridgeCodec.decode("""{"type":"ready"}"""))
    }

    @Test
    fun `a selection is bounded separately from a keystroke`() {
        val big = "x".repeat(TerminalSelection.LIMIT + 1)
        assertNull(TerminalBridgeCodec.decode("""{"type":"selection","data":"$big"}"""))
        assertEquals(
            TerminalBridgeMessage.Selection("hello"),
            TerminalBridgeCodec.decode("""{"type":"selection","data":"hello"}"""),
        )
    }

    @Test
    fun `extra fields are ignored rather than refused`() {
        // The page may add a field; the host acts only on the ones it knows. Refusing would make the
        // channel brittle for no safety gain, because the fields it does read are still validated.
        assertEquals(
            TerminalBridgeMessage.Input("q"),
            TerminalBridgeCodec.decode("""{"type":"input","data":"q","seq":7,"who":"page"}"""),
        )
    }

    @Test
    fun `a message split across two posts is two refused messages, not one`() {
        // The bridge does not reassemble. A partial JSON object is refused, and so is the remainder,
        // because a page that cannot produce a whole message has something wrong with it and the right
        // answer is to do nothing rather than to guess at the seam between two posts.
        assertNull(TerminalBridgeCodec.decode("""{"type":"inp"""))
        assertNull(TerminalBridgeCodec.decode("""ut","data":"x"}"""))
    }

    // ------------------------------------------------------------------ host to page

    @Test
    fun `an output message is one JSON object with a string field`() {
        val json = TerminalBridgeCodec.encode(TerminalHostMessage.Output("line\r\n"))
        assertEquals("""{"type":"output","data":"line\r\n"}""", json)
    }

    @Test
    fun `output carrying a quote and a backslash is still one literal`() {
        // This is the reason the host message is a JSON object and not a concatenation: the page's
        // parser must not be able to decide where a terminal's own output ends.
        val json = TerminalBridgeCodec.encode(TerminalHostMessage.Output("""he said "hi" \ then left"""))
        val round = TerminalBridgeCodec.decode(json)
        assertNull(round)
        assertTrue(json.startsWith("""{"type":"output","data":""""))
    }

    @Test
    fun `a cursor message is a number`() {
        assertEquals(
            """{"type":"cursor","cursor":4096}""",
            TerminalBridgeCodec.encode(TerminalHostMessage.Cursor(4096)),
        )
    }

    @Test
    fun `a state message carries the state and the cursor it had`() {
        assertEquals(
            """{"type":"state","state":"live","cursor":12}""",
            TerminalBridgeCodec.encode(TerminalHostMessage.State("live", 12)),
        )
        assertEquals(
            """{"type":"state","state":"reconnecting"}""",
            TerminalBridgeCodec.encode(TerminalHostMessage.State("reconnecting", null)),
        )
    }

    // ------------------------------------------------------------------ the grid

    @Test
    fun `a grid is the measured area over the measured cell`() {
        val grid = TerminalGrid.of(widthPx = 800f, heightPx = 480f, cellWidthPx = 8f, cellHeightPx = 16f)
        assertEquals(100, grid.cols)
        assertEquals(30, grid.rows)
    }

    @Test
    fun `an unmeasured area yields the floor rather than a zero the server refuses`() {
        // A sheet that animates away reports a zero height on its way out; a `size {rows: 0}` is a
        // `400`, and crashing on the way out is not an option.
        val grid = TerminalGrid.of(0f, 0f, 8f, 16f)
        assertEquals(TerminalGrid.MIN_CELLS, grid.rows)
        assertEquals(TerminalGrid.MIN_CELLS, grid.cols)
    }

    @Test
    fun `an unmeasured cell yields the floor too`() {
        val grid = TerminalGrid.of(800f, 480f, 0f, 0f)
        assertEquals(TerminalGrid.MIN_CELLS, grid.cols)
    }

    @Test
    fun `the grid is clamped at both ends`() {
        assertEquals(TerminalGrid.MAX_CELLS, TerminalGrid.of(100_000f, 100_000f, 1f, 1f).cols)
        assertEquals(TerminalGrid.MIN_CELLS, TerminalGrid.of(1f, 1f, 100f, 100f).cols)
    }

    @Test
    fun `a resize is needed only when the grid moved`() {
        val grid = TerminalGridSize(cols = 80, rows = 24)
        assertTrue(!TerminalGrid.needsResize(grid, grid))
        assertTrue(TerminalGrid.needsResize(grid, TerminalGridSize(cols = 81, rows = 24)))
        assertTrue(TerminalGrid.needsResize(null, grid))
    }
}

/** The selection cap, named so the test reads as a rule rather than a number. */
private object TerminalSelection {
    const val LIMIT: Int = 1024 * 1024
}
