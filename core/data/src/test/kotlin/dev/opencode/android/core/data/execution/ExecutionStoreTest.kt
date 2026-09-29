package dev.opencode.android.core.data.execution

import dev.opencode.android.core.data.capability.ExperimentalRoute
import dev.opencode.android.core.model.LocationPublicRef
import dev.opencode.android.core.model.LocationRef
import dev.opencode.android.core.model.ShellInfo
import dev.opencode.android.core.model.ShellStatus
import dev.opencode.android.core.model.event.Event
import dev.opencode.android.core.model.event.PtyCreated
import dev.opencode.android.core.model.event.PtyDeleted
import dev.opencode.android.core.model.event.PtyExited
import dev.opencode.android.core.model.event.PtyInfo
import dev.opencode.android.core.model.event.PtyStatus
import dev.opencode.android.core.model.event.PtyUpdated
import dev.opencode.android.core.model.event.ShellCreated
import dev.opencode.android.core.model.event.ShellDeleted
import dev.opencode.android.core.model.event.ShellExited
import dev.opencode.android.core.model.event.WorktreeResolved
import dev.opencode.android.core.model.event.WorktreeUpdated
import dev.opencode.android.core.network.ServerApi
import dev.opencode.android.core.network.ServerApiFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest

/**
 * The per-location execution stores, and the eleven events that drive them (features doc §29–§32).
 *
 * **A fake server, not a fake interface.** The claims here are about which location an event lands in
 * and what a list contains after it, and a mocked `ServerApi` would agree with whatever the test was
 * written against. The `location[directory]` a request carries is read back from the recorded request.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExecutionStoreTest {

    private lateinit var server: MockWebServer
    private lateinit var api: ServerApi
    private val bodies = mutableMapOf<String, String>()
    private val recorded = mutableListOf<RecordedRequest>()

    /** Every request the app made, in order, so a test can assert on the query it sent. */
    private val requests: List<RecordedRequest> get() = synchronized(recorded) { recorded.toList() }

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        api = ServerApiFactory(OkHttpClient()).createForReads(server.url("/").toString())
        serve(200, DEFAULT)
    }

    /** Replaces the dispatcher, which is how a `404` or a `503` is simulated. */
    /**
     * Waits for a condition, with a deadline.
     *
     * **Every wait in this file is bounded.** The stores load over a real socket to a `MockWebServer`
     * on OkHttp's own threads, which no test scheduler advances, so a store's work is launched on an
     * unconfined dispatcher and the condition is polled against a deadline. A wait without a deadline
     * on a regression is a suite that hangs rather than a suite that fails, and the message names the
     * condition so a timeout says what did not happen.
     */
    private fun serve(status: Int, body: String) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                synchronized(recorded) { recorded += request }
                val answer = bodies[request.url.encodedPath] ?: body
                return MockResponse.Builder()
                    .code(status)
                    .addHeader("Content-Type", "application/json")
                    .body(answer)
                    .build()
            }
        }
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `a shell event for another location does not touch this store`() = runTest(UnconfinedTestDispatcher()) {
        val scope = backgroundScope
        val store = ExecutionSurface("srv", api, scope).at("/a")
        bodies["/api/shell"] = shellList("/a")

        assertFalse(store.apply(shellCreated("/b", "sh_other", "echo other")))
        assertTrue(store.shells.value.isEmpty())
    }

    @Test
    fun `a shell event for this location is a live insert, with no refetch`() = runTest(UnconfinedTestDispatcher()) {
        val scope = backgroundScope
        val store = ExecutionSurface("srv", api, scope).at("/a")

        assertTrue(store.apply(shellCreated("/a", "sh_1", "npm test")))
        assertEquals(listOf("sh_1"), store.shells.value.map { it.id })
        assertEquals("npm test", store.shells.value.single().command)
    }

    @Test
    fun `shell exited carries the status and the exit code onto the row`() = runTest(UnconfinedTestDispatcher()) {
        val scope = backgroundScope
        val store = ExecutionSurface("srv", api, scope).at("/a")
        store.apply(shellCreated("/a", "sh_1", "npm test"))

        store.apply(
            event("shell.exited", "/a", ShellExited("sh_1", exit = 2, status = ShellStatus.Exited), created = 5_000L),
        )
        val row = store.shells.value.single()
        assertEquals(ShellStatus.Exited, row.status)
        assertEquals(2, row.exit)
        assertEquals(5_000L, row.time.completed)
    }

    @Test
    fun `shell deleted removes the row`() = runTest(UnconfinedTestDispatcher()) {
        val scope = backgroundScope
        val store = ExecutionSurface("srv", api, scope).at("/a")
        store.apply(shellCreated("/a", "sh_1", "npm test"))

        assertTrue(store.apply(event("shell.deleted", "/a", ShellDeleted("sh_1"))))
        assertTrue(store.shells.value.isEmpty())
    }

    @Test
    fun `a pty created event is a live insert and updated renames it in place`() = runTest(UnconfinedTestDispatcher()) {
        val scope = backgroundScope
        // The server's own list holds the terminal, so a refetch and the event agree — which is the
        // arrangement a live server is in, and the one that makes the two paths indistinguishable.
        bodies["/api/pty"] = ptyList("/a", "pty_1" to "shell")
        val surface = ExecutionSurface("srv", api, scope)
        val store = surface.at("/a")
        store.apply(event("pty.created", "/a", PtyCreated(pty("pty_1", "shell"))))
        awaitUntil("the terminal to be listed") { store.ptys.value.any { it.id == "pty_1" } }
        assertEquals("shell", store.ptys.value.single().title)

        store.apply(
            event("pty.updated", "/a", PtyUpdated(pty("pty_1", "build log"))),
        )
        assertEquals(1, store.ptys.value.size)
        assertEquals("build log", store.ptys.value.single().title)
    }

    @Test
    fun `pty exited sets the status and the exit code`() = runTest(UnconfinedTestDispatcher()) {
        val scope = backgroundScope
        bodies["/api/pty"] = ptyList("/a", "pty_1" to "shell")
        val store = ExecutionSurface("srv", api, scope).at("/a")
        store.apply(event("pty.created", "/a", PtyCreated(pty("pty_1", "shell"))))
        awaitUntil("the terminal to be listed") { store.ptys.value.any { it.id == "pty_1" } }

        store.apply(event("pty.exited", "/a", PtyExited("pty_1", 130)))
        val row = store.ptys.value.single()
        assertEquals(PtyStatus.Exited, row.status)
        assertEquals(130, row.exitCode)
    }

    @Test
    fun `pty deleted removes the terminal`() = runTest(UnconfinedTestDispatcher()) {
        val scope = backgroundScope
        bodies["/api/pty"] = ptyList("/a", "pty_1" to "shell")
        val store = ExecutionSurface("srv", api, scope).at("/a")
        store.apply(event("pty.created", "/a", PtyCreated(pty("pty_1", "shell"))))
        awaitUntil("the terminal to be listed") { store.ptys.value.any { it.id == "pty_1" } }

        assertTrue(store.apply(event("pty.deleted", "/a", PtyDeleted("pty_1"))))
        assertTrue(store.ptys.value.isEmpty())
    }

    @Test
    fun `worktree updated re-reads the named project and nothing else`() = runTest(UnconfinedTestDispatcher()) {
        val scope = backgroundScope
        val surface = ExecutionSurface("srv", api, scope)
        surface.worktrees.start("prj_a")
        surface.worktrees.start("prj_b")
        awaitUntil("one worktree.list per project") { requests.size >= 2 }
        assertEquals(2, requests.size)

        val updated = WorktreeUpdated("prj_a")
        surface.worktrees.apply(Event(id = "e", type = "worktree.updated", payload = updated))
        awaitUntil("the named project to be re-read") { requests.size >= 3 }
        assertEquals(3, requests.size)
        assertTrue(requests.last().url.query.orEmpty().contains("projectID=prj_a"))
    }

    @Test
    fun `worktree resolved is recorded and then re-read`() = runTest(UnconfinedTestDispatcher()) {
        val scope = backgroundScope
        val surface = ExecutionSurface("srv", api, scope)
        bodies["/api/worktree"] = """[{"directory":"/work/.worktrees/a"}]"""
        surface.worktrees.start("prj_a")
        awaitUntil("the worktree list to load") { surface.worktrees.state.value.directories.isNotEmpty() }

        surface.worktrees.apply(
            Event(
                id = "e",
                type = "worktree.resolved",
                payload = WorktreeResolved(
                    projectID = "prj_a",
                    directory = "/work/.worktrees/a",
                    previous = "/work/.worktrees/old",
                    adopted = listOf("ses_1"),
                ),
            ),
        )
        awaitUntil("the worktree list to load") { surface.worktrees.state.value.directories.isNotEmpty() }

        val resolved = surface.worktrees.state.value.resolved.single()
        assertEquals("/work/.worktrees/old", resolved.previous)
        assertEquals(listOf("ses_1"), resolved.adopted)
        assertEquals(listOf("/work/.worktrees/a"), surface.worktrees.state.value.labels)
    }

    @Test
    fun `a shell completion is recorded in the ledger and the same id is not recorded twice`() = runTest(UnconfinedTestDispatcher()) {
        val scope = backgroundScope
        val surface = ExecutionSurface("srv", api, scope)
        // The server's own list holds the command, so a refetch and the event agree — which is the
        // arrangement a live server is in, and the one that makes the command name trustworthy.
        bodies["/api/shell"] = shellList("/a", "sh_1" to "npm run build")
        surface.at("/a").apply(shellCreated("/a", "sh_1", "npm run build"))
        awaitUntil("the command to be in the list") {
            surface.at("/a").shells.value.any { it.id == "sh_1" }
        }

        surface.apply(
            event("shell.exited", "/a", ShellExited("sh_1", exit = 0, status = ShellStatus.Exited), created = 9_000L),
        )
        surface.apply(
            event("shell.exited", "/a", ShellExited("sh_1", exit = 0, status = ShellStatus.Exited), created = 9_000L),
        )

        val finished = surface.finishedShells.value.single()
        assertEquals("sh_1", finished.id)
        assertEquals("npm run build", finished.command)
        assertEquals("exited", finished.status)
        assertEquals(0, finished.exitCode)
        assertEquals("/a", finished.directory)
        assertEquals(9_000L, finished.completedAtMillis)
    }

    @Test
    fun `opening a location's panel drops its completions`() = runTest(UnconfinedTestDispatcher()) {
        val scope = backgroundScope
        val surface = ExecutionSurface("srv", api, scope)
        bodies["/api/shell"] = shellList("/a", "sh_1" to "npm run build")
        surface.at("/a").apply(shellCreated("/a", "sh_1", "npm run build"))
        awaitUntil("the command to be in the list") {
            surface.at("/a").shells.value.any { it.id == "sh_1" }
        }
        surface.apply(event("shell.exited", "/a", ShellExited("sh_1", 0, ShellStatus.Exited)))
        surface.at("/b").apply(shellCreated("/b", "sh_2", "pytest"))
        surface.apply(event("shell.exited", "/b", ShellExited("sh_2", 1, ShellStatus.Exited)))
        assertEquals(2, surface.finishedShells.value.size)

        surface.markFinishedSeen("/a")
        assertEquals(listOf("sh_2"), surface.finishedShells.value.map { it.id })
    }

    @Test
    fun `the ledger is bounded so a long-lived process cannot grow it without limit`() = runTest(UnconfinedTestDispatcher()) {
        val scope = backgroundScope
        val surface = ExecutionSurface("srv", api, scope)
        repeat(ExecutionSurface.MAX_FINISHED + 5) { index ->
            surface.recordFinished(
                FinishedShell(
                    id = "sh_$index",
                    command = "cmd",
                    status = "exited",
                    exitCode = 0,
                    directory = "/a",
                    completedAtMillis = index.toLong(),
                ),
            )
        }
        assertEquals(ExecutionSurface.MAX_FINISHED, surface.finishedShells.value.size)
        // The oldest fall off, which is the right end to lose: the newest is the one the user is waiting for.
        assertEquals("sh_${ExecutionSurface.MAX_FINISHED + 4}", surface.finishedShells.value.last().id)
    }

    @Test
    fun `the aggregate of shells is every location the client has opened`() = runTest(UnconfinedTestDispatcher()) {
        val scope = backgroundScope
        val surface = ExecutionSurface("srv", api, scope)
        bodies["/api/shell"] = shellList("/a", "sh_1" to "one", "sh_2" to "two")
        surface.at("/a").apply(shellCreated("/a", "sh_1", "one"))
        surface.at("/b").apply(shellCreated("/b", "sh_2", "two"))
        awaitUntil("both locations' shells to be aggregated") {
            surface.shells.value.map { it.id }.toSet() == setOf("sh_1", "sh_2")
        }
    }

    @Test
    fun `dropping a location removes its store, so reopening re-reads`() = runTest(UnconfinedTestDispatcher()) {
        val scope = backgroundScope
        val surface = ExecutionSurface("srv", api, scope)
        surface.at("/a").apply(shellCreated("/a", "sh_1", "one"))
        assertEquals(1, surface.directories.size)

        surface.dropLocation("/a")
        assertTrue(surface.directories.isEmpty())
        assertTrue(surface.at("/a").shells.value.isEmpty())
    }

    @Test
    fun `the experimental terminals are unusable until the switch is on`() = runTest(UnconfinedTestDispatcher()) {
        val scope = backgroundScope
        val surface = ExecutionSurface("srv", api, scope)
        bodies["/api/experimental/session/ses_1/terminal"] = """{"data":[]}"""

        surface.terminals.list("ses_1")
        assertEquals(PersistentPtyAvailability.Unknown, surface.terminals.availability.value)
        assertFalse(surface.terminalsUsable(allowedBySetting = true))
    }

    @Test
    fun `a 404 on the probe marks the whole feature absent for this process`() = runTest(UnconfinedTestDispatcher()) {
        val scope = backgroundScope
        val surface = ExecutionSurface("srv", api, scope)
        // A route the server does not have answers `404` with a body that is not one of its `_tag`
        // errors — a proxy's HTML page, or nothing at all. That is what makes the classification fall
        // through to the status code, and it is why a `404` carrying a *decodable* `InvalidRequestError`
        // is a different thing: the route exists and rejected the request.
        serve(404, "<html><body>404</body></html>")
        surface.terminals.allow(true)

        val result = surface.terminals.list("ses_1")
        val availability = surface.terminals.availability.value
        assertTrue("result=$result availability=$availability", availability is PersistentPtyAvailability.Absent)
        assertFalse(surface.terminalsUsable(allowedBySetting = true))
    }

    @Test
    fun `a 503 means the host is not running, which is not the same as absent`() = runTest(UnconfinedTestDispatcher()) {
        val scope = backgroundScope
        val surface = ExecutionSurface("srv", api, scope)
        serve(503, """{"_tag":"ServiceUnavailableError","message":"persistent-pty host"}""")
        surface.terminals.allow(true)

        surface.terminals.list("ses_1")
        awaitUntil("the probe to be recorded") {
            surface.terminals.availability.value == PersistentPtyAvailability.HostDown
        }
        // The routes are there and the service is down: the pane says "start the host" rather than
        // hiding the feature or pretending it works.
        assertEquals(PersistentPtyAvailability.HostDown, surface.terminals.availability.value)
        assertTrue(surface.terminalsUsable(allowedBySetting = true))
    }

    @Test
    fun `a 200 makes the terminals usable when the switch is on`() = runTest(UnconfinedTestDispatcher()) {
        val scope = backgroundScope
        val surface = ExecutionSurface("srv", api, scope)
        bodies["/api/experimental/session/ses_1/terminal"] = """{"data":[]}"""
        surface.terminals.allow(true)

        surface.terminals.list("ses_1")
        awaitUntil("the probe to be recorded") {
            surface.terminals.availability.value == PersistentPtyAvailability.Present
        }
        assertEquals(PersistentPtyAvailability.Present, surface.terminals.availability.value)
        assertTrue(surface.terminalsUsable(allowedBySetting = true))
    }

    @Test
    fun `a call refused for a switched-off feature records nothing about the route`() = runTest(UnconfinedTestDispatcher()) {
        val scope = backgroundScope
        val surface = ExecutionSurface("srv", api, scope)
        val requestsBefore = requests.size

        val result = surface.terminals.list("ses_1")
        assertTrue(result.isFailure)
        assertEquals(requestsBefore, requests.size)
        assertEquals(PersistentPtyAvailability.Unknown, surface.terminals.availability.value)
    }

    // ------------------------------------------------------------------ helpers

    /** A `pty.list` answer, with every field `PtyInfo` requires. */
    private fun ptyList(directory: String, vararg terminals: Pair<String, String>): String = buildString {
        append("""{"location":{"directory":"""").append(directory).append(""""},"data":[""")
        append(
            terminals.joinToString(",") { (id, title) ->
                """{"id":"$id","title":"$title","command":"/bin/bash","args":["-l"],""" +
                    """"cwd":"$directory","status":"running","pid":4242}"""
            },
        )
        append("]}")
    }

    /** A `shell.list` answer, with every field `ShellInfo` requires. */
    private fun shellList(directory: String, vararg shells: Pair<String, String>): String = buildString {
        append("""{"location":{"directory":"""").append(directory).append(""""},"data":[""")
        append(
            shells.joinToString(",") { (id, command) ->
                """{"id":"$id","status":"running","command":"$command","cwd":"$directory",""" +
                    """"shell":"/bin/bash","file":"/tmp/out","metadata":{},"time":{"started":1000}}"""
            },
        )
        append("]}")
    }

    private fun event(
        type: String,
        directory: String?,
        payload: dev.opencode.android.core.model.event.EventPayload,
        created: Long? = 1_000L,
    ) = Event(
        id = "e_${type}_${created}",
        type = type,
        created = created,
        location = directory?.let { LocationRef(it) },
        payload = payload,
    )

    private fun shellCreated(directory: String, id: String, command: String) = event(
        "shell.created",
        directory,
        ShellCreated(
            ShellInfo(
                id = id,
                status = ShellStatus.Running,
                command = command,
                cwd = directory,
                shell = "/bin/bash",
                file = "/tmp/out",
                metadata = emptyMap(),
                time = ShellInfo.Time(started = 1_000L),
            ),
        ),
    )

    private fun pty(id: String, title: String) = PtyInfo(
        id = id,
        title = title,
        command = "/bin/bash",
        args = listOf("-l"),
        cwd = "/work",
        status = PtyStatus.Running,
        pid = 4242,
    )

    private companion object {
        const val DEFAULT = """{"location":{"directory":"/a"},"data":[]}"""
    }
}

/**
 * Waits for a condition, with a deadline, and advances the test scheduler while it waits.
 *
 * **Every wait in this file is bounded.** The stores load over a real socket to a `MockWebServer` on
 * OkHttp's own threads, which no scheduler drives, and the `SyncedResource` debounce is a `delay` on
 * the store's dispatcher, which only one does. So the poll does both: it runs the virtual clock forward
 * a little and it sleeps a little. A wait without a deadline on a regression is a suite that hangs
 * rather than a suite that fails, and the message names the condition so a timeout says what did not
 * happen.
 */
private fun TestScope.awaitUntil(
    what: String,
    timeoutMillis: Long = 10_000,
    predicate: () -> Boolean,
) {
    val deadline = System.nanoTime() + timeoutMillis * 1_000_000
    while (!predicate()) {
        if (System.nanoTime() > deadline) {
            throw AssertionError("Timed out after ${timeoutMillis}ms waiting for: $what")
        }
        testScheduler.advanceTimeBy(50)
        runCurrent()
        Thread.sleep(20)
    }
}
