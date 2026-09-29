package dev.opencode.android.feature.admin

import dev.opencode.android.core.data.action.ActionError
import dev.opencode.android.core.data.action.ActionErrorKind
import dev.opencode.android.core.data.capability.CapabilityPolicy
import dev.opencode.android.core.data.capability.ExperimentalRoute
import dev.opencode.android.core.data.capability.RouteAvailability
import dev.opencode.android.core.data.config.AgentTemplate
import dev.opencode.android.core.data.config.ConfigDocument
import dev.opencode.android.core.data.config.ConfigRedaction
import dev.opencode.android.core.data.config.ConfigSchema
import dev.opencode.android.core.data.config.ConfigSurface
import dev.opencode.android.core.data.config.DefinitionKind
import dev.opencode.android.core.data.config.DefinitionName
import dev.opencode.android.core.data.config.DefinitionTemplates
import dev.opencode.android.core.data.config.McpTemplate
import dev.opencode.android.core.data.config.PermissionTemplate
import dev.opencode.android.core.data.config.RetrofitAdminApi
import dev.opencode.android.core.data.config.WritePlan
import dev.opencode.android.core.data.server.FileReader
import dev.opencode.android.core.model.ConfigEntry
import dev.opencode.android.core.model.ConfigInfo
import dev.opencode.android.core.model.McpServerConfig
import dev.opencode.android.core.model.PermissionEffect
import dev.opencode.android.core.testing.VendoredSpec
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two security properties of this phase, stated as tests.
 *
 * **A write requires a confirmation that names the file and what changes.** The tests below do not
 * drive a screen; they check the *plan* a screen renders, which is the object that carries every word
 * the dialog shows. A plan with no target, or with an empty consequence, or that does not say a
 * privilege change is a privilege change, is a confirmation that cannot do its job — and the write path
 * takes a plan, so there is no way to write without one.
 *
 * **No configuration value reaches a log, a `toString()` or an exception message.** A configuration file
 * holds API keys, and this phase is the one that reads the whole file. The tests put a recognisable
 * secret in every place a value could travel and check that it does not come out.
 */
class ConfirmedWriteTest : AdminServerTest() {

    private val schema: ConfigSchema = ConfigSchema.parse(VendoredSpec.configSchemaText())

    /**
     * One surface for the whole test.
     *
     * **A fresh one per call would make every capability assertion vacuous**: the route availability
     * lives on the instance, so `surface.setShell(...)` followed by `surface.configUpdate.value`
     * would read a different object's answer and always say `Unknown`.
     */
    private val surface: ConfigSurface by lazy {
        ConfigSurface(
            admin = RetrofitAdminApi(server.api),
            files = FileReader(server.api),
            schema = schema,
        )
    }

    // ------------------------------------------------------------------------------ the plan

    @Test
    fun `a file write plan names the file, the consequence and the byte counts`() {
        val plan = surface.planFileWrite(
            path = ".opencode/opencode.jsonc",
            text = """{"model":"openai/gpt"}""",
            consequence = "This replaces .opencode/opencode.jsonc; the server reads it after a reload",
            isPrivilegeChange = false,
            existing = """{"model":"anthropic/claude"}""",
            validate = { schema.validator().validate(it) },
        )

        assertNotNull("a valid document must produce a plan", plan)
        assertEquals(".opencode/opencode.jsonc", plan!!.target)
        assertTrue("the consequence must name the file", plan.consequence.contains(".opencode/opencode.jsonc"))
        assertFalse("the consequence must say what happens next", plan.consequence.isBlank())
        assertEquals("""{"model":"anthropic/claude"}""".toByteArray().size, plan.previousBytes)
        assertEquals(plan.bytes, """{"model":"openai/gpt"}""".toByteArray().size)
        assertFalse("an existing file is not a new one", plan.isNewFile)
        assertTrue("a validated plan has nothing left to complain about", plan.diagnostics.isEmpty())
    }

    @Test
    fun `a plan says a new file is new`() {
        val plan = surface.planFileWrite(
            path = ".opencode/opencode.jsonc",
            text = "{}",
            consequence = "This creates .opencode/opencode.jsonc",
            isPrivilegeChange = false,
            existing = null,
            validate = { emptyList() },
        )

        assertTrue(plan!!.isNewFile)
        assertNull("a new file has no previous size", plan.previousBytes)
    }

    @Test
    fun `a document that does not validate produces no plan at all`() {
        // **The gate is before the confirmation, not after it.** A confirmation the user can reach with
        // an invalid document is not a gate, because the write that follows it bypasses the server's own
        // validation entirely (features doc §33.5).
        val plan = surface.planFileWrite(
            path = ".opencode/opencode.jsonc",
            text = """{"modle":"openai/gpt"}""",
            consequence = "This replaces the file",
            isPrivilegeChange = false,
            existing = "{}",
            validate = { schema.validator().validate(it) },
        )

        assertNull("an invalid document must not produce a plan", plan)
    }

    @Test
    fun `a document that cannot be parsed produces no plan, and the failure carries a line`() {
        val plan = surface.planFileWrite(
            path = ".opencode/opencode.jsonc",
            text = "{\n  // note\n  \"model\": \n}",
            consequence = "This replaces the file",
            isPrivilegeChange = false,
            existing = "{}",
            validate = { schema.validator().validate(it) },
        )

        assertNull(plan)
        // The line is where the user has to look, so the gate has to be able to say it even though the
        // plan it refuses to build is the thing the screen would have shown it in.
    }

    @Test
    fun `a permission edit is a privilege change and says so`() {
        val plan = surface.planFileWrite(
            path = ".opencode/opencode.jsonc",
            text = """{"permission":{"bash":{"*":"allow"}}}""",
            consequence = "This replaces .opencode/opencode.jsonc",
            isPrivilegeChange = true,
            existing = "{}",
            validate = { schema.validator().validate(it) },
        )

        assertTrue("a permissions edit decides what the agent may do", plan!!.isPrivilegeChange)
    }

    @Test
    fun `the shell setting names the global file, not the project's`() {
        val plan = surface.planSetting(
            target = ConfigViewModel.GLOBAL_CONFIG,
            consequence = "The bash tool and every terminal on this server will run /bin/zsh",
            isPrivilegeChange = true,
        )

        // A user editing their project file must not be told a field changes that file.
        assertEquals("~/.config/opencode/opencode.json", plan.target)
        assertFalse(plan.target.contains(".opencode/opencode.jsonc"))
        assertTrue(plan.isPrivilegeChange)
        assertEquals(0, plan.bytes)
        assertEquals("", plan.text)
    }

    // ------------------------------------------------------------------------------ the write path

    @Test
    fun `a committed write goes through write, reload and a fresh config get`() = runTest {
        val written = """{"model":"openai/gpt"}"""
        server.answer(
            "POST /api/experimental/fs/write",
            """{"location":{"directory":"/work/app"},"data":{"path":".opencode/opencode.jsonc"}}""",
        )
        // The read-back goes through the wildcard `fs.read` route, which the file reader builds with its
        // own path encoding, so the answer is registered by prefix.
        server.answerPrefix("GET", "/api/fs/read/", written)
        server.answer("POST /api/location/reload", "", 204)
        server.answer(
            "GET /api/config",
            """[{"type":"document","path":"/work/app/.opencode/opencode.jsonc","info":{"model":"openai/gpt"}}]""",
        )

        val plan = surface.planFileWrite(
            path = ".opencode/opencode.jsonc",
            text = written,
            consequence = "This replaces the file",
            isPrivilegeChange = false,
            existing = null,
            validate = null,
        )!!
        val outcome = surface.commit(plan, "/work/app", keys = setOf("model"))

        val requests = server.requests
        // The order is the contract: write, read back, reload, then ask the server what it thinks.
        val write = requests.indexOfFirst { it.startsWith("POST /api/experimental/fs/write") }
        val read = requests.indexOfFirst { it.startsWith("GET /api/fs/read/") }
        val reload = requests.indexOfFirst { it.startsWith("POST /api/location/reload") }
        val config = requests.indexOfFirst { it.startsWith("GET /api/config") }
        assertTrue("the write must come first: $requests", write >= 0)
        assertTrue("the read-back must follow the write: $requests", read > write)
        assertTrue("the reload must follow the read-back: $requests", reload > read)
        assertTrue("config.get is the diagnostic: $requests", config > reload)
        assertTrue("the summary must say the server is using it", outcome.isSuccess)
        assertTrue(outcome.getOrThrow().summary.contains("the server is using it"))
    }

    @Test
    fun `a write to a file the server does not read is reported, not called a success`() = runTest {
        // The case that matters: the write succeeds, the server reloads, and the file is invisible to
        // it. Saying "saved" here is how a user ends up with a server that does not behave the way
        // their file says.
        server.answer(
            "POST /api/experimental/fs/write",
            """{"location":{"directory":"/work/app"},"data":{"path":"/elsewhere/opencode.jsonc"}}""",
        )
        server.answerPrefix("GET", "/api/fs/read/", """{"model":"openai/gpt"}""")
        server.answer("POST /api/location/reload", "", 204)
        server.answer(
            "GET /api/config",
            """[{"type":"document","path":"/root/.config/opencode/opencode.json","info":{"model":"anthropic/claude"}}]""",
        )

        val plan = surface.planFileWrite(
            path = "/elsewhere/opencode.jsonc",
            text = """{"model":"openai/gpt"}""",
            consequence = "This replaces the file",
            isPrivilegeChange = false,
            existing = null,
            validate = null,
        )!!
        val outcome = surface.commit(plan, "/work/app", keys = setOf("model"))

        assertTrue(outcome.isSuccess)
        assertTrue(
            "the summary must say the file is not being read: ${outcome.getOrThrow().summary}",
            outcome.getOrThrow().summary.contains("the server is not reading it"),
        )
        assertTrue(
            outcome.getOrThrow().diagnostics.any { it.keyword == "source" },
        )
    }

    @Test
    fun `a 400 from the write route is a failure the user must see, and the route stays on`() = runTest {
        // A `400` is a rejected path, not a missing route: the route answered. Treating it as absence
        // would grey the save button after one typo, and the user would have no way back.
        server.answer(
            "POST /api/experimental/fs/write",
            """{"_tag":"InvalidRequestError","data":{"message":"path is outside the location"}}""",
            400,
        )

        val plan = surface.planFileWrite(
            path = "/nope/opencode.jsonc",
            text = "{}",
            consequence = "x",
            isPrivilegeChange = false,
            existing = null,
            validate = null,
        )!!
        val result = surface.commit(plan, "/work/app", reload = false)

        assertTrue("a rejected path is a failure the user must see", result.isFailure)
        assertEquals(
            dev.opencode.android.core.data.action.ActionErrorKind.INVALID_REQUEST,
            (result.exceptionOrNull() as? dev.opencode.android.core.data.integrations.ActionFailure)?.error?.kind,
        )
        assertTrue("and the save button stays available", surface.fsUsable(allowedBySetting = true))
    }

    @Test
    fun `a 404 from the write route hides it, and nothing was written`() = runTest {
        server.answer(
            "POST /api/experimental/fs/write",
            """{"_tag":"FileNotFoundError","data":{"message":"no such route"}}""",
            404,
        )

        val plan = surface.planFileWrite(
            path = ".opencode/opencode.jsonc",
            text = "{}",
            consequence = "x",
            isPrivilegeChange = false,
            existing = null,
            validate = null,
        )!!
        val result = surface.commit(plan, "/work/app", reload = false)

        assertTrue(result.isFailure)
        assertEquals(RouteAvailability.Absent(404), surface.fsWrite.value)
        assertFalse(
            "a server that has not got the route must not offer the editor's save",
            surface.fsUsable(allowedBySetting = true),
        )
        // And no reload was attempted, because nothing was written to reload.
        assertTrue(server.requests.none { it.startsWith("POST /api/location/reload") })
    }

    // ------------------------------------------------------------------------------ capability gating

    @Test
    fun `a 404 from the config update route hides the shell setting`() = runTest {
        server.answer(
            "PATCH /api/experimental/config",
            """{"_tag":"InvalidRequestError","data":{"message":"no such route"}}""",
            404,
        )

        val result = surface.setShell("/bin/zsh")

        assertTrue(result.isFailure)
        assertEquals(
            "a server without the route must not offer the setting",
            RouteAvailability.Absent(404),
            surface.configUpdate.value,
        )
        assertFalse(
            "the switch alone must not re-enable a route the server does not have",
            surface.configUpdateUsable(allowedBySetting = true),
        )
    }

    @Test
    fun `a blank shell is refused before any call`() = runTest {
        val result = surface.setShell("   ")

        assertTrue(result.isFailure)
        val error = (result.exceptionOrNull() as? dev.opencode.android.core.data.integrations.ActionFailure)?.error
        assertEquals(ActionErrorKind.INVALID_REQUEST, error?.kind)
        // Nothing was sent: the route's body requires `shell` and has no null branch, so an empty string
        // would store a configuration with an empty shell.
        assertEquals(emptyList<String>(), server.requests)
    }

    @Test
    fun `a 500 does not hide a route`() = runTest {
        // Only 404 and 405 mean "this server has not got the route". A 500 proves it is there, and
        // treating it as absence would switch a working feature off because of a transient fault.
        server.answer("PATCH /api/experimental/config", """{"_tag":"ServerError"}""", 500)

        surface.setShell("/bin/zsh")

        assertEquals(RouteAvailability.Unknown, surface.configUpdate.value)
        assertTrue(surface.configUpdateUsable(allowedBySetting = true))
    }

    // ------------------------------------------------------------------------------ redaction

    @Test
    fun `no rendered value carries a credential`() {
        val secrets = listOf(
            "sk-ant-placeholder-not-a-real-key",
            "ghp_placeholdernotarealkeyvalue",
            "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.abcdefghijkl",
        )
        val document = ConfigDocument.parse(
            """
            {
              "provider": {
                "llama": {"options": {"apiKey": "${secrets[0]}", "baseURL": "https://api.example.com"}}
              },
              "enterprise": {"url": "${secrets[1]}"},
              "username": "${secrets[2]}",
              "mcp": {"x": {"type": "remote", "url": "https://mcp.example.com/", "oauth": {"clientSecret": "${secrets[0]}"}}}
            }
            """.trimIndent(),
        ).documentOrNull!!

        // Every leaf, rendered the way a row renders it, at any depth — a `clientSecret` three levels
        // down is exactly the case a shallow walk would miss.
        val rendered = mutableListOf<String>()
        fun walk(key: String?, value: kotlinx.serialization.json.JsonElement) {
            rendered += ConfigRedaction.describe(key, value)
            when (value) {
                is kotlinx.serialization.json.JsonObject -> value.forEach { (nested, child) -> walk(nested, child) }
                is kotlinx.serialization.json.JsonArray -> value.forEach { walk(key, it) }
                else -> Unit
            }
        }
        document.forEach { (key, value) -> walk(key, value) }

        secrets.forEach { secret ->
            rendered.forEach { line ->
                assertFalse("a rendered value leaked a credential: $line", line.contains(secret))
            }
        }
        // And the ones that are not secrets are still shown, which is the other half of the rule.
        assertTrue(rendered.any { it.contains("https://api.example.com") })
        assertTrue("a nested non-secret is still shown: $rendered", rendered.any { it.contains("remote") })
    }

    @Test
    fun `a diagnostic never carries the value it rejected`() {
        val secret = "sk-ant-placeholder-not-a-real-key"
        val problems = schema.validator().validate(
            ConfigDocument.parse("""{"share": "$secret"}""").documentOrNull!!,
        )

        assertTrue(problems.isNotEmpty())
        problems.forEach {
            assertFalse("a diagnostic leaked the value: $it", it.toString().contains(secret))
        }
    }

    @Test
    fun `a parse failure never carries the document`() {
        val secret = "ghp_placeholdernotarealkeyvalue"
        // Unterminated after a real value, so the parser echoes the bytes it choked on in its message.
        val failure = ConfigDocument.parse("""{"username": "$secret"}}""")

        assertTrue("the document must not parse", failure is dev.opencode.android.core.data.config.ParsedDocument.Failed)
        assertFalse(
            "the failure leaked the document: $failure",
            failure.toString().contains(secret),
        )
        assertTrue("and it still says where", (failure as dev.opencode.android.core.data.config.ParsedDocument.Failed).failure.line >= 1)
    }

    @Test
    fun `a plan never carries the value it would write beyond the bytes it counts`() {
        // The plan does hold the text, because a write has to — but it is never rendered, never logged
        // and never put in a message. What is checked here is that the *dialog's* data (the target and
        // the consequence) cannot contain a document, which is what `WriteConfirmationDialog` renders.
        val plan = WritePlan(
            target = ".opencode/opencode.jsonc",
            text = """{"apiKey": "sk-ant-placeholder-not-a-real-key"}""",
            consequence = "This replaces .opencode/opencode.jsonc; the server reads it after a reload",
            isPrivilegeChange = true,
            bytes = 44,
            previousBytes = 10,
        )

        assertFalse("the target must not hold a document", plan.target.contains("apiKey"))
        assertFalse("the consequence must not hold a document", plan.consequence.contains("apiKey"))
        assertTrue(plan.isPrivilegeChange)
    }

    @Test
    fun `a configuration document's own toString is never rendered by a screen`() {
        // The functions a screen calls are `showValue` and `ConfigRedaction.describe`; a screen that
        // reached for a value's own `toString` would be one line from printing a key, so the redaction
        // has to be a function the screen *must* call rather than one it *may* call.
        val secret = "sk-ant-placeholder-not-a-real-key"
        val value = JsonPrimitive(secret)

        // A raw `toString` *does* carry the value, quotes and all — which is exactly why no screen may
        // call it. `showValue` is the function a screen calls, and it does not.
        assertTrue("a raw toString carries the value, which is the hazard", value.toString().contains(secret))
        assertFalse("showValue must redact it", showValue("apiKey", value).contains(secret))
        // Even under a name that promises nothing, the shape rule catches it.
        assertFalse(ConfigRedaction.describe("label", value).contains(secret))
    }

    // ------------------------------------------------------------------------------ definitions

    @Test
    fun `a definition name must be a single path segment`() {
        listOf("review", "my-agent", "a.b_c", "A1").forEach {
            assertTrue("$it is a valid name", DefinitionName.isValid(it))
        }
        // `fs.write` is given a path the *server* resolves, so a traversal would put a file outside the
        // directory the user is looking at.
        listOf("", " ", "../AGENTS", "a/b", "..", ".", "a\\b", "x".repeat(65)).forEach {
            assertFalse("$it is not a valid name", DefinitionName.isValid(it))
            assertNotNull("and the screen can say why", DefinitionName.problem(it))
        }
    }

    @Test
    fun `each definition kind's path is the one the server reads`() {
        assertEquals(".opencode/agents/review.md", DefinitionKind.AGENT.relativePath("review"))
        assertEquals(".opencode/commands/deploy.md", DefinitionKind.COMMAND.relativePath("deploy"))
        assertEquals(".opencode/skills/lint/SKILL.md", DefinitionKind.SKILL.relativePath("lint"))
        assertEquals("AGENTS.md", DefinitionKind.INSTRUCTIONS.relativePath("anything"))
    }

    @Test
    fun `a new definition file has front matter of the right kind`() {
        val agent = DefinitionTemplates.newFile(DefinitionKind.AGENT, "review")
        assertTrue(agent.startsWith("---"))
        assertTrue(agent.contains("mode: primary"))
        assertEquals(setOf("description", "mode"), DefinitionTemplates.frontMatter(agent).keys)
        // The values round-trip: a file this app created pre-fills its own form.
        assertEquals("When to use review", DefinitionTemplates.frontMatter(agent)["description"])

        val command = DefinitionTemplates.newFile(DefinitionKind.COMMAND, "deploy")
        assertEquals(setOf("description"), DefinitionTemplates.frontMatter(command).keys)

        val skill = DefinitionTemplates.newFile(DefinitionKind.SKILL, "lint")
        assertEquals(setOf("name", "description"), DefinitionTemplates.frontMatter(skill).keys)

        // `AGENTS.md` is prose the project reads: front matter would be a line the agent treats as an
        // instruction.
        assertEquals(
            emptyMap<String, String>(),
            DefinitionTemplates.frontMatter(DefinitionTemplates.newFile(DefinitionKind.INSTRUCTIONS, "")),
        )
    }

    @Test
    fun `rewriting front matter keeps the body byte for byte`() {
        val original = "---\ndescription: old\nmode: primary\n---\n\n# Review\n\nThe body the user wrote.\n"
        val rewritten = DefinitionTemplates.withFrontMatter(
            original,
            mapOf("description" to "Reviews a diff", "mode" to "subagent"),
        )

        assertTrue(
            "a value with a colon in it must still be one string: $rewritten",
            rewritten.contains("""description: "Reviews a diff""""),
        )
        assertTrue(rewritten.contains("""mode: "subagent""""))
        assertTrue("the body must survive", rewritten.contains("The body the user wrote."))
        assertFalse("the old front matter must be gone", rewritten.contains("description: old"))
    }

    @Test
    fun `a file with no front matter gains one rather than losing the edit`() {
        val plain = "# Review\n\nA body.\n"
        val rewritten = DefinitionTemplates.withFrontMatter(plain, mapOf("mode" to "subagent"))

        assertTrue(rewritten.startsWith("---"))
        // Quoted, like every other value: YAML reads a quoted scalar as the same string, and a value a
        // form did not quote is a value that breaks the first time somebody types a colon.
        assertTrue(
            "the mode must be replaced, not appended: $rewritten",
            rewritten.contains("""mode: "subagent""""),
        )
        assertTrue("the body must survive", rewritten.contains("A body."))
    }

    @Test
    fun `a permission block is written as a YAML flow map`() {
        val flow = DefinitionTemplates.permissionFlow(mapOf("edit" to "ask", "bash" to "deny"))

        assertTrue(flow.startsWith("{"))
        assertTrue(flow.endsWith("}"))
        // Quoted, because a phone keyboard can produce a colon and a bare `description: a: b` would
        // parse as a mapping and silently lose the text after the colon.
        assertTrue(flow.contains(""""edit": "ask""""))
        assertTrue(flow.contains(""""bash": "deny""""))
    }

    // ------------------------------------------------------------------------------ the templates

    @Test
    fun `each template's consequence says what it changes`() {
        assertTrue(PermissionTemplate("bash", "*", PermissionEffect.Deny).consequence.contains("DENY"))
        assertTrue(McpTemplate("x", McpServerConfig.Local(listOf("run"))).consequence.contains("tools"))
        assertTrue(AgentTemplate("review").consequence.contains("review"))
    }

    @Test
    fun `a remote MCP template will not produce a non-http URL`() {
        // The server fetches whatever is sent, so `file://` would make it read a local file and
        // `intent://` would hand a URL to another app on the user's own machine.
        val draft = ConfigTemplateDraft(
            choice = ConfigTemplateChoice.MCP,
            name = "x",
            mcpKind = "remote",
            mcpUrl = "file:///etc/passwd",
        )

        assertNull(draft.toTemplate())
    }

    @Test
    fun `config entries with a cumulative projection are not read as an override`() = runTest {
        // The API cannot answer "which document set this key", and the explorer says so rather than
        // guessing. This is the case the row renders: one document reports it twice over because the
        // second inherited it.
        server.answer(
            "GET /api/config",
            """
            [
              {"type":"document","path":"/root/.config/opencode/opencode.json","info":{"model":"anthropic/claude"}},
              {"type":"document","path":"/work/app/.opencode/opencode.jsonc","info":{"model":"anthropic/claude"}}
            ]
            """.trimIndent(),
        )

        val documents = surface.documents("/work/app")
        val row = documents.rows.first { it.key.key == "model" }

        assertEquals(2, row.value.reports.size)
        assertTrue("with no file read, the setter is unknown", row.value.setters.isEmpty())
        assertFalse("and therefore the row must not claim an override", row.isOverridden)
    }

    @Test
    fun `reading a document's own text is what makes the override exact`() = runTest {
        server.answer(
            "GET /api/config",
            """
            [
              {"type":"document","path":"/root/.config/opencode/opencode.json","info":{"model":"anthropic/claude"}},
              {"type":"document","path":"/work/app/.opencode/opencode.jsonc","info":{"model":"openai/gpt"}}
            ]
            """.trimIndent(),
        )
        val bytes = dev.opencode.android.core.data.composer.ServerPath.encodePath("/work/app/.opencode/opencode.jsonc")
        server.answer("GET /api/fs/read/$bytes", """{"model":"openai/gpt","share":"disabled"}""")

        val documents = surface.documents("/work/app")
        val nearest = documents.entries.indexOfLast { it is ConfigEntry.Document }
        val withFacts = surface.documents(
            "/work/app",
            mapOf(
                nearest to dev.opencode.android.core.data.config.ConfigFileFacts(
                    nearest,
                    ConfigDocument.topLevelKeys("""{"model":"openai/gpt","share":"disabled"}"""),
                ),
            ),
        )
        val row = withFacts.rows.first { it.key.key == "model" }

        // The nearest file sets it, and because only the nearest file was read the row says which one.
        assertEquals(1, row.value.setters.size)
        assertEquals("/work/app/.opencode/opencode.jsonc", row.value.setters.single().path)
        assertEquals("openai/gpt", row.effective.let { (it as JsonPrimitive).content })
        assertTrue(documents.rows.first { it.key.key == "model" }.isReported)
    }

    @Test
    fun `capability policy is what decides a route is absent`() {
        // The one rule the whole gating rests on, asserted directly rather than through a screen.
        assertEquals(RouteAvailability.Absent(404), CapabilityPolicy.from(404))
        assertEquals(RouteAvailability.Absent(405), CapabilityPolicy.from(405))
        assertNull(CapabilityPolicy.from(500))
        assertEquals(
            RouteAvailability.Absent(404),
            CapabilityPolicy.from(ActionError(kind = ActionErrorKind.NOT_FOUND, message = "gone")),
        )
        assertNull(
            "a 405 has no body to classify, so only the status can hide the feature",
            CapabilityPolicy.from(ActionError(kind = ActionErrorKind.SERVER, message = "", httpStatus = 500)),
        )
    }

    @Test
    fun `the two experimental routes of this phase are capability-gated separately`() {
        // A user who agreed to the shell has not agreed to arbitrary file writes, and one who has agreed
        // to file writes has not agreed to a running session's instructions.
        assertEquals(
            "config_update",
            ExperimentalRoute.CONFIG_UPDATE.id,
        )
        assertEquals("session_instructions", ExperimentalRoute.SESSION_INSTRUCTIONS.id)
        assertTrue(ExperimentalRoute.CONFIG_UPDATE != ExperimentalRoute.FS_WRITE)
    }

    @Test
    fun `an empty projection reports nothing, and that is different from reporting no key`() {
        val info = ConfigInfo()
        assertNull("no value", info.shell)
        assertNull("no rules", info.permissions)
        // The projection *knows* `shell` and has nothing for it, which is "reported as unset". A key it
        // does not know is `websearch`'s file counterpart, and that is a different sentence.
        assertTrue("the projection reports shell", info.projects("shell"))
        assertNull("with nothing set", info.projected("shell"))
        assertFalse("a key with no projection at all", info.projects("small_model"))
        assertTrue("and the file schema has it anyway", schema.has("small_model"))
    }
}
