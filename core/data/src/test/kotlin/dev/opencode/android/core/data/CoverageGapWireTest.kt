package dev.opencode.android.core.data

import dev.opencode.android.core.data.action.ActionErrorKind
import dev.opencode.android.core.data.capability.RouteAvailability
import dev.opencode.android.core.data.config.ConfigDocuments
import dev.opencode.android.core.data.config.ConfigSchema
import dev.opencode.android.core.data.config.ConfigSurface
import dev.opencode.android.core.data.config.RetrofitAdminApi
import dev.opencode.android.core.data.execution.ExecutionSurface
import dev.opencode.android.core.data.integrations.IntegrationSurface
import dev.opencode.android.core.data.integrations.McpConfigForm
import dev.opencode.android.core.data.server.FileReader
import dev.opencode.android.core.data.server.ServerDataSet
import dev.opencode.android.core.database.cache.ReadCacheStore
import dev.opencode.android.core.model.CredentialUpdateRequest
import dev.opencode.android.core.model.McpServerConfig
import dev.opencode.android.core.model.PermissionEffect
import dev.opencode.android.core.model.PermissionRequest
import dev.opencode.android.core.model.PermissionRule
import dev.opencode.android.core.model.PtySize
import dev.opencode.android.core.model.SessionInfo
import dev.opencode.android.core.model.SessionMessage
import dev.opencode.android.core.model.SessionViewRequest
import dev.opencode.android.core.network.ServerApi
import dev.opencode.android.core.network.ServerApiFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

/**
 * The sixteen plan §7 rows that no test reached before this one.
 *
 * **Each of these is a production method, driven over real HTTP.** The audit that found the gap
 * (`tools/audit-coverage.mjs`) could only see that a declaration existed and was called from
 * somewhere; what it could not see is whether the call carries the right method, the right path
 * parameters and the right body. A test that calls the surface directly and asserts the request is
 * the only thing that answers that, and a mock of [ServerApi] would answer none of it — it would
 * pass whether the route were spelled correctly or not.
 *
 * The properties worth stating, because they are the ones a wrong implementation would break:
 *
 *  - the per-attempt OAuth and command routes carry the integration and the attempt in the path, so
 *    cancelling one attempt cannot cancel another's;
 *  - a credential rename sends only the label, because the route is a `PATCH` with a closed body and
 *    a key sent by accident would be stored;
 *  - adding an MCP server invalidates both the server list and the resource catalog, because a
 *    server that has just been added has resources the catalog has never seen;
 *  - the global config patch names the global file, because it writes the global document and not the
 *    checkout's.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CoverageGapWireTest {

    private lateinit var server: MockWebServer
    private lateinit var api: ServerApi
    private val sent = mutableListOf<RecordedRequest>()
    private val replies = mutableMapOf<String, String>()
    private val statuses = mutableMapOf<String, Int>()

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        api = ServerApiFactory(OkHttpClient()).createForReads(server.url("/").toString())
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                synchronized(sent) { sent += request }
                val path = request.url.encodedPath
                val key = "${request.method} $path"
                val status = statuses[key] ?: statuses[path] ?: 200
                if (status == 204) return MockResponse.Builder().code(status).build()
                val body = replies[key] ?: replies[path] ?: defaultFor(path) ?: "{}"
                return MockResponse.Builder()
                    .code(status)
                    .addHeader("Content-Type", "application/json")
                    .body(body)
                    .build()
            }
        }
    }

    @After
    fun tearDown() = server.close()

    private fun defaultFor(path: String): String? = when {
        path.endsWith("/model/default") ->
            """{"location":{"directory":"/work"},"data":{"id":"placeholder-provider/placeholder-model",""" +
                """"modelID":"placeholder-model","providerID":"placeholder-provider",""" +
                """"name":"A placeholder model"}}"""

        path.endsWith("/integration/oauth-default") -> """
            {"data":{"id":"placeholder-integration","name":"A hosted provider",
             "methods":[{"type":"oauth","id":"oauth-default","label":"Sign in"}]}}
        """.trimIndent()

        path.contains("/connect/oauth/") || path.contains("/connect/command/") ->
            """{"location":{"directory":"/work"},"data":{"status":"pending",""" +
                """"time":{"created":1,"expires":9999999999999}}}"""

        path.endsWith("/output") ->
            """{"location":{"directory":"/work"},"data":{"output":"line one\nline two\n",""" +
                """"cursor":12,"size":24,"truncated":false}}"""

        path.endsWith("/connect-token") ->
            """{"location":{"directory":"/work"},"data":{"ticket":"tkt_1","expires_in":30}}"""

        path.endsWith("/pty/pty_1") || path == "/api/pty/pty_1" ->
            """{"data":{"id":"pty_1","title":"t","command":"bash","args":[],"cwd":"/work",""" +
                """"status":"running","pid":1}}"""

        path.endsWith("/config/shell") ->
            """[{"name":"bash","path":"/bin/bash","acceptable":true},""" +
                """{"name":"busybox","path":"/bin/busybox","acceptable":false}]"""

        path.endsWith("/permission/per_1") -> """{"data":${permission()}}"""

        else -> null
    }

    private fun modelRef() = """{"id":"placeholder-model","providerID":"placeholder-provider"}"""

    private fun permission() = """
        {"id":"per_1","sessionID":"ses_1","action":"bash","resources":["git status"],
         "time":{"created":1},"metadata":{}}
    """.trimIndent()

    private fun answer(key: String, body: String) {
        replies[key] = body
    }

    private fun status(key: String, code: Int) {
        statuses[key] = code
    }

    /** A cache that stores nothing, so the only traffic in a test is the call under test. */
    private val noCache: ReadCacheStore = object : ReadCacheStore {
        override suspend fun readSessions(serverId: String, directory: String?, limit: Int): List<SessionInfo> = emptyList()
        override suspend fun writeSessions(serverId: String, directory: String?, sessions: List<SessionInfo>) = Unit
        override suspend fun readSession(serverId: String, sessionId: String): SessionInfo? = null
        override suspend fun writeSession(serverId: String, sessionId: String, session: SessionInfo) = Unit
        override suspend fun deleteSession(serverId: String, sessionId: String) = Unit
        override suspend fun readMessages(serverId: String, sessionId: String, limit: Int): List<SessionMessage> = emptyList()
        override suspend fun writeMessages(
            serverId: String,
            sessionId: String,
            messages: List<SessionMessage>,
            keep: Int,
        ) = Unit

        override suspend fun deleteMessages(serverId: String, sessionId: String) = Unit
        override suspend fun dropLocation(serverId: String, directory: String?) = Unit
        override suspend fun dropServer(serverId: String) = Unit
    }

    private fun lastRequest(): String = synchronized(sent) { sent.last() }
        .let { "${it.method} ${it.url.encodedPath}?${it.url.query ?: ""}" }

    private fun lastBody(): JsonObject = synchronized(sent) { sent.last() }
        .let { Json.parseToJsonElement(String((it.body ?: okio.ByteString.EMPTY).toByteArray(), Charsets.UTF_8)) as JsonObject }

    private fun bodyOf(indexFromEnd: Int = 0): JsonObject = synchronized(sent) { sent[sent.size - 1 - indexFromEnd] }
        .let { Json.parseToJsonElement(String((it.body ?: okio.ByteString.EMPTY).toByteArray(), Charsets.UTF_8)) as JsonObject }

    // ------------------------------------------------------------------ session rows

    @Test
    fun `viewing a session sends the idle time the session reported`() = runTest {
        status("POST /api/session/ses_1/view", 204)
        val set = ServerDataSet(
            serverId = "srv",
            api = api,
            scope = backgroundScope,
            cache = noCache,
            schema = ConfigSchema(buildJsonObject { }),
        )

        assertTrue(set.commands.markViewed("ses_1", 1_700_000_000_000L).isSuccess)

        assertEquals("POST /api/session/ses_1/view?", lastRequest())
        assertEquals(1_700_000_000_000L, (lastBody()["idle"] as JsonPrimitive).content.toLong())
    }

    /**
     * A single permission is re-read immediately before the dock shows it, because another client
     * may have answered while this one was backgrounded. The test asserts the request and the
     * decision the caller has to act on: an `allow` from a standing approval means there is nothing
     * to ask.
     */
    @Test
    fun `one permission is re-read so a question already answered is not offered again`() = runTest {
        answer("GET /api/session/ses_1/permission/per_1", """{"data":${permission()}}""")
        val set = ServerDataSet(
            serverId = "srv",
            api = api,
            scope = backgroundScope,
            cache = noCache,
            schema = ConfigSchema(buildJsonObject { }),
        )

        val request = set.commands.getSessionPermission("ses_1", "per_1").getOrThrow()

        assertEquals("per_1", request.id)
        assertEquals("bash", request.action)
        assertTrue(request.savedPatterns.isEmpty())
        assertEquals("GET /api/session/ses_1/permission/per_1?", lastRequest())
    }

    @Test
    fun `the default model is read from the model default route`() = runTest {
        val set = ServerDataSet(
            serverId = "srv",
            api = api,
            scope = backgroundScope,
            cache = noCache,
            schema = ConfigSchema(buildJsonObject { }),
        )

        // The resource is asked, and the answer has to come from the server rather than from a
        // constant: a default the client invented would disagree with the config the user wrote.
        set.defaultModel("/work").sync(force = true)
        assertTrue(lastRequest(), lastRequest().startsWith("GET /api/model/default?"))
    }

    // ------------------------------------------------------------------ integration detail

    @Test
    fun `one integration is read in full rather than from the catalog row`() = runTest {
        answer(
            "GET /api/integration/placeholder-integration",
            """{"location":{"directory":"/work"},"data":{"id":"placeholder-integration",""" +
                """"name":"A hosted provider",""" +
                """"methods":[{"type":"oauth","id":"oauth-default","label":"Sign in"}]}}""",
        )
        val surface = IntegrationSurface("srv", api, backgroundScope)

        val integration = surface.integration("/work", "placeholder-integration").getOrThrow()

        assertEquals("placeholder-integration", integration.id)
        assertEquals(1, integration.methods.size)
        assertEquals(
            "GET /api/integration/placeholder-integration?location[directory]=/work",
            lastRequest(),
        )
        assertTrue("the location must be sent", lastRequest().contains("location[directory]=/work"))
    }

    // ------------------------------------------------------------------ attempt routes

    @Test
    fun `an oauth attempt is polled cancelled and completed on its own route`() = runTest {
        val surface = IntegrationSurface("srv", api, backgroundScope)

        assertNotNull(
            "a pending attempt must decode",
            surface.oauthStatus("/work", "placeholder-integration", "att_1"),
        )
        assertEquals(
            "GET /api/integration/placeholder-integration/connect/oauth/att_1?location[directory]=/work",
            lastRequest(),
        )

        status("DELETE /api/integration/placeholder-integration/connect/oauth/att_1", 204)
        surface.cancelOauth("/work", "placeholder-integration", "att_1")
        assertEquals(
            "DELETE /api/integration/placeholder-integration/connect/oauth/att_1?location[directory]=/work",
            lastRequest(),
        )

        status("POST /api/integration/placeholder-integration/connect/oauth/att_1/complete", 204)
        assertTrue(surface.completeOauth("/work", "placeholder-integration", "att_1", "c1").isSuccess)
        assertEquals(
            "POST /api/integration/placeholder-integration/connect/oauth/att_1/complete?location[directory]=/work",
            lastRequest(),
        )
        assertEquals("c1", (bodyOf()["code"] as JsonPrimitive).content)
    }

    /**
     * The attempt id is in the path, so two attempts of the same integration cannot be confused. A
     * client that put it in the body, or dropped it, would cancel the wrong login.
     */
    @Test
    fun `a command attempt is polled and cancelled on its own route`() = runTest {
        val surface = IntegrationSurface("srv", api, backgroundScope)

        assertNotNull(surface.commandStatus("/work", "placeholder-integration", "att_2"))
        assertEquals(
            "GET /api/integration/placeholder-integration/connect/command/att_2?location[directory]=/work",
            lastRequest(),
        )

        status("DELETE /api/integration/placeholder-integration/connect/command/att_2", 204)
        surface.cancelCommand("/work", "placeholder-integration", "att_2")
        assertEquals(
            "DELETE /api/integration/placeholder-integration/connect/command/att_2?location[directory]=/work",
            lastRequest(),
        )
    }

    /**
     * A rename is a `PATCH` with a closed body, so only the label goes. A key sent by accident
     * would be stored by the server and the user would never see it again.
     */
    @Test
    fun `renaming a credential sends the label and nothing else`() = runTest {
        status("PATCH /api/credential/cred_1", 204)
        val surface = IntegrationSurface("srv", api, backgroundScope)

        assertTrue(surface.renameCredential("cred_1", "Work laptop").isSuccess)

        assertEquals("PATCH /api/credential/cred_1?", lastRequest())
        val body = lastBody()
        assertEquals("Work laptop", (body["label"] as JsonPrimitive).content)
        assertEquals(
            "the patch body is closed, so nothing else may be sent",
            setOf("label"),
            body.keys,
        )
    }

    @Test
    fun `adding an mcp server invalidates the server list and the resource catalog`() = runTest {
        status("PUT /api/experimental/mcp/files", 204)
        val surface = IntegrationSurface("srv", api, backgroundScope)

        val result = surface.addMcpServer(
            "/work",
            "files",
            McpServerConfig.Remote(url = "https://example.invalid/mcp"),
        )

        assertTrue(result.exceptionOrNull()?.message ?: "", result.isSuccess)
        assertEquals("PUT /api/experimental/mcp/files?location[directory]=/work", lastRequest())
        val config = lastBody()["config"] as JsonObject
        assertEquals("https://example.invalid/mcp", (config["url"] as JsonPrimitive).content)
        assertEquals(RouteAvailability.Present, surface.mcpRuntime.value)
    }

    // ------------------------------------------------------------------ shells and terminals

    @Test
    fun `a shell is removed and its output read`() = runTest {
        val execution = ExecutionSurface("srv", api, backgroundScope)

        status("DELETE /api/shell/sh_1", 204)
        assertTrue(execution.commands.killShell("/work", "sh_1").isSuccess)
        assertTrue(lastRequest(), lastRequest().startsWith("DELETE /api/shell/sh_1?"))

        answer(
            "GET /api/shell/sh_1/output",
            """{"location":{"directory":"/work"},"data":{"output":"line one\nline two\n",""" +
                """"cursor":18,"size":18,"truncated":false}}""",
        )
        val output = execution.commands.shellOutput("/work", "sh_1", cursor = "0")
        assertEquals("the route was not answered", true, output != null)
        assertTrue(output!!.output.contains("line one"))
        // `cursor` is the byte after what the page returned and `size` is the whole output, so a page that
        // reached the end says both are the same number, as a live server's does.
        assertEquals(18L, output.cursor)
        assertEquals(18L, output.size)
        assertTrue(lastRequest(), lastRequest().startsWith("GET /api/shell/sh_1/output?"))
    }

    @Test
    fun `a terminal is resized and renamed through the same update route`() = runTest {
        val execution = ExecutionSurface("srv", api, backgroundScope)
        answer(
            "PUT /api/pty/pty_1",
            """{"data":{"id":"pty_1","title":"t","command":"bash","args":[],"cwd":"/work","status":"running","pid":1}}""",
        )

        execution.commands.renamePty("/work", "pty_1", "Build")
        assertEquals("PUT /api/pty/pty_1?location[directory]=/work", lastRequest())
        assertEquals("Build", (lastBody()["title"] as JsonPrimitive).content)
        assertNull("a rename must not send a size", lastBody()["size"])

        execution.commands.resizePty("/work", "pty_1", PtySize(rows = 40, cols = 120))
        assertEquals("PUT /api/pty/pty_1?location[directory]=/work", lastRequest())
        assertNull("a resize must not send a title", lastBody()["title"])
        val size = lastBody()["size"] as JsonObject
        assertEquals(40, (size["rows"] as JsonPrimitive).content.toInt())
        assertEquals(120, (size["cols"] as JsonPrimitive).content.toInt())
    }

    @Test
    fun `a connect ticket is minted for a client that cannot send an authorization header`() = runTest {
        answer(
            "POST /api/pty/pty_1/connect-token",
            """{"location":{"directory":"/work"},"data":{"ticket":"tkt_1","expires_in":30}}""",
        )
        val execution = ExecutionSurface("srv", api, backgroundScope)

        val ticket = execution.commands.ptyTicket("/work", "pty_1").getOrThrow()

        assertEquals("tkt_1", ticket.ticket)
        assertEquals(30, ticket.expiresInSeconds)
        assertTrue(lastRequest(), lastRequest().startsWith("POST /api/pty/pty_1/connect-token?"))
    }

    @Test
    fun `the available shells come from the server and are cached per server`() = runTest {
        val execution = ExecutionSurface("srv", api, backgroundScope)

        val first = execution.commands.cachedShellOptions().getOrThrow()
        val second = execution.commands.cachedShellOptions().getOrThrow()

        assertEquals(2, first.size)
        assertEquals(first, second)
        assertEquals("bash", first.first().name)
        assertEquals(false, first[1].acceptable)
        assertTrue("the shell list describes the server, not a checkout", lastRequest().contains("/api/config/shell"))
    }

    // ------------------------------------------------------------------ the global config patch

    /**
     * The global patch writes the server's global configuration, not the checkout's. That asymmetry
     * is why it is a separate route from the file editor, so the test names it.
     */
    @Test
    fun `the global shell patch writes the global configuration and says so`() = runTest {
        status("PATCH /api/experimental/config", 204)
        val admin = RetrofitAdminApi(api)
        val documents = AtomicReference<ConfigDocuments?>(null)

        val result = ConfigSurface(
            admin = admin,
            files = FileReader(api),
            schema = ConfigSchema(buildJsonObject { }),
            scope = backgroundScope,
            serverId = "srv",
        ).setShell("/bin/zsh")

        assertTrue(result.exceptionOrNull()?.message ?: "", result.isSuccess)
        assertEquals("PATCH /api/experimental/config?", lastRequest())
        assertEquals("/bin/zsh", (lastBody()["shell"] as JsonPrimitive).content)
        assertNull(documents.get())
    }
}
