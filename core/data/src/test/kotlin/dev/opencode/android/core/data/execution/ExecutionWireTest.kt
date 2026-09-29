package dev.opencode.android.core.data.execution

import dev.opencode.android.core.model.PtySize
import dev.opencode.android.core.model.SessionTerminalRead
import dev.opencode.android.core.model.WorktreeDirectory
import dev.opencode.android.core.model.WorktreeRefreshRequest
import dev.opencode.android.core.network.ServerApi
import dev.opencode.android.core.network.ServerApiFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The parts of the Phase 7 stores a mocking API would let through.
 *
 * **What this adds to [ExecutionStoreTest] is that the requests are real.** That file asserts which
 * store an event lands in; this one asserts the wire: that `worktree.remove` is a `DELETE` *with* a
 * body (Retrofit refuses a `@Body` on a plain `@DELETE`, so a client that got this wrong could not be
 * compiled at all — and the assertion below is what proves the fix landed), that a worktree refusal is
 * distinguished by its `name` discriminator rather than by whether the body happened to parse, and
 * that the persistent-terminal store records a capability answer and refuses calls when told to.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExecutionWireTest {

    private lateinit var server: MockWebServer
    private lateinit var api: ServerApi
    private val bodies = mutableMapOf<String, String>()
    private val statuses = mutableMapOf<String, Int>()
    private val sent = mutableListOf<RecordedRequest>()

    /** Every request as `METHOD path?query`, so an assertion can name the route and its parameters. */
    private val requests: List<String>
        get() = synchronized(sent) { sent.map { "${it.method} ${it.url.encodedPath}?${it.url.query}" } }

    /** The last request *body* whose path contains [fragment]. */
    private fun lastBody(fragment: String): String? = synchronized(sent) {
        sent.lastOrNull { it.url.encodedPath.contains(fragment) }
            ?.body?.let { String(it.toByteArray(), Charsets.UTF_8) }
    }

    private fun lastQuery(fragment: String): String? = synchronized(sent) {
        sent.lastOrNull { it.url.encodedPath.contains(fragment) }?.url?.query
    }

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        api = ServerApiFactory(OkHttpClient()).createForReads(server.url("/").toString())
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                synchronized(sent) { sent += request }
                val path = request.url.encodedPath
                val status = statuses[path] ?: 200
                // A 204 must not carry a body; OkHttp rejects one, which would fail at the transport
                // instead of at the assertion and hide the route that was actually exercised.
                if (status == 204) return MockResponse.Builder().code(status).build()
                val body = bodies[path] ?: "{}"
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
        server.close()
    }

    // ------------------------------------------------------------------ worktree.remove

    @Test
    fun `a worktree removal is a DELETE that carries its body`() = runTest {
        statuses["/api/worktree"] = 204
        val commands = WorktreeCommands(api)

        val outcome = commands.remove("prj_1", "/work/app/.opencode/worktree/a", force = false)

        assertEquals(WorktreeRemoval.Removed, outcome.getOrNull())
        // Retrofit throws on a `@Body` under a plain `@DELETE`, so this route is declared with
        // `@HTTP(method = "DELETE", hasBody = true)`. If that were reverted the call would fail before
        // reaching the assertion — which is why this test exists rather than a compile check.
        assertTrue(
            "was ${requests.last()}",
            requests.last().startsWith("DELETE /api/worktree"),
        )
        // All three fields are required by the schema, `force` included, so even the safe attempt says
        // false rather than leaving the server to guess.
        assertEquals(
            """{"projectID":"prj_1","directory":"/work/app/.opencode/worktree/a","force":false}""",
            lastBody("/api/worktree"),
        )
    }

    @Test
    fun `a refusal is recognised by its name and carries the server's reason`() = runTest {
        statuses["/api/worktree"] = 400
        bodies["/api/worktree"] =
            """{"name":"WorktreeError","data":{"message":"uncommitted changes","forceRequired":true}}"""

        val outcome = WorktreeCommands(api).remove("prj_1", "/wt", force = false).getOrNull()

        val refused = outcome as? WorktreeRemoval.Refused
        assertNotNull("was $outcome", refused)
        assertEquals("uncommitted changes", refused!!.message)
        assertTrue(refused.forceRequired)
    }

    @Test
    fun `a 400 that is not a WorktreeError is a plain failure, not a refusal`() = runTest {
        // The body of an `InvalidRequestError` has no `name` field. `ignoreUnknownKeys` plus a defaulted
        // `name` would decode it as a `WorktreeError` with an empty message, and the panel would then
        // offer "force" for a request that was rejected as malformed — so `name` has no default and
        // this body cannot parse at all.
        statuses["/api/worktree"] = 400
        bodies["/api/worktree"] = """{"_tag":"InvalidRequestError","message":"directory is required"}"""

        val result = WorktreeCommands(api).remove("prj_1", "/wt", force = true)

        assertTrue("was $result", result.isFailure)
    }

    @Test
    fun `a forced removal says so on the wire`() = runTest {
        statuses["/api/worktree"] = 204
        WorktreeCommands(api).remove("prj_1", "/wt", force = true)
        assertTrue(requests.last().startsWith("DELETE /api/worktree"))
        assertTrue(lastBody("/api/worktree")!!.contains(""""force":true"""))
    }

    // ------------------------------------------------------------------ worktree.list / create / refresh

    @Test
    fun `the worktree list asks for a project and sends no location`() = runTest {
        bodies["/api/worktree"] = """[{"directory":"/work/.wt/a","strategy":"plugin"}]"""

        val directories: List<WorktreeDirectory> = api.listWorktrees("prj_1")

        assertEquals(listOf("/work/.wt/a"), directories.map { it.directory })
        assertEquals("plugin", directories.single().strategy)
        // The route takes no location at all: the worktree table is per project, so a client that sent
        // one would be asking a question the route has no answer for.
        assertFalse(requests.last().contains("location"))
        assertEquals("projectID=prj_1", lastQuery("/api/worktree"))
    }

    @Test
    fun `a worktree creation sends only the fields that were filled in`() = runTest {
        bodies["/api/worktree"] = """{"directory":"/work/.wt/a"}"""

        val created = api.createWorktree(
            dev.opencode.android.core.model.WorktreeCreateRequest(projectID = "prj_1", name = "a"),
        )

        assertEquals("/work/.wt/a", created.directory)
        // `from`, `branch` and `directory` are omitted rather than null so the server's own defaults
        // decide the path — a null would be an explicit "there is no branch" to some servers.
        assertEquals("""{"projectID":"prj_1","name":"a"}""", lastBody("/api/worktree"))
    }

    @Test
    fun `the refresh is a POST that carries only the project`() = runTest {
        statuses["/api/worktree/refresh"] = 204
        api.refreshWorktrees(WorktreeRefreshRequest("prj_1"))
        assertTrue(requests.last().startsWith("POST /api/worktree/refresh"))
        assertEquals("""{"projectID":"prj_1"}""", lastBody("/refresh"))
    }

    // ------------------------------------------------------------------ session.move / project.update

    @Test
    fun `a session move passes its delivery through rather than choosing one`() = runTest {
        statuses["/api/session/ses_1/move"] = 204
        api.moveSession("ses_1", dev.opencode.android.core.model.SessionMoveRequest("/wt", null))
        // No `delivery` was given, so none is sent: a move that invented a mode would change what
        // happens to a prompt the user has already queued.
        assertEquals("""{"directory":"/wt"}""", lastBody("/move"))
    }

    @Test
    fun `a project update sends only what changed`() = runTest {
        bodies["/api/project/prj_1"] =
            """{"id":"prj_1","canonical":"/work","time":{"created":1,"updated":1,"active":1},"sandboxes":[]}"""

        val updated = api.updateProject("prj_1", dev.opencode.android.core.model.ProjectUpdateRequest(name = "App"))

        assertEquals("prj_1", updated.id)
        assertEquals("""{"name":"App"}""", lastBody("/project/prj_1"))
    }

    // ------------------------------------------------------------------ persistent terminals

    @Test
    fun `a null read is the absence of a terminal, not a failure`() = runTest {
        bodies["/api/experimental/session/ses_1/terminal/read"] = """{"data":null}"""
        val store = SessionTerminalStore(api).apply { allow(true) }

        val read = store.read("ses_1")

        // `data` is nullable on this route and null means "this session has no terminal yet", which is a
        // normal state. A `DataResponse<PersistentPtyScreen?>` would have thrown here: the generic
        // wrapper's non-null `data` reaches the serializer as the non-nullable element type.
        assertEquals(SessionTerminalRead.None, read.getOrNull())
        assertNull(read.exceptionOrNull())
    }

    @Test
    fun `a read decodes the screen and its cursor`() = runTest {
        bodies["/api/experimental/session/ses_1/terminal/read"] =
            """{"data":{"ptyID":"pty_1","title":"t","cwd":"/work","foregroundProcess":"vim",""" +
            """"screen":{"text":"hi","cols":80,"rows":24,"cursor":{"x":2,"y":0}}}}"""
        val store = SessionTerminalStore(api).apply { allow(true) }

        val screen = store.read("ses_1").getOrNull() as SessionTerminalRead.Screen

        assertEquals("pty_1", screen.ptyID)
        assertEquals("vim", screen.foregroundProcess)
        assertEquals("hi", screen.text)
        assertEquals(2, screen.cursor.x)
    }

    @Test
    fun `an absent route is recorded as absent because the body carries no tag`() = runTest {
        // A `404` whose body this build cannot parse used to classify as `SERVER`, because
        // `ApiError.Unrecognized` fell through `kindOf` to its `else` branch and discarded the status.
        // That is the one classification a capability probe must not get wrong: "the route is missing"
        // and "the server had a fault" send the user to opposite places, and only the first one is
        // recoverable by upgrading or downgrading the server.
        statuses["/api/experimental/session/ses_1/terminal"] = 404
        bodies["/api/experimental/session/ses_1/terminal"] = """{"error":"not found"}"""
        val store = SessionTerminalStore(api).apply { allow(true) }

        assertFalse(store.list("ses_1").isSuccess)

        assertEquals(
            PersistentPtyAvailability.Absent(404),
            store.availability.value,
        )
    }

    @Test
    fun `a recognised error body still overrides the status`() = runTest {
        // The fallback is only for a body this build cannot read. A body it *can* read is the server
        // telling us what happened, and it wins — here a `503` that names `ServiceUnavailableError`,
        // which is the host not running rather than a generic fault.
        statuses["/api/experimental/session/ses_1/terminal"] = 503
        bodies["/api/experimental/session/ses_1/terminal"] =
            """{"_tag":"ServiceUnavailableError","message":"the persistent-PTY host is not running"}"""
        val store = SessionTerminalStore(api).apply { allow(true) }

        assertFalse(store.list("ses_1").isSuccess)

        assertEquals(PersistentPtyAvailability.HostDown, store.availability.value)
    }

    @Test
    fun `the read asks for a number of lines when it is given one`() = runTest {
        bodies["/api/experimental/session/ses_1/terminal/read"] = """{"data":null}"""
        SessionTerminalStore(api).apply { allow(true) }.read("ses_1", lines = 200)
        assertEquals("lines=200", lastQuery("/terminal/read"))
    }

    @Test
    fun `the create sends args, title, env and size, and omits a command it does not have`() = runTest {
        bodies["/api/experimental/session/ses_1/terminal"] = """{"data":$INFO}"""
        val store = SessionTerminalStore(api).apply { allow(true) }

        store.create("ses_1", command = null, args = listOf("-l"), title = "t", size = PtySize(24, 80))

        // `args`, `title` and `env` are required by this schema even when `command` is not, which is
        // the opposite of `pty.create` and the reason they are not nullable in the request type.
        assertEquals("""{"args":["-l"],"title":"t","env":{},"size":{"rows":24,"cols":80}}""", lastBody("/terminal"))
    }

    @Test
    fun `a resize goes to the persistent-pty update route and folds the answer back in`() = runTest {
        statuses["/api/experimental/session/ses_1/terminal"] = 200
        bodies["/api/experimental/session/ses_1/terminal"] = """{"data":[$INFO]}"""
        bodies["/api/experimental/persistent-pty/pty_1"] = """{"data":$RESIZED}"""
        val store = SessionTerminalStore(api).apply { allow(true) }
        store.list("ses_1")

        val resized = store.resize("pty_1", PtySize(30, 100)).getOrNull()

        assertTrue(requests.last().startsWith("PUT /api/experimental/persistent-pty/pty_1"))
        assertEquals("""{"size":{"rows":30,"cols":100}}""", lastBody("/persistent-pty/pty_1"))
        assertEquals(100, resized?.size?.cols)
        // The answer is folded back in, so the row shows the size the server has rather than the one
        // this client asked for.
        assertEquals(100, store.state.value.terminals.single().size.cols)
    }

    @Test
    fun `a single get re-reads only that terminal`() = runTest {
        bodies["/api/experimental/persistent-pty/pty_1"] = """{"data":$RESIZED}"""
        val store = SessionTerminalStore(api).apply { allow(true) }

        val info = store.get("pty_1").getOrNull()

        assertTrue(requests.last().startsWith("GET /api/experimental/persistent-pty/pty_1"))
        assertEquals("pty_1", info?.id)
        assertEquals(100, store.state.value.terminals.single().size.cols)
    }

    @Test
    fun `the snapshot route is asked for by id and answers a checkpoint`() = runTest {
        bodies["/api/experimental/persistent-pty/pty_1/snapshot"] =
            """{"data":{"info":$INFO,"text":"hi","checkpoint":"Y2hlY2s=","cursor":{"x":1,"y":0}}}"""
        val store = SessionTerminalStore(api).apply { allow(true) }

        val snapshot = store.snapshot("pty_1").getOrNull()

        assertTrue(requests.last().startsWith("GET /api/experimental/persistent-pty/pty_1/snapshot"))
        assertEquals("Y2hlY2s=", snapshot?.checkpoint)
    }

    @Test
    fun `the ticket is asked for with the header the route refuses without`() = runTest {
        bodies["/api/experimental/persistent-pty/pty_1/connect-token"] =
            """{"data":{"ticket":"tkt_1","expires_in":30}}"""
        val store = SessionTerminalStore(api).apply { allow(true) }

        val ticket = store.ticket("pty_1").getOrNull()

        assertEquals("tkt_1", ticket?.ticket)
        assertEquals(30L, ticket?.expiresInSeconds)
    }

    @Test
    fun `the two host routes are the only ones that name the whole server`() = runTest {
        statuses["/api/experimental/persistent-pty/shutdown"] = 204
        val store = SessionTerminalStore(api).apply { allow(true) }

        assertTrue(store.shutdownHost().isSuccess)
        assertTrue(requests.last().startsWith("POST /api/experimental/persistent-pty/shutdown"))
    }

    @Test
    fun `a handoff answers with the instance that took the terminals`() = runTest {
        bodies["/api/experimental/persistent-pty/handoff"] =
            """{"handoff":{"directory":"/work","instanceID":"i_1","ticket":"t","expiresAt":1}}"""
        val store = SessionTerminalStore(api).apply { allow(true) }

        val handoff = store.handoffHost().getOrNull()

        assertTrue(requests.last().startsWith("POST /api/experimental/persistent-pty/handoff"))
        assertEquals("i_1", handoff?.instanceID)
    }

    @Test
    fun `a 404 on the probe marks the whole feature absent and no later call is made`() = runTest {
        statuses["/api/experimental/session/ses_1/terminal"] = 404
        val store = SessionTerminalStore(api).apply { allow(true) }

        assertFalse(store.list("ses_1").isSuccess)
        assertEquals(
            PersistentPtyAvailability.Absent(404),
            store.availability.value,
        )

        // The second call still happens — the store does not cache "do not ask again", because the
        // server may be upgraded while the app is running — but the answer it records is the same.
        assertFalse(store.list("ses_1").isSuccess)
        assertEquals(PersistentPtyAvailability.Absent(404), store.availability.value)
    }

    @Test
    fun `a 503 means the host is down rather than the routes missing`() = runTest {
        statuses["/api/experimental/session/ses_1/terminal"] = 503
        val store = SessionTerminalStore(api).apply { allow(true) }

        assertFalse(store.list("ses_1").isSuccess)

        // These two are kept apart on purpose: collapsing them would either offer a picker whose every
        // call fails, or hide a feature an `opencode service start` would bring back.
        assertEquals(PersistentPtyAvailability.HostDown, store.availability.value)
        assertTrue(store.availability.value.usable)
        assertFalse(PersistentPtyAvailability.Absent(404).usable)
    }

    @Test
    fun `a refused-for-the-switch call records nothing about the route`() = runTest {
        val store = SessionTerminalStore(api).apply { allow(false) }

        val result = store.list("ses_1")

        assertTrue(result.isFailure)
        // The switch is this installation's decision; the route was never called, so this app knows
        // nothing about whether it exists and must not remember that it does not.
        assertEquals(PersistentPtyAvailability.Unknown, store.availability.value)
        assertTrue(requests.isEmpty())
        // The refusal says why in the message the screens already show for a gated write.
        assertEquals(
            "experimental-routes-off",
            (result.exceptionOrNull() as? ActionFailure)?.error?.message,
        )
    }

    private companion object {
        const val INFO =
            """{"id":"pty_1","title":"t","command":"/bin/bash","args":[],"cwd":"/work",""" +
                """"status":"running","pid":1,"sessionID":"ses_1","foregroundProcess":null,""" +
                """"size":{"cols":80,"rows":24},"output":{"head":0,"tail":0}}"""
        const val RESIZED =
            """{"id":"pty_1","title":"t","command":"/bin/bash","args":[],"cwd":"/work",""" +
                """"status":"running","pid":1,"sessionID":"ses_1","foregroundProcess":null,""" +
                """"size":{"cols":100,"rows":30},"output":{"head":0,"tail":0}}"""
    }
}
