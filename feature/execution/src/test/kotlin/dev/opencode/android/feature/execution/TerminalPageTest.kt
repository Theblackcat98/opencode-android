package dev.opencode.android.feature.execution

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import dev.opencode.android.core.data.terminal.TerminalBridgeMessage
import dev.opencode.android.core.network.PtyStreamState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The page a terminal is drawn on, and what the socket does when the page is not the one it started with.
 *
 * **The bug this file exists for.** A terminal opened, said "Live", accepted a tap and a typed command, and
 * drew nothing. Three separate things sat behind that on a device, and this file covers the ones that are
 * this client's rather than Chromium's or xterm's:
 *
 *  - a reconnect and a second terminal both left the page as it was but marked it "not ready", and the page
 *    says `ready` once, when it loads — so everything the new socket sent was held for a `ready` that could
 *    never come;
 *  - the activity being recreated (a rotation is enough) re-ran `open`, which threw the open terminal away
 *    while its socket stayed connected, and a listed terminal could not be tapped to get it back;
 *  - the WebView that replaced the old page was empty, and the socket, which only says what comes next,
 *    never replayed what the old page had shown.
 *
 * **Main is a queue, as it is on a device**, and the socket is the app's own over a `MockWebServer`, so the
 * server can speak as the terminal: output, and the cursor frame that ends a replay.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TerminalPageTest {

    private val directory = "/work/app"
    private val scheduler = TestCoroutineScheduler()
    private lateinit var server: ExecutionServer
    private val viewModels = ViewModelStore()
    private lateinit var model: TerminalViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
        server = ExecutionServer(directory)
        server.answer("GET /api/pty", """{"location":{"directory":"$directory"},"data":[]}""")
        server.answer("GET /api/config/shell", "[]")
        server.answer("PUT /api/pty/pty_a", updated("pty_a"))
        server.answer("PUT /api/pty/pty_b", updated("pty_b"))
        openPanel()
    }

    @After
    fun tearDown() {
        // Clearing the store is what runs `onCleared`, which closes the socket; a socket left open makes the
        // server's `close()` wait for a queue that never shuts down.
        viewModels.clear()
        server.close()
        Dispatchers.resetMain()
    }

    private fun openPanel() {
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = TerminalViewModel(
                active = MutableStateFlow(server.set),
                sockets = TerminalSockets { location, ptyID, ticket ->
                    terminalSocket(server.baseUrl, OkHttpClient(), location, ptyID, ticket)
                },
            ) as T
        }
        model = ViewModelProvider(viewModels, factory)[TerminalViewModel::class.java]
        model.open(directory)
        scheduler.runCurrent()
        awaitUntil("the terminal list to be read") { server.requests.any { it.startsWith("GET /api/pty?") } }
    }

    /** Creates a terminal, waits for its socket, and lets the server finish the (empty) replay. */
    private fun startTerminal(id: String, sockets: Int) {
        server.answer("POST /api/pty", created(id))
        model.createTerminal(command = null)
        awaitState("$id to open") { it.open?.id == id }
        awaitUntil("$sockets socket(s) to be accepted") { server.sockets >= sockets }
        server.sendCursor(0)
        awaitState("the replay to finish") { it.stream is PtyStreamState.Live }
    }

    // ------------------------------------------------------------------ output waits for a page

    @Test
    fun `what the socket says before the page is ready is held, and released by ready`() {
        startTerminal("pty_a", sockets = 1)

        server.sendOutput("banner")
        // Held: no page is mounted yet, and writing into nothing loses the banner and the prompt.
        pumpFor(SETTLE_MILLIS)
        assertEquals("", model.state.value.pendingOutput)

        model.onBridgeMessage(TerminalBridgeMessage.Ready)

        assertEquals("banner", awaitState("the banner to be released") { it.pendingOutput.isNotEmpty() }.pendingOutput)
    }

    @Test
    fun `a reconnect on the page that is already mounted still delivers output`() {
        startTerminal("pty_a", sockets = 1)
        model.onBridgeMessage(TerminalBridgeMessage.Ready)
        server.sendOutput("first")
        val screen = awaitState("the first output") { it.pendingOutput.contains("first") }
        model.outputConsumed(screen.pendingOutput.length)

        // The refresh button: a new socket, on the page that said `ready` once and will not say it again.
        model.reconnect()
        awaitUntil("the second socket") { server.sockets >= 2 }
        server.sendOutput("second")

        // Before, the reconnect marked the page not ready and this was held for a `ready` that never came:
        // a terminal that said Live and stayed blank.
        val state = awaitState("output on the second socket to reach the page") {
            it.pendingOutput.contains("second")
        }
        assertFalse("the first socket's output is sent again: ${state.pendingOutput}", "first" in state.pendingOutput)
    }

    @Test
    fun `a second terminal on the same page gets its output and the grid the page measured`() {
        startTerminal("pty_a", sockets = 1)
        model.onBridgeMessage(TerminalBridgeMessage.Ready)
        model.onBridgeMessage(TerminalBridgeMessage.Resize(cols = 80, rows = 24))
        awaitUntil("the first terminal to be resized") { server.requests.any { it.startsWith("PUT /api/pty/pty_a") } }

        startTerminal("pty_b", sockets = 2)
        server.sendOutput("second terminal")

        val state = awaitState("the second terminal's output") { it.pendingOutput.contains("second terminal") }
        assertEquals("pty_b", state.open?.id)
        // The page will not measure again — nothing about it moved — so the terminal the server has just made
        // would have kept the size it was born with unless the client told it.
        awaitUntil("the second terminal to be told the grid") {
            server.requests.any { it.startsWith("PUT /api/pty/pty_b") }
        }
        val body = server.lastBody("/api/pty/pty_b", method = "PUT").orEmpty()
        assertTrue("rows and columns are on the wire: $body", """"rows":24""" in body && """"cols":80""" in body)
    }

    // ------------------------------------------------------------------ a page that is not the first

    @Test
    fun `a second ready is a new page and the socket is started again to fill it`() {
        startTerminal("pty_a", sockets = 1)
        model.onBridgeMessage(TerminalBridgeMessage.Ready)
        server.sendOutput("what the old page showed")
        val old = awaitState("output for the old page") { it.pendingOutput.isNotEmpty() }
        model.outputConsumed(old.pendingOutput.length)

        // The activity was recreated: the WebView is new and empty, and it says `ready` like any page.
        model.onBridgeMessage(TerminalBridgeMessage.Ready)

        awaitUntil("a second socket, to replay the retained buffer") { server.sockets >= 2 }
        server.sendOutput("the replay")
        val state = awaitState("the replay to reach the new page") { it.pendingOutput.contains("the replay") }
        assertEquals("pty_a", state.open?.id)
        assertFalse("nothing is held over from the old page", state.pendingOutput.contains("old page"))
    }

    @Test
    fun `the first ready of a page is not a replacement`() {
        startTerminal("pty_a", sockets = 1)

        model.onBridgeMessage(TerminalBridgeMessage.Ready)
        pumpFor(SETTLE_MILLIS)

        assertEquals("one page, one socket", 1, server.sockets)
    }

    @Test
    fun `every new socket starts from an empty page`() {
        startTerminal("pty_a", sockets = 1)
        model.onBridgeMessage(TerminalBridgeMessage.Ready)
        val first = model.state.value.epoch
        assertTrue("a socket was started", first > 0)
        server.sendOutput("not yet written")
        awaitState("output waiting") { it.pendingOutput.isNotEmpty() }

        model.reconnect()

        val state = model.state.value
        // A new socket replays from the start, so the page is emptied first and what was waiting for the old
        // stream is dropped: written after the reset it would be the replay's own first lines, twice.
        assertTrue("the epoch moved from $first to ${state.epoch}", state.epoch > first)
        assertEquals("", state.pendingOutput)
    }

    // ------------------------------------------------------------------ the activity coming back

    @Test
    fun `binding to the location that is already bound keeps the open terminal`() {
        startTerminal("pty_a", sockets = 1)

        // `LaunchedEffect(directory, startCommand)` runs again when the activity is recreated.
        model.open(directory)
        scheduler.runCurrent()

        val state = model.state.value
        assertEquals("pty_a", state.open?.id)
        assertTrue("still the same live stream: ${state.stream}", state.stream is PtyStreamState.Live)
        assertEquals("no second socket was made for a terminal that never went away", 1, server.sockets)
        assertNull(state.error)
    }

    @Test
    fun `binding to another location starts over`() {
        startTerminal("pty_a", sockets = 1)
        server.answer("GET /api/pty", """{"location":{"directory":"/other"},"data":[]}""")

        model.open("/other")
        scheduler.runCurrent()

        val state = model.state.value
        assertEquals("/other", state.directory)
        assertNull("a terminal of the first location is not open in the second", state.open)
    }

    @Test
    fun `tapping the row of the terminal that is open and live does not reconnect it`() {
        startTerminal("pty_a", sockets = 1)
        server.set.apply(
            dev.opencode.android.core.model.event.Event.decode(
                """{"id":"evt_a","type":"pty.created","created":1,"location":{"directory":"$directory"},""" +
                    """"data":{"info":${pty("pty_a")}}}""",
            ),
        )
        awaitState("the list to hold the terminal") { it.terminals.isNotEmpty() }

        model.openTerminal("pty_a")
        pumpFor(SETTLE_MILLIS)

        assertEquals(1, server.sockets)
    }

    // ------------------------------------------------------------------ a page that went away

    @Test
    fun `removing the open terminal unmounts the page, so the next terminal waits for a new one`() {
        server.answer("DELETE /api/pty/pty_a", "{}", status = 204)
        startTerminal("pty_a", sockets = 1)
        model.onBridgeMessage(TerminalBridgeMessage.Ready)
        model.requestKill("pty_a")
        model.confirmKill()
        awaitState("the terminal to be removed") { it.open == null }

        startTerminal("pty_b", sockets = 2)
        server.sendOutput("banner of the second")
        pumpFor(SETTLE_MILLIS)
        // The old page is gone with the terminal that was on it; the new one has not said `ready`.
        assertEquals("", model.state.value.pendingOutput)

        model.onBridgeMessage(TerminalBridgeMessage.Ready)
        awaitState("the banner to be released") { it.pendingOutput.contains("banner of the second") }
        assertEquals("a first ready is not a replacement", 2, server.sockets)
    }

    // ------------------------------------------------------------------ helpers

    private fun pty(id: String) =
        """{"id":"$id","title":"Terminal $id","command":"/usr/bin/zsh","args":["-l"],"cwd":"$directory",""" +
            """"status":"running","pid":4242}"""

    private fun created(id: String) = """{"location":{"directory":"$directory"},"data":${pty(id)}}"""

    private fun updated(id: String) = created(id)

    private fun awaitState(what: String, predicate: (TerminalUiState) -> Boolean): TerminalUiState {
        val deadline = System.nanoTime() + TIMEOUT_MILLIS * NANOS_PER_MILLI
        while (System.nanoTime() < deadline) {
            scheduler.runCurrent()
            model.state.value.takeIf(predicate)?.let { return it }
            Thread.sleep(POLL_MILLIS)
        }
        throw AssertionError("Timed out after ${TIMEOUT_MILLIS}ms waiting for: $what. State: ${model.state.value}")
    }

    private fun awaitUntil(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TIMEOUT_MILLIS * NANOS_PER_MILLI
        while (true) {
            scheduler.runCurrent()
            if (condition()) return
            if (System.nanoTime() > deadline) {
                throw AssertionError("Timed out after ${TIMEOUT_MILLIS}ms waiting for $what: ${server.requests}")
            }
            Thread.sleep(POLL_MILLIS)
        }
    }

    /** Lets the socket's frames arrive and Main run, for a claim that something did *not* happen. */
    private fun pumpFor(millis: Long) {
        val until = System.nanoTime() + millis * NANOS_PER_MILLI
        while (System.nanoTime() < until) {
            scheduler.runCurrent()
            Thread.sleep(POLL_MILLIS)
        }
    }

    private companion object {
        const val TIMEOUT_MILLIS = 10_000L
        const val SETTLE_MILLIS = 400L
        const val POLL_MILLIS = 10L
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
