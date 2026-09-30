package dev.opencode.android.feature.execution

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import dev.opencode.android.core.data.attention.OpenLocationTracker
import dev.opencode.android.core.model.ShellStatus
import dev.opencode.android.core.model.event.Event
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A command's output reaches the pane: while it runs, when it ends, and after the screen was away.
 *
 * **The bug this file exists for** is manual test F1. `echo one && sleep 3 && echo two && sleep 3 &&
 * echo three` ran, the row said "Running" and then "Exited with code 0", and the pane said "No output
 * yet." throughout, although the server had captured `one\ntwo\nthree\n`. Three faults stacked:
 *
 *  - the poller took a page that had caught up with the server (`cursor == size`) for "nothing more will
 *    come" and stopped, so the very first poll — which runs before the command has printed anything —
 *    was also the last;
 *  - the page was folded into the row of the open command, and the row was not there yet: `shell.create`'s
 *    answer never put it in the list, so a page that arrived before the `shell.created` event was thrown
 *    away and, the poller having stopped, never sent again;
 *  - nothing started the poller again when the screen came back from the background.
 *
 * **The view model, the read model and the `ServerApi` are the real ones over a `MockWebServer`.** The
 * server's end is [FakeShell], which answers `shell.output` the way 2.0.18 does, and one test replays the
 * page recorded from a live 2.0.18 byte for byte. Main is a queue the test pumps, as it is on a device, and
 * the poller's delays run on the same virtual clock, so "it keeps asking" is measured in requests rather
 * than in seconds of a test's life.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ShellsOutputTest {

    private val directory = "/work/app"
    private val scheduler = TestCoroutineScheduler()
    private lateinit var server: ExecutionServer
    private lateinit var shell: FakeShell
    private val viewModels = ViewModelStore()
    private lateinit var model: ShellsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
        server = ExecutionServer(directory)
        shell = FakeShell(directory = directory)
        shell.serveOn(server)
        server.answer("GET /api/shell", """{"location":{"directory":"$directory"},"data":[]}""")
    }

    @After
    fun tearDown() {
        // Clearing the store runs `onCleared`, which stops the poller.
        viewModels.clear()
        server.close()
        Dispatchers.resetMain()
    }

    private fun openPanel() {
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ShellsViewModel(MutableStateFlow(server.set), OpenLocationTracker()) as T
        }
        model = ViewModelProvider(viewModels, factory)[ShellsViewModel::class.java]
        model.openPanel(directory)
        // The list is read before anything runs, as it is when a user has looked at the screen before
        // typing: a list that lands *after* a command is created replaces the row, which is another bug.
        settle("the shell list to be read") { server.set.execution.at(directory).shellList.value != null }
    }

    private fun run(command: String) {
        model.setDraft(command)
        model.run()
        settle("the command to open") { model.state.value.openID != null }
    }

    private fun paneText(): String? = model.state.value.open?.output?.text

    // ------------------------------------------------------------------ F1, as recorded

    @Test
    fun `the page a live 2 0 18 sent for a finished command fills the pane`() {
        // Recorded from @opencode/cli 2.0.18 for `echo one && sleep 3 && echo two && sleep 3 && echo three`,
        // with the directory normalised. The three variants of the request (no cursor, `cursor=0`, and
        // `cursor=0&limit=65536`) all returned exactly this.
        server.handlers.clear()
        server.answer("POST /api/shell", wrap(INFO_RUNNING))
        server.answer("GET /api/shell/sh_1", wrap(INFO_EXITED))
        // The recorded page answers the first read. What answers a read from the end of it is the empty page
        // the route gives once the cursor has caught up, because repeating the full page for a later cursor
        // is not something the server does.
        server.handlers["GET /api/shell/sh_1/output"] = { request ->
            val cursor = request.url.queryParameter("cursor")?.toLong() ?: 0L
            ExecutionServer.Reply(
                if (cursor == 0L) RECORDED_PAGE.replace("@DIR@", directory) else wrap(EMPTY_PAGE_AT_14),
            )
        }
        openPanel()

        run(COMMAND)

        settle("the recorded output to reach the pane") { paneText() == "one\ntwo\nthree\n" }
        val open = model.state.value.open!!
        assertEquals(14L, open.output?.cursor)
        assertEquals(14L, open.output?.size)
        assertFalse(open.output!!.truncated)
        // The command's end came from `shell.get`, since no event was sent.
        settle("the exit to be read") { model.state.value.open?.info?.status == ShellStatus.Exited }
        assertEquals(0, model.state.value.open?.info?.exit)
        assertPollingStops()
    }

    // ------------------------------------------------------------------ streaming

    @Test
    fun `output streams while the command runs and every poll asks from where the last page ended`() {
        openPanel()
        run(COMMAND)

        // Nothing printed yet: the first page is empty, which is not the end. It has to keep asking.
        settle("a second and third poll of a command that has printed nothing") { shell.outputRequests >= 3 }
        assertEquals("", paneText())
        assertFalse("the command has not exited", model.state.value.open?.output?.exited == true)

        shell.print("one\n")
        settle("the first line to appear") { paneText() == "one\n" }
        assertEquals(4L, model.state.value.open?.output?.cursor)

        shell.print("two\n")
        settle("the second line to appear") { paneText() == "one\ntwo\n" }
        assertEquals(8L, model.state.value.open?.output?.cursor)

        // The command ends, having written its last line, and `shell.get` says so. The bytes written just
        // before the end are still read: the exit is learned first and the last page is read after it.
        shell.print("three\n")
        shell.finish(0)
        settle("the last line and the exit") {
            paneText() == "one\ntwo\nthree\n" && model.state.value.open?.info?.status == ShellStatus.Exited
        }
        assertEquals(0, model.state.value.open?.info?.exit)
        assertEquals(14L, model.state.value.open?.output?.cursor)
        assertPollingStops()

        // The cursor is the server's: only ever a value a page returned, and never one that went back.
        val cursors = outputCursors()
        assertEquals("the cursor never went backwards", cursors.sorted(), cursors)
        assertEquals("asked from 0, 4, 8 and 14 and nowhere else", listOf(0L, 4L, 8L, 14L), cursors.distinct())
    }

    @Test
    fun `an exit the event stream reports ends the stream after one more read`() {
        openPanel()
        run(COMMAND)
        shell.print("one\n")
        settle("the first line") { paneText() == "one\n" }

        // `shell.get` keeps saying "running": only the event knows. The last bytes were written before it.
        shell.print("two\n")
        server.set.apply(shellExited())
        settle("the last line") { paneText() == "one\ntwo\n" }
        settle("the exit to reach the row") { model.state.value.open?.info?.status == ShellStatus.Exited }
        assertPollingStops()
    }

    @Test
    fun `a failed poll is retried instead of ending the stream`() {
        var failures = 2
        val page = server.handlers.getValue("GET /api/shell/sh_1/output")
        server.handlers["GET /api/shell/sh_1/output"] = { request ->
            if (failures-- > 0) ExecutionServer.Reply("{}", status = 500) else page(request)
        }
        openPanel()
        run(COMMAND)

        shell.print("one\n")
        settle("output to arrive after two failed polls") { paneText() == "one\n" }
        assertNull("a retry that worked leaves nothing to report", model.state.value.open?.output?.error)
    }

    @Test
    fun `a stream that keeps failing stops and says so`() {
        server.handlers["GET /api/shell/sh_1/output"] = { ExecutionServer.Reply("{}", status = 500) }
        openPanel()
        run(COMMAND)

        settle("the poller to give up") { model.state.value.open?.output?.error != null }
        assertPollingStops()
    }

    // ------------------------------------------------------------------ truncated

    @Test
    fun `a page the server marks truncated is remembered and the stream goes on`() {
        server.handlers["GET /api/shell/sh_1/output"] = { request ->
            val cursor = request.url.queryParameter("cursor")?.toLong() ?: 0L
            val page = if (cursor == 0L) {
                """{"output":"tail","cursor":4,"size":4,"truncated":true}"""
            } else {
                """{"output":"","cursor":4,"size":4,"truncated":false}"""
            }
            ExecutionServer.Reply(wrap(page))
        }
        openPanel()
        run(COMMAND)

        settle("the truncated page") { paneText() == "tail" }
        settle("later polls") { outputCursors().count { it == 4L } >= 2 }
        // The empty page that followed says `truncated: false`, and must not take the note back.
        assertTrue(model.state.value.open!!.output!!.truncated)
    }

    // ------------------------------------------------------------------ the screen leaves and returns

    @Test
    fun `coming back to the screen resumes following from where it stopped`() {
        openPanel()
        run(COMMAND)
        shell.print("one\n")
        settle("the first line") { paneText() == "one\n" }

        // ON_STOP, then a line printed while the phone was in a pocket, then ON_RESUME.
        model.close()
        shell.print("two\n")
        model.resumePanel()

        settle("the line printed while away") { paneText() == "one\ntwo\n" }
        assertEquals("nothing was read twice", "one\ntwo\n", paneText())
    }

    // ------------------------------------------------------------------ helpers

    private fun wrap(data: String) = """{"location":{"directory":"$directory"},"data":$data}"""

    private fun shellExited() = Event.decode(
        """{"id":"evt_exit","type":"shell.exited","created":7,"location":{"directory":"$directory"},""" +
            """"data":{"id":"sh_1","exit":0,"status":"exited"}}""",
    )

    /** The `cursor` of every output request, in order. */
    private fun outputCursors(): List<Long> = server.requests
        .filter { it.startsWith("GET /api/shell/sh_1/output?") }
        .map { request -> Regex("""cursor=(\d+)""").find(request)?.groupValues?.get(1)?.toLong() ?: 0L }

    /** No further page is asked for however long the screen stays open. */
    private fun assertPollingStops() {
        // Let anything already in flight land, then watch a long stretch of virtual time.
        repeat(QUIET_ROUNDS) {
            scheduler.advanceTimeBy(STEP_MILLIS)
            scheduler.runCurrent()
            Thread.sleep(1)
        }
        val before = server.requests.count { it.contains("/output?") }
        repeat(QUIET_ROUNDS) {
            scheduler.advanceTimeBy(STEP_MILLIS)
            scheduler.runCurrent()
            Thread.sleep(1)
        }
        val after = server.requests.count { it.contains("/output?") }
        assertEquals("the stream went on asking after it had ended: ${server.requests}", before, after)
    }

    /** Pumps Main and the poller's clock until [condition] holds; every wait has a deadline. */
    private fun settle(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TIMEOUT_MILLIS * NANOS_PER_MILLI
        while (true) {
            scheduler.advanceTimeBy(STEP_MILLIS)
            scheduler.runCurrent()
            if (condition()) return
            if (System.nanoTime() > deadline) {
                throw AssertionError(
                    "Timed out after ${TIMEOUT_MILLIS}ms waiting for: $what.\nState: ${model.state.value}\n" +
                        "Requests: ${server.requests}",
                )
            }
            Thread.sleep(POLL_MILLIS)
        }
    }

    private companion object {
        const val COMMAND = "echo one && sleep 3 && echo two && sleep 3 && echo three"
        const val TIMEOUT_MILLIS = 5_000L
        const val POLL_MILLIS = 5L
        const val NANOS_PER_MILLI = 1_000_000L

        /** One virtual step of the poller's clock; the poller's own pause is several of these. */
        const val STEP_MILLIS = 100L

        /** 40 s of virtual time, far past the poller's longest pause. */
        const val QUIET_ROUNDS = 400

        private const val CMD_JSON = """"command":"echo one && sleep 3 && echo two && sleep 3 && echo three","""

        const val INFO_RUNNING =
            """{"id":"sh_1","status":"running",""" + CMD_JSON +
                """"cwd":"/work/app","shell":"/usr/bin/zsh","file":"/work/app/.out/sh_1.out","pid":4242,""" +
                """"metadata":{},"time":{"started":1}}"""
        const val INFO_EXITED =
            """{"id":"sh_1","status":"exited",""" + CMD_JSON +
                """"cwd":"/work/app","shell":"/usr/bin/zsh","file":"/work/app/.out/sh_1.out","pid":4242,"exit":0,""" +
                """"metadata":{},"time":{"started":1,"completed":7}}"""
        const val EMPTY_PAGE_AT_14 = """{"output":"","cursor":14,"size":14,"truncated":false}"""
        const val RECORDED_PAGE =
            """{"location":{"directory":"@DIR@"},"data":{"output":"one\ntwo\nthree\n","cursor":14,""" +
                """"size":14,"truncated":false}}"""
    }
}
