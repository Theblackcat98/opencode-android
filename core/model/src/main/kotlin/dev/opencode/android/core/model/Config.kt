package dev.opencode.android.core.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import dev.opencode.android.core.model.json.DiscriminatedUnionSerializer
import dev.opencode.android.core.model.json.OpenCodeJson
import dev.opencode.android.core.model.json.UnknownVariant
import dev.opencode.android.core.model.json.variant

/**
 * One entry of `GET /api/config`: a configuration document, or a directory the server searched
 * (features doc §33.1, §33.3).
 *
 * **The list is in precedence order, lowest first** — the route's own description says "from lowest
 * to highest priority" — so the index in the server's answer *is* the precedence. Nothing here
 * carries an index: re-deriving one from the path would put a project file ahead of the global one,
 * and a store that invented its own ordering would be the one thing that decides which value wins.
 * `ConfigSource` in the data layer is what pairs an entry with its position.
 *
 * **A `directory` entry carries no information, and that is the point.** It says "the server looked
 * here and found nothing", which is what a user needs when a key they set is not taking effect: the
 * file is in the wrong place. Dropping those entries would make an unset key look unset for a reason
 * the screen cannot explain.
 */
@Serializable(with = ConfigEntrySerializer::class)
sealed interface ConfigEntry {

    /** Where this entry lives, or `null` when the server did not say. */
    val path: String?

    /** A parsed configuration file, and the effective projection of the documents up to here. */
    @Serializable
    data class Document(
        @SerialName("path") val pathOrNull: String? = null,
        val info: ConfigInfo,
    ) : ConfigEntry {
        override val path: String? get() = pathOrNull
    }

    /** A directory the server searched and found no `opencode.json(c)` in. */
    @Serializable
    data class Directory(
        @SerialName("path") val pathOrNull: String,
    ) : ConfigEntry {
        override val path: String? get() = pathOrNull
    }

    /** An entry of a shape this build does not know. Kept so nothing is silently dropped. */
    data class Unknown(override val raw: JsonObject) : ConfigEntry, UnknownVariant {
        override val discriminator: String?
            get() = (raw["type"] as? JsonPrimitive)?.contentOrNull
        override val path: String? get() = (raw["path"] as? JsonPrimitive)?.contentOrNull
    }
}

internal object ConfigEntrySerializer : DiscriminatedUnionSerializer<ConfigEntry>(
    serialName = "dev.opencode.android.ConfigEntry",
    unknown = { _, raw -> ConfigEntry.Unknown(raw) },
    variants = listOf(
        variant("document", ConfigEntry.Document.serializer()),
        variant("directory", ConfigEntry.Directory.serializer()),
    ),
)

/**
 * The runtime projection of every configuration document up to and including its own
 * (schema `Config.InfoEncoded`, features doc §33.2).
 *
 * **This is not the configuration file's shape, and the difference is the whole point of the
 * explorer.** A file writes `{"model": "x/y", "permission": {...}, "agent": {...}}`; the projection
 * reports *effective* values under different names — `permissions`, `agents`, `snapshots`,
 * `providers`, `plugins`, `commands` — and adds keys that were never in any file (`$schema`,
 * `update`, `media`). So the two vocabularies are separate types with a mapping between
 * them (`ConfigKeyNames` in the data layer), and folding them into one class would make it
 * impossible to say which of a key's two names a value came from.
 *
 * **Every field is nullable and the projection is read as a set of present keys.** A `null` field
 * means the server did not report a value, which usually means nothing set it — but not always,
 * because eleven of the schema's thirty-six keys have no projection at all. [projected] returning
 * `null` and [projects] returning `false` are therefore different answers, and a screen has to show
 * both rather than collapsing them into one "unset".
 *
 * **Keys this build does not know are dropped, not kept.** `Config.InfoEncoded` is
 * `additionalProperties: false` and the client cannot invent a meaning for a key it has never seen;
 * [ConfigEntry.Unknown] covers the *outer* union, where a whole document shape could change. Every
 * field below is checked against the spec's property list by `ConfigInfoCoverageTest`, so an
 * addition is a failing test rather than a silently missing row in the explorer.
 */
@Serializable
data class ConfigInfo(
    @SerialName("\$schema") val schema: String? = null,
    val shell: String? = null,
    val model: ConfigModel? = null,
    val default_agent: String? = null,
    val update: String? = null,
    val share: String? = null,
    val enterprise: JsonObject? = null,
    val username: String? = null,
    /** The effective, ordered permission rules. Read-only: no route writes them (features doc §33.2). */
    val permissions: List<PermissionRule>? = null,
    val agents: Map<String, JsonObject>? = null,
    val snapshots: Boolean? = null,
    val watcher: JsonElement? = null,
    val formatter: JsonElement? = null,
    val lsp: JsonElement? = null,
    val media: JsonElement? = null,
    val tool_output: JsonObject? = null,
    val mcp: JsonObject? = null,
    val compaction: JsonObject? = null,
    val skills: JsonObject? = null,
    val commands: Map<String, JsonObject>? = null,
    val instructions: JsonElement? = null,
    val references: JsonObject? = null,
    val websearch: JsonElement? = null,
    val plugins: JsonElement? = null,
    val worktree: JsonObject? = null,
    val warming: JsonElement? = null,
    val providers: JsonElement? = null,
    val experimental: JsonObject? = null,
) {
    /**
     * The projection's value for the file key [fileKey], or `null` when the projection reports
     * nothing for it.
     *
     * The key names are the *file* schema's, not the projection's, because this is called by the
     * explorer, which walks the file schema: that is what makes "every top-level config key is
     * visible" a checkable statement rather than an aspiration.
     */
    fun projected(fileKey: String): JsonElement? = when (fileKey) {
        "\$schema" -> schema?.let(::JsonPrimitive)
        "shell" -> shell?.let(::JsonPrimitive)
        "model" -> model?.toJson()
        "default_agent" -> default_agent?.let(::JsonPrimitive)
        "autoupdate" -> update?.let(::JsonPrimitive)
        "share" -> share?.let(::JsonPrimitive)
        "enterprise" -> enterprise
        "username" -> username?.let(::JsonPrimitive)
        "permission" -> permissions?.let { OpenCodeJson.encodeToJsonElement(ListSerializer(PermissionRule.serializer()), it) }
        "agent" -> agents?.let(::JsonObject)
        "snapshot" -> snapshots?.let(::JsonPrimitive)
        "formatter" -> formatter
        "lsp" -> lsp
        "attachment" -> media
        "tool_output" -> tool_output
        "mcp" -> mcp
        "compaction" -> compaction
        "skills" -> skills
        "command" -> commands?.let(::JsonObject)
        "instructions" -> instructions
        "watcher" -> watcher
        "references" -> references
        // `websearch` and `worktree` are reachable here but have no key in the configuration file; see
        // `ConfigSchema.PROJECTION_ONLY_KEYS`.
        "websearch" -> websearch
        "plugin" -> plugins
        "worktree" -> worktree
        "warming" -> warming
        "provider" -> providers
        "experimental" -> experimental
        // The eleven keys with no projection: `reference`, `autoshare`, `disabled_providers`,
        // `enabled_providers`, `layout`, `logLevel`, `mode`, `server`, `small_model`,
        // `subagent_depth` and `tools`.
        else -> null
    }

    /**
     * Whether the projection reports this key **at all**.
     *
     * Distinct from [projected] returning `null`. A key the projection knows but has nothing for is
     * "reported as unset"; a key it does not know is "not reported". Those are different sentences
     * and the explorer shows both, because "the server does not tell me this key's value" and "this
     * key is not set" lead the user to different places.
     */
    fun projects(fileKey: String): Boolean = fileKey in PROJECTED_KEYS

    private companion object {
        val PROJECTED_KEYS: Set<String> = setOf(
            "\$schema", "shell", "model", "default_agent", "autoupdate", "share", "enterprise",
            "username", "permission", "agent", "snapshot", "watcher", "formatter", "lsp", "attachment",
            "tool_output", "mcp", "compaction", "skills", "command", "instructions", "references",
            "websearch", "plugin", "worktree", "warming", "provider", "experimental",
        )
    }
}

/**
 * `Config.InfoEncoded.model`: a `provider/model[#variant]` string, or the three-field object the
 * 2.0.18 spec also allows.
 */
@Serializable(with = ConfigModelSerializer::class)
sealed interface ConfigModel {

    /** The `provider/model` spelling, with an optional `#variant`. */
    @Serializable
    data class Ref(val providerID: String, val model: String, val variant: String? = null) : ConfigModel

    /** The object spelling: `{"providerID": …, "model": …, "variant": …}`. */
    @Serializable
    data class Detailed(
        val providerID: String,
        val model: String,
        val variant: String? = null,
    ) : ConfigModel

    data class Unknown(override val raw: JsonObject) : ConfigModel, UnknownVariant {
        override val discriminator: String? get() = null
    }

    /** `provider/model[#variant]`, which is what the header and the explorer show. */
    fun display(): String = when (this) {
        is Ref -> spell(providerID, model, variant)
        is Detailed -> spell(providerID, model, variant)
        is Unknown -> "?"
    }

    /** The file spelling, so a guided template writes what a config file expects. */
    fun toFileString(): String = display()

    fun toJson(): JsonElement = when (this) {
        is Ref -> JsonPrimitive(toFileString())
        is Detailed -> JsonObject(
            buildMap {
                put("providerID", JsonPrimitive(providerID))
                put("model", JsonPrimitive(model))
                variant?.let { put("variant", JsonPrimitive(it)) }
            },
        )
        // An unknown shape is kept as it arrived rather than rendered as a guess.
        is Unknown -> raw
    }

    companion object {
        /**
         * Parses `provider/model[#variant]`, or `null` when [text] is not that shape.
         *
         * The rule is the spec's own pattern (`^[^/#]+\/[^#]+(?:#[^#]+)?$`), written out rather than
         * imported, because a template that could not tell a model reference from a sentence would
         * write a configuration the server rejects.
         */
        fun parse(text: String): Ref? {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return null
            val hasVariant = trimmed.contains('#')
            val head = trimmed.substringBefore('#')
            val variant = trimmed.substringAfter('#', "").takeIf { hasVariant }
            val provider = head.substringBefore('/', missingDelimiterValue = "")
            val model = head.substringAfter('/', missingDelimiterValue = "")
            if (provider.isEmpty() || model.isEmpty()) return null
            // The spec's own pattern, `^[^/#]+\/[^#]+(?:#[^#]+)?$`: neither the provider nor the
            // model may carry the separator, and the variant may not carry a second `#`. A template
            // that could not tell a model reference from a sentence would write a configuration the
            // server rejects.
            if ('/' in provider || '#' in provider || '#' in model) return null
            if (hasVariant && (variant.isNullOrEmpty() || '#' in variant)) return null
            return Ref(provider, model, variant)
        }

        private fun spell(providerID: String, model: String, variant: String?): String =
            "$providerID/$model" + (variant?.let { "#$it" } ?: "")
    }
}

internal object ConfigModelSerializer : KSerializer<ConfigModel> {
    override val descriptor: SerialDescriptor =
        SerialDescriptor("dev.opencode.android.ConfigModel", JsonElement.serializer().descriptor)

    override fun deserialize(decoder: Decoder): ConfigModel {
        val input = decoder as? JsonDecoder ?: throw SerializationException("ConfigModel needs JSON")
        return when (val element = input.decodeJsonElement()) {
            is JsonPrimitive ->
                ConfigModel.parse(element.contentOrNull ?: "")
                    ?: ConfigModel.Unknown(JsonObject(mapOf("raw" to element)))
            is JsonObject -> runCatching {
                input.json.decodeFromJsonElement(ConfigModel.Detailed.serializer(), element)
            }.getOrElse { ConfigModel.Unknown(element) }
            else -> ConfigModel.Unknown(JsonObject(mapOf("raw" to element)))
        }
    }

    override fun serialize(encoder: Encoder, value: ConfigModel) {
        val output = encoder as? JsonEncoder
            ?: throw SerializationException("ConfigModel can only be encoded to JSON")
        output.encodeJsonElement(value.toJson())
    }
}

/** `PATCH /api/experimental/config` — `{"shell": "…"}`, and the only key the route accepts. */
@Serializable
data class ConfigPatchRequest(val shell: String)
