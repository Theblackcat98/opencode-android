package dev.opencode.android.core.data.server

import dev.opencode.android.core.data.config.VendoredSchema
import dev.opencode.android.core.model.ConfigEntry
import dev.opencode.android.core.model.event.Event
import dev.opencode.android.core.network.ServerApi
import dev.opencode.android.core.network.ServerApiFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * `config.updated`, applied through the one place events are applied.
 *
 * **This event has no payload, which is the whole difficulty.** Every other `*.updated` event either
 * carries the changed item or carries a location, and so can invalidate one resource or one screen.
 * `config.updated` says only that *a configuration document somewhere* changed — which may name a
 * model, a provider, an MCP server, a permission rule, an agent or a plugin, and which does not say
 * which. A client that maps it to a single resource has guessed; a client that ignores it shows values
 * the server has already stopped using.
 *
 * So the honest response is the blunt one: invalidate every catalog that is *derived* from
 * configuration, and invalidate the configuration documents themselves. The test asserts the scope,
 * because a too-narrow invalidation is the failure that is invisible — the screen would look right
 * until a user edited a file on their desktop and watched the app disagree with the server.
 */
class ConfigUpdatedEventTest {

    private lateinit var server: MockWebServer
    private lateinit var scope: CoroutineScope
    private lateinit var set: ServerDataSet

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val api: ServerApi = ServerApiFactory(okHttpClient = OkHttpClient(), credentialProvider = { null })
            .createForReads(server.url("/").toString())
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = MockResponse.Builder()
                .code(200)
                .addHeader("content-type", "application/json")
                .body(
                    when (request.url.encodedPath) {
                        "/api/config" -> """[{"type":"document","path":"/work/opencode.json","info":{"share":"disabled"}}]"""
                        "/api/agent" -> """{"location":{"directory":"x"},"data":[{"name":"build"}]}"""
                        "/api/provider" -> """{"location":{"directory":"x"},"data":[{"id":"p"}]}"""
                        "/api/mcp" -> """{"location":{"directory":"x"},"data":[{"name":"local"}]}"""
                        else -> """{"location":{"directory":"x"},"data":[]}"""
                    },
                )
                .build()
        }
        set = ServerDataSet(
            serverId = "s1",
            api = api,
            scope = scope,
            cache = FakeReadCacheStore(),
            schema = VendoredSchema.schema(),
        )
    }

    @After
    fun tearDown() {
        if (::scope.isInitialized) scope.cancel()
        server.close()
    }

    @Test
    fun `config updated invalidates the configuration documents the explorer is built from`() = runTest {
        set.configuration.documents("/work").let { assertEquals(1, it.entries.size) }
        assertFalse(set.configuration.entries("/work").isStale)

        set.apply(event("config.updated"))

        assertTrue("a changed document must not be answered from a cache the app knows is stale",
            set.configuration.entries("/work").isStale)
    }

    @Test
    fun `config updated invalidates every derived catalog, and not only the obvious one`() = runTest {
        set.agents("/work").sync()
        set.composerCatalogs.commands("/work").sync()
        set.integrations.providers("/work").sync()
        set.integrations.mcpServers("/work").sync()

        set.apply(event("config.updated"))

        assertTrue("a file can name an agent", set.agents("/work").isStale)
        assertTrue("a file can name a command", set.composerCatalogs.commands("/work").isStale)
        assertTrue("a file can name a provider", set.integrations.providers("/work").isStale)
        assertTrue("a file can add an MCP server", set.integrations.mcpServers("/work").isStale)
    }

    @Test
    fun `a re-read after the event shows the server's new answer, not the old one`() = runTest {
        val before = set.configuration.documents("/work").entries.single()
        assertTrue("the fixture must be a document for this test to mean anything", before is ConfigEntry.Document)
        assertEquals("disabled", (before as ConfigEntry.Document).info.share)

        // The user edits the file on their desktop. The server now reports a different value; only a
        // re-read can tell the difference, which is the reason the event invalidates rather than
        // patches.
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = MockResponse.Builder()
                .code(200)
                .addHeader("content-type", "application/json")
                .body("""[{"type":"document","path":"/work/opencode.json","info":{"share":"global"}}]""")
                .build()
        }

        set.apply(event("config.updated"))
        val after = set.configuration.documents("/work").entries.single()
        assertTrue(after is ConfigEntry.Document)
        assertEquals("global", (after as ConfigEntry.Document).info.share)
    }

    @Test
    fun `a config updated event with no data is not an unknown event`() = runTest {
        // `EventPayload.ConfigUpdated` is a data object, so a `{}` body is the correct decode. If the
        // binding were missing, the envelope would fall through to `EventPayload.Unknown` and this
        // would still be a silent no-op — so the invalidation is the assertion.
        set.agents("/work").sync()
        set.apply(event("config.updated"))
        assertTrue(set.agents("/work").isStale)
    }

    private fun event(type: String): Event = Event.decode("""{"id":"evt_1","type":"$type","created":1,"data":{}}""")
}
