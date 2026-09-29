package dev.opencode.android.core.data.config

import dev.opencode.android.core.model.McpServerConfig
import dev.opencode.android.core.model.PermissionEffect
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The four guided templates of plan §6: a persistent MCP server, a permission rule, the default
 * model, and a new agent.
 *
 * **Each one produces a *value for a top-level key*, not a whole file.** The user has an existing
 * `opencode.jsonc` with comments, their formatting and their other keys, and a template that replaced
 * the file would destroy all three. So a template answers "the JSON value for this key", and
 * [ConfigDocument.set] merges it. That is the only kind of edit from a phone that survives being made
 * on a phone.
 *
 * **The shapes come from the vendored schema, not from the projection.**
 *
 *  - A **persistent MCP server** is `"mcp": { "<name>": { "type": "local"|"remote", … } }` — and the
 *    file's `McpLocalConfig`/`McpRemoteConfig` do **not** carry the runtime panel's `codemode` or
 *    `protocol` fields, and their `timeout` is a single integer rather than the runtime's three-field
 *    object. [McpTemplate] maps what the file can express and **refuses** what it cannot, rather than
 *    dropping it and writing a server that behaves differently from what the form said.
 *  - A **permission rule** is `"permission": { "<action>": "<effect>" }` or
 *    `"permission": { "<action>": { "<resource>": "<effect>" } }`. It is *not* the
 *    `[{action, resource, effect}]` array — that array is `Permission.Ruleset`, which is what the
 *    runtime projection and `session.update` use. Writing the array here would be rejected by the
 *    file schema, and a session's rule list is a different thing from a file's rules.
 *  - The **default model** is a string: the file schema says `"model": { "type": "string" }`, while
 *    the projection also accepts the three-field object. The template writes the string, because the
 *    string is what the file accepts.
 *  - A **new agent** is `"agent": { "<name>": { …AgentConfig… } }`, and an agent's `permission`
 *    block is the same `PermissionConfig` map the top-level one is.
 *
 * **Validation happens in [ConfigTemplates.build], before the confirmation is reachable.** The write
 * path bypasses the server's own validation entirely (features doc §33.5), so a template that could
 * reach a confirmation with an invalid document would make the confirmation worthless.
 */
sealed interface ConfigTemplate {

    /** A stable identifier the screen keys its copy and tests on. */
    val id: String

    /** The top-level key this template writes. */
    val key: String

    /** What it would do, in one sentence, for the confirmation (plan §5.2). */
    val consequence: String

    /**
     * Whether changing this key changes what the agent is allowed to do, which the confirmation
     * leads with.
     */
    val isPrivilegeChange: Boolean

    /** The JSON value for [key], or `null` when the inputs are not ready. */
    fun value(): JsonElement?
}

/**
 * A persistent MCP server: `"mcp": { "<name>": { … } }`.
 *
 * **The union comes from [dev.opencode.android.core.data.integrations.McpConfigForm], the same one
 * the runtime MCP panel builds.** That is deliberate: the two routes want the same server described
 * in two places, and two hand-written shapes would drift. What the file *cannot* say is handled
 * explicitly — see [unsupported].
 *
 * @param name the key under `mcp`, which is the server's name on this location.
 */
data class McpTemplate(val name: String, val config: McpServerConfig) : ConfigTemplate {

    override val id: String get() = "mcp"
    override val key: String get() = "mcp"
    override val consequence: String
        get() = "The agent gains the tools the $name server publishes, and can call them without asking"
    override val isPrivilegeChange: Boolean get() = true

    /**
     * Things the draft asked for that the configuration file cannot express.
     *
     * **Reported, never silently dropped.** `codemode` and `protocol` exist on the runtime MCP
     * configuration but not in `McpLocalConfig`/`McpRemoteConfig`, and the file's `timeout` is one
     * integer where the runtime's is three fields. Writing the server without them would leave the
     * user with a persistent server that does not behave like the one they described, and no error
     * would ever say so.
     */
    val unsupported: List<String> = buildList {
        if (config.codemode) add("codemode")
        if (config.protocol.isNotBlank() && config.protocol != dev.opencode.android.core.model.McpProtocol.LEGACY) {
            add("protocol")
        }
        if (config.timeout.startup != null || config.timeout.catalog != null || config.timeout.execution != null) {
            add("the separate startup, catalog and execution timeouts")
        }
    }

    override fun value(): JsonElement? {
        if (name.isBlank()) return null
        val body = when (config) {
            is McpServerConfig.Local -> buildMap<String, JsonElement> {
                put("type", JsonPrimitive("local"))
                put("command", JsonArray(config.command.map(::JsonPrimitive)))
                config.cwd?.let { put("cwd", JsonPrimitive(it)) }
                config.environment.takeIf { it.isNotEmpty() }?.let { put("environment", strings(it)) }
                if (config.disabled) put("enabled", JsonPrimitive(false))
                fileTimeout()?.let { put("timeout", it) }
            }

            is McpServerConfig.Remote -> buildMap<String, JsonElement> {
                put("type", JsonPrimitive("remote"))
                put("url", JsonPrimitive(config.url))
                config.headers.takeIf { it.isNotEmpty() }?.let { put("headers", strings(it)) }
                if (config.disabled) put("enabled", JsonPrimitive(false))
                fileTimeout()?.let { put("timeout", it) }
            }

            // An `Unknown` union member is a config this build cannot re-serialise, and writing a
            // partial one would produce a server the user did not describe.
            is McpServerConfig.Unknown -> return null
        }
        return JsonObject(mapOf(name to JsonObject(body)))
    }

    /**
     * The single `timeout` the file accepts.
     *
     * The **execution** timeout, because that is the one a user means when they type one number into
     * a field labelled "timeout"; the runtime's other two are startup and catalog. When only one of
     * the three is set it is that one; when several are, they cannot be expressed and [unsupported]
     * says so, so the number written here is not the whole answer and the screen will not pretend it
     * is.
     */
    private fun fileTimeout(): JsonPrimitive? = config.timeout.let { timeout ->
        timeout.execution ?: timeout.startup ?: timeout.catalog
    }?.let { JsonPrimitive(it) }
}

/**
 * A permission rule, in the **file's** shape.
 *
 * **The resource-specific form is `[action]: { [resource]: effect }` and the blanket form is
 * `[action]: effect`.** `PermissionObjectConfig`'s values are `PermissionActionConfig` — one of
 * `ask`, `allow`, `deny` — and its keys are resources, so `{"bash": {"*": "deny"}}` denies every
 * command and `{"bash": "deny"}` does the same thing more tersely. Both are accepted by the schema
 * and the resource-specific one is written whenever a resource is given, because a file that already
 * has a blanket `bash` rule gains a `{ "*": … }` sibling rather than changing shape under the user.
 *
 * **This is not the session's rule list.** `SessionUpdateRequest.permissions` is
 * `Permission.Ruleset` — an ordered `[{action, resource, effect}]` array — and the session editor
 * writes that, because that is what the route takes. The two shapes are the same idea in two
 * vocabularies, and `ConfigPermissionTemplate` is the only place that has to know the difference.
 */
data class PermissionTemplate(
    val action: String,
    val resource: String,
    val effect: PermissionEffect,
) : ConfigTemplate {

    override val id: String get() = "permission"
    override val key: String get() = "permission"
    override val consequence: String
        get() = "${effect.value.uppercase()} $action on ${resource.ifBlank { "everything" }}"
    override val isPrivilegeChange: Boolean get() = true

    /** The effects the file's `PermissionActionConfig` allows. A template may not write anything else. */
    val ALLOWED_EFFECTS: Set<String> = setOf("allow", "deny", "ask")

    override fun value(): JsonElement? {
        if (action.isBlank()) return null
        if (effect.value !in ALLOWED_EFFECTS) return null
        val leaf = JsonPrimitive(effect.value)
        val forAction = if (resource.isBlank()) leaf else JsonObject(mapOf(resource to leaf))
        return JsonObject(mapOf(action to forAction))
    }
}

/**
 * The default model: `"model": "provider/model[#variant]"`.
 *
 * **The string spelling, because the file schema says `type: string`.** The projection also accepts
 * the object form; writing it here would be rejected by the file's own schema, which is precisely
 * the class of mistake the vendored schema is checked against.
 */
data class ModelTemplate(val providerID: String, val model: String, val variant: String? = null) : ConfigTemplate {

    override val id: String get() = "model"
    override val key: String get() = "model"
    override val consequence: String
        get() = "Every session that has not chosen a model will use $text"
    override val isPrivilegeChange: Boolean get() = false

    /** The `provider/model[#variant]` this template would write, or `null` when it is not one. */
    val text: String? = dev.opencode.android.core.model.ConfigModel
        .parse(listOfNotNull(providerID.takeIf { it.isNotBlank() }, model.takeIf { it.isNotBlank() }).joinToString("/"))
        ?.display()

    override fun value(): JsonElement? = text?.let(::JsonPrimitive)
}

/**
 * A new agent: `"agent": { "<name>": { …AgentConfig… } }`.
 *
 * **`AgentConfig`'s `permission` is the same `PermissionConfig` map the top-level key uses**, so an
 * agent's permission block is written through [PermissionTemplate]'s shape rather than invented.
 * `color` is the one field with a `pattern` in it — `^#[0-9a-fA-F]{6}$` *or* a theme colour name — and
 * it is deliberately passed through unvalidated here so the schema does that check, which is the
 * honest division of labour: the template collects, the vendored schema decides.
 */
data class AgentTemplate(
    val name: String,
    val mode: String? = null,
    val model: String? = null,
    val variant: String? = null,
    val temperature: Double? = null,
    val steps: Int? = null,
    val color: String? = null,
    val description: String? = null,
    val permission: Map<String, PermissionEffect>? = null,
    val prompt: String? = null,
) : ConfigTemplate {

    override val id: String get() = "agent"
    override val key: String get() = "agent"
    override val consequence: String
        get() = "A new agent named $name becomes selectable in every session of this location"
    override val isPrivilegeChange: Boolean get() = true

    /** The modes `AgentConfig` allows. Anything else is the schema's rejection, not a silent drop. */
    val ALLOWED_MODES: Set<String> = setOf("subagent", "primary", "all")

    override fun value(): JsonElement? {
        if (name.isBlank()) return null
        val body = buildMap<String, JsonElement> {
            mode?.takeIf { it.isNotBlank() }?.let { put("mode", JsonPrimitive(it)) }
            model?.takeIf { it.isNotBlank() }?.let { put("model", JsonPrimitive(it)) }
            variant?.takeIf { it.isNotBlank() }?.let { put("variant", JsonPrimitive(it)) }
            temperature?.let { put("temperature", JsonPrimitive(it)) }
            steps?.takeIf { it > 0 }?.let { put("steps", JsonPrimitive(it)) }
            color?.takeIf { it.isNotBlank() }?.let { put("color", JsonPrimitive(it)) }
            description?.takeIf { it.isNotBlank() }?.let { put("description", JsonPrimitive(it)) }
            permission
                ?.filterValues { effect -> effect.value in PermissionTemplate("x", "y", effect).ALLOWED_EFFECTS }
                ?.takeIf { it.isNotEmpty() }
                ?.let { values -> put("permission", JsonObject(values.mapValues { (_, e) -> JsonPrimitive(e.value) })) }
            prompt?.takeIf { it.isNotBlank() }?.let { put("prompt", JsonPrimitive(it)) }
        }
        return JsonObject(mapOf(name to JsonObject(body)))
    }
}

private fun strings(values: Map<String, String>): JsonObject =
    JsonObject(values.mapValues { (_, value) -> JsonPrimitive(value) })

/** What a template needs before it may be offered. */
enum class TemplateProblem {
    /** A required field is empty. */
    REQUIRED_FIELD,

    /** The value does not satisfy the vendored schema. */
    INVALID,
}

/**
 * The registry that decides whether a template is usable.
 *
 * **Validation happens here, once, and a template that fails never reaches a confirmation.** The
 * confirmation is the last gate before a write to the server's own filesystem, and a gate that can be
 * reached with an invalid document is not a gate. So [build] refuses anything the vendored schema
 * rejects and hands back the same diagnostics the editor shows for hand-typed text.
 */
object ConfigTemplates {

    /** Every template's key, which is also the plan's list of "the common edits". */
    val keys: List<String> = listOf("mcp", "permission", "model", "agent")

    /**
     * The value for [template] merged into [document], or why it cannot be offered.
     *
     * @param document the current parsed `opencode.jsonc`, or [JsonObject] `{}` for a new file.
     */
    fun build(
        schema: ConfigSchema,
        template: ConfigTemplate,
        document: JsonElement,
    ): TemplateOutcome {
        val value = template.value() ?: return TemplateOutcome.NotReady(TemplateProblem.REQUIRED_FIELD)
        val merged = ConfigDocument.set(document, template.key, value)
        val problems = schema.validator().validate(merged)
        if (problems.isNotEmpty()) return TemplateOutcome.Invalid(problems)
        return TemplateOutcome.Ready(merged)
    }
}

/** What [ConfigTemplates.build] concluded. */
sealed interface TemplateOutcome {
    /** The merged document, which validates against the vendored schema. */
    data class Ready(val document: JsonElement) : TemplateOutcome

    /** A required field is empty. */
    data class NotReady(val problem: TemplateProblem) : TemplateOutcome

    /** The merge does not satisfy the vendored schema, with the diagnostics that say why. */
    data class Invalid(val problems: List<SchemaDiagnostic>) : TemplateOutcome
}
