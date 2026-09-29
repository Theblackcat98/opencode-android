package dev.opencode.android.feature.integrations

import dev.opencode.android.core.data.action.ActionErrorKind
import dev.opencode.android.core.data.capability.RouteAvailability
import dev.opencode.android.core.data.integrations.ActionFailure
import dev.opencode.android.core.data.integrations.IntegrationSurface
import dev.opencode.android.core.data.integrations.McpConfigForm
import dev.opencode.android.core.data.integrations.McpConfigProblem
import dev.opencode.android.core.data.integrations.McpServerDraft
import dev.opencode.android.core.model.McpProtocol
import dev.opencode.android.core.model.McpServerConfig
import dev.opencode.android.core.model.McpTimeout
import dev.opencode.android.core.model.event.Event
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The store: capability detection, event invalidation, and the form that becomes a wire union.
 *
 * **Driven through a real [IntegrationSurface] over a real HTTP client on a real dispatcher**, which
 * is the arrangement [dev.opencode.android.core.data.sync.SyncedResourceTest] established. The
 * debounce a `*.updated` event goes through is a `delay` on the store's own scope, so testing it on
 * virtual time would be testing a scheduler rather than the invalidation.
 *
 * **Every wait is bounded and reports what the server actually saw.** A test that waits for a
 * count with no timeout is the trap a previous phase fell into, and a failure here that says
 * "expected 2 requests" without saying which two arrived would be the same defect wearing a
 * different hat.
 */
class IntegrationStoreTest : IntegrationsServerTest() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun stopScope() {
        scope.cancel()
    }

    private fun surface() = IntegrationSurface("srv", server.api, scope)

    // ------------------------------------------------------------------------------ capability detection

    @Test
    fun `an experimental route is unprobed until a call says otherwise`() = runBlocking {
        val surface = surface()

        assertEquals(RouteAvailability.Unknown, surface.mcpRuntime.value)
        assertEquals(RouteAvailability.Unknown, surface.wellknownSources.value)
        // Unknown is callable, because the first call is the only way to learn the answer. Hiding the
        // feature on Unknown would mean never probing it at all.
        assertTrue(surface.mcpUsable(allowedBySetting = true))
    }

    @Test
    fun `a 404 from the runtime mcp route hides the runtime writes and only them`() = runBlocking {
        val surface = surface()
        server.answer(
            "POST /api/experimental/mcp/files/connect",
            """{"_tag":"McpServerNotFoundError","server":"files","message":"no such route"}""",
            404,
        )

        val result = surface.connectMcpServer(server.directory, "files")

        assertTrue(result.isFailure)
        assertEquals(
            "the 404 must be read as a missing route, not as a server fault",
            RouteAvailability.Absent(404),
            surface.mcpRuntime.value,
        )
        assertFalse("a 404 must switch the runtime writes off", surface.mcpUsable(allowedBySetting = true))
        // The well-known route is a different feature and is untouched by this call's answer.
        assertEquals(RouteAvailability.Unknown, surface.wellknownSources.value)
        assertTrue(surface.wellknownUsable(allowedBySetting = true))
    }

    @Test
    fun `a 405 hides the route as surely as a 404 does`() = runBlocking {
        val surface = surface()
        server.answer("POST /api/experimental/mcp/files/connect", "", 405)

        surface.connectMcpServer(server.directory, "files")

        // `405` means the server has the route and refuses this method, which is exactly as
        // unavailable for this client as a `404` and must be read off the *status*, because no
        // `_tag` in the body says so.
        assertEquals(RouteAvailability.Absent(405), surface.mcpRuntime.value)
        assertFalse(surface.mcpUsable(allowedBySetting = true))
    }

    @Test
    fun `a 500 does not hide the route, because the route is clearly there`() = runBlocking {
        // The rule is the capability policy's and it is the important one: a transient server fault
        // must not switch a working feature off, or the user's next action disappears for a reason
        // they cannot see.
        val surface = surface()
        server.answer("POST /api/experimental/mcp/files/connect", """{"_tag":"UnknownError","message":"boom"}""", 500)

        val result = surface.connectMcpServer(server.directory, "files")

        assertTrue(result.isFailure)
        assertEquals(RouteAvailability.Unknown, surface.mcpRuntime.value)
        assertTrue(surface.mcpUsable(allowedBySetting = true))
    }

    @Test
    fun `a successful experimental call records the route as present`() = runBlocking {
        val surface = surface()
        server.answer("POST /api/experimental/mcp/files/connect", "", 204)

        surface.connectMcpServer(server.directory, "files")

        assertEquals(RouteAvailability.Present, surface.mcpRuntime.value)
    }

    @Test
    fun `the user switch and the route availability are both required`() = runBlocking {
        val surface = surface()
        server.answer("POST /api/experimental/mcp/files/connect", "", 204)
        surface.connectMcpServer(server.directory, "files")

        // Present route, switch off: nothing offered, because the user has not agreed.
        assertFalse(surface.mcpUsable(allowedBySetting = false))
        assertTrue(surface.mcpUsable(allowedBySetting = true))
    }

    @Test
    fun `a well-known url is refused before any request is made`() = runBlocking {
        val surface = surface()

        val result = surface.addWellknownSource(server.directory, "file:///etc/passwd")

        assertTrue(result.isFailure)
        val error = (result.exceptionOrNull() as ActionFailure).error
        assertEquals(ActionErrorKind.INVALID_REQUEST, error.kind)
        // Nothing was sent: the check is on this side, and a server that fetches the URL is the whole
        // reason the check exists.
        assertEquals("no request may be made", emptyList<String>(), server.requests)
    }

    @Test
    fun `a well-known url the scheme check accepts is sent and records the route`() = runBlocking {
        val surface = surface()
        server.answer("POST /api/experimental/integration/wellknown", "", 204)

        val result = surface.addWellknownSource(server.directory, "https://integrations.example.com/catalog.json")

        assertTrue(result.isSuccess)
        assertEquals(RouteAvailability.Present, surface.wellknownSources.value)
        assertNotNull(server.lastBody("wellknown"))
        assertTrue(server.lastBody("wellknown")!!.contains("catalog.json"))
    }

    // ------------------------------------------------------------------------------ event invalidation

    @Test
    fun `integration updated refetches the catalog`() = runBlocking {
        val surface = surface()
        server.answer("GET /api/integration", Envelopes.list(Fixtures.integration()))
        surface.integrations(server.directory).sync()
        val before = server.countOf("api/integration?")

        surface.apply(event("integration.updated", "{}"))
        awaitCount(before + 1)

        assertEquals(
            "an empty-payload event must cost exactly one refetch",
            before + 1,
            server.countOf("api/integration?"),
        )
    }

    @Test
    fun `a burst of catalog events costs one refetch, not one each`() = runBlocking {
        val surface = surface()
        server.answer("GET /api/integration", Envelopes.list(Fixtures.integration()))
        surface.integrations(server.directory).sync()
        val before = server.countOf("api/integration?")

        // A login writes several events in quick succession; a client that refetched per event would
        // make three requests for one keypress.
        listOf("integration.updated", "credential.updated", "credential.updated").forEach {
            surface.apply(event(it, "{}"))
        }
        awaitCount(before + 1)
        settle()

        assertEquals("three events, one refetch", before + 1, server.countOf("api/integration?"))
    }

    @Test
    fun `credential switched invalidates the integrations and nothing else`() = runBlocking {
        val surface = surface()
        server.answer("GET /api/integration", Envelopes.list(Fixtures.integration()))
        server.answer("GET /api/credential/cred_2", Envelopes.one("{}"))
        surface.integrations(server.directory).sync()
        val before = server.countOf("api/integration?")

        surface.apply(
            event("credential.switched", """{"integrationID":"placeholder-integration","credentialID":"cred_2"}"""),
        )
        awaitCount(before + 1)
        settle()

        assertEquals(before + 1, server.countOf("api/integration?"))
    }

    @Test
    fun `mcp status changed refetches the servers but not the resource catalog`() = runBlocking {
        val surface = surface()
        server.answer("GET /api/mcp", Envelopes.list(Fixtures.mcpConnected))
        server.answer("GET /api/mcp/resource", Envelopes.one(Fixtures.resourceCatalog))
        surface.mcpServers(server.directory).sync()
        surface.mcpResources(server.directory).sync()
        val serversBefore = server.countOf("api/mcp?")
        val resourcesBefore = server.countOf("mcp/resource")

        surface.apply(event("mcp.status.changed", """{"server":"files"}"""))
        awaitCount(serversBefore + 1)
        // Longer than the store's debounce, so a refetch the event *should* have caused would have
        // landed by now. A wait that only checked "no extra request yet" would pass a broken store.
        settle()

        assertEquals(serversBefore + 1, server.countOf("api/mcp?"))
        // The catalog says what a server *publishes*. A connect does not change what the other
        // servers publish, and `mcp.resources.changed` is the event that says so.
        assertEquals(
            "a status change must not refetch the resource catalog",
            resourcesBefore,
            server.countOf("mcp/resource"),
        )
    }

    @Test
    fun `mcp resources changed refetches the catalog`() = runBlocking {
        val surface = surface()
        server.answer("GET /api/mcp/resource", Envelopes.one(Fixtures.resourceCatalog))
        surface.mcpResources(server.directory).sync()
        val before = server.countOf("mcp/resource")

        surface.apply(event("mcp.resources.changed", """{"server":"files"}"""))
        awaitCount(before + 1)

        assertEquals(before + 1, server.countOf("mcp/resource"))
    }

    @Test
    fun `plugin updated refetches the plugin list`() = runBlocking {
        val surface = surface()
        server.answer("GET /api/plugin", Envelopes.list(Fixtures.pluginOutdated))
        surface.plugins(server.directory).sync()
        val before = server.countOf("api/plugin?")

        surface.apply(event("plugin.updated", "{}"))
        awaitCount(before + 1)

        assertEquals(before + 1, server.countOf("api/plugin?"))
    }

    @Test
    fun `provider updated refetches the providers and nothing else`() = runBlocking {
        val surface = surface()
        server.answer("GET /api/provider", Envelopes.list(Fixtures.providerCloud))
        server.answer("GET /api/integration", Envelopes.list(Fixtures.integration()))
        surface.providers(server.directory).sync()
        surface.integrations(server.directory).sync()
        val before = server.countOf("api/provider?")
        val integrationsBefore = server.countOf("api/integration?")

        surface.apply(event("provider.updated", "{}"))
        awaitCount(before + 1)
        settle()

        assertEquals(before + 1, server.countOf("api/provider?"))
        assertEquals(integrationsBefore, server.countOf("api/integration?"))
    }

    @Test
    fun `an event for a location this client never opened costs nothing`() = runBlocking {
        val surface = surface()
        surface.apply(event("integration.updated", "{}"))
        settle()
        assertTrue("no directory is open, so no request may be made", server.requests.isEmpty())
    }

    @Test
    fun `a directory shutdown drops that location's catalogs and leaves the store empty`() = runBlocking {
        val surface = surface()
        server.answer("GET /api/integration", Envelopes.list(Fixtures.integration()))
        surface.integrations(server.directory).sync()
        assertTrue(surface.integrations(server.directory).value?.isNotEmpty() == true)

        surface.dropLocation(server.directory)

        assertNull(surface.integrations(server.directory).value)
    }

    // ------------------------------------------------------------------------------ the add form

    @Test
    fun `a local draft becomes a local config with the command split into arguments`() {
        val draft = McpServerDraft(
            name = "files",
            kind = McpServerDraft.Kind.LOCAL,
            command = "mcp-server-filesystem /work/app",
            cwd = "/work/app",
            environment = "TOKEN=abc\nEMPTY\n=novalue\nPATH=/usr/bin",
            startupTimeout = "30",
            executionTimeout = "120",
            codemode = true,
            protocol = McpProtocol.AUTO,
        )
        val config = McpConfigForm.toConfig(draft) as McpServerConfig.Local

        assertEquals(listOf("mcp-server-filesystem", "/work/app"), config.command)
        assertEquals("/work/app", config.cwd)
        // A line with no `=` or with an empty key is dropped rather than sent as a variable the
        // server cannot use, which would be a `400` for the whole server.
        assertEquals(mapOf("TOKEN" to "abc", "PATH" to "/usr/bin"), config.environment)
        assertEquals(McpTimeout(startup = 30L, execution = 120L), config.timeout)
        assertTrue(config.codemode)
        assertEquals(McpProtocol.AUTO, config.protocol)
    }

    @Test
    fun `a remote draft becomes a remote config and a non-http url is refused`() {
        val good = McpConfigForm.toConfig(
            McpServerDraft(
                name = "github",
                kind = McpServerDraft.Kind.REMOTE,
                url = "https://mcp.example.com",
                headers = "Authorization=Bearer x",
            ),
        ) as McpServerConfig.Remote
        assertEquals("https://mcp.example.com", good.url)
        assertEquals(mapOf("Authorization" to "Bearer x"), good.headers)

        // The server will fetch this URL, so a file:// or an intent:// must never be produced.
        listOf("file:///etc/passwd", "intent://x", "not a url", "").forEach { url ->
            val draft = McpServerDraft(name = "x", kind = McpServerDraft.Kind.REMOTE, url = url)
            assertNull("must not build a config for '$url'", McpConfigForm.toConfig(draft))
            val expected = if (url.isBlank()) McpConfigProblem.URL_REQUIRED else McpConfigProblem.URL_NOT_HTTP
            assertTrue("'$url' must be $expected", McpConfigForm.problems(draft).contains(expected))
        }
    }

    @Test
    fun `an incomplete draft names every field that is wrong`() {
        val problems = McpConfigForm.problems(McpServerDraft())
        assertTrue(problems.contains(McpConfigProblem.NAME_REQUIRED))
        assertTrue(problems.contains(McpConfigProblem.COMMAND_REQUIRED))
        assertFalse(McpConfigForm.isReady(McpServerDraft()))

        val named = McpServerDraft(name = "files", command = "server", startupTimeout = "0")
        assertTrue(McpConfigForm.problems(named).contains(McpConfigProblem.NOT_A_POSITIVE_INTEGER))
        assertFalse("zero is not a positive timeout", McpConfigForm.isReady(named))
        assertTrue(McpConfigForm.isReady(named.copy(startupTimeout = "1")))
    }

    @Test
    fun `a device code in a command's output is found, and the last one wins`() {
        val output = """
            Open https://github.com/login/device
            Enter code: ABCD-EFGH-IJKL
            Waiting for you to sign in
        """.trimIndent()
        // The *code*, not the line: copying "Enter code: XXXX" into another device's field is the
        // one thing that never works.
        assertEquals("ABCD-EFGH-IJKL", McpConfigForm.deviceCodeIn(output))

        // A retry prints a second code; the real one is the last.
        val retried = output + "\nEnter code: MNOP-QRST-UVWX"
        assertEquals("MNOP-QRST-UVWX", McpConfigForm.deviceCodeIn(retried))

        // No code means no copy button, which is honest: the instructions still say what to do.
        assertNull(McpConfigForm.deviceCodeIn("Running the login command"))
        assertNull(McpConfigForm.deviceCodeIn("code: lower-case-is-not-a-device-code"))
    }

    // ------------------------------------------------------------------------------ helpers

    /** An event as the SSE stream delivers it, decoded the way the dispatcher decodes it. */
    private fun event(type: String, data: String): Event =
        Event.decode("""{"id":"evt_1","created":1,"type":"$type","data":$data}""")

    /**
     * Waits for the server to have seen [expected] requests, or fails saying what it did see.
     *
     * A timeout here means the invalidation never reached the wire, which is the defect this file
     * exists to catch, so the message lists the paths rather than only the count.
     */
    private suspend fun awaitCount(expected: Int) {
        try {
            withTimeout(5_000) {
                while (server.countOf("api/") < expected) delay(10)
            }
        } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("expected $expected requests, saw ${server.requests}")
        }
    }

    /**
     * Waits out the store's debounce, so a refetch that *should not* have happened would have.
     *
     * Bounded by construction: the store debounces at 400 ms, and this waits 900 ms. Without it an
     * assertion of "no extra request" would pass a store that refetched everything, because the
     * check would simply run first.
     */
    private suspend fun settle() = delay(900)
}
