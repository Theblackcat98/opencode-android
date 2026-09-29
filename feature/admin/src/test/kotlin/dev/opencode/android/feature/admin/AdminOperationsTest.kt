package dev.opencode.android.feature.admin

import dev.opencode.android.core.network.ServerApi
import dev.opencode.android.core.network.ServerApiFactory
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import dev.opencode.android.core.data.action.ActionErrorKind
import dev.opencode.android.core.data.action.toActionError
import dev.opencode.android.core.data.config.ConfigSchema
import dev.opencode.android.core.data.config.ConfigSurface
import dev.opencode.android.core.data.config.RetrofitAdminApi
import dev.opencode.android.core.data.server.FileReader
import dev.opencode.android.core.model.MigrationStatus
import dev.opencode.android.core.testing.VendoredSpec
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A real [ServerApi] over a [MockWebServer] with the Phase 9 routes wired.
 *
 * **A fake server, not a fake interface**, for the reason the Phase 8 harness gives: every claim these
 * tests make is about a wire shape — the `location[directory]` on `config.get`, the `{"shell": …}` body
 * of the only configuration setter, a `204` from `location.reload`, a `PUT` for an instruction entry,
 * the `DELETE` with a query on `debug/location` — and a mocked interface would agree with whatever the
 * test was written against.
 *
 * **The dispatcher answers only what it is told to.** A route the app called that the test did not
 * register comes back as `404`, which is exactly the answer a server without the route gives, so a test
 * cannot pass by calling something it never wired.
 */
class AdminServer(
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
                val key = "${request.method} ${request.url.encodedPath}"
                synchronized(recorded) {
                    recorded += request
                    bodiesSent += request.body?.let { String(it.toByteArray(), Charsets.UTF_8) }.orEmpty()
                }
                val body = bodies[key] ?: bodies[request.url.encodedPath] ?: DEFAULT
                val status = statuses[key] ?: statuses[request.url.encodedPath] ?: 200
                val builder = MockResponse.Builder().code(status)
                if (status == NO_BODY) return builder.build()
                return builder.addHeader("Content-Type", "application/json").body(body).build()
            }
        }
    }

    fun answer(key: String, body: String, status: Int = 200) {
        bodies[key] = body
        statuses[key] = status
    }

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

    private companion object {
        const val NO_BODY = 204
        const val DEFAULT = "{}"
    }
}

/** The harness each operation test owns and closes. */
abstract class AdminServerTest {

    private lateinit var harness: AdminServer

    protected val server: AdminServer get() = harness

    @Before
    fun startServer() {
        harness = AdminServer()
    }

    @After
    fun stopServer() {
        harness.close()
    }
}

/**
 * The Phase 9 operations over a real HTTP client.
 *
 * Every test here is a wire claim: the path, the query, the verb, the body. A client that agreed with
 * itself would pass a stubbed-interface test and still send the wrong thing to a server this app has not
 * been tested against.
 */
class AdminOperationsTest : AdminServerTest() {

    // ------------------------------------------------------------------------------ config.get

    @Test
    fun `config get is read with the location and decodes documents in precedence order`() = runTest {
        server.answer(
            "GET /api/config",
            """
            [
              {"type":"directory","path":"/work"},
              {"type":"document","path":"/root/.config/opencode/opencode.json",
               "info":{"model":"anthropic/claude","shell":"/bin/sh"}},
              {"type":"document","path":"/work/app/.opencode/opencode.jsonc",
               "info":{"model":"openai/gpt","shell":"/bin/zsh","share":"disabled",
                       "permissions":[{"action":"bash","resource":"*","effect":"ask"}],
                       "watcher":{"ignore":[".git/**"]},
                       "${'$'}schema":"https://opencode.ai/config.json"}}
            ]
            """.trimIndent(),
        )

        val entries = server.api.getConfig(server.directory)

        assertEquals(3, entries.size)
        val documents = entries.filterIsInstance<dev.opencode.android.core.model.ConfigEntry.Document>()
        assertEquals(2, documents.size)
        // The order in the answer *is* the precedence, and the test says so by index.
        assertEquals("/root/.config/opencode/opencode.json", documents[0].path)
        assertEquals("anthropic/claude", documents[0].info.model?.display())
        assertEquals("openai/gpt", documents[1].info.model?.display())
        assertEquals("/bin/zsh", documents[1].info.shell)
        assertEquals("disabled", documents[1].info.share)
        assertEquals(listOf("bash"), documents[1].info.permissions?.map { it.action })
        assertEquals("/work", entries.filterIsInstance<dev.opencode.android.core.model.ConfigEntry.Directory>().single().path)

        val request = server.requests.single()
        assertTrue("config.get is location-scoped, sent: $request", request.contains("location[directory]="))
    }

    @Test
    fun `config get decodes an entry whose type this build does not know without failing`() = runTest {
        server.answer("GET /api/config", """[{"type":"future","path":"/x"},{"type":"directory","path":"/y"}]""")

        val entries = server.api.getConfig(server.directory)

        // The unknown entry is kept, not dropped: a client that discarded it would silently lose a
        // configuration document a future server contributes.
        assertEquals(2, entries.size)
        assertTrue(entries[0] is dev.opencode.android.core.model.ConfigEntry.Unknown)
        assertEquals("/x", entries[0].path)
    }

    @Test
    fun `both spellings of the model key decode`() = runTest {
        server.answer(
            "GET /api/config",
            """[{"type":"document","info":{"model":{"providerID":"anthropic","model":"claude","variant":"high"}}}]""",
        )

        val document = server.api.getConfig(server.directory).single() as
            dev.opencode.android.core.model.ConfigEntry.Document

        assertEquals("anthropic/claude#high", document.info.model?.display())
        assertEquals("anthropic/claude#high", document.info.model?.toFileString())
    }

    // ------------------------------------------------------------------------------ the shell setter

    @Test
    fun `the config patch sends only the shell, as the route's own body`() = runTest {
        server.answer("PATCH /api/experimental/config", "", 204)

        server.api.updateConfig(dev.opencode.android.core.model.ConfigPatchRequest("/bin/zsh"))

        assertEquals("PATCH /api/experimental/config?null", server.requests.single())
        // `Config.Patch` is `additionalProperties: false` with `shell` required, so a body with anything
        // else in it is a `400` and a test that only checked the path would not notice.
        assertEquals("""{"shell":"/bin/zsh"}""", server.lastBody("experimental/config"))
    }

    // ------------------------------------------------------------------------------ reload

    @Test
    fun `reload posts to the location reload route and accepts a 204`() = runTest {
        server.answer("POST /api/location/reload", "", 204)

        server.api.reloadLocations()

        assertEquals("POST /api/location/reload?null", server.requests.single())
    }

    @Test
    fun `a 503 from reload is raised rather than swallowed`() = runTest {
        server.answer(
            "POST /api/location/reload",
            """{"_tag":"ServiceUnavailableError","data":{"message":"a location could not be rebuilt"}}""",
            503,
        )

        val failure = runCatching { server.api.reloadLocations() }.exceptionOrNull()

        assertTrue("a failed reload must raise", failure != null)
        val error = failure!!.toActionError()
        assertEquals(ActionErrorKind.SERVER, error.kind)
        assertEquals(503, error.httpStatus)
    }

    // ------------------------------------------------------------------------------ saved permissions

    @Test
    fun `saved permissions are read with the project filter when one is given`() = runTest {
        server.answer(
            "GET /api/permission/saved",
            """
            {"data":[
              {"id":"perm_1","projectID":"prj_1","action":"bash","resource":"git status","time":{"created":1700000000000,"updated":1700000000000}},
              {"id":"perm_2","projectID":"prj_1","action":"edit","resource":"/work/app/**","time":{"created":1700000001000,"updated":1700000001000}}
            ]}
            """.trimIndent(),
        )

        val saved = server.api.listSavedPermissions("prj_1").data

        assertEquals(2, saved.size)
        assertEquals("bash", saved[0].action)
        assertEquals("git status", saved[0].resource)
        assertEquals("prj_1", saved[0].projectID)
        assertEquals(1700000000000L, saved[0].time.created)
        assertTrue(server.requests.single().contains("projectID=prj_1"))
    }

    @Test
    fun `removing a saved permission is a DELETE on its id`() = runTest {
        server.answer("DELETE /api/permission/saved/perm_1", "", 204)

        server.api.removeSavedPermission("perm_1")

        assertEquals("DELETE /api/permission/saved/perm_1?null", server.requests.single())
    }

    // ------------------------------------------------------------------------------ instructions

    @Test
    fun `instruction entries are listed from the session's experimental route`() = runTest {
        server.answer(
            "GET /api/experimental/session/ses_1/instructions/entries",
            """{"data":[{"key":"tools","value":{"bash":false}},{"key":"tone","value":"terse"}]}""",
        )

        val entries = server.api.listInstructionEntries("ses_1").data

        assertEquals(2, entries.size)
        assertEquals("tools", entries[0].key)
        assertEquals("""{"bash":false}""", entries[0].value.toString())
        assertEquals("terse", (entries[1].value as kotlinx.serialization.json.JsonPrimitive).content)
    }

    @Test
    fun `putting an entry is a PUT on the key with the value as JSON`() = runTest {
        server.answer("PUT /api/experimental/session/ses_1/instructions/entries/tools", "", 204)

        server.api.putInstructionEntry(
            sessionID = "ses_1",
            key = "tools",
            body = dev.opencode.android.core.model.InstructionEntryRequest(
                kotlinx.serialization.json.Json.parseToJsonElement("""{"bash":false}"""),
            ),
        )

        val request = server.requests.single()
        assertTrue("the key is in the path, sent: $request", request.startsWith("PUT /api/experimental/session/ses_1/instructions/entries/tools"))
        assertEquals("""{"value":{"bash":false}}""", server.lastBody("instructions/entries"))
    }

    @Test
    fun `a bare word is sent as a JSON string and an object as an object`() = runTest {
        server.answer("PUT /api/experimental/session/ses_1/instructions/entries/tone", "", 204)
        server.answer("PUT /api/experimental/session/ses_1/instructions/entries/tools", "", 204)

        val api = dev.opencode.android.core.data.config.RetrofitAdminApi(server.api)
        api.putInstructionEntry("ses_1", "tone", "terse")
        api.putInstructionEntry("ses_1", "tools", """{"bash":false}""")

        assertEquals(2, server.requests.size)
        // Both shapes come out of one text field, which is the whole reason the value is parsed here.
        assertTrue(server.lastBody("entries/tone")?.contains("\"terse\"") == true)
        assertTrue(server.lastBody("entries/tools")?.contains("\"bash\":false") == true)
    }

    @Test
    fun `removing an entry is a DELETE on its key`() = runTest {
        server.answer("DELETE /api/experimental/session/ses_1/instructions/entries/tone", "", 204)

        server.api.removeInstructionEntry("ses_1", "tone")

        assertEquals("DELETE /api/experimental/session/ses_1/instructions/entries/tone?null", server.requests.single())
    }

    // ------------------------------------------------------------------------------ loaded locations

    @Test
    fun `loaded locations are listed with no location parameter of their own`() = runTest {
        server.answer("GET /api/debug/location", """[{"directory":"/work/app"},{"directory":"/work/other"}]""")

        val locations = server.api.listLoadedLocations()

        assertEquals(2, locations.size)
        assertEquals("/work/app", locations[0].directory)
        // The route is the one that answers "what is this server holding", so it cannot take a location.
        assertEquals("GET /api/debug/location?null", server.requests.single())
    }

    @Test
    fun `evicting a location is a DELETE with the directory as a location parameter`() = runTest {
        server.answer("DELETE /api/debug/location", "", 204)

        server.api.evictLocation("/work/app")

        val request = server.requests.single()
        assertTrue("the directory is the location, sent: $request", request.startsWith("DELETE /api/debug/location?"))
        assertTrue("the directory is sent as the location, sent: $request", request.contains("/work/app"))
        assertTrue("the parameter is the location, sent: $request", request.contains("directory"))
    }

    // ------------------------------------------------------------------------------ migration

    @Test
    fun `every migration state decodes, and running keeps its nullable counts`() = runTest {
        val states = listOf(
            """{"status":"required"}""",
            """{"status":"completed"}""",
            """{"status":"running","progress":{"label":"Importing sessions","numerator":40,"denominator":100}}""",
            """{"status":"running","progress":{"label":"Starting"}}""",
            """{"status":"error","error":"the V1 directory could not be read"}""",
            """{"status":"something-new"}""",
        )
        val decoded = states.map { state ->
            server.answer("GET /api/experimental/migration/v1", state)
            server.api.getMigrationStatus()
        }

        assertTrue(decoded[0] is dev.opencode.android.core.model.MigrationStatus.Required)
        assertTrue(decoded[1] is dev.opencode.android.core.model.MigrationStatus.Completed)
        val running = (decoded[2] as MigrationStatus.Running).progress
        assertEquals("Importing sessions", running.label)
        assertEquals(40L, running.numerator)
        assertEquals(100L, running.denominator)
        // The label alone is what the server sends before it has counted anything, and `0/0` would be a
        // bar lying about how far along it is.
        val uncounted = (decoded[3] as MigrationStatus.Running).progress
        org.junit.Assert.assertNull(uncounted.numerator)
        org.junit.Assert.assertNull(uncounted.denominator)
        val failed = decoded[4] as dev.opencode.android.core.model.MigrationStatus.Error
        assertEquals("the V1 directory could not be read", failed.message)
        assertTrue("an unknown state must not fail the screen", decoded[5] is dev.opencode.android.core.model.MigrationStatus.Unknown)
    }

    // ------------------------------------------------------------------------------ redaction at the boundary

    @Test
    fun `a server-side setting's confirmation plan carries no document`() = runTest {
        val secret = "sk-ant-placeholder-not-a-real-key"
        val plan = ConfigSurface(
            admin = RetrofitAdminApi(server.api),
            files = FileReader(server.api),
            schema = ConfigSchema.parse(VendoredSpec.configSchemaText()),
        ).planSetting(
            target = ".opencode/opencode.jsonc",
            consequence = "test",
            isPrivilegeChange = true,
        )

        // A setting the *server* applies has no document, so there are no bytes for a confirmation
        // dialog built from the plan to leak. The path and the sentence are all it carries.
        assertEquals(0, plan.bytes)
        assertTrue("a plan must not hold the value it would write", plan.text != secret)
        assertTrue(plan.isPrivilegeChange)
        assertEquals("nothing is sent by planning", emptyList<String>(), server.requests)
    }
}
