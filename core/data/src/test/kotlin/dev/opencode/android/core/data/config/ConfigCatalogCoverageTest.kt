package dev.opencode.android.core.data.config

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The plan's first exit criterion, made exhaustive: **every top-level configuration key is visible
 * with its source.**
 *
 * The claim is checkable because the key list has an authority that is not this client's memory — the
 * vendored `config.schema.json`. These tests read that file and compare the explorer's answer against
 * it in both directions:
 *
 *  - **every** key the schema declares has a row, in the schema's own order;
 *  - **no** row exists for a name the schema does not declare, so a stale hardcoded spelling cannot
 *    hide behind a passing subset check;
 *  - every key's type, enum and description are the schema's own, so a row cannot describe a key
 *    wrongly;
 *  - every projection name the server can send is claimed by exactly one file key, and every claim is
 *    a real property of `Config.InfoEncoded` — which is what stops a future server from adding a
 *    projection the explorer silently ignores;
 *  - the keys with **no** projection are exactly the set the code names, so "the server does not
 *    report this key" is a checked statement rather than a hope.
 */
class ConfigCatalogCoverageTest {

    private val schema = VendoredSchema.schema()
    private val root = VendoredSchema.root()

    // ------------------------------------------------------------------------------ row coverage

    @Test
    fun `every top-level key of the vendored schema has exactly one row, in the schema's order`() {
        val expected = topLevelPropertyNames(root)
        val rows = ConfigExplorer.rows(schema, emptyList())

        assertEquals(expected.size, rows.size)
        assertEquals(
            "the explorer must have one row per schema key, in the schema's order",
            expected,
            rows.map { it.key.key },
        )
    }

    @Test
    fun `a row exists for every key even when no document sets it and none is reported`() {
        val rows = ConfigExplorer.rows(schema, emptyList())

        assertTrue(rows.isNotEmpty())
        rows.forEach { row ->
            assertFalse("${row.key.key} must not claim to be set", row.value.isSet)
            assertFalse("${row.key.key} must not claim to be reported", row.isReported)
            assertFalse("${row.key.key} must not claim to be overridden", row.isOverridden)
            assertNull(row.effective)
            assertFalse(row.hasContent)
        }
    }

    @Test
    fun `no row is produced for a projection name the file schema does not declare`() {
        // The schema's key names are singular where the projection's are plural, and three are renamed
        // outright. If the explorer read the projection's vocabulary these would be the names it
        // produced, and a user would be shown a key they cannot type into their file.
        val rows = ConfigExplorer.rows(schema, emptyList()).map { it.key.key }
        listOf(
            "permissions", "agents", "snapshots", "commands", "plugins", "providers", "update", "media",
        ).forEach { assertFalse("$it is a projection name, not a file key", rows.contains(it)) }
    }

    // ------------------------------------------------------------------------------ the projection

    @Test
    fun `the keys with no projection are exactly the set the code names`() {
        val fromSchema = topLevelPropertyNames(root).filterNot { ConfigSchema.PROJECTION_NAMES.containsKey(it) }
        assertEquals(
            "a key that gained or lost a projection must be declared in ConfigSchema",
            ConfigSchema.UNPROJECTED_KEYS,
            fromSchema.toSet(),
        )
    }

    @Test
    fun `every projection name is a real property of Config InfoEncoded`() {
        val info = VendoredSpec.propertiesOf("Config.InfoEncoded")
        ConfigSchema.PROJECTION_NAMES.forEach { (fileKey, projectionName) ->
            assertTrue(
                "$projectionName is claimed for $fileKey but is not a property of Config.InfoEncoded",
                info.containsKey(projectionName),
            )
        }
    }

    @Test
    fun `every property of Config InfoEncoded is claimed by exactly one file key`() {
        val info = VendoredSpec.propertiesOf("Config.InfoEncoded")
        val claimed = ConfigSchema.PROJECTION_NAMES.values
        assertEquals(
            "Config.InfoEncoded has properties the explorer's mapping does not name",
            emptySet<String>(),
            info.keys - claimed,
        )
        assertEquals("a projection name may not be claimed twice", claimed.size, claimed.toSet().size)
    }

    // ------------------------------------------------------------------------------ per-key detail

    @Test
    fun `each key's type, description and enum are the schema's own`() {
        // Chosen because each is spelled differently in the schema, so a parser that read one shape
        // only would get one of these wrong.
        assertType("shell", "string")
        assertType("share", "string")
        assertType("snapshot", "boolean")
        assertType("subagent_depth", "integer")
        assertType("instructions", "array")
        assertType("tool_output", "object")
        assertType("mcp", "object")
        assertType("server", "a named shape")
        assertType("layout", "a named shape")
        assertType("formatter", "one of several shapes")
        assertType("permission", "a named shape")

        assertEquals(listOf("manual", "auto", "disabled"), schema.key("share")?.allowedValues)
        // `logLevel` has no enum of its own — it is `{"$ref": "#/$defs/LogLevel"}` — so this also
        // proves the catalogue follows a local reference to find one.
        assertEquals(listOf("DEBUG", "INFO", "WARN", "ERROR"), schema.key("logLevel")?.allowedValues)
        assertEquals(listOf("auto", "stretch"), schema.key("layout")?.allowedValues)
        // `mode`'s values are `AgentConfig`s, which have no enum of their own at the key's level.
        assertEquals(emptyList<String>(), schema.key("mode")?.allowedValues)

        assertEquals("Default shell to use for terminal and bash tool", schema.key("shell")?.description)
        assertEquals(
            "Default agent to use when none is specified. Must be a primary agent. " +
                "Falls back to 'build' if not set or if the specified agent is invalid.",
            schema.key("default_agent")?.description,
        )
        assertEquals(
            "Small model to use for tasks like title generation in the format of provider/model",
            schema.key("small_model")?.description,
        )
    }

    @Test
    fun `a key with additionalProperties false is marked as not accepting anything`() {
        assertFalse(schema.key("experimental")!!.acceptsAnyValue)
        assertFalse(schema.key("tool_output")!!.acceptsAnyValue)
        assertFalse(schema.key("watcher")!!.acceptsAnyValue)
        // `agent` is `additionalProperties: {$ref: AgentConfig}` — it accepts values but only of one
        // shape, so the flag says "not anything" and the type says what.
        assertFalse(schema.key("agent")!!.acceptsAnyValue)
        assertTrue(schema.key("shell")!!.acceptsAnyValue)
        assertTrue(schema.key("instructions")!!.acceptsAnyValue)
    }

    @Test
    fun `the four templated keys are the plan's common edits`() {
        val templated = schema.keys.filter { it.hasTemplate }.map { it.key }
        // The schema's order, not the hand-written one: the point is the *set* of four, and the
        // registry's order is a presentation detail the test does not need to police.
        assertEquals(4, templated.size)
        assertEquals(ConfigTemplates.keys.toSet(), templated.toSet())
    }

    @Test
    fun `the privilege-changing keys include the four the confirmation must name`() {
        val privileged = schema.keys.filter { it.isPrivilegeChange }.map { it.key }.toSet()
        listOf("mcp", "permission", "agent", "experimental", "model").forEach {
            assertTrue("$it changes what the agent may do", privileged.contains(it))
        }
        // A key that only changes presentation must not be on the list: if every write were announced
        // as a privilege change, the user would learn to click through the ones that are.
        listOf("watcher", "tool_output", "logLevel", "username", "formatter").forEach {
            assertFalse("$it is not a privilege change", privileged.contains(it))
        }
    }

    @Test
    fun `experimental is the one key the explorer shows read-only`() {
        // Its `policies` array is the server's resource grants and there is no route that writes one,
        // so the row links to the file rather than offering an editor.
        assertEquals(
            listOf("experimental"),
            schema.keys.filter { ConfigRow(it, dev.opencode.android.core.data.config.ConfigValue(null, null, emptyList(), emptyList()), null, false).isReadOnly }
                .map { it.key },
        )
    }

    private fun assertType(key: String, type: String) {
        assertEquals("the declared type of $key", type, schema.key(key)?.typeName)
    }
}

/**
 * The drift test that makes the subset validator trustworthy.
 *
 * **It walks the real vendored schema and fails on any assertion keyword the validator does not
 * implement.** Ignoring an annotation (`description`, `hidden`) is correct; ignoring an assertion
 * (`not`, `if`, `dependentRequired`) would be a hole through which an invalid document would be
 * accepted, silently. The two constants list what this build knows, and this test is what makes those
 * lists honest rather than aspirational.
 */
class UnsupportedKeywordTest {

    private val root = VendoredSchema.root()

    @Test
    fun `the vendored schema uses no assertion keyword the validator does not implement`() {
        val unsupported = SchemaKeywords.used(root) -
            JsonSchemaValidator.ASSERTION_KEYWORDS -
            JsonSchemaValidator.ANNOTATION_KEYWORDS

        assertEquals(
            "implement the keyword and add it to JsonSchemaValidator, or add it to ANNOTATION_KEYWORDS: $unsupported",
            emptySet<String>(),
            unsupported,
        )
    }

    @Test
    fun `the assertions the schema uses are the ones the constants claim`() {
        // A floor rather than an exact list, because the walk may reach keywords through definitions
        // this build does not exercise. The exact claim is the test above: nothing the file uses is
        // missing from the constants.
        val used = SchemaKeywords.used(root)
        assertTrue(
            "the validator must implement the keywords this schema leans on",
            used.count { it in JsonSchemaValidator.ASSERTION_KEYWORDS } >= 8,
        )
    }

    @Test
    fun `the schema's own extension flags are the ones Jsonc exists for`() {
        assertEquals(
            "allowComments is what makes comment masking necessary",
            true,
            (root["allowComments"] as? JsonPrimitive)?.booleanOrNull,
        )
        assertEquals(
            true,
            (root["allowTrailingCommas"] as? JsonPrimitive)?.booleanOrNull,
        )
    }

    @Test
    fun `the walker follows the schema's local references, so no keyword hides behind one`() {
        // The whole value of the keyword walk is that it reaches every part of the file. A walker that
        // stopped at the first `$ref` would report a clean sheet over the twenty definitions it never
        // looked at, so this counts the references it resolved.
        assertTrue(
            "the schema is ref-heavy, so the walker must have followed them",
            SchemaKeywords.walk(root).localRefs > 30,
        )
    }
}
