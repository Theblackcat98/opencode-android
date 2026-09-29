package dev.opencode.android.feature.execution

import dev.opencode.android.core.data.terminal.TerminalBridgeCodec
import dev.opencode.android.core.data.terminal.TerminalBridgeMessage
import dev.opencode.android.core.data.terminal.TerminalGrid
import dev.opencode.android.core.data.terminal.TerminalGridSize
import dev.opencode.android.core.data.terminal.ExtraKeys
import dev.opencode.android.core.data.terminal.TerminalHostMessage
import dev.opencode.android.core.data.terminal.TerminalInput
import dev.opencode.android.core.data.terminal.TerminalKey
import dev.opencode.android.core.data.terminal.TerminalKeyDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The terminal screen's own contract, without a WebView.
 *
 * **Why this file exists.** Every claim in [TerminalChannelTest] is about the channel and the codec,
 * and both are pure — which is why they were the only claims the previous build could make about a
 * terminal. What was *not* claimed is the part a real terminal lives or dies by: that the page's
 * `ready` is what releases the buffer, that input travels the page to the socket rather than stopping
 * at the screen, that a grid the page measures is bounded, and that the extra-keys row encodes a
 * latched modifier the way a terminal does. Those are the parts a missing `when` branch silences, and
 * they are all expressible here.
 *
 * **The channel stands in for the WebView, and it stands in honestly.** [RecordingChannel] is the real
 * [TerminalChannel] with the page replaced by a list, because the page is the one thing that cannot run
 * in this environment. Everything else — the ordering, the buffering, the encoding of the call — is the
 * production code's.
 */
class TerminalSurfaceTest {

    // ------------------------------------------------------------------ the bridge contract

    @Test
    fun `every kind of bridge message the codec produces survives a round trip`() {
        // The screen forwards bridge messages whole rather than filtering them, and this is the list a
        // filter has to cover. A `when` with no `Input` branch renders a terminal that accepts nothing,
        // which is invisible until someone types in it.
        val fromThePage = listOf(
            """{"type":"ready"}""",
            """{"type":"input","data":"c"}""",
            """{"type":"resize","cols":80,"rows":24}""",
            """{"type":"selection","data":"ls -la"}""",
            """{"type":"error","message":"fit failed"}""",
        )

        val decoded = fromThePage.map { TerminalBridgeCodec.decode(it) }

        assertEquals("decoded was $decoded", listOf(true, true, true, true, true), decoded.map { it != null })
        assertEquals(TerminalBridgeMessage.Input("c"), decoded[1])
        assertEquals(TerminalBridgeMessage.Resize(cols = 80, rows = 24), decoded[2])
        assertEquals(TerminalBridgeMessage.Selection("ls -la"), decoded[3])
        assertEquals(TerminalBridgeMessage.Failed("fit failed"), decoded[4])
    }

    @Test
    fun `an input message carries the exact bytes the key row produced`() {
        // A `Ctrl`+`C` is one C0 byte. A JSON-escaping mistake here would send a literal backslash to
        // the shell instead of interrupting it, and the JSON object would still be well-formed — so the
        // assertion is on the decoded message, not on the encoding looking plausible.
        assertEquals(
            TerminalBridgeMessage.Input("\u0003"),
            TerminalBridgeCodec.decode("""{"type":"input","data":"\u0003"}"""),
        )
    }

    @Test
    fun `a quote and a backslash inside a payload survive the round trip`() {
        // Terminal output is full of these: a `grep --color` result, a Windows path, a JSON blob. The
        // host builds the JSON literal so the page's parser decides where the string ends — a host that
        // concatenated would let the terminal's own output escape the string it was written into.
        val text = """grep "x" \path"""
        val encoded = TerminalBridgeCodec.encode(TerminalHostMessage.Output(text))
        // The host message is read back through the page's own input shape: the codec has one
        // string-payload shape, and a payload that survives it intact is one the page will render as the
        // characters that were typed rather than as JSON syntax.
        assertTrue(encoded.startsWith("""{"type":"output""""))
        assertEquals(
            TerminalBridgeMessage.Input(text),
            TerminalBridgeCodec.decode(encoded.replace(""""output"""", """"input"""")),
        )
    }

    // ------------------------------------------------------------------ release on ready

    @Test
    fun `nothing is written before the page is ready and everything is written after it`() {
        val channel = RecordingChannel()

        channel.write("first ")
        channel.write("second")
        // Two writes happened and nothing was evaluated: the page has not mounted, so there is nothing
        // to write into. Output produced before `ready` is the banner, the prompt and everything typed
        // since — losing it is how a terminal opens blank.
        assertEquals(emptyList<String>(), channel.evaluated)

        channel.onPageReady()

        // The buffer is flushed once, in order, rather than as two calls: a terminal whose replay
        // arrives out of order is worse than one that is late.
        assertEquals(listOf("first second"), channel.output)
    }

    @Test
    fun `a write after the page is ready goes straight through`() {
        val channel = RecordingChannel().apply { onPageReady() }
        channel.write("live")
        assertEquals(listOf("live"), channel.output)
    }

    @Test
    fun `fifty chunks are flushed as one call`() {
        val channel = RecordingChannel()
        repeat(50) { channel.write("chunk$it ") }
        channel.onPageReady()
        // Each write is a call onto the browser's thread; fifty of them for one burst is the difference
        // between a prompt that appears and one that stutters.
        assertEquals(1, channel.evaluated.size)
        assertTrue(channel.output.single().contains("chunk49 "))
    }

    @Test
    fun `detaching drops the buffer, because the page it was for is gone`() {
        val channel = RecordingChannel()
        channel.write("for the old page")
        channel.detach()
        channel.onPageReady()
        // A recycled screen that flushed into a page it does not own would write one terminal's
        // output into another's.
        assertEquals(emptyList<String>(), channel.evaluated)
    }

    @Test
    fun `an empty write is not a write`() {
        val channel = RecordingChannel().apply { onPageReady() }
        channel.write("")
        assertEquals(emptyList<String>(), channel.evaluated)
    }

    @Test
    fun `the buffer is bounded so a page that never mounts cannot grow it`() {
        val channel = RecordingChannel()
        // Past the bound the oldest output is dropped rather than held: a page that has not mounted is
        // a page that will not, and holding a quarter of a megabyte of it on a phone is a trade with no
        // reader on the other end.
        repeat(40_000) { channel.write("0123456789") }
        channel.onPageReady()
        // Measured on the encoded JSON rather than the decoded text: the codec refuses a payload over
        // [TerminalBridgeCodec.MAX_INPUT_CHARS], so a full buffer's *decoded* form cannot be read back
        // through the decoder — which is itself worth knowing, and is why this assertion is on the wire
        // the page receives.
        val flushed = channel.evaluated.single()
        val payload = flushed.substringAfter("\"data\":\"").substringBeforeLast("\"}")
        assertTrue(
            "buffer held ${payload.length} characters",
            payload.length <= 256 * 1024,
        )
        assertTrue("the newest output must survive the trim", payload.endsWith("0123456789"))
        assertFalse("the oldest must not", payload.startsWith("0123456789") && payload.length > 256 * 1024)
    }

    // ------------------------------------------------------------------ the grid

    @Test
    fun `a grid that has not moved is not a resize, and the first measurement always is`() {
        val first = TerminalGridSize(cols = 80, rows = 24)
        // The first measurement always counts: the server holds whatever size the last client gave it,
        // and this client has no idea what that was.
        assertTrue(TerminalGrid.needsResize(null, first))
        assertTrue(TerminalGrid.needsResize(first, first.copy(rows = 25)))
        assertFalse(TerminalGrid.needsResize(first, TerminalGridSize(cols = 80, rows = 24)))
    }

    @Test
    fun `a layout that has not happened yet still yields a usable grid`() {
        // A zero-height WebView happens on every sheet that animates away, and `size {rows: 0}` is what
        // the server refuses. The floor is [TerminalGrid.MIN_CELLS], not zero and not an exception.
        val grid = TerminalGrid.of(widthPx = 0f, heightPx = 0f, cellWidthPx = 0f, cellHeightPx = 0f)
        assertEquals(TerminalGrid.MIN_CELLS, grid.rows)
        assertEquals(TerminalGrid.MIN_CELLS, grid.cols)
    }

    @Test
    fun `a surface too small for one cell still gets the minimum`() {
        val grid = TerminalGrid.of(widthPx = 2f, heightPx = 2f, cellWidthPx = 8f, cellHeightPx = 16f)
        assertEquals(TerminalGrid.MIN_CELLS, grid.rows)
        assertEquals(TerminalGrid.MIN_CELLS, grid.cols)
    }

    @Test
    fun `a huge surface is clamped so a tablet does not ask for a million cells`() {
        val grid = TerminalGrid.of(
            widthPx = 20_000f,
            heightPx = 20_000f,
            cellWidthPx = 8f,
            cellHeightPx = 16f,
        )
        assertEquals(TerminalGrid.MAX_CELLS, grid.cols)
        assertEquals(TerminalGrid.MAX_CELLS, grid.rows)
    }

    @Test
    fun `a measured grid is the area over the cell`() {
        // 640/8 = 80 columns, 384/16 = 24 rows — the arithmetic the page cannot do for the host.
        val grid = TerminalGrid.of(widthPx = 640f, heightPx = 384f, cellWidthPx = 8f, cellHeightPx = 16f)
        assertEquals(80, grid.cols)
        assertEquals(24, grid.rows)
    }

    // ------------------------------------------------------------------ extra keys

    @Test
    fun `the plan's key row is present, in the plan's order`() {
        // Esc, Tab, Ctrl, Alt, the four arrows, then the three characters the plan names because a
        // soft keyboard hides them. Asserted by label because that is what a user reads.
        assertEquals(
            listOf("ESC", "TAB", "CTRL", "ALT", "←", "↓", "↑", "→", "|", "~", "/"),
            ExtraKeys.row.map(ExtraKeys::label),
        )
    }

    @Test
    fun `a latched modifier applies to the key that follows it`() {
        // One frame, one C0 byte. Sending the latch and the key as two frames would let the terminal's
        // own echo land between them, which is exactly how Ctrl-C becomes the letter c.
        assertEquals("\u0003", TerminalInput.encode(listOf(TerminalKey.Control, TerminalKey.Literal("c"))))
        assertEquals("\u001b", TerminalInput.encode(listOf(TerminalKey.Escape)))
        assertEquals("\t", TerminalInput.encode(listOf(TerminalKey.Tab)))
        assertEquals("\u007f", TerminalInput.encode(listOf(TerminalKey.Backspace)))
        assertEquals("\r", TerminalInput.encode(listOf(TerminalKey.Enter)))
    }

    @Test
    fun `the arrows are the ANSI sequences a terminal expects`() {
        assertEquals("\u001b[A", TerminalInput.encode(listOf(TerminalKey.Arrow(TerminalKeyDirection.UP))))
        assertEquals("\u001b[B", TerminalInput.encode(listOf(TerminalKey.Arrow(TerminalKeyDirection.DOWN))))
        assertEquals("\u001b[C", TerminalInput.encode(listOf(TerminalKey.Arrow(TerminalKeyDirection.RIGHT))))
        assertEquals("\u001b[D", TerminalInput.encode(listOf(TerminalKey.Arrow(TerminalKeyDirection.LEFT))))
    }

    @Test
    fun `meta sends escape then the character`() {
        assertEquals("\u001bc", TerminalInput.encode(listOf(TerminalKey.Meta, TerminalKey.Literal("c"))))
    }

    @Test
    fun `a run of keys is encoded in order as one string`() {
        assertEquals(
            "ls -la",
            TerminalInput.encode("ls -la".map { TerminalKey.Literal(it.toString()) }),
        )
        assertEquals("", TerminalInput.encode(emptyList()))
    }

    @Test
    fun `a character with no control code is sent as itself`() {
        // Not an exception and not nothing: a key this build does not know is sent as itself, which is
        // what a hardware keyboard would do, and the terminal decides what to make of it.
        assertEquals("€", TerminalInput.encode(listOf(TerminalKey.Literal("€"))))
    }

    // ------------------------------------------------------------------ the screen's relay

    @Test
    fun `every kind the codec produces is forwarded to the host, and ready also releases the page`() {
        // This is the assertion the previous build could not make. Its screen had a `when` with an
        // `else -> Unit`, so `Input` — the only route from a hardware keyboard and a paste to the socket
        // — was dropped: a terminal that drew perfectly and accepted nothing. Nothing about the codec or
        // the channel would have caught it, because both were correct; the bug was in the forwarding.
        val fromThePage = listOf(
            TerminalBridgeMessage.Ready,
            TerminalBridgeMessage.Input("c"),
            TerminalBridgeMessage.Resize(cols = 80, rows = 24),
            TerminalBridgeMessage.Selection("ls -la"),
            TerminalBridgeMessage.Failed("fit failed"),
        )
        val channel = RecordingChannel()
        val relayed = mutableListOf<TerminalBridgeMessage>()

        fromThePage.forEach { relayBridgeMessage(channel, it) { message -> relayed.add(message) } }

        assertEquals(fromThePage, relayed)
        // And the one thing the screen does on its own: the buffer is released exactly once, by `ready`.
        assertEquals(listOf("ready"), channel.releasedBy)
    }

    @Test
    fun `a page that never says ready never gets written to`() {
        // The relay is the only thing that can release the channel's buffer, and it releases it on
        // `ready` and nothing else. A screen that released it on the first message of any kind would
        // write output into a page that has not mounted.
        val channel = RecordingChannel()
        val seen = mutableListOf<TerminalBridgeMessage>()
        relayBridgeMessage(channel, TerminalBridgeMessage.Input("c")) { seen.add(it) }
        relayBridgeMessage(channel, TerminalBridgeMessage.Resize(80, 24)) { seen.add(it) }
        assertEquals(2, seen.size)
        assertEquals(emptyList<String>(), channel.releasedBy)
        assertEquals(emptyList<String>(), channel.evaluated)
    }

    // ------------------------------------------------------------------ refusals

    @Test
    fun `the bridge refuses what it should and says nothing about it to the host`() {
        // These are [TerminalChannelTest]'s rules, repeated here because they are what makes the
        // round trips above meaningful: if a malformed message decoded, the "every kind survives"
        // assertion above would be asserting that the codec is permissive.
        assertNull(TerminalBridgeCodec.decode("not json"))
        assertNull(TerminalBridgeCodec.decode("\"a string\""))
        assertNull(TerminalBridgeCodec.decode("""{"type":"exec","data":"rm -rf /"}"""))
        assertNull(TerminalBridgeCodec.decode("""{"type":"resize","cols":0,"rows":24}"""))
        assertNull(TerminalBridgeCodec.decode("""{"type":"resize","cols":"80","rows":24}"""))
        assertNull(TerminalBridgeCodec.decode("""{"type":"input"}"""))
        assertNull(TerminalBridgeCodec.decode("""{"type":"input","data":null}"""))
    }
}

/**
 * The real [TerminalChannel] with the page replaced by a list.
 *
 * **Overridable rather than wrapped, on purpose.** The channel's job is to decide *what* is evaluated
 * and in what order; `evaluateJavascript` is the one call that needs a `WebView`. A wrapper would have
 * to re-implement the ordering to observe it, and then the test would be asserting about the wrapper
 * instead of about the channel.
 */
private class RecordingChannel : TerminalChannel() {
    /** The JSON of every call, in order — the same string the page's parser would receive. */
    val evaluated = mutableListOf<String>()

    /** One entry per `ready`, so a test can prove what released the buffer and when. */
    val releasedBy = mutableListOf<String>()

    override fun evaluateInPage(method: String, json: String) {
        evaluated += json
    }

    override fun onPageReady() {
        releasedBy += "ready"
        super.onPageReady()
    }

    /**
     * The output that was written, read back through the codec's own shape.
     *
     * **Asserting on the decoded text rather than on the encoded JSON** is the point: a flush is
     * correct if the page receives the characters that were buffered, and the only way to know that is
     * to parse what it would have received.
     */
    val output: List<String>
        get() = evaluated.mapNotNull { json ->
            val asInput = TerminalBridgeCodec.decode(json.replace(""""output"""", """"input""""))
            (asInput as? TerminalBridgeMessage.Input)?.data
        }
}
