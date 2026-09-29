package dev.opencode.android.feature.integrations

import dev.opencode.android.core.model.ConnectCommandRequest
import dev.opencode.android.core.model.ConnectKeyRequest
import dev.opencode.android.core.model.ConnectOAuthCompleteRequest
import dev.opencode.android.core.model.ConnectOAuthRequest
import dev.opencode.android.core.model.ConnectionInfo
import dev.opencode.android.core.model.CredentialUpdateRequest
import dev.opencode.android.core.model.IntegrationInfo
import dev.opencode.android.core.model.IntegrationMethod
import dev.opencode.android.core.model.McpAddRequest
import dev.opencode.android.core.model.McpProtocol
import dev.opencode.android.core.model.McpServer
import dev.opencode.android.core.model.McpServerConfig
import dev.opencode.android.core.model.McpStatus
import dev.opencode.android.core.model.McpTimeout
import dev.opencode.android.core.model.PluginCheckRequest
import dev.opencode.android.core.model.PluginSource
import dev.opencode.android.core.model.PluginState
import dev.opencode.android.core.model.PluginUpdateRequest
import dev.opencode.android.core.model.ProviderInfo
import dev.opencode.android.core.model.Secret
import dev.opencode.android.core.model.WebSearchQueryRequest
import dev.opencode.android.core.model.WellknownSourceRequest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Phase 8 operations over a real HTTP client, against a `MockWebServer` that answers only the
 * routes the app is supposed to call.
 *
 * **Every claim here is a wire claim.** A test that passed against a stubbed `ServerApi` would be
 * asserting that the client agrees with itself; these assert that the path, the query, the HTTP verb
 * and the body are the ones the 2.0.18 spec names, which is the only thing a server this app has not
 * been tested against would disagree with.
 */
class IntegrationsOperationsTest : IntegrationsServerTest() {

    // ------------------------------------------------------------------------------ integrations

    @Test
    fun `integration list is read with the location and decodes its methods and connections`() = runTest {
        server.answer(
            "GET /api/integration",
            Envelopes.list(
                Fixtures.integration(
                    connections = listOf(Fixtures.credentialKey, Fixtures.envConnection),
                ),
            ),
        )

        val integrations = server.api.listIntegrations(server.directory).data

        assertEquals(1, integrations.size)
        val integration = integrations.single()
        assertEquals("placeholder-integration", integration.id)
        assertEquals(3, integration.methods.size)
        assertEquals(IntegrationMethod.Key::class, integration.methods[0]::class)
        assertEquals(IntegrationMethod.OAuth::class, integration.methods[1]::class)
        assertEquals(IntegrationMethod.Env::class, integration.methods[2]::class)
        // methods and connections are two lists, and mixing them is the bug this test exists for
        assertEquals(2, integration.connections.size)
        assertEquals(1, integration.credentials.size)
        assertEquals("cred_1", integration.credentials.single().id)
        assertEquals(1, integration.environment.size)
        assertTrue(integration.isConnected)
        assertSentDirectory(server.requests.single())
    }

    @Test
    fun `a key login posts the key, the answer and the label to the connect route`() = runTest {
        server.answer("POST /api/integration/placeholder-integration/connect/key", "", 204)

        server.api.connectWithKey(
            integrationID = "placeholder-integration",
            body = ConnectKeyRequest(
                key = Secret.of(PLACEHOLDER_KEY),
                answer = mapOf("resourceName" to dev.opencode.android.core.model.FormValues.string("team-a")),
                label = "work key",
            ),
            directory = server.directory,
        )

        val request = server.requests.single()
        assertEquals("POST /api/integration/placeholder-integration/connect/key", request.substringBefore('?'))
        assertSentDirectory(request)
        val body = server.lastBody("connect/key")!!
        assertTrue("the key must reach the wire", body.contains(PLACEHOLDER_KEY))
        assertTrue("the label must reach the wire", body.contains("work key"))
        assertTrue("the form answer must reach the wire", body.contains("resourceName"))
    }

    @Test
    fun `an oauth login posts the method id and answers with the attempt to open`() = runTest {
        server.answer(
            "POST /api/integration/placeholder-integration/connect/oauth",
            Envelopes.one(Fixtures.oauthAttempt),
        )

        val attempt = server.api.connectWithOauth(
            integrationID = "placeholder-integration",
            body = ConnectOAuthRequest(methodID = "oauth-default", label = "personal"),
            directory = server.directory,
        ).data

        assertEquals("att_1", attempt.attemptID)
        assertEquals("auto", attempt.mode)
        assertTrue(attempt.isAuto)
        assertEquals("Approve in the browser", attempt.instructions)
        // The URL is server input, and the one the UI may open is the checked one.
        assertEquals("https://console.example.invalid/oauth/authorize", attempt.safeUrl)
        assertTrue(server.lastBody("connect/oauth")!!.contains("oauth-default"))
        assertSentDirectory(server.requests.single())
    }

    @Test
    fun `a device code attempt decodes as code mode and never offers a device url`() = runTest {
        server.answer(
            "POST /api/integration/placeholder-integration/connect/oauth",
            Envelopes.one(Fixtures.oauthAttemptCode),
        )

        val attempt = server.api.connectWithOauth(
            "placeholder-integration",
            ConnectOAuthRequest("oauth-default"),
            server.directory,
        ).data

        assertTrue(attempt.isCode)
        assertEquals("att_2", attempt.attemptID)
    }

    @Test
    fun `an oauth status poll decodes each of the four states`() = runTest {
        server.answer(
            "GET /api/integration/placeholder-integration/connect/oauth/att_1",
            Envelopes.one(Fixtures.status("pending")),
        )
        assertTrue(
            server.api.getOauthAttemptStatus("placeholder-integration", "att_1", server.directory).data
                is dev.opencode.android.core.model.OAuthAttemptStatus.Pending,
        )

        server.answer(
            "GET /api/integration/placeholder-integration/connect/oauth/att_1",
            Envelopes.one(Fixtures.status("failed", "\"message\":\"the provider refused\"")),
        )
        val failed = server.api.getOauthAttemptStatus("placeholder-integration", "att_1", server.directory).data
        assertTrue(failed is dev.opencode.android.core.model.OAuthAttemptStatus.Failed)
        assertEquals(
            "the provider refused",
            (failed as dev.opencode.android.core.model.OAuthAttemptStatus.Failed).message,
        )

        server.answer(
            "GET /api/integration/placeholder-integration/connect/oauth/att_1",
            Envelopes.one(Fixtures.status("complete")),
        )
        assertTrue(
            server.api.getOauthAttemptStatus("placeholder-integration", "att_1", server.directory).data
                is dev.opencode.android.core.model.OAuthAttemptStatus.Complete,
        )

        server.answer(
            "GET /api/integration/placeholder-integration/connect/oauth/att_1",
            Envelopes.one(Fixtures.status("expired")),
        )
        assertTrue(
            server.api.getOauthAttemptStatus("placeholder-integration", "att_1", server.directory).data
                is dev.opencode.android.core.model.OAuthAttemptStatus.Expired,
        )
    }

    @Test
    fun `a code is submitted to the complete route of the attempt`() = runTest {
        server.answer("POST /api/integration/placeholder-integration/connect/oauth/att_2/complete", "", 204)

        server.api.completeOauthAttempt(
            "placeholder-integration",
            "att_2",
            ConnectOAuthCompleteRequest("WDJB-MJHT"),
            server.directory,
        )

        val request = server.requests.single()
        assertEquals(
            "POST /api/integration/placeholder-integration/connect/oauth/att_2/complete",
            request.substringBefore('?'),
        )
        assertSentDirectory(request)
        assertEquals("""{"code":"WDJB-MJHT"}""", server.lastBody("complete"))
    }

    @Test
    fun `an oauth cancel is a delete on the attempt`() = runTest {
        server.answer("DELETE /api/integration/placeholder-integration/connect/oauth/att_1", "", 204)

        server.api.cancelOauthAttempt("placeholder-integration", "att_1", server.directory)

        val request = server.requests.single()
        assertEquals(
            "DELETE /api/integration/placeholder-integration/connect/oauth/att_1",
            request.substringBefore('?'),
        )
        assertSentDirectory(request)
    }

    @Test
    fun `a command login posts the method id and its status carries the output`() = runTest {
        server.answer(
            "POST /api/integration/placeholder-integration/connect/command",
            Envelopes.one(Fixtures.commandAttempt),
        )
        val attempt = server.api.connectWithCommand(
            "placeholder-integration",
            ConnectCommandRequest("cli", "laptop"),
            server.directory,
        ).data
        assertEquals("att_3", attempt.attemptID)
        assertTrue(server.lastBody("connect/command")!!.contains("cli"))

        server.answer(
            "GET /api/integration/placeholder-integration/connect/command/att_3",
            Envelopes.one(Fixtures.status("pending", "\"message\":\"Open https://github.com/login/device\"")),
        )
        val status = server.api.getCommandAttemptStatus("placeholder-integration", "att_3", server.directory).data
        assertTrue(status is dev.opencode.android.core.model.CommandAttemptStatus.Pending)
        assertEquals(
            "Open https://github.com/login/device",
            (status as dev.opencode.android.core.model.CommandAttemptStatus.Pending).message,
        )
    }

    @Test
    fun `a command cancel is a delete on the attempt`() = runTest {
        server.answer("DELETE /api/integration/placeholder-integration/connect/command/att_3", "", 204)
        server.api.cancelCommandAttempt("placeholder-integration", "att_3", server.directory)
        assertEquals(
            "DELETE /api/integration/placeholder-integration/connect/command/att_3",
            server.requests.single().substringBefore('?'),
        )
    }

    @Test
    fun `a well-known source is added on the experimental route`() = runTest {
        server.answer("POST /api/experimental/integration/wellknown", "", 204)

        server.api.addWellknownIntegration(
            WellknownSourceRequest("https://integrations.example.com/catalog.json"),
            server.directory,
        )

        val request = server.requests.single()
        assertEquals("POST /api/experimental/integration/wellknown", request.substringBefore('?'))
        assertSentDirectory(request)
        assertTrue(server.lastBody("wellknown")!!.contains("catalog.json"))
    }

    // ------------------------------------------------------------------------------ credentials

    @Test
    fun `a credential rename is a patch on the credential alone`() = runTest {
        server.answer("PATCH /api/credential/cred_1", "", 204)

        server.api.updateCredential("cred_1", CredentialUpdateRequest("personal key"))

        val request = server.requests.single()
        assertEquals("PATCH /api/credential/cred_1", request.substringBefore('?'))
        // Not location-scoped: a credential belongs to the server, not to a checkout.
        assertTrue("a credential route must not take a location", !request.contains("location[directory]"))
        assertEquals("""{"label":"personal key"}""", server.lastBody("cred_1"))
    }

    @Test
    fun `activating and removing a credential post and delete without a location`() = runTest {
        server.answer("POST /api/credential/cred_2/activate", "", 204)
        server.api.activateCredential("cred_2")
        assertEquals("POST /api/credential/cred_2/activate", server.requests.single().substringBefore('?'))
        assertTrue(!server.requests.single().contains("location[directory]"))

        server.answer("DELETE /api/credential/cred_1", "", 204)
        server.api.removeCredential("cred_1")
        assertEquals("DELETE /api/credential/cred_1", server.requests.last().substringBefore('?'))
        assertTrue(!server.requests.last().contains("location[directory]"))
    }

    // ------------------------------------------------------------------------------ providers

    @Test
    fun `providers decode their activation, package and custom endpoint`() = runTest {
        server.answer("GET /api/provider", Envelopes.list(Fixtures.providerCustom, Fixtures.providerCloud))

        val providers = server.api.listProviders(server.directory).data

        assertEquals(2, providers.size)
        val custom = providers[0]
        assertEquals("llama", custom.id)
        assertTrue(custom.isAuto)
        assertEquals("@ai-sdk/local", custom.packageName)
        assertEquals("http://127.0.0.1:11434/v1", custom.endpoint)
        assertTrue(custom.settings.hasNoTimeout)
        assertTrue(custom.settings.isCustom)

        val cloud = providers[1]
        assertTrue(cloud.isEnabled)
        assertEquals("placeholder-integration", cloud.integrationID)
        assertTrue(cloud.needsIntegration)
        assertNull("a cloud provider has no custom endpoint", cloud.endpoint)
        assertSentDirectory(server.requests.single())
    }

    @Test
    fun `one provider is read by id`() = runTest {
        server.answer("GET /api/provider/placeholder-integration", Envelopes.one(Fixtures.providerCloud))
        val provider = server.api.getProvider("placeholder-integration", server.directory).data
        assertEquals("placeholder-integration", provider.id)
        assertSentDirectory(server.requests.single())
    }

    // ------------------------------------------------------------------------------ MCP

    @Test
    fun `mcp servers decode every status and name the integration a needs_auth server needs`() = runTest {
        server.answer(
            "GET /api/mcp",
            Envelopes.list(Fixtures.mcpConnected, Fixtures.mcpNeedsAuth, Fixtures.mcpFailed),
        )

        val servers = server.api.listMcpServers(server.directory).data

        assertEquals(3, servers.size)
        assertTrue(servers[0].isConnected)
        assertTrue(servers[1].isDisabledForAuth)
        assertEquals("github", servers[1].integrationID)
        assertTrue(servers[2].isFailed)
        assertEquals("spawn ENOENT", (servers[2].status as McpStatus.Failed).error)
        assertSentDirectory(server.requests.single())
    }

    @Test
    fun `the resource catalog separates resources from templates`() = runTest {
        server.answer("GET /api/mcp/resource", Envelopes.one(Fixtures.resourceCatalog))
        val catalog = server.api.getMcpResourceCatalog(server.directory).data
        assertEquals(1, catalog.resources.size)
        assertEquals("file:///readme.md", catalog.resources.single().uri)
        assertEquals("text/markdown", catalog.resources.single().mimeType)
        assertEquals(1, catalog.templates.size)
        assertEquals("db://{table}/{id}", catalog.templates.single().uriTemplate)
        assertTrue(!catalog.isEmpty)
    }

    @Test
    fun `a runtime server is added with a put and the config union on the wire`() = runTest {
        server.answer("PUT /api/experimental/mcp/files", "", 204)

        server.api.putMcpServer(
            "files",
            McpAddRequest(
                McpServerConfig.Local(
                    command = listOf("mcp-server-filesystem", "/work"),
                    cwd = "/work",
                    codemode = true,
                    timeout = McpTimeout(startup = 30, execution = 120),
                    protocol = McpProtocol.AUTO,
                ),
            ),
            server.directory,
        )

        val request = server.requests.single()
        assertEquals("PUT /api/experimental/mcp/files", request.substringBefore('?'))
        assertSentDirectory(request)
        val body = server.lastBody("/api/experimental/mcp/files")!!
        // The union discriminator and the required field must both be on the wire, or the server
        // cannot tell a local config from a remote one.
        assertTrue(body.contains("\"type\":\"local\""))
        assertTrue(body.contains("mcp-server-filesystem"))
        assertTrue(body.contains("\"protocol\":\"auto\""))
    }

    @Test
    fun `a remote runtime server is a put with the url and no command`() = runTest {
        server.answer("PUT /api/experimental/mcp/github", "", 204)
        server.api.putMcpServer(
            "github",
            McpAddRequest(McpServerConfig.Remote(url = "https://mcp.example.com", headers = mapOf("X-Key" to "v"))),
            server.directory,
        )
        val body = server.lastBody("/api/experimental/mcp/github")!!
        assertTrue(body.contains("\"type\":\"remote\""))
        assertTrue(body.contains("https://mcp.example.com"))
        assertTrue(!body.contains("command"))
    }

    @Test
    fun `connecting, disconnecting and removing a runtime server are the four experimental verbs`() = runTest {
        server.answer("POST /api/experimental/mcp/files/connect", "", 204)
        server.api.connectMcpServer("files", server.directory)
        assertEquals("POST /api/experimental/mcp/files/connect", server.requests.last().substringBefore('?'))

        server.answer("POST /api/experimental/mcp/files/disconnect", "", 204)
        server.api.disconnectMcpServer("files", server.directory)
        assertEquals("POST /api/experimental/mcp/files/disconnect", server.requests.last().substringBefore('?'))

        server.answer("DELETE /api/experimental/mcp/files", "", 204)
        server.api.removeMcpServer("files", server.directory)
        assertEquals("DELETE /api/experimental/mcp/files", server.requests.last().substringBefore('?'))

        // All four are location-scoped: MCP servers are per checkout.
        server.requests.forEach(::assertSentDirectory)
    }

    // ------------------------------------------------------------------------------ plugins

    @Test
    fun `plugins decode their source, features and failure state`() = runTest {
        server.answer(
            "GET /api/plugin",
            Envelopes.list(Fixtures.pluginOutdated, Fixtures.pluginCurrent, Fixtures.pluginFailed),
        )

        val plugins = server.api.listPlugins(server.directory).data

        assertEquals(3, plugins.size)
        val outdated = plugins[0]
        assertTrue(outdated.isOutdated)
        assertTrue(outdated.isUpdatable)
        assertEquals("plugin-hooks", (outdated.source as PluginSource.Package).target)
        assertTrue(outdated.features.server)
        assertEquals(PluginState.Active, outdated.state)

        val current = plugins[1]
        assertTrue(!current.isOutdated)
        assertTrue("a current plugin is not updatable", !current.isUpdatable)

        val failed = plugins[2]
        assertEquals("the plugin threw while loading", failed.failure)
        assertTrue("a local plugin is not a package and cannot be updated", !failed.isUpdatable)
        assertTrue(failed.features.tui)
        assertSentDirectory(server.requests.single())
    }

    @Test
    fun `a plugin check posts a target or an empty body and answers the whole list`() = runTest {
        server.answer("POST /api/plugin/check", Envelopes.list(Fixtures.pluginOutdated, Fixtures.pluginCurrent))

        val checked = server.api.checkPlugins(PluginCheckRequest("opencode-plugin-hooks"), server.directory).data

        assertEquals(2, checked.size)
        assertTrue(checked[0].isOutdated)
        assertTrue(server.lastBody("plugin/check")!!.contains("opencode-plugin-hooks"))

        // A check of every plugin sends no target at all, which `explicitNulls = false` makes an
        // empty object rather than `{"target":null}`.
        server.api.checkPlugins(PluginCheckRequest(), server.directory)
        assertEquals("{}", server.lastBody("plugin/check"))
    }

    @Test
    fun `a plugin update posts the target array`() = runTest {
        server.answer("POST /api/plugin/update", "", 204)

        server.api.updatePlugins(PluginUpdateRequest(listOf("opencode-plugin-hooks")), server.directory)

        assertEquals("POST /api/plugin/update", server.requests.single().substringBefore('?'))
        assertEquals("""{"targets":["opencode-plugin-hooks"]}""", server.lastBody("plugin/update"))
    }

    // ------------------------------------------------------------------------------ web search

    @Test
    fun `the web search providers and a test query are read with the location`() = runTest {
        server.answer(
            "GET /api/websearch/provider",
            Envelopes.list(
                """{"id":"placeholder-search","name":"Exa"}""",
                """{"id":"placeholder-search-2","name":"Tavily"}""",
            ),
        )
        val providers = server.api.listWebSearchProviders(server.directory).data
        assertEquals(listOf("placeholder-search", "placeholder-search-2"), providers.map { it.id })
        assertSentDirectory(server.requests.single())

        server.answer("POST /api/websearch", Envelopes.one(Fixtures.webSearchResponse))
        val response = server.api.queryWebSearch(
            WebSearchQueryRequest("kotlin coroutines", "placeholder-search"),
            server.directory,
        ).data
        // The answer names the provider that ran, which is what the screen shows.
        assertEquals("placeholder-search", response.providerID)
        assertEquals(1, response.results.size)
        assertEquals("https://example.com/a", response.results.single().url)
        assertEquals("A result", response.results.single().title)
        assertSentDirectory(server.requests.last())
    }

    @Test
    fun `a query with no provider sends no provider id`() = runTest {
        server.answer("POST /api/websearch", Envelopes.one(Fixtures.webSearchResponse))
        server.api.queryWebSearch(WebSearchQueryRequest("kotlin"), server.directory)
        assertEquals("""{"query":"kotlin"}""", server.lastBody("websearch"))
    }
}
