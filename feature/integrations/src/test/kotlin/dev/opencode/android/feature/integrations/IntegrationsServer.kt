package dev.opencode.android.feature.integrations

import dev.opencode.android.core.network.ServerApi
import dev.opencode.android.core.network.ServerApiFactory
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest

/**
 * A real [ServerApi] over a [MockWebServer], with the Phase 8 routes wired.
 *
 * **A fake server, not a fake interface.** Every claim the Phase 8 tests make is about a wire shape —
 * the `location[directory]` every location-scoped route takes, the `{location, data}` wrapper, the
 * `mode`/`time` envelope of an OAuth attempt, a `PUT` for a runtime MCP server, the `targets` array
 * of a plugin update, a `DELETE` for an OAuth cancel — and a mocked interface would agree with
 * whatever the test was written against. Here a request the app would not send cannot pass.
 *
 * **The OAuth server is on this side too.** The `mode=auto` polling loop is driven against routes
 * this class answers, so the loop's complete/failed/expired/cancel transitions are decided by real
 * HTTP answers rather than by a stubbed status object. The protocol on the provider's side of the
 * browser is the one thing a `MockWebServer` cannot stand in for, and the report says so.
 */
class IntegrationsServer(
    val directory: String = "/work/app",
) {
    val server: MockWebServer = MockWebServer().apply { start() }
    val baseUrl: String get() = server.url("/").toString().trimEnd('/')
    val api: ServerApi = ServerApiFactory(OkHttpClient()).createForReads(baseUrl)

    private val recorded = mutableListOf<RecordedRequest>()
    private val bodiesSent = mutableListOf<String>()

    val bodies = mutableMapOf<String, String>()
    val statuses = mutableMapOf<String, Int>()

    /** Requests the app made, in order, as `METHOD path?query`. */
    val requests: List<String>
        get() = synchronized(recorded) {
            recorded.map { "${it.method} ${it.url.encodedPath}?${it.url.query}" }
        }

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                synchronized(recorded) {
                    recorded += request
                    bodiesSent += request.body?.let { String(it.toByteArray(), Charsets.UTF_8) }.orEmpty()
                }
                val key = "${request.method} ${request.url.encodedPath}"
                val body = bodies[key] ?: bodies[request.url.encodedPath] ?: "{}"
                val status = statuses[key] ?: statuses[request.url.encodedPath] ?: 200
                val builder = MockResponse.Builder().code(status)
                if (status == NO_BODY_STATUS) return builder.build()
                return builder.addHeader("Content-Type", "application/json").body(body).build()
            }
        }
    }

    fun answer(key: String, body: String, status: Int = 200) {
        bodies[key] = body
        statuses[key] = status
    }

    /** The number of times a `METHOD path` was called, which is how a poll count is asserted. */
    fun countOf(fragment: String): Int = requests.count { it.contains(fragment) }

    fun lastRequest(fragment: String): String? = requests.lastOrNull { it.contains(fragment) }

    fun lastBody(fragment: String): String? = synchronized(bodiesSent) {
        var index = recorded.size - 1
        while (index >= 0) {
            if (recorded[index].url.encodedPath.contains(fragment)) return bodiesSent[index]
            index--
        }
        null
    }

    fun close() {
        server.close()
    }

    companion object {
        const val NO_BODY_STATUS = 204
    }
}

/** The harness each operation test owns and closes. */
abstract class IntegrationsServerTest {

    private lateinit var harness: IntegrationsServer

    protected val server: IntegrationsServer get() = harness

    @Before
    fun startServer() {
        harness = IntegrationsServer()
    }

    @After
    fun stopServer() {
        harness.close()
    }

    /**
     * Asserts a request carried the location.
     *
     * Every location-scoped route takes `location[directory]`, and a client that forgets it is not
     * answered with an error — it is answered about the *server's* working directory, which is a
     * different checkout and therefore a different set of logins. For this phase that is worse than
     * for a catalog: the answer would be another project's credentials.
     */
    protected fun assertSentDirectory(request: String) {
        assertTrue("no location on $request", request.contains("location[directory]="))
    }
}

/** The JSON envelopes the routes answer with, so a test never hand-writes a wrapper twice. */
internal object Envelopes {
    fun list(vararg data: String) = """{"location":{"directory":"/work/app"},"data":[${data.joinToString(",")}]}"""
    fun one(data: String) = """{"location":{"directory":"/work/app"},"data":$data}"""

    const val ATTEMPT_TIME = """{"created":1000,"expires":600000}"""
}

/** Fixtures shaped exactly like the 2.0.18 spec's examples. */
internal object Fixtures {
    val keyMethod = """{"type":"key","label":"API key","form":[{"key":"resourceName","type":"string","title":"Resource name","required":true}]}"""
    val oauthMethod = """{"type":"oauth","id":"oauth-default","label":"Sign in with Microsoft"}"""
    val commandMethod = """{"type":"command","id":"cli","label":"Sign in with the CLI","command":["gh","auth","login"]}"""
    val envMethod = """{"type":"env","names":["ANTHROPIC_API_KEY"]}"""

    val credentialKey = """{"type":"credential","id":"cred_1","label":"work key","method":"key"}"""
    val credentialOauth = """{"type":"credential","id":"cred_2","label":"personal","method":"oauth"}"""
    val envConnection = """{"type":"env","name":"ANTHROPIC_API_KEY"}"""

    fun integration(
        id: String = "anthropic",
        name: String = "Anthropic",
        methods: List<String> = listOf(keyMethod, oauthMethod, envMethod),
        connections: List<String> = emptyList(),
    ) = """{"id":"$id","name":"$name","methods":[${methods.joinToString(",")}],"connections":[${connections.joinToString(",")}]}"""

    val oauthAttempt = """{"attemptID":"att_1","url":"https://console.anthropic.com/oauth/authorize","instructions":"Approve in the browser","mode":"auto","time":${Envelopes.ATTEMPT_TIME}}"""
    val oauthAttemptCode = """{"attemptID":"att_2","url":"https://example.com/device","instructions":"Enter the code","mode":"code","time":${Envelopes.ATTEMPT_TIME}}"""
    val commandAttempt = """{"attemptID":"att_3","time":${Envelopes.ATTEMPT_TIME}}"""

    fun status(state: String, extra: String = "") =
        """{"status":"$state"${if (extra.isEmpty()) "" else ",$extra"},"time":${Envelopes.ATTEMPT_TIME}}"""

    val mcpConnected = """{"name":"files","status":{"status":"connected"}}"""
    val mcpNeedsAuth = """{"name":"github","status":{"status":"needs_auth","error":"authorization required"},"integrationID":"github"}"""
    val mcpFailed = """{"name":"broken","status":{"status":"failed","error":"spawn ENOENT"}}"""

    val resourceCatalog = """{"resources":[{"server":"files","name":"readme","uri":"file:///readme.md","description":"The readme","mimeType":"text/markdown"}],"templates":[{"server":"files","name":"row","uriTemplate":"db://{table}/{id}"}]}"""

    val providerCustom = """{"id":"llama","name":"Llama","activation":"auto","package":"@ai-sdk/openai-compatible","settings":{"baseURL":"http://127.0.0.1:11434/v1","timeout":false,"transport":"http"}}"""
    val providerCloud = """{"id":"anthropic","name":"Anthropic","activation":"enabled","package":"@ai-sdk/anthropic","integrationID":"anthropic","canonical":"anthropic"}"""

    val pluginOutdated = """{"id":"hooks","source":{"type":"package","target":"opencode-plugin-hooks","version":"1.2.0","outdated":true},"features":{"server":true},"state":{"status":"active"}}"""
    val pluginCurrent = """{"source":{"type":"package","target":"opencode-plugin-lint","version":"2.0.0"},"features":{"server":true,"rpc":true},"state":{"status":"active"}}"""
    val pluginFailed = """{"id":"broken","source":{"type":"local","path":"/work/plugin"},"features":{"tui":true},"state":{"status":"failed","error":"the plugin threw while loading"}}"""

    val webSearchProviders = """[{"id":"exa","name":"Exa"},{"id":"tavily","name":"Tavily"}]"""
    val webSearchResponse = """{"providerID":"exa","results":[{"url":"https://example.com/a","title":"A result","content":"Some content","time":{"published":1700000000000}}]}"""
}

/** A key that exists in this repository's tests and nowhere else. */
internal const val PLACEHOLDER_KEY = "placeholder-api-key-not-a-real-credential"
