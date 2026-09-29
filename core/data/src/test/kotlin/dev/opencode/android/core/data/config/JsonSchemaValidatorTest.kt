package dev.opencode.android.core.data.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The validator, against the **real** vendored schema.
 *
 * Every document here is a plausible `opencode.jsonc`, and each one is checked for the diagnostic it
 * should produce — not merely that it is rejected. The exit criterion asks for "useful diagnostics at
 * a useful location", and a test that only asserted `isValid == false` would pass for a validator that
 * rejects everything with an empty message.
 */
class JsonSchemaValidatorTest {

    private val schema = VendoredSchema.schema()
    private val validator = schema.validator()

    // ------------------------------------------------------------------------------ accepted

    @Test
    fun `an empty object is valid, because that is a configuration with nothing set`() {
        assertTrue(validator.isValid(json("{}")))
    }

    @Test
    fun `a document using several of the file's real keys is valid`() {
        val document = json(
            """
            {
              "${'$'}schema": "https://opencode.ai/config.json",
              "model": "placeholder-provider/placeholder-model",
              "small_model": "placeholder-provider/small-placeholder-model",
              "default_agent": "build",
              "share": "disabled",
              "autoupdate": "notify",
              "snapshot": true,
              "logLevel": "WARN",
              "shell": "/bin/bash",
              "username": "someone",
              "subagent_depth": 3,
              "instructions": ["AGENTS.md", "docs/style.md"],
              "plugin": ["opencode-plugin-hooks"],
              "enabled_providers": ["placeholder-provider"],
              "disabled_providers": ["ollama"],
              "tool_output": { "max_lines": 400, "max_bytes": 40000 },
              "watcher": { "ignore": [".git/**"] },
              "experimental": { "batch_tool": true, "mcp_timeout": 5000 },
              "compaction": { "auto": true },
              "mcp": { "linear": { "type": "remote", "url": "https://mcp.example.com/", "enabled": true } },
              "agent": { "review": { "mode": "subagent", "steps": 20, "color": "#FF5733" } },
              "permission": { "bash": { "*": "ask" } },
              "formatter": { "prettier": { "extensions": ["ts"] } },
              "provider": { "llama": { "options": { "baseURL": "http://127.0.0.1:11434/v1" } } },
              "enterprise": { "url": "https://example.com" },
              "skills": { "paths": ["./skills"] },
              "references": { "docs": { "path": "./docs" }, "upstream": { "repository": "https://example.com/repo" } },
              "command": { "deploy": { "template": "Deploy ${'$'}ARGUMENTS", "agent": "build" } },
              "lsp": false
            }
            """,
        )
        val problems = validator.validate(document)

        assertEquals("a document made of real keys must validate: $problems", emptyList<SchemaDiagnostic>(), problems)
    }

    @Test
    fun `both spellings of a permission block are accepted`() {
        // `PermissionConfig` is an `anyOf` of a blanket action string and an object map; both are legal
        // and the guided template writes the second.
        assertTrue(validator.isValid(json("""{"permission": {"bash": "ask"}}""")))
        assertTrue(validator.isValid(json("""{"permission": {"bash": {"*": "deny"}}}""")))
        assertTrue(validator.isValid(json("""{"permission": {"edit": "allow"}}""")))
    }

    @Test
    fun `both MCP connection types are accepted`() {
        assertTrue(validator.isValid(json("""{"mcp": {"x": {"type": "local", "command": ["npx", "server"]}}}""")))
        assertTrue(validator.isValid(json("""{"mcp": {"x": {"type": "remote", "url": "https://example.com"}}}""")))
        // `{"enabled": …}` is the third branch: an entry that only switches a server off.
        assertTrue(validator.isValid(json("""{"mcp": {"x": {"enabled": false}}}""")))
    }

    // ------------------------------------------------------------------------------ rejected

    @Test
    fun `an unknown top-level key is rejected, naming the key`() {
        // `Config` is `additionalProperties: false`, so this is a typo the server would ignore and the
        // user would never notice. The diagnostic has to name the key or it is not actionable.
        val problems = validator.validate(json("""{"modle": "x/y"}"""))

        assertEquals(1, problems.size)
        assertEquals("/modle", problems.single().path)
        assertEquals("additionalProperties", problems.single().keyword)
        assertTrue(problems.single().expected.contains("allows"))
    }

    @Test
    fun `a wrong type is rejected at the key that has it`() {
        val problems = validator.validate(json("""{"snapshot": "yes"}"""))

        assertEquals(1, problems.size)
        assertEquals("/snapshot", problems.single().path)
        assertEquals("type", problems.single().keyword)
        assertEquals("a boolean", problems.single().expected)
    }

    @Test
    fun `a value outside an enum names the options`() {
        val problems = validator.validate(json("""{"share": "sometimes"}"""))

        assertEquals(1, problems.size)
        assertEquals("/share", problems.single().path)
        assertEquals("enum", problems.single().keyword)
        assertTrue(problems.single().expected.contains("manual"))
        assertTrue(problems.single().expected.contains("disabled"))
    }

    @Test
    fun `a missing required key inside a nested object is reported at that key`() {
        val problems = validator.validate(json("""{"mcp": {"x": {"type": "remote"}}}"""))

        val required = problems.filter { it.keyword == "required" }
        assertEquals(1, required.size)
        assertEquals("/mcp/x/url", required.single().path)
        assertTrue(required.single().expected.contains("url"))
    }

    @Test
    fun `a numeric bound is enforced, and an integer is not a decimal`() {
        // `experimental.mcp_timeout` is an integer with `exclusiveMinimum: 0`.
        assertTrue(validator.isValid(json("""{"experimental": {"mcp_timeout": 5000}}""")))
        val zero = validator.validate(json("""{"experimental": {"mcp_timeout": 0}}"""))
        assertEquals(listOf("exclusiveMinimum"), zero.map { it.keyword })
        val fraction = validator.validate(json("""{"experimental": {"mcp_timeout": 1.5}}"""))
        assertEquals(listOf("type"), fraction.map { it.keyword })
    }

    @Test
    fun `a pattern is enforced on the agent's colour`() {
        val good = validator.validate(json("""{"agent": {"r": {"color": "#FF5733"}}}"""))
        assertSameProblems(emptyList(), good)
        val named = validator.validate(json("""{"agent": {"r": {"color": "primary"}}}"""))
        assertSameProblems(emptyList(), named)

        val bad = validator.validate(json("""{"agent": {"r": {"color": "#FF57"}}}"""))
        assertTrue(bad.isNotEmpty())
        assertTrue(bad.any { it.keyword == "pattern" || it.keyword == "enum" })
    }

    @Test
    fun `an unknown key inside a nested object is rejected`() {
        val problems = validator.validate(json("""{"watcher": {"ignroe": []}}"""))

        assertTrue(problems.isNotEmpty())
        assertTrue(problems.any { it.path == "/watcher/ignroe" && it.keyword == "additionalProperties" })
    }

    @Test
    fun `every problem is reported, not only the first`() {
        // One round trip per save on a phone: a file with three mistakes must not take three saves.
        val problems = validator.validate(json("""{"modle": "x/y", "snapshot": "yes", "share": "maybe"}"""))

        assertEquals(3, problems.size)
        assertEquals(
            listOf("/modle", "/share", "/snapshot"),
            problems.map { it.path }.sortedBy { it },
        )
    }

    @Test
    fun `an anyOf failure says which shapes were wanted and keeps the closest complaint`() {
        // `permission` accepts a blanket string or an object map. A number is neither, and the
        // diagnostic has to say so *and* name the specific problem with the closest branch.
        val problems = validator.validate(json("""{"permission": 7}"""))

        val anyOf = problems.first { it.keyword == "anyOf" }
        assertEquals("/permission", anyOf.path)
        assertTrue(anyOf.expected.startsWith("one of:"))
        assertTrue(
            "the closest branch's own diagnostic should be kept",
            problems.any { it.keyword == "type" && it.path == "/permission" },
        )
    }

    // ------------------------------------------------------------------------------ the external ref

    @Test
    fun `an external reference is skipped but its siblings are still applied`() {
        // `model` is `{"type": "string", "$ref": "https://models.dev/model-schema.json#/$defs/Model"}`.
        // A validator that followed the `$ref` exclusively would apply no check at all to the one key
        // the guided default-model template writes.
        assertSameProblems(emptyList(), validator.validate(json("""{"model": "placeholder-provider/placeholder-model"}""")))

        val wrong = validator.validate(json("""{"model": 42}"""))
        assertEquals(listOf("type"), wrong.map { it.keyword })

        val alsoWrong = validator.validate(json("""{"small_model": true}"""))
        assertEquals(listOf("type"), alsoWrong.map { it.keyword })

        assertTrue(
            "the skipped reference must be reported rather than hidden",
            validator.unresolvedRefs.any { it.contains("models.dev") },
        )
    }

    // ------------------------------------------------------------------------------ secrecy

    @Test
    fun `a diagnostic never carries the value it rejected`() {
        // The point of describing rather than quoting. The rejected value here *is* shaped like a
        // credential — which is the case that matters, because it is the one where a naive
        // "expected one of ..." message would print the key the user had just typed.
        val secret = "sk-ant-placeholder-not-a-real-key"
        val problems = validator.validate(json("""{"share": "$secret"}"""))

        assertEquals(1, problems.size)
        assertEquals("enum", problems.single().keyword)
        assertFalse("the rejected value leaked", problems.single().found.contains(secret))
        assertFalse("the rejected value leaked", problems.single().toString().contains(secret))
        assertEquals("a string of ${secret.length} characters", problems.single().found)
        // The *allowed* values come from the schema and are printed in full, which is what makes the
        // message actionable rather than merely correct.
        assertEquals("one of manual, auto, disabled", problems.single().expected)
    }

    @Test
    fun `a secret elsewhere in the document does not reach any diagnostic`() {
        // `provider.*.options` is an open object, so a document carrying a key is rejected only by
        // something else. A secret sitting in the tree must not ride along in the message about it.
        val secret = "ghp_placeholdernotarealkeyvalue"
        val problems = validator.validate(
            json("""{"enterprise": {"url": 42}, "provider": {"llama": {"options": {"apiKey": "$secret"}}}}"""),
        )

        assertTrue("the document must be rejected", problems.isNotEmpty())
        assertTrue(problems.any { it.path == "/enterprise/url" })
        assertFalse(problems.toString().contains(secret))
    }

    @Test
    fun `a diagnostic describes an object by its size and not its contents`() {
        val problems = validator.validate(
            json("""{"watcher": {"ignore": ["secret-one", "secret-two"], "extra": 1}}"""),
        )
        val extra = problems.single { it.path == "/watcher/extra" }
        assertFalse(extra.found.contains("secret-one"))
        assertFalse(problems.toString().contains("secret-one"))
    }

    // ------------------------------------------------------------------------------ diagnostics shape

    @Test
    fun `a diagnostic path is a JSON Pointer, with a tilde and a slash escaped`() {
        // RFC 6901: a `~` is `~0` and a `/` is `~1`, so a key containing either does not corrupt the
        // path of every key after it.
        val problems = validator.validate(json("""{"agent": {"a/b": {"steps": -1}, "c~d": {"steps": -1}}}"""))

        val paths = problems.map { it.path }
        assertTrue(paths.contains("/agent/a~1b/steps"))
        assertTrue(paths.contains("/agent/c~0d/steps"))
    }

    @Test
    fun `the same document is validated the same way twice`() {
        // A validator that accumulated state would give a different answer the second time, and the
        // editor re-validates on every keystroke.
        val document = json("""{"model": 42, "share": "maybe"}""")
        assertEquals(validator.validate(document), validator.validate(document))
    }

    @Test
    fun `an array item is reported by its index`() {
        val problems = validator.validate(json("""{"instructions": ["a.md", 7]}"""))

        assertTrue(problems.isNotEmpty())
        assertTrue(problems.any { it.path == "/instructions/1" })
    }

    @Test
    fun `the schema is a real draft 2020-12 document and every local ref resolves`() {
        val tree = VendoredSchema.tree()
        val validator = JsonSchemaValidator(tree as kotlinx.serialization.json.JsonObject)
        val problems = validator.validate(json("""{"share": "nope"}"""))

        assertEquals(1, problems.size)
        assertTrue("only an external ref may be unresolved", validator.unresolvedRefs.all { it.startsWith("http") })
        assertNotNull(validator.unresolvedRefs.filter { it.startsWith("http") })
    }

    private fun assertSameProblems(
        expected: List<SchemaDiagnostic>,
        actual: List<SchemaDiagnostic>,
    ) = assertEquals(expected.map { it.path to it.keyword }, actual.map { it.path to it.keyword })
}
