package dev.opencode.android.core.data.execution

import dev.opencode.android.core.data.action.ActionErrorKind
import dev.opencode.android.core.data.action.ActionFailure
import dev.opencode.android.core.model.ShellOutput
import dev.opencode.android.core.model.ShellStatus
import dev.opencode.android.core.model.event.Event
import dev.opencode.android.core.network.ServerApi
import dev.opencode.android.core.network.ServerApiFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * `shell.output` on the wire, the way a live 2.0.18 answers it, and the row `shell.create` leaves behind.
 *
 * **The page below is recorded, not written.** It is what `GET /api/shell/{id}/output` returned for
 * `echo one && sleep 3 && echo two && sleep 3 && echo three` once the command had exited, for the request
 * with no query, with `cursor=0`, and with `cursor=0&limit=65536` alike — with the directory normalised.
 * The panel showed "No output yet." for exactly this command, and a fixture written from the schema had
 * never contained a page whose `cursor` and `size` agreed, which is the page a caught-up read gets.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ShellOutputWireTest {

    private val directory = "/work/app"
    private lateinit var server: MockWebServer
    private lateinit var api: ServerApi
    private val scope = CoroutineScope(SupervisorJob())
    private val answers = mutableMapOf<String, Pair<Int, String>>()
    private val sent = mutableListOf<RecordedRequest>()

    private val requests: List<String>
        get() = synchronized(sent) { sent.map { "${it.method} ${it.url.encodedPath}?${it.url.query}" } }

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        api = ServerApiFactory(OkHttpClient()).createForReads(server.url("/").toString())
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                synchronized(sent) { sent += request }
                val (status, body) = answers["${request.method} ${request.url.encodedPath}"] ?: (200 to "{}")
                return MockResponse.Builder()
                    .code(status)
                    .addHeader("Content-Type", "application/json")
                    .body(body)
                    .build()
            }
        }
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.close()
    }

    private fun commands(store: ExecutionStore? = null) =
        ExecutionCommands(api) { store?.let { mapOf(directory to it) } ?: emptyMap() }

    // ------------------------------------------------------------------ the recorded page

    @Test
    fun `the recorded page decodes and the three recorded requests all read it`() = runTest {
        answers["GET /api/shell/sh_1/output"] = 200 to RECORDED_PAGE.replace("@DIR@", directory)
        val expected = ShellOutput(output = "one\ntwo\nthree\n", cursor = 14, size = 14, truncated = false)

        assertEquals(expected, api.getShellOutput("sh_1", directory).data)
        assertEquals(expected, api.getShellOutput("sh_1", directory, cursor = "0").data)
        assertEquals(expected, api.getShellOutput("sh_1", directory, cursor = "0", limit = "65536").data)
        assertEquals(expected, commands().shellOutput(directory, "sh_1", cursor = "0"))

        // What the app sends: the location and the cursor as text, and a limit only when it is given one.
        val sentQueries = requests.map { it.substringAfter('?') }
        assertEquals(
            listOf(
                "location[directory]=$directory",
                "location[directory]=$directory&cursor=0",
                "location[directory]=$directory&cursor=0&limit=65536",
                "location[directory]=$directory&cursor=0",
            ),
            sentQueries,
        )
    }

    @Test
    fun `a page the server marks truncated decodes as truncated`() = runTest {
        answers["GET /api/shell/sh_1/output"] =
            200 to """{"location":{"directory":"$directory"},"data":{"output":"tail\n","cursor":5,"size":5,""" +
            """"truncated":true}}"""

        val page = commands().shellOutput(directory, "sh_1", cursor = "0")

        assertEquals(ShellOutput(output = "tail\n", cursor = 5, size = 5, truncated = true), page)
    }

    // ------------------------------------------------------------------ what a failure means

    @Test
    fun `a 404 with the server's own tag is the end of the stream and is not recorded as an error`() = runTest {
        // The body a removed command gets. `ShellNotFoundError` is not a tag `ActionErrorKind` calls "not
        // found", so it is the status that says this is the ordinary end and not a fault.
        answers["GET /api/shell/sh_1/output"] =
            404 to """{"_tag":"ShellNotFoundError","id":"sh_1","message":"Shell sh_1 was not found"}"""
        val commands = commands()

        assertNull(commands.shellOutput(directory, "sh_1", cursor = "0"))
        assertNull("the end of a stream is not something to show a dialog for", commands.error.value)
    }

    @Test
    fun `any other failure is thrown for the poller to retry and is not recorded either`() = runTest {
        answers["GET /api/shell/sh_1/output"] = 500 to "{}"
        val commands = commands()

        try {
            commands.shellOutput(directory, "sh_1", cursor = "0")
            fail("a 500 was taken for the end of the stream")
        } catch (failure: ActionFailure) {
            assertEquals(ActionErrorKind.SERVER, failure.error.kind)
        }
        assertNull("a poll that fails every second would raise a dialog every second", commands.error.value)
    }

    // ------------------------------------------------------------------ shell.create leaves a row

    @Test
    fun `the answer to shell create puts the command in the list before its event arrives`() = runTest {
        answers["POST /api/shell"] = 200 to """{"location":{"directory":"$directory"},"data":$RUNNING}"""
        val store = ExecutionStore("srv", directory, api, scope)

        val info = commands(store).runShell(directory, "echo one").getOrNull()

        assertEquals("sh_1", info?.id)
        assertEquals(listOf("sh_1"), store.shells.value.map { it.id })
        assertTrue(requests.single().startsWith("POST /api/shell?"))
    }

    @Test
    fun `an event that beat the answer keeps its newer state and the row is not listed twice`() = runTest {
        answers["POST /api/shell"] = 200 to """{"location":{"directory":"$directory"},"data":$RUNNING}"""
        val store = ExecutionStore("srv", directory, api, scope)
        store.apply(shellCreated())
        store.apply(shellExited())

        commands(store).runShell(directory, "echo one")

        // The answer describes the command as it was created. Putting it over a row an event has already
        // finished would show a command that ended as still running.
        assertEquals(listOf("sh_1"), store.shells.value.map { it.id })
        assertEquals(ShellStatus.Exited, store.shells.value.single().status)
        assertEquals(0, store.shells.value.single().exit)
    }

    private fun shellCreated() = Event.decode(
        """{"id":"evt_c","type":"shell.created","created":1,"location":{"directory":"$directory"},""" +
            """"data":{"info":$RUNNING}}""",
    )

    private fun shellExited() = Event.decode(
        """{"id":"evt_x","type":"shell.exited","created":7,"location":{"directory":"$directory"},""" +
            """"data":{"id":"sh_1","exit":0,"status":"exited"}}""",
    )

    private companion object {
        /** Recorded from @opencode/cli 2.0.18; the directory is normalised. */
        const val RECORDED_PAGE =
            """{"location":{"directory":"@DIR@"},"data":{"output":"one\ntwo\nthree\n","cursor":14,""" +
                """"size":14,"truncated":false}}"""

        const val RUNNING =
            """{"id":"sh_1","status":"running","command":"echo one","cwd":"/work/app","shell":"/usr/bin/zsh",""" +
                """"file":"/work/app/.out/sh_1.out","pid":4242,"metadata":{},"time":{"started":1}}"""
    }
}
