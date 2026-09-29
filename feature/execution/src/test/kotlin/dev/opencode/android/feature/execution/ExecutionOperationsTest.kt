package dev.opencode.android.feature.execution

import dev.opencode.android.core.data.execution.ExecutionCommands
import dev.opencode.android.core.data.execution.ExecutionSurface
import dev.opencode.android.core.data.execution.PersistentPtyAvailability
import dev.opencode.android.core.data.execution.WorktreeCommands
import dev.opencode.android.core.data.execution.WorktreeRemoval
import dev.opencode.android.core.model.Delivery
import dev.opencode.android.core.model.PtySize
import dev.opencode.android.core.model.ShellCreateRequest
import dev.opencode.android.core.model.WorktreeCreateRequest
import dev.opencode.android.core.model.WorktreeRemoveRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * All thirty Phase 7 operations, over a real [dev.opencode.android.core.network.ServerApi] on a
 * `MockWebServer` (plan §6, "API").
 *
 * **The claims are about the wire, so the wire is what is asserted.** Three of them are the ones a
 * compiled client gets wrong without noticing:
 *
 *  - `shell.output`'s `cursor` and `limit` are **strings** on the wire even though the schema calls
 *    them numbers (features doc §30), and this client only ever sends a cursor a previous page returned.
 *  - `worktree.list` answers a **bare array** and its `projectID` is **required**, so a client that
 *    expects a `{data}` wrapper or that sends a location gets nothing.
 *  - `pty.connect-token` answers `403` without the `x-opencode-ticket` header, and a `403` is
 *    "forbidden" about a route that exists — which would send capability detection the wrong way.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExecutionOperationsTest : ExecutionServerTest() {

    private val directory = "/work/app"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun stopScope() {
        scope.cancel()
    }

    private fun surface(): ExecutionSurface = ExecutionSurface("srv", server.api, scope)

    // ------------------------------------------------------------------ shells (5)

    @Test
    fun `shell list is location-scoped and unwrapped from data`() = runBlocking {
        server.answer(
            "GET /api/shell",
            """{"location":{"directory":"$directory"},"data":[{"id":"sh_1","status":"running",""" +
                """"command":"npm test","cwd":"$directory","shell":"/bin/bash","file":"/tmp/o",""" +
                """"metadata":{},"time":{"started":1}}]}""",
        )
        val shells = server.api.listShells(directory).data
        assertEquals(listOf("sh_1"), shells.map { it.id })
        assertSentDirectory(server.lastRequest("/api/shell")!!)
    }

    @Test
    fun `shell create sends the command as the only required field`() = runBlocking {
        server.answer("POST /api/shell", """{"location":{"directory":"$directory"},"data":$SHELL}""")
        val result = server.api.createShell(directory, ShellCreateRequest(command = "npm test")).data
        assertEquals("sh_1", result.id)
        // The optional fields the user did not fill in are absent rather than null, so the server applies
        // its own defaults.
        assertEquals("""{"command":"npm test"}""", server.lastBody("/api/shell"))
    }

    @Test
    fun `shell get takes the id and the location`() = runBlocking {
        server.answer("GET /api/shell/sh_1", """{"location":{"directory":"$directory"},"data":$SHELL}""")
        server.api.getShell("sh_1", directory)
        assertSentDirectory(server.lastRequest("/api/shell/sh_1")!!)
    }

    @Test
    fun `shell output sends the cursor as a string`() = runBlocking {
        server.answer(
            "GET /api/shell/sh_1/output",
            """{"location":{"directory":"$directory"},"data":{"output":"hi","cursor":2,"size":9,"truncated":false}}""",
        )
        val page = server.api.getShellOutput("sh_1", directory, cursor = "2").data
        assertEquals(2L, page.cursor)
        assertEquals(9L, page.size)
        // A number here would not be accepted by the route, and a mocked interface would not notice.
        assertTrue(server.lastRequest("/api/shell/sh_1/output")!!.contains("cursor=2"))
    }

    @Test
    fun `shell remove deletes by id`() = runBlocking {
        server.answer("DELETE /api/shell/sh_1", "{}", status = 204)
        server.api.removeShell("sh_1", directory)
        assertTrue(server.requests.last().startsWith("DELETE /api/shell/sh_1"))
    }

    // ------------------------------------------------------------------ terminals (7)

    @Test
    fun `pty list is location-scoped`() = runBlocking {
        server.answer("GET /api/pty", """{"location":{"directory":"$directory"},"data":[]}""")
        server.api.listPtys(directory)
        assertSentDirectory(server.lastRequest("/api/pty")!!)
    }

    @Test
    fun `pty create omits the command so the configured shell runs`() = runBlocking {
        server.answer("POST /api/pty", """{"location":{"directory":"$directory"},"data":$PTY}""")
        server.api.createPty(directory, dev.opencode.android.core.model.PtyCreateRequest(title = "build"))
        // Only the title is sent: a `null` command is what asks the server for its configured shell.
        assertEquals("""{"title":"build"}""", server.lastBody("/api/pty"))
    }

    @Test
    fun `pty update sends the size and not the title`() = runBlocking {
        server.answer("PUT /api/pty/pty_1", """{"location":{"directory":"$directory"},"data":$PTY}""")
        server.api.updatePty("pty_1", dev.opencode.android.core.model.PtyUpdateRequest(size = PtySize(24, 80)), directory)
        // Resizing does not rename: a `pty.update` that carried the old title would fire `pty.updated`
        // and make every open view of the terminal appear to change.
        assertEquals("""{"size":{"rows":24,"cols":80}}""", server.lastBody("/api/pty/pty_1"))
    }

    @Test
    fun `pty update renames without a size`() = runBlocking {
        server.answer("PUT /api/pty/pty_1", """{"location":{"directory":"$directory"},"data":$PTY}""")
        server.api.updatePty("pty_1", dev.opencode.android.core.model.PtyUpdateRequest(title = "log"), directory)
        assertEquals("""{"title":"log"}""", server.lastBody("/api/pty/pty_1"))
    }

    @Test
    fun `pty remove deletes by id`() = runBlocking {
        server.answer("DELETE /api/pty/pty_1", "{}", status = 204)
        server.api.removePty("pty_1", directory)
        assertTrue(server.requests.last().startsWith("DELETE /api/pty/pty_1"))
    }

    @Test
    fun `the connect token is asked for with the ticket header`() = runBlocking {
        server.answer(
            "POST /api/pty/pty_1/connect-token",
            """{"location":{"directory":"$directory"},"data":{"ticket":"tkt_1","expires_in":30}}""",
        )
        val token = server.api.createPtyTicket("pty_1", directory).data
        assertEquals("tkt_1", token.ticket)
        assertEquals(30L, token.expiresInSeconds)
        // Without this header the route answers `403` — "forbidden" about a route that exists, which is
        // the worst possible answer for capability detection.
        assertEquals("1", server.lastHeader("/connect-token", "x-opencode-ticket"))
    }

    @Test
    fun `pty connect takes the cursor and the ticket as query names`() = runBlocking {
        server.answer("GET /api/pty/pty_1/connect", "true")
        server.api.ptyConnect("pty_1", directory, cursor = "1000", ticket = "tkt_1")
        val request = server.lastRequest("/connect")!!
        assertTrue(request.contains("cursor=1000"))
        assertTrue(request.contains("ticket=tkt_1"))
        assertSentDirectory(request)
    }

    // ------------------------------------------------------------------ persistent terminals (11)

    @Test
    fun `the session terminal list is the capability probe and is experimental`() = runBlocking {
        server.answer("GET /api/experimental/session/ses_1/terminal", """{"data":[]}""")
        val terminals = server.api.listSessionTerminals("ses_1").data
        assertTrue(terminals.isEmpty())
        assertTrue(server.requests.last().startsWith("GET /api/experimental/session/ses_1/terminal"))
    }

    @Test
    fun `a session terminal create sends the three fields the schema requires`() = runBlocking {
        server.answer(
            "POST /api/experimental/session/ses_1/terminal",
            """{"data":{"id":"pty_1","title":"t","command":"/bin/bash","args":[],"cwd":"/work",""" +
                """"status":"running","pid":1,"sessionID":"ses_1","foregroundProcess":null,""" +
                """"size":{"cols":80,"rows":24},"output":{"head":0,"tail":0}}}""",
        )
        val terminal = server.api.createSessionTerminal(
            "ses_1",
            dev.opencode.android.core.model.SessionTerminalCreateRequest(
                command = null,
                args = listOf("-l"),
                title = "t",
                env = emptyMap(),
                size = PtySize(24, 80),
            ),
        ).data
        assertEquals("pty_1", terminal.id)
        // `args`, `title` and `env` are required by the schema even when the command is not, which is
        // the opposite of `pty.create` and the reason they are not nullable here.
        assertEquals(
            """{"args":["-l"],"title":"t","env":{},"size":{"rows":24,"cols":80}}""",
            server.lastBody("/terminal"),
        )
    }

    @Test
    fun `a session terminal read accepts a null data as no terminal`() = runBlocking {
        server.answer("GET /api/experimental/session/ses_1/terminal/read", """{"data":null}""")
        val read = server.api.readSessionTerminal("ses_1", "200").data
        // The route's `data` is nullable and null means "this session has no terminal yet", which is a
        // normal state rather than a failure.
        assertNull(read)
        assertTrue(server.lastRequest("/terminal/read")!!.contains("lines=200"))
    }

    @Test
    fun `a session terminal read decodes the screen`() = runBlocking {
        server.answer(
            "GET /api/experimental/session/ses_1/terminal/read",
            """{"data":{"ptyID":"pty_1","title":"t","cwd":"/work","foregroundProcess":"vim",""" +
                """"screen":{"text":"hi","cols":80,"rows":24,"cursor":{"x":2,"y":0}}}}""",
        )
        val read = server.api.readSessionTerminal("ses_1").data
        assertEquals("pty_1", read?.ptyID)
        assertEquals("vim", read?.foregroundProcess)
        assertEquals("hi", read?.screen?.text)
    }

    @Test
    fun `a persistent pty get, update, snapshot and remove are all declared`() = runBlocking {
        server.answer("GET /api/experimental/persistent-pty/pty_1", """{"data":$PERSISTENT_INFO}""")
        server.api.getSessionTerminal("pty_1")
        assertTrue(server.requests.last().startsWith("GET /api/experimental/persistent-pty/pty_1"))

        server.answer("PUT /api/experimental/persistent-pty/pty_1", """{"data":$PERSISTENT_INFO}""")
        server.api.updateSessionTerminal(
            "pty_1",
            dev.opencode.android.core.model.SessionTerminalUpdateRequest(attachmentID = "att_1", size = PtySize(30, 100)),
        )
        assertEquals(
            """{"attachmentID":"att_1","size":{"rows":30,"cols":100}}""",
            server.lastBody("/persistent-pty/pty_1"),
        )

        server.answer(
            "GET /api/experimental/persistent-pty/pty_1/snapshot",
            """{"data":{"info":$PERSISTENT_INFO,"text":"hi","checkpoint":"Y2hlY2s=","cursor":{"x":1,"y":0}}}""",
        )
        val snapshot = server.api.getSessionTerminalSnapshot("pty_1").data
        assertEquals("Y2hlY2s=", snapshot.checkpoint)
        assertEquals(1, snapshot.cursor.x)

        server.answer("DELETE /api/experimental/persistent-pty/pty_1", "{}", status = 204)
        server.api.removeSessionTerminal("pty_1")
        assertTrue(server.requests.last().startsWith("DELETE /api/experimental/persistent-pty/pty_1"))
    }

    @Test
    fun `the persistent pty connect takes the role, attachment and takeover names`() = runBlocking {
        server.answer("GET /api/experimental/persistent-pty/pty_1/connect", "true")
        server.api.sessionTerminalConnect(
            "pty_1",
            cursor = "10",
            role = "viewer",
            attachmentID = "att_1",
            takeover = "1",
            inputProtocol = "v1",
            ticket = "tkt_1",
        )
        val request = server.lastRequest("/persistent-pty/pty_1/connect")!!
        assertTrue(request.contains("role=viewer"))
        assertTrue(request.contains("attachment_id=att_1"))
        assertTrue(request.contains("takeover=1"))
        assertTrue(request.contains("input_protocol=v1"))
    }

    @Test
    fun `the two host routes answer 204 and a handoff carries its instance`() = runBlocking {
        server.answer("POST /api/experimental/persistent-pty/shutdown", "{}", status = 204)
        server.api.shutdownPersistentPtyHost()
        assertTrue(server.requests.last().startsWith("POST /api/experimental/persistent-pty/shutdown"))

        server.answer(
            "POST /api/experimental/persistent-pty/handoff",
            """{"handoff":{"directory":"/work","instanceID":"i_1","ticket":"t","expiresAt":1}}""",
        )
        val handoff = server.api.handoffPersistentPtyHost().handoff
        assertEquals("i_1", handoff?.instanceID)
    }

    // ------------------------------------------------------------------ worktrees (4)

    @Test
    fun `the worktree list is a bare array and the project is required`() = runBlocking {
        server.answer("GET /api/worktree", """[{"directory":"/work/.wt/a","strategy":"plugin"}]""")
        val list = server.api.listWorktrees("prj_1")
        assertEquals(listOf("/work/.wt/a"), list.map { it.directory })
        // A `{data}` wrapper is not what this route answers, and no location parameter exists on it.
        assertTrue(server.lastRequest("/api/worktree")!!.contains("projectID=prj_1"))
        assertFalse(server.lastRequest("/api/worktree")!!.contains("location"))
    }

    @Test
    fun `the worktree create sends only the fields the user filled in`() = runBlocking {
        server.answer("POST /api/worktree", """{"directory":"/work/.wt/a"}""")
        val created = server.api.createWorktree(WorktreeCreateRequest(projectID = "prj_1", name = "a"))
        assertEquals("/work/.wt/a", created.directory)
        assertEquals("""{"projectID":"prj_1","name":"a"}""", server.lastBody("/api/worktree"))
    }

    @Test
    fun `the worktree remove is a DELETE with a body and force is explicit`() = runBlocking {
        server.answer("DELETE /api/worktree", "{}", status = 204)
        server.api.removeWorktree(WorktreeRemoveRequest("prj_1", "/work/.wt/a", force = false))
        val request = server.requests.last()
        assertTrue(request.startsWith("DELETE /api/worktree"))
        // All three are required by the schema, `force` included, so even the safe attempt says false.
        assertEquals(
            """{"projectID":"prj_1","directory":"/work/.wt/a","force":false}""",
            server.lastBody("/api/worktree"),
        )
    }

    @Test
    fun `a refused removal keeps the server's own reason and the force hint`() = runBlocking {
        server.answer(
            "DELETE /api/worktree",
            """{"name":"WorktreeError","data":{"message":"uncommitted changes","forceRequired":true}}""",
            status = 400,
        )
        val commands = WorktreeCommands(server.api)
        val outcome = commands.remove("prj_1", "/work/.wt/a", force = false).getOrNull()
        assertTrue("was $outcome", outcome is WorktreeRemoval.Refused)
        outcome as WorktreeRemoval.Refused
        assertEquals("uncommitted changes", outcome.message)
        assertTrue(outcome.forceRequired)
    }

    @Test
    fun `a removal that is not a worktree error is a plain failure`() = runBlocking {
        server.answer("DELETE /api/worktree", """{"_tag":"UnauthorizedError","message":"no"}""", status = 401)
        val result = WorktreeCommands(server.api).remove("prj_1", "/work/.wt/a", force = true)
        assertTrue(result.isFailure)
    }

    @Test
    fun `the worktree refresh posts the project and answers 204`() = runBlocking {
        server.answer("POST /api/worktree/refresh", "{}", status = 204)
        server.api.refreshWorktrees(dev.opencode.android.core.model.WorktreeRefreshRequest("prj_1"))
        assertEquals("""{"projectID":"prj_1"}""", server.lastBody("/api/worktree/refresh"))
    }

    // ------------------------------------------------------------------ move, project, shells

    @Test
    fun `a session move sends the directory and the delivery it was given`() = runBlocking {
        server.answer("POST /api/session/ses_1/move", "{}", status = 204)
        server.api.moveSession(
            "ses_1",
            dev.opencode.android.core.model.SessionMoveRequest(
                directory = "/work/.wt/a",
                delivery = Delivery.Queue,
            ),
        )
        // Delivery is passed through rather than chosen here: a move that switched a queue to a steer
        // would change what happens to a prompt the user has already sent.
        assertEquals(
            """{"directory":"/work/.wt/a","delivery":"queue"}""",
            server.lastBody("/move"),
        )
    }

    @Test
    fun `a project update sends only the fields the user changed`() = runBlocking {
        server.answer("PATCH /api/project/prj_1", """{"id":"prj_1","canonical":"/work","time":{""" +
            """"created":1,"updated":1,"active":1},"sandboxes":[]}""")
        val updated = server.api.updateProject(
            "prj_1",
            dev.opencode.android.core.model.ProjectUpdateRequest(
                name = "App",
                commands = dev.opencode.android.core.model.Project.Commands("npm run dev"),
            ),
        )
        assertEquals("prj_1", updated.id)
        assertEquals("""{"name":"App","commands":{"start":"npm run dev"}}""", server.lastBody("/project/prj_1"))
    }

    @Test
    fun `the shell options are a bare array and are not location-scoped`() = runBlocking {
        server.answer("GET /api/config/shell", """[{"path":"/bin/bash","name":"bash","acceptable":true}]""")
        val shells = server.api.listShellOptions()
        assertEquals(listOf("/bin/bash"), shells.map { it.path })
        // It describes the server's `PATH`, not a checkout, so no location is sent.
        assertEquals("GET /api/config/shell", server.lastRequest("/api/config/shell")?.substringBefore("?"))
    }

    @Test
    fun `a shell the server will not run is reported as unacceptable`() = runBlocking {
        server.answer(
            "GET /api/config/shell",
            """[{"path":"/bin/zsh","name":"zsh","acceptable":false}]""",
        )
        assertFalse(server.api.listShellOptions().single().acceptable)
    }

    // ------------------------------------------------------------------ through the surface

    @Test
    fun `the commands object is what the stores use, and a failed call records the error`() = runBlocking {
        server.answer("POST /api/shell", """{"location":{"directory":"$directory"},"data":$SHELL}""")
        val commands = ExecutionCommands(server.api) { emptyMap() }
        val shell = commands.runShell(directory, "npm test").getOrNull()
        assertEquals("sh_1", shell?.id)
        assertNull(commands.error.value)
    }

    @Test
    fun `a session terminal is refused before a call when the switch is off`() = runBlocking {
        val surface = surface()
        val before = server.requests.size
        val result = surface.terminals.list("ses_1")
        assertTrue(result.isFailure)
        assertEquals(before, server.requests.size)
        // A route the app was told not to call has told us nothing about whether it exists.
        assertEquals(PersistentPtyAvailability.Unknown, surface.terminals.availability.value)
    }

    @Test
    fun `a session move through the surface is the same call`() = runBlocking {
        server.answer("POST /api/session/ses_1/move", "{}", status = 204)
        val result = surface().moveSession("ses_1", "/work/.wt/a", Delivery.Steer)
        assertTrue(result.isSuccess)
        assertEquals("""{"directory":"/work/.wt/a","delivery":"steer"}""", server.lastBody("/move"))
    }

    private companion object {
        const val SHELL = """{"id":"sh_1","status":"running","command":"npm test","cwd":"/work/app",""" +
            """"shell":"/bin/bash","file":"/tmp/o","metadata":{},"time":{"started":1}}"""
        const val PTY = """{"id":"pty_1","title":"shell","command":"/bin/bash","args":["-l"],""" +
            """"cwd":"/work/app","status":"running","pid":1}"""
        const val PERSISTENT_INFO = """{"id":"pty_1","title":"t","command":"/bin/bash","args":[],""" +
            """"cwd":"/work","status":"running","pid":1,"sessionID":"ses_1","foregroundProcess":null,""" +
            """"size":{"cols":80,"rows":24},"output":{"head":0,"tail":0}}"""
    }
}
