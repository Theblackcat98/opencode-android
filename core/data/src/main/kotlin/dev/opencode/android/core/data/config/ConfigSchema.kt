package dev.opencode.android.core.data.config

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * One top-level key of the configuration file schema, and everything the explorer says about it.
 *
 * **[key] is the file's own name**, which is the name a user writes in `opencode.jsonc`. The runtime
 * projection spells ten of the thirty-six differently (`permission`/`permissions`,
 * `agent`/`agents`, …), and [projectionName] is the bridge; a screen that showed the projection's
 * name for a key the user has to type would be naming a key that does not exist in their file.
 */
data class ConfigKey(
    val key: String,
    /** The name `Config.InfoEncoded` uses for this key, or `null` when it reports none. */
    val projectionName: String?,
    /** The declared type, for a row's subtitle. A `$ref` or an `anyOf` shows as its own words. */
    val typeName: String,
    /** The schema's `description`, which is the only human explanation that exists. */
    val description: String?,
    /** The schema marks this key `hidden`, which means the TUI does not surface it either. */
    val hidden: Boolean,
    /** Whether the schema accepts anything at all under this key, read from `additionalProperties`. */
    val acceptsAnyValue: Boolean,
    /** The enum the key is restricted to, or an empty list. */
    val allowedValues: List<String>,
) {
    /**
     * Whether a change to this key changes how the agent behaves.
     *
     * **This is what the write confirmation is built from (plan §5.2).** `model`, `permission`,
     * `agent`, `experimental.policies`, `mcp` and `instructions` decide what the agent may do; a
     * cosmetic key does not, and telling a user that every key is a privilege change trains them to
     * click through the ones that are.
     */
    val isPrivilegeChange: Boolean get() = key in PRIVILEGE_KEYS

    /** Whether the guided templates can edit this key, which is where the exit criteria live. */
    val hasTemplate: Boolean get() = key in TEMPLATED_KEYS

    private companion object {
        /**
         * The keys whose value decides what the agent is allowed to do, or who it talks to.
         *
         * `agent` because an agent's `permission` block and system prompt are the two most powerful
         * things in a configuration file; `permission` because it is the ruleset itself; `mcp`
         * because adding a server adds tools the agent can call; `experimental` because its
         * `policies` are resource grants; `instructions` and `model` because the first changes what
         * the agent is told and the second changes which system processes the conversation.
         */
        val PRIVILEGE_KEYS: Set<String> = setOf(
            "agent", "permission", "mcp", "experimental", "instructions", "model", "mode",
            "plugin", "skills", "small_model", "default_agent",
        )

        /**
         * The keys a guided template can write: an MCP server, a permission rule, the default model
         * and a new agent (plan §6, "Guided templates").
         */
        val TEMPLATED_KEYS: Set<String> = setOf("mcp", "permission", "model", "agent")
    }
}

/**
 * The vendored `api/opencode-2.0.x/config.schema.json`, parsed into what the explorer needs.
 *
 * **The file is the source of truth and nothing here is hardcoded.** Every top-level key, its type,
 * its description, its enum and its projection name are read from the schema at construction, so a
 * new key in a future `opencode` appears as a row rather than needing a code change — and
 * `ConfigCatalogCoverageTest` fails if the client's mapping table stops covering the file's keys,
 * which is what keeps "every top-level config key is visible" a checked statement.
 *
 * **[keys] is every top-level key, in the schema's own order.** Nothing filters it. A key the
 * projection does not report is still a key, and a key marked `hidden` is still a key; filtering
 * either would make the explorer's coverage a judgement call rather than a fact.
 */
class ConfigSchema(
    /** The parsed schema, which is also what the validator is built from. */
    val json: JsonObject,
) {
    val keys: List<ConfigKey> = readTopLevelKeys()

    private val byName: Map<String, ConfigKey> = keys.associateBy { it.key }

    /** The key [name], or `null` when the schema has no such top-level key. */
    fun key(name: String): ConfigKey? = byName[name]

    /** Whether [name] is a top-level key of the configuration file. */
    fun has(name: String): Boolean = byName.containsKey(name)

    /** A validator for this schema. */
    fun validator(): JsonSchemaValidator = JsonSchemaValidator(json)

    /** The schema's `$ref` root, resolved, which is where `Config` lives. */
    val configRoot: JsonObject
        get() = ((json["\$ref"] as? JsonPrimitive)?.contentOrNull?.let { ref ->
            ref.removePrefix("#/\$defs/").takeIf { ref.startsWith("#/\$defs/") }?.let { name -> json.definitions()[name] }
        } as? JsonObject) ?: JsonObject(emptyMap())

    private fun readTopLevelKeys(): List<ConfigKey> {
        val properties = configRoot["properties"] as? JsonObject ?: return emptyList()
        return properties.map { (name, rule) -> describe(name, rule) }
    }

    private fun describe(name: String, rule: JsonElement): ConfigKey {
        val schema = rule as? JsonObject
        val names = PROJECTION_NAMES[name]
        return ConfigKey(
            key = name,
            projectionName = names,
            typeName = typeOf(schema),
            description = (schema?.get("description") as? JsonPrimitive)?.contentOrNull,
            hidden = (schema?.get("hidden") as? JsonPrimitive)?.booleanOrNull ?: false,
            // Absent means "anything", which is the JSON Schema default. A schema — or a `false` —
            // means only some values are acceptable, and `agent`'s `additionalProperties: AgentConfig`
            // is the case that matters: it accepts values, but only of one shape.
            acceptsAnyValue = when (val additional = schema?.get("additionalProperties")) {
                null -> true
                is JsonPrimitive -> additional.booleanOrNull ?: true
                else -> false
            },
            allowedValues = enumOf(schema),
        )
    }

    private fun typeOf(schema: JsonObject?): String {
        if (schema == null) return "any"
        val type = schema["type"]
        if (type is JsonPrimitive) return type.contentOrNull ?: "any"
        if (type is JsonArray) return type.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.joinToString(" or ")
        if (schema["anyOf"] is JsonArray) return "one of several shapes"
        if (schema["\$ref"] != null) return "a named shape"
        if (schema["enum"] is JsonArray) return "one of a fixed set"
        return "any"
    }

    /**
     * The enum a key is restricted to, following a local `$ref` when the key has one of its own.
     *
     * **`logLevel` is the case that makes this worth doing.** Its rule is `{"$ref": "#/$defs/LogLevel",
     * "description": …}` with no enum beside it, so a row that read only the rule would say "any value"
     * for the one key in the file whose whole content is four names. An external `$ref` is not followed,
     * because it is not in the file and a network fetch on a LAN is not an option.
     */
    private fun enumOf(schema: JsonObject?): List<String> {
        val own = (schema?.get("enum") as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            .orEmpty()
        if (own.isNotEmpty()) return own
        val ref = (schema?.get("\$ref") as? JsonPrimitive)?.contentOrNull ?: return emptyList()
        if (!ref.startsWith("#/\$defs/")) return emptyList()
        val target = json.definitions()[ref.removePrefix("#/\$defs/")] as? JsonObject ?: return emptyList()
        return (target["enum"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
    }

    companion object {
        /**
         * The file key to the projection name, for the twenty-five keys `Config.InfoEncoded` reports
         * under a different spelling.
         *
         * **Written out rather than derived, because most of it cannot be derived.** `permission`
         * becomes `permissions` and `snapshot` becomes `snapshots`, but `autoupdate` becomes `update`,
         * `attachment` becomes `media` and `formatter` and `lsp` keep their names while changing what
         * they hold — a pluralisation rule gets the first two right and the other three wrong, which
         * is worse than a table someone can check against the spec. `UnsupportedProjectionNameTest`
         * fails if a name here is not a real property of `Config.InfoEncoded`, and
         * `ConfigCatalogCoverageTest` fails if a projected key has no entry, so neither direction can
         * rot unnoticed.
         */
        val PROJECTION_NAMES: Map<String, String> = mapOf(
            // Same name in both vocabularies.
            "\$schema" to "\$schema",
            "shell" to "shell",
            "model" to "model",
            "default_agent" to "default_agent",
            "share" to "share",
            "enterprise" to "enterprise",
            "username" to "username",
            "formatter" to "formatter",
            "lsp" to "lsp",
            "tool_output" to "tool_output",
            "mcp" to "mcp",
            "compaction" to "compaction",
            "skills" to "skills",
            "instructions" to "instructions",
            "watcher" to "watcher",
            "references" to "references",
            "experimental" to "experimental",
            // Renamed.
            "permission" to "permissions",
            "agent" to "agents",
            "snapshot" to "snapshots",
            "command" to "commands",
            "plugin" to "plugins",
            "provider" to "providers",
            "autoupdate" to "update",
            "attachment" to "media",
        )

        /**
         * Projection names with **no** file key, because the runtime reports something the
         * configuration file cannot express.
         *
         * `Config.InfoEncoded` has 28 properties and `Config` has 36 keys, and the two sets are not
         * nested: eleven file keys have no projection and three projections have no file key.
         * `websearch`, `worktree` and `warming` are what the server computes and reports but no
         * `opencode.json(c)` can set, so the file editor has nothing to write for them and the explorer
         * shows them under a heading that says so. Listing them is what stops them being "fixed" by
         * adding a file key that does not exist, and `ConfigCatalogCoverageTest` checks that the three
         * sets are disjoint and that together they account for every projection property.
         */
        val PROJECTION_ONLY_KEYS: Set<String> = setOf("websearch", "worktree", "warming")

        /**
         * The eleven file keys the projection never reports.
         *
         * **A separate list because "not reported" is an answer the explorer must show.** These keys
         * are still fully valid, still have a source document, and are still editable — the
         * projection simply does not compute them, so the explorer says so rather than implying the
         * key is unused or unset.
         */
        val UNPROJECTED_KEYS: Set<String> = setOf(
            "reference", "autoshare", "disabled_providers", "enabled_providers", "layout",
            "logLevel", "mode", "server", "small_model", "subagent_depth", "tools",
        )

        /** Parses [text], the contents of `config.schema.json`. */
        fun parse(text: String): ConfigSchema {
            val json = kotlinx.serialization.json.Json.parseToJsonElement(text) as? JsonObject
                ?: error("config.schema.json is not a JSON object")
            require(json["\$defs"] is JsonObject) { "config.schema.json has no \$defs" }
            return ConfigSchema(json)
        }
    }
}

/** The schema's `$defs`, the twenty named definitions its `$ref`s point into. */
private fun JsonObject.definitions(): JsonObject = this["\$defs"] as? JsonObject ?: JsonObject(emptyMap())
