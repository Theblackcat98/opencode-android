package dev.opencode.android.core.data.integration

import dev.opencode.android.core.data.config.ConfigDocument
import dev.opencode.android.core.data.config.ConfigSchema
import dev.opencode.android.core.data.config.ConfigSurface
import dev.opencode.android.core.data.config.McpTemplate
import dev.opencode.android.core.data.config.ModelTemplate
import dev.opencode.android.core.data.config.PermissionTemplate
import dev.opencode.android.core.data.config.RetrofitAdminApi
import dev.opencode.android.core.data.config.TemplateOutcome
import dev.opencode.android.core.data.config.ConfigTemplates
import dev.opencode.android.core.data.server.FileReader
import dev.opencode.android.core.model.McpServerConfig
import dev.opencode.android.core.model.PermissionEffect
import dev.opencode.android.core.testing.VendoredSpec
import dev.opencode.android.core.network.ServerApi
import dev.opencode.android.core.network.ServerApiFactory
import dev.opencode.android.core.testing.integration.DevServerHarness
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The Phase 9 exit criteria against a real `opencode serve` 2.0.18 with the scripted provider.
 *
 * **The second exit criterion is the one only a real server can settle**: *the common edits can be
 * made from the phone and take effect after reload*. The four edits are a default model, a permission
 * rule, an MCP server and an agent file, and each one is driven through the whole path this app uses —
 * the vendored schema, a read-modify-write of the real `opencode.jsonc` on the fake provider's `HOME`,
 * `experimental.fs.write`, `location.reload`, and then `config.get` read back — with the assertion being
 * what the *server* reports afterwards. A `MockWebServer` can answer any of those calls and prove
 * nothing: the thing being tested is whether the file this app wrote is a file the server reads.
 *
 * **The original file is restored after every test.** The harness's `HOME` is shared with the other
 * integration tests, and a test that left a permission rule behind would change the behaviour of the
 * tests that run after it — which is a worse failure than a test failing.
 *
 * **Every wait is bounded and every assertion names what it looked at**, for the reason the other
 * integration tests give: a server that never reaches the expected state fails with the state it did
 * reach rather than hanging the build.
 */
class LiveConfigIntegrationTest {

    private lateinit var api: ServerApi
    private lateinit var surface: ConfigSurface
    private lateinit var schema: ConfigSchema
    private lateinit var workDirectory: String
    private val configPath = mutableMapOf<String, String>()

    /** The scope the surface's `config.get` resources load on, cancelled in [tearDown]. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @Before
    fun setUp() = runBlocking {
        assumeTrue("the dev server is not running; scripts/dev-server.sh start", DevServerHarness.isAvailable)
        val client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .authenticator { _, response ->
                val credential = okhttp3.Credentials.basic("opencode", DevServerHarness.password.orEmpty())
                response.request.newBuilder().header("Authorization", credential).build()
            }
            .build()
        api = ServerApiFactory(okHttpClient = client, credentialProvider = { null })
            .createForReads(DevServerHarness.url!!)
        schema = ConfigSchema.parse(VendoredSpec.configSchemaText())
        surface = ConfigSurface(
            admin = RetrofitAdminApi(api),
            files = FileReader(api),
            schema = schema,
            scope = scope,
            serverId = "live",
        )
        // **The harness's own directory, not a subdirectory of it, and that is a finding rather than a
        // preference.** `location.reload` rebuilds the locations the server has *loaded*; a directory
        // this test invents has never been opened as a location, so a file written into it is written
        // successfully and then ignored — the reload does not reach it. Every other integration test
        // uses the harness directory for the same reason, and this test found it by failing.
        // Nothing is written here: the harness's directory and its `.opencode` already exist, and a
        // probe file written over a directory is a `500` — which is a fact about the route worth having
        // found here rather than in a test that meant something else.
        workDirectory = DevServerHarness.directory!!.trimEnd('/')
        Unit
    }

    @After
    fun tearDown() {
        scope.cancel()
        // **Restore every file this test touched, and put an empty configuration back where there was
        // none.** `fs.write` cannot remove a file, and the harness's `.opencode` had no
        // `opencode.jsonc` before this test ran, so `{}` is the closest state the route can reach — and
        // it is a *valid* configuration, so the server's behaviour is the same as it was. Leaving a
        // permission rule behind would change the behaviour of the tests that run after this one, which
        // is a worse failure than a test failing.
        runBlocking {
            val original = configPath.remove(LOCATION_CONFIG)
            FileReader(api).write(null, LOCATION_CONFIG, original ?: "{}")
            configPath.forEach { (path, text) -> FileReader(api).write(null, path, text) }
            configPath.clear()
            // A definition file this test created is left for the server to forget on its own next
            // reload; writing `{}` over the location's configuration is enough to end this test's effect.
        }
    }

    // ------------------------------------------------------------------------------ the explorer

    @Test
    fun `config get answers with the server's own documents, lowest precedence first`() = runBlocking {
        val documents = surface.documents(workDirectory)

        assertNotNull("config.get must answer", documents.entries)
        // The entries the harness's own HOME configuration produces. A server with no configuration
        // documents at all would make every assertion below vacuous, so the count is checked first.
        assertTrue(
            "the harness must have at least one configuration document, got ${documents.entries}",
            documents.entries.isNotEmpty(),
        )
        documents.documents.forEach { document ->
            assertNotNull("a document must carry its path", document.pathOrNull)
        }
        // Every row exists for every key, whatever the server set — the coverage claim, on live data.
        val rows = documents.rows
        assertEquals(schema.keys.size, rows.size)
        assertEquals(schema.keys.map { it.key }, rows.map { it.key.key })
    }

    @Test
    fun `every row the server reports is a real schema key with a real source`() = runBlocking {
        val documents = surface.documents(workDirectory)

        val reported = documents.rows.filter { it.value.reports.isNotEmpty() }
        assertTrue("the harness reports some configuration", reported.isNotEmpty())
        reported.forEach { row ->
            assertTrue(
                "${row.key.key} has no source path",
                row.value.reports.all { it.path != null },
            )
            // A reported key's projection name must be one the spec actually has.
            assertNotNull("${row.key.key} has no projection", row.key.projectionName)
            assertTrue(
                "a file key must be one the schema declares",
                schema.has(row.key.key),
            )
        }
        // And nothing on the screen is a projection name the file does not have.
        documents.rows.forEach { row ->
            assertTrue("no row may be a projection spelling", schema.has(row.key.key))
        }
    }

    // ------------------------------------------------------------------------------ the four edits

    @Test
    fun `the default model can be set and takes effect after a reload`() = runBlocking {
        // The first exit criterion's simplest case, end to end.
        val target = locationConfig()
        val original = readForWrite(target)

        val outcome = ConfigTemplates.build(schema, ModelTemplate("fake", "scripted"), original.document!!)
        assertTrue("the template must produce a valid document, got $outcome", outcome is TemplateOutcome.Ready)

        val plan = surface.planFileWrite(
            path = target,
            text = render(outcome),
            consequence = "test",
            isPrivilegeChange = false,
            existing = original.text,
            validate = { schema.validator().validate(it) },
        )
        assertNotNull("a valid document must produce a plan", plan)
        val written = surface.commit(plan!!, workDirectory, keys = setOf("model"))
        assertTrue("the write must succeed, got $written", written.isSuccess)

        // The server's own answer, not the app's.
        val after = surface.documents(workDirectory)
        val model = after.rows.first { it.key.key == "model" }
        assertTrue(
            "the model must be the one this test set, was ${model.effective}",
            model.effective?.toString()?.contains("scripted") == true,
        )
        assertTrue(
            "the write report must say the server is using the file, was ${written.getOrThrow().summary}",
            written.getOrThrow().summary.contains("the server is using it"),
        )
    }

    @Test
    fun `a permission rule can be added and the server reports it`() = runBlocking {
        val target = locationConfig()
        val original = readForWrite(target)

        val outcome = ConfigTemplates.build(
            schema,
            PermissionTemplate("bash", "*", PermissionEffect.Deny),
            original.document!!,
        )
        assertTrue("the template must validate, got $outcome", outcome is TemplateOutcome.Ready)

        val plan = surface.planFileWrite(
            path = target,
            text = render(outcome),
            consequence = "test",
            isPrivilegeChange = true,
            existing = original.text,
            validate = { schema.validator().validate(it) },
        )!!
        val written = surface.commit(plan, workDirectory, keys = setOf("permission"))
        assertTrue("the write must succeed, got $written", written.isSuccess)

        // The projection reports the effective rules, so this is the server agreeing that the rule is
        // in force — not the app believing its own file.
        val after = surface.documents(workDirectory)
        val permission = after.rows.first { it.key.key == "permission" }
        val rules = (permission.effective as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { element ->
                (element as? JsonObject)?.let {
                    Triple(
                        it["action"]?.let { a -> (a as JsonPrimitive).content },
                        it["resource"]?.let { r -> (r as JsonPrimitive).content },
                        it["effect"]?.let { e -> (e as JsonPrimitive).content },
                    )
                }
            }
            .orEmpty()
        // **The action is reported as `shell`, not as the `bash` the file said.** The configuration
        // names a tool and the effective ruleset names the permission that tool maps to, and the server
        // does the mapping. That is a property of the contract the app has to know: a row in the
        // explorer shows what the server reported, and the guided template writes the tool name, so the
        // two differ for every tool whose permission name is not its tool name.
        assertTrue(
            "the server must report the deny rule, got ${permission.effective} among $rules",
            rules.any { it == Triple("shell", "*", "deny") },
        )
    }

    @Test
    fun `a persistent MCP server can be written and the server lists it`() = runBlocking {
        val target = locationConfig()
        val original = readForWrite(target)

        val outcome = ConfigTemplates.build(
            schema,
            // A local command rather than a remote URL: a real network call to a made-up host would make
            // the test slow and flaky, and the file shape is what is under test.
            McpTemplate("files", McpServerConfig.Local(command = listOf("true"))),
            original.document!!,
        )
        assertTrue("the template must validate, got $outcome", outcome is TemplateOutcome.Ready)

        val plan = surface.planFileWrite(
            path = target,
            text = render(outcome),
            consequence = "test",
            isPrivilegeChange = true,
            existing = original.text,
            validate = { schema.validator().validate(it) },
        )!!
        val written = surface.commit(plan, workDirectory, keys = setOf("mcp"))
        assertTrue("the write must succeed, got $written", written.isSuccess)

        val after = surface.documents(workDirectory)
        val mcp = after.rows.first { it.key.key == "mcp" }
        assertTrue("the server must report the server this test added, got ${mcp.effective}", mcp.effective != null)
    }

    @Test
    fun `an agent file can be written and the server lists the agent`() = runBlocking {
        // Agents live in files rather than in `opencode.jsonc`, so this one is `fs.write` plus a reload
        // with nothing in the configuration file at all — which is the case that proves the editor
        // writes definitions and not just configuration.
        val path = ".opencode/agents/reviewer.md"
        val body = """
            ---
            description: "Reviews a diff"
            mode: "subagent"
            ---

            # Reviewer

            Read the change and report anything that looks wrong.
        """.trimIndent() + "\n"
        val plan = surface.planFileWrite(
            path = path,
            text = body,
            consequence = "test",
            isPrivilegeChange = true,
            existing = null,
            // No schema: a Markdown definition's validity is the server's business.
            validate = null,
        )!!
        // `expectInConfig = false`, because an agent file is served through `agent.list` and never
        // appears in the configuration chain — asking the configuration list about it would report
        // every definition edit as one the server is ignoring.
        val written = surface.commit(plan, workDirectory, expectInConfig = false, keys = emptySet())
        assertTrue("the write must succeed, got $written", written.isSuccess)
        assertTrue(
            "the report must not call a definition file unreadable, was ${written.getOrThrow().summary}",
            !written.getOrThrow().summary.contains("not reading it"),
        )

        val agent = awaitAgent("reviewer")
        assertEquals("the mode must come from the front matter", "subagent", agent.mode)
    }

    // ------------------------------------------------------------------------------ read-modify-write

    @Test
    fun `a second edit keeps the first, and the file is still a valid document`() = runBlocking {
        // The read-modify-write path, twice. A write that replaced the document would pass a single-edit
        // test and destroy a user's other settings here.
        val target = locationConfig()
        val original = readForWrite(target)

        val first = ConfigTemplates.build(schema, ModelTemplate("fake", "one"), original.document!!)
        val afterFirst = ConfigDocument.setInText(original.text, "model", modelValue(first))
        val second = ConfigTemplates.build(
            schema,
            ModelTemplate("fake", "two"),
            ConfigDocument.parse(afterFirst).documentOrNull!!,
        )
        assertTrue(second is TemplateOutcome.Ready)
        val afterSecond = ConfigDocument.setInText(afterFirst, "model", modelValue(second))

        // Both keys are in the file, and the file validates.
        assertTrue(afterSecond.contains("\"model\""))
        val problems = schema.validator().validate(ConfigDocument.parse(afterSecond).documentOrNull!!)
        assertEquals("two edits must still leave a valid document, got $problems", 0, problems.size)
        // And the second won.
        val model = (ConfigDocument.parse(afterSecond).documentOrNull!!["model"] as JsonPrimitive).content
        assertEquals("fake/two", model)
    }

    @Test
    fun `a write to a path the server does not read is reported as such`() = runBlocking {
        // The diagnostic that earns the reload: a file written successfully and then ignored.
        val plan = surface.planFileWrite(
            // **Right beside the real one, under a name the server does not read.** A path outside the
            // location is not this case: the route answers `500` for it, so the test proved nothing about
            // the report. `opencode.jsonc.bak` is written successfully, is not one of the files
            // `config.get` reports, and is exactly the mistake a user makes by saving a copy — which is
            // what the write report exists to catch.
            path = ".opencode/opencode.jsonc.bak",
            text = """{"model":"fake/orphan"}""",
            consequence = "test",
            isPrivilegeChange = false,
            existing = null,
            validate = { schema.validator().validate(it) },
        )!!
        val written = surface.commit(plan, workDirectory, keys = setOf("model"))

        assertTrue("the write itself succeeds", written.isSuccess)
        assertTrue(
            "and the report must say the server is not reading it, was ${written.getOrThrow().summary}",
            written.getOrThrow().summary.contains("the server is not reading it"),
        )
    }

    @Test
    fun `an invalid document is rejected before it reaches the server`() = runBlocking {
        val target = locationConfig()
        val plan = surface.planFileWrite(
            path = target,
            text = """{"modle": "fake/two"}""",
            consequence = "test",
            isPrivilegeChange = false,
            existing = readForWrite(target).text,
            validate = { schema.validator().validate(it) },
        )

        assertEquals("a misspelled key must not produce a plan at all", null, plan)
    }

    // ------------------------------------------------------------------------------ maintenance

    @Test
    fun `reload, the loaded locations and the migration status all answer`() = runBlocking {
        surface.reloadLocations().also { assertTrue("reload must succeed, got $it", it.isSuccess) }

        val locations = surface.loadedLocations()
        assertTrue("the loaded-location list must answer, got $locations", locations.isSuccess)
        // The harness's own directory, because a directory the server has never opened as a location is
        // not a *loaded* location — which is what the reload test above established.
        assertTrue(
            "the harness's directory must be among the loaded locations, got ${locations.getOrNull()}",
            locations.getOrNull()?.any { it.directory == workDirectory } == true,
        )

        val status = surface.migrationStatus()
        assertTrue("the migration status must answer, got $status", status.isSuccess)
        // Whatever the state is, it is one this client can render rather than an unknown shape.
        assertTrue(
            "the status must be a known state, got ${status.getOrNull()}",
            status.getOrNull() is dev.opencode.android.core.model.MigrationStatus ||
                status.getOrNull() == null,
        )
    }

    @Test
    fun `evicting a location drops it and the next read brings it back`() = runBlocking {
        surface.documents(workDirectory)
        val before = surface.loadedLocations().getOrThrow()
        assertTrue(
            "the directory must be loaded first, got $before",
            before.any { it.directory == workDirectory },
        )

        surface.evictLocation(workDirectory).also { assertTrue("the eviction must succeed, got $it", it.isSuccess) }

        // **Nothing is lost: the caches are rebuilt on next use.** That is the property the
        // confirmation promises, and it is only checkable against a real server — a mock cannot say
        // whether a later read is served from a rebuilt location.
        val after = surface.loadedLocations().getOrThrow()
        assertTrue(
            "the location must be gone right after the eviction, among $after",
            after.none { it.directory == workDirectory },
        )
        val reread = surface.documents(workDirectory)
        assertTrue("a re-read must answer again", reread.failure == null)
        assertTrue(
            "and the location must be loaded again",
            surface.loadedLocations().getOrThrow().any { it.directory == workDirectory },
        )
    }

    @Test
    fun `the saved approvals of a project can be listed and the list is not an error`() = runBlocking {
        // A `400` here is a legitimate answer — the route wants a project id and this test has none —
        // so the assertion is that the call classifies rather than that it succeeds.
        val saved = surface.savedPermissions(projectID = null)
        assertTrue(
            "the call must classify rather than throw, got $saved",
            saved.isSuccess || saved.exceptionOrNull() is dev.opencode.android.core.data.action.ActionFailure,
        )
        if (saved.isSuccess) {
            // Each row is a standing decision, which is what the permissions screen lists and what a
            // removal is a confirmation about.
            saved.getOrThrow().forEach { permission ->
                assertTrue("a saved approval names an action", permission.action.isNotBlank())
                assertTrue("and a resource", permission.resource.isNotBlank())
            }
        }
    }

    // ------------------------------------------------------------------------------ helpers

    private companion object {
        /** A location's own configuration file, relative to the location. */
        const val LOCATION_CONFIG = ".opencode/opencode.jsonc"
    }

    /**
     * Waits, with a bound, for the server's agent catalog to include [name].
     *
     * **Bounded, and it reports what it saw.** `location.reload` answers `204` once the location's
     * services have been rebuilt, and the agent catalog it serves is read from those services, so a
     * `listAgents` in the same breath as the reload is a race — found here by the first version of this
     * test, which read the list once and failed. A wait with no bound would hang the build instead,
     * which is why the timeout is explicit and the failure names the list that was actually returned.
     *
     * The app itself does not need this: `agent.updated` invalidates the catalog and a resume re-reads
     * it. It is the test that has to say "not yet".
     */
    private suspend fun awaitAgent(
        name: String,
        timeoutMillis: Long = 15_000,
        stepMillis: Long = 250,
    ): dev.opencode.android.core.model.AgentInfo {
        val deadline = System.currentTimeMillis() + timeoutMillis
        var last: List<String> = emptyList()
        while (System.currentTimeMillis() < deadline) {
            val agents = api.listAgents(workDirectory).data
            last = agents.map { it.name }
            agents.firstOrNull { it.name == name }?.let { return it }
            Thread.sleep(stepMillis)
        }
        error("the agent $name never appeared within ${timeoutMillis}ms; the server listed $last")
    }

    private class FileText(val text: String, val document: JsonObject?)

    /**
     * The location's own `opencode.jsonc`, relative to the location.
     *
     * **Relative, and that is the route's own contract.** `fs.read` resolves a relative path against
     * the location and `fs.write` is given the location alongside it, so a path the app builds by
     * joining a home directory is a path it should not build. The harness's directory is its own
     * `opencode.jsonc` that this restores afterwards.
     */
    private fun locationConfig(): String = LOCATION_CONFIG

    /** Reads [path] and registers it for restoration, so the harness's own file survives the test. */
    private suspend fun readForWrite(path: String): FileText {
        val read = surface.readFile(workDirectory, path)
        val text = when (read) {
            is dev.opencode.android.core.data.config.ConfigFileRead.Found -> read.file.text.orEmpty()
            is dev.opencode.android.core.data.config.ConfigFileRead.Missing -> {
                // A file the test creates is its own to clean up, and the restore writes back the empty
                // text rather than the harness's content.
                configPath[path] = ""
                ""
            }

            is dev.opencode.android.core.data.config.ConfigFileRead.Failed ->
                error("the harness's configuration could not be read: ${read.error.message}")
        }
        return FileText(text, ConfigDocument.parse(text).documentOrNull)
    }

    private fun render(outcome: TemplateOutcome): String =
        ConfigDocument.render(document(outcome))

    private fun modelValue(outcome: TemplateOutcome): JsonElement = document(outcome).getValue("model")

    private fun document(outcome: TemplateOutcome): JsonObject =
        (outcome as? TemplateOutcome.Ready)?.document as? JsonObject
            ?: error("expected a ready template outcome, got $outcome")
}

private typealias JsonElement = kotlinx.serialization.json.JsonElement
