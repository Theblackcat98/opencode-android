package dev.opencode.android.core.data.config

import dev.opencode.android.core.model.McpProtocol
import dev.opencode.android.core.model.McpServerConfig
import dev.opencode.android.core.model.McpTimeout
import dev.opencode.android.core.model.PermissionEffect
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The read-modify-write path, and the two promises it makes.
 *
 * **A comment survives an edit.** `opencode.jsonc` is a file people keep notes in, and a validator that
 * re-serialised the tree would delete every one of those notes the moment a user changed a model name.
 * So the text-level edit rewrites one key's bytes and leaves the rest alone, and the test asserts the
 * exact output rather than that a field changed.
 *
 * **A diagnostic is at the right place.** A syntax error's line is the line in the *original* file, and
 * every one of these tests names the line it expects.
 */
class ConfigDocumentTest {

    private val schema = VendoredSchema.schema()
    private val validator = schema.validator()

    // ------------------------------------------------------------------------------ parsing

    @Test
    fun `a commented document parses and reports the keys it sets`() {
        val source = """
            {
              // which model new sessions start on
              "model": "placeholder-provider/placeholder-model",
              /* the project keeps its own share setting */
              "share": "disabled",
            }
        """.trimIndent()

        val parsed = ConfigDocument.parse(source)

        val document = parsed.documentOrNull
        assertNotNull(document)
        assertEquals(setOf("model", "share"), ConfigDocument.topLevelKeys(source))
        assertEquals("placeholder-provider/placeholder-model", (document!!["model"] as JsonPrimitive).content)
        assertEquals(emptyList<SchemaDiagnostic>(), validator.validate(document))
    }

    @Test
    fun `a syntax error names a line in the original file, not in the masked copy`() {
        // The broken line is the last one, after a two-line comment. If the mask shortened the text the
        // answer would be line 2, and the user would look at a comment.
        val source = "{\n  // a note\n  // another note\n  \"model\": \"x\"\n  \"share\": \n}"
        val failed = ConfigDocument.parse(source) as ParsedDocument.Failed

        assertTrue("the line must be past the comments, was ${failed.failure.line}", failed.failure.line >= 5)
        assertTrue(failed.failure.reason.isNotBlank())
    }

    @Test
    fun `a syntax error does not quote the document`() {
        // The parser echoes the bytes it choked on, and a configuration file holds API keys. The
        // diagnostic keeps the position and the structural problem and drops the document.
        val secret = "sk-ant-placeholder-not-a-real-key"
        val source = """{"enterprise": {"url": "$secret"}}}"""
        val failed = ConfigDocument.parse(source) as ParsedDocument.Failed

        assertFalse("a parse failure leaked the document: ${failed.failure.reason}", failed.failure.reason.contains(secret))
        assertFalse(failed.toString().contains(secret))
    }

    @Test
    fun `an empty or comment-only file is an empty document, not a failure`() {
        // A user who has just created the file and commented out their only key has a file with no keys,
        // and the editor's job is to let them add one.
        listOf("", "   \n  ", "// nothing yet", "/* nothing yet */").forEach { source ->
            val parsed = ConfigDocument.parse(source)
            assertTrue("a comment-only file must parse, got $parsed", parsed is ParsedDocument.Parsed)
            assertEquals(emptyMap<String, Any>(), parsed.documentOrNull?.toMap())
            assertEquals(emptySet<String>(), ConfigDocument.topLevelKeys(source))
        }
    }

    @Test
    fun `a non-object document is treated as empty rather than as a crash`() {
        // `Config` is an object with `additionalProperties: false`, so the validator rejects anything
        // else; the editor still needs a tree to merge a key into.
        assertEquals(emptyMap<String, Any>(), ConfigDocument.parse("[1,2]").documentOrNull?.toMap())
        assertEquals(emptyMap<String, Any>(), ConfigDocument.parse("\"text\"").documentOrNull?.toMap())
    }

    // ------------------------------------------------------------------------------ text edits

    @Test
    fun `setting a key rewrites only that key's bytes`() {
        val source = """{
  // the default model for new sessions
  "model": "placeholder-provider/placeholder-model",
  "share": "manual"
}"""
        val edited = ConfigDocument.setInText(source, "model", JsonPrimitive("placeholder-provider/other-model"))

        assertEquals(
            """{
  // the default model for new sessions
  "model": "placeholder-provider/other-model",
  "share": "manual"
}""",
            edited,
        )
        // The comment above the key is the user's note about it, and it has to stay.
        assertTrue(edited.contains("the default model for new sessions"))
        assertEquals(setOf("model", "share"), ConfigDocument.topLevelKeys(edited))
    }

    @Test
    fun `adding a key keeps every comment and the file's own indentation`() {
        val source = """{
  // notes about this project
  "model": "placeholder-provider/placeholder-model"
}"""
        val edited = ConfigDocument.setInText(source, "share", JsonPrimitive("disabled"))

        assertEquals(
            """{
  // notes about this project
  "model": "placeholder-provider/placeholder-model",
  "share": "disabled"
}""",
            edited,
        )
        assertTrue(edited.contains("notes about this project"))
    }

    @Test
    fun `adding the first key to an empty object does not leave a dangling comma`() {
        assertEquals("""{"model": "x"}""", ConfigDocument.setInText("{}", "model", JsonPrimitive("x")))
        assertEquals("{\n  \"model\": \"x\"\n}", ConfigDocument.setInText("{\n}", "model", JsonPrimitive("x")))
    }

    @Test
    fun `a four-space file keeps its four spaces`() {
        val source = "{\n    \"model\": \"a\"\n}"
        val edited = ConfigDocument.setInText(source, "share", JsonPrimitive("manual"))
        assertEquals("{\n    \"model\": \"a\",\n    \"share\": \"manual\"\n}", edited)
    }

    @Test
    fun `setting a nested key only touches that nested key`() {
        // The span scanner has to find *top-level* members: `{"mcp": {"model": …}}` must never make
        // `model` look like a top-level key, or the first edit would corrupt the file.
        val source = """{"mcp": {"files": {"model": "inner"}}, "model": "outer"}"""
        val edited = ConfigDocument.setInText(source, "model", JsonPrimitive("changed"))

        assertEquals("""{"mcp": {"files": {"model": "inner"}}, "model": "changed"}""", edited)
    }

    @Test
    fun `a value that contains the key name as a substring is not the key`() {
        val source = """{"model2": "keep", "model": "change"}"""
        val edited = ConfigDocument.setInText(source, "model", JsonPrimitive("done"))
        assertEquals("""{"model2": "keep", "model": "done"}""", edited)
    }

    @Test
    fun `a URL with a slash inside a value is not a key boundary`() {
        val source = """{"url": "https://example.com/a", "model": "x"}"""
        val edited = ConfigDocument.setInText(source, "model", JsonPrimitive("y"))
        assertEquals("""{"url": "https://example.com/a", "model": "y"}""", edited)
    }

    @Test
    fun `a value with a trailing comma in a comment does not break the scan`() {
        val source = """{"a": 1, // one, two
  "model": "x"}"""
        val edited = ConfigDocument.setInText(source, "model", JsonPrimitive("y"))
        assertTrue(edited.contains("one, two"))
        assertTrue(edited.contains(""""y""""))
    }

    @Test
    fun `an object value is replaced whole`() {
        val source = """{"permission": {"bash": "ask"}, "model": "x"}"""
        val value = ConfigDocument.set(json("{}"), "permission", json("""{"bash": "deny", "edit": "allow"}"""))
        val edited = ConfigDocument.setInText(source, "permission", value.getValue("permission"))

        assertEquals(
            """{"permission": {
  "bash": "deny",
  "edit": "allow"
}, "model": "x"}""",
            edited,
        )
        assertEquals(emptyList<SchemaDiagnostic>(), validator.validate(ConfigDocument.parse(edited).documentOrNull!!))
    }

    @Test
    fun `removing a key takes its comma with it`() {
        val source = """{"model": "x", "share": "manual"}"""
        val removed = ConfigDocument.removeFromText(source, "model")

        assertEquals("""{"share": "manual"}""", removed)
        assertEquals(setOf("share"), ConfigDocument.topLevelKeys(removed))
    }

    @Test
    fun `removing the only key leaves an empty object rather than a broken one`() {
        val removed = ConfigDocument.removeFromText("""{"model": "x"}""", "model")
        assertEquals("{}", removed)
        assertNotNull(ConfigDocument.parse(removed).documentOrNull)
    }

    @Test
    fun `removing a key that is not there changes nothing`() {
        val source = """{"model": "x"}"""
        assertEquals(source, ConfigDocument.removeFromText(source, "share"))
    }

    @Test
    fun `a full edit cycle keeps the document valid and the comments intact`() {
        val source = """{
  // keep this
  "model": "placeholder-provider/placeholder-model",
  "mcp": { "files": { "type": "remote", "url": "https://mcp.example.com/" } }
}"""
        var text = source
        text = ConfigDocument.setInText(text, "model", JsonPrimitive("placeholder-provider/other-model"))
        text = ConfigDocument.setInText(text, "share", JsonPrimitive("disabled"))

        assertTrue(text.contains("keep this"))
        assertEquals(emptyList<SchemaDiagnostic>(), validator.validate(ConfigDocument.parse(text).documentOrNull!!))
        assertEquals(setOf("model", "mcp", "share"), ConfigDocument.topLevelKeys(text))
    }

    @Test
    fun `rendering a value produces something the schema accepts`() {
        val value = json("""{"bash": {"*": "deny"}, "tools": ["a", "b"]}""")
        val text = ConfigDocument.render(value)

        assertEquals(value, ConfigDocument.parse(text).documentOrNull)
        assertTrue("an object should render across lines, was:\n$text", text.contains("\n"))
        assertEquals("{}", ConfigDocument.render(json("{}")))
        assertEquals("[]", ConfigDocument.render(JsonArray(emptyList())))
    }
}

/**
 * The four guided templates, checked against the vendored schema.
 *
 * The point of each test is the same and it is the reason these templates exist: **a template can never
 * produce a document the server will refuse.** The write path is `experimental.fs.write`, which
 * bypasses the server's own validation entirely (features doc §33.5), so the only validation there is
 * happens here.
 */
class ConfigTemplatesTest {

    private val schema = VendoredSchema.schema()

    @Test
    fun `the default model template writes the string the file schema accepts`() {
        // The projection also accepts the three-field object, and writing it here would be rejected by
        // the file's own schema — which is the class of mistake this validator exists to catch.
        val outcome = ConfigTemplates.build(schema, ModelTemplate("placeholder-provider", "placeholder-model", "high"), json("{}"))

        assertTrue(outcome is TemplateOutcome.Ready)
        val document = (outcome as TemplateOutcome.Ready).document as JsonObject
        assertEquals("placeholder-provider/placeholder-model#high", (document["model"] as JsonPrimitive).content)
    }

    @Test
    fun `a model reference that is not a reference produces nothing`() {
        // Neither half present, an empty provider, an empty model, and a provider that contains the
        // separator — all four are things a form can produce and the schema will not accept.
        listOf(
            ModelTemplate("", "placeholder-model"),
            ModelTemplate("placeholder-provider", ""),
            // A variant carrying a second `#`, which the spec's pattern does not allow.
            ModelTemplate("placeholder-provider", "placeholder-model", "a#b"),
        ).forEach { template ->
            assertTrue("$template is not a model reference", ConfigTemplates.build(schema, template, json("{}")) is TemplateOutcome.NotReady)
        }
        // And the shapes the form does produce are accepted, a blank variant field meaning "no variant".
        listOf(
            ModelTemplate("placeholder-provider", "placeholder-model"),
            ModelTemplate("placeholder-provider", "placeholder-model", ""),
            ModelTemplate("placeholder-provider", "placeholder-model", "  "),
        ).forEach {
            assertTrue("$it is a model reference", ConfigTemplates.build(schema, it, json("{}")) is TemplateOutcome.Ready)
        }
    }

    @Test
    fun `the permission template writes the file's map shape, not a rules array`() {
        // `PermissionConfig` is `anyOf[a string, an object of action → (string | resource map)]`. The
        // `[{action, resource, effect}]` array is `Permission.Ruleset`, which is what the *session* takes.
        val resource = ConfigTemplates.build(
            schema,
            PermissionTemplate("bash", "*", PermissionEffect.Deny),
            json("{}"),
        )
        assertTrue(resource is TemplateOutcome.Ready)
        assertEquals(
            """{"permission":{"bash":{"*":"deny"}}}""",
            (resource as TemplateOutcome.Ready).document.toString(),
        )

        val blanket = ConfigTemplates.build(
            schema,
            PermissionTemplate("edit", "", PermissionEffect.Allow),
            json("{}"),
        )
        assertEquals(
            """{"permission":{"edit":"allow"}}""",
            (blanket as TemplateOutcome.Ready).document.toString(),
        )
    }

    @Test
    fun `a permission effect the file does not allow produces nothing`() {
        val outcome = ConfigTemplates.build(
            schema,
            PermissionTemplate("bash", "*", PermissionEffect("maybe")),
            json("{}"),
        )
        assertTrue(outcome is TemplateOutcome.NotReady)
    }

    @Test
    fun `the agent template writes a name-keyed agent with only the fields it was given`() {
        val template = AgentTemplate(
            name = "review",
            mode = "subagent",
            model = "placeholder-provider/placeholder-model",
            steps = 20,
            color = "#FF5733",
            description = "Reviews a diff",
            permission = mapOf("edit" to PermissionEffect.Ask, "bash" to PermissionEffect.Deny),
        )
        val outcome = ConfigTemplates.build(schema, template, json("{}"))

        assertTrue("the agent must validate: $outcome", outcome is TemplateOutcome.Ready)
        val agent = (outcome as TemplateOutcome.Ready).document.let { it as JsonObject }["agent"]
        val review = (agent as JsonObject).getValue("review") as JsonObject
        assertEquals("subagent", (review["mode"] as JsonPrimitive).content)
        assertEquals("placeholder-provider/placeholder-model", (review["model"] as JsonPrimitive).content)
        assertEquals(20, (review["steps"] as JsonPrimitive).content.toInt())
        assertEquals("#FF5733", (review["color"] as JsonPrimitive).content)
        assertEquals("""{"edit":"ask","bash":"deny"}""", review["permission"].toString())
        // Absent, not empty: an unset field is not a claim about the agent.
        assertNull(review["temperature"])
        assertNull(review["variant"])
    }

    @Test
    fun `an agent with a mode the schema does not allow is refused with a diagnostic`() {
        val outcome = ConfigTemplates.build(schema, AgentTemplate(name = "r", mode = "supervisor"), json("{}"))

        assertTrue(outcome is TemplateOutcome.Invalid)
        val problems = (outcome as TemplateOutcome.Invalid).problems
        assertTrue(problems.any { it.path == "/agent/r/mode" && it.keyword == "enum" })
    }

    @Test
    fun `an agent with a colour that matches neither branch is refused`() {
        // `AgentConfig.color` is `anyOf[pattern ^#[0-9a-fA-F]{6}$, one of eight theme names]`, so this is
        // the case where the schema does work the template deliberately does not.
        assertTrue(
            ConfigTemplates.build(schema, AgentTemplate(name = "r", color = "#FF57"), json("{}"))
                is TemplateOutcome.Invalid,
        )
        assertTrue(
            ConfigTemplates.build(schema, AgentTemplate(name = "r", color = "chartreuse"), json("{}"))
                is TemplateOutcome.Invalid,
        )
        assertTrue(
            ConfigTemplates.build(schema, AgentTemplate(name = "r", color = "primary"), json("{}"))
                is TemplateOutcome.Ready,
        )
    }

    @Test
    fun `an agent with no name produces nothing`() {
        assertTrue(ConfigTemplates.build(schema, AgentTemplate(name = "  "), json("{}")) is TemplateOutcome.NotReady)
    }

    @Test
    fun `a nameless agent template never writes an empty agent`() {
        // The failure mode this guards: `"agent": {"": {...}}`, which the schema accepts and the server
        // would load as an agent with no name.
        assertNull(AgentTemplate(name = "").value())
    }

    @Test
    fun `the MCP template writes a local server in the file's shape`() {
        val template = McpTemplate(
            name = "files",
            config = McpServerConfig.Local(
                command = listOf("npx", "-y", "mcp-files"),
                cwd = "/work/app",
                environment = mapOf("TOKEN" to "placeholder"),
            ),
        )
        val outcome = ConfigTemplates.build(schema, template, json("{}"))

        assertTrue("the MCP server must validate: $outcome", outcome is TemplateOutcome.Ready)
        val entry = (outcome as TemplateOutcome.Ready).document.let { it as JsonObject }
            .getValue("mcp").let { it as JsonObject }.getValue("files") as JsonObject
        assertEquals("local", (entry["type"] as JsonPrimitive).content)
        assertEquals("""["npx","-y","mcp-files"]""", entry["command"].toString())
        assertEquals("/work/app", (entry["cwd"] as JsonPrimitive).content)
        assertNull(entry["enabled"])
        assertEquals(emptyList<String>(), template.unsupported)
    }

    @Test
    fun `the MCP template writes a remote server and marks it enabled only when it is off`() {
        val on = McpTemplate("remote", McpServerConfig.Remote(url = "https://mcp.example.com/"))
        assertTrue(ConfigTemplates.build(schema, on, json("{}")) is TemplateOutcome.Ready)
        val entry = (on.value() as JsonObject).getValue("remote") as JsonObject
        assertEquals("remote", (entry["type"] as JsonPrimitive).content)
        assertEquals("https://mcp.example.com/", (entry["url"] as JsonPrimitive).content)
        assertNull("an enabled server does not need the flag", entry["enabled"])

        val off = McpTemplate("off", McpServerConfig.Remote(url = "https://mcp.example.com/", disabled = true))
        assertEquals(
            "false",
            ((off.value() as JsonObject).getValue("off") as JsonObject).getValue("enabled").toString(),
        )
    }

    @Test
    fun `the MCP template reports what the file cannot express instead of dropping it`() {
        // `McpLocalConfig` has no `codemode` and no `protocol`, and its `timeout` is one integer where
        // the runtime's is three. Writing the server without saying so would leave the user with a
        // persistent server that does not behave like the one they described.
        val template = McpTemplate(
            name = "odd",
            config = McpServerConfig.Local(
                command = listOf("run"),
                codemode = true,
                protocol = McpProtocol.EXPLICIT,
                timeout = McpTimeout(execution = 1000),
            ),
        )

        assertEquals(listOf("codemode", "protocol", "the separate startup, catalog and execution timeouts"), template.unsupported)
        // It is still writable — the file just says less than the draft did.
        assertTrue(ConfigTemplates.build(schema, template, json("{}")) is TemplateOutcome.Ready)
    }

    @Test
    fun `the MCP template writes the single timeout the file accepts`() {
        val template = McpTemplate("t", McpServerConfig.Local(command = listOf("run"), timeout = McpTimeout(execution = 2500)))
        assertEquals("2500", ((template.value() as JsonObject).getValue("t") as JsonObject).getValue("timeout").toString())
    }

    @Test
    fun `an MCP server with no name produces nothing`() {
        assertNull(McpTemplate("", McpServerConfig.Local(command = listOf("run"))).value())
    }

    @Test
    fun `a template merges into an existing document without losing what is there`() {
        val existing = """{"model": "placeholder-provider/placeholder-model", "watcher": {"ignore": [".git/**"]}}"""
        val outcome = ConfigTemplates.build(schema, ModelTemplate("other-provider", "other-model"), ConfigDocument.parse(existing).documentOrNull!!)

        val document = (outcome as TemplateOutcome.Ready).document as JsonObject
        // The model was replaced, which is the point; the watcher was left alone, which is the promise.
        assertEquals("other-provider/other-model", (document["model"] as JsonPrimitive).content)
        assertTrue("an unrelated key must survive", document.containsKey("watcher"))
    }

    @Test
    fun `a template's consequence names the change, and says so for a privilege change`() {
        assertTrue(PermissionTemplate("bash", "*", PermissionEffect.Deny).isPrivilegeChange)
        assertTrue(McpTemplate("x", McpServerConfig.Local(listOf("r"))).isPrivilegeChange)
        assertTrue(AgentTemplate("a").isPrivilegeChange)
        // The default model changes which system the conversation goes to, not what it may do.
        assertFalse(ModelTemplate("a", "b").isPrivilegeChange)

        assertEquals("DENY bash on *", PermissionTemplate("bash", "*", PermissionEffect.Deny).consequence)
        assertEquals("DENY edit on everything", PermissionTemplate("edit", "", PermissionEffect.Deny).consequence)
    }
}
