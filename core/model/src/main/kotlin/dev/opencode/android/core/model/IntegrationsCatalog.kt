package dev.opencode.android.core.model

import dev.opencode.android.core.model.json.DiscriminatedUnionSerializer
import dev.opencode.android.core.model.json.UnknownVariant
import dev.opencode.android.core.model.json.variant
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * One MCP server and its status (features doc §17; schema `Mcp.Server`).
 *
 * **[integrationID] is what makes a `needs_auth` server fixable from the phone.** A remote MCP server
 * that wants OAuth reports `needs_auth` and the integration that owns the flow, and the app opens
 * *that integration's* OAuth flow. Without the id the only honest thing a client can do is show the
 * error, which is why it is a first-class field rather than something read out of the message text.
 */
@Serializable
data class McpServer(
    val name: String,
    val status: McpStatus,
    val integrationID: String? = null,
) {
    val isConnected: Boolean get() = status is McpStatus.Connected
    val isPending: Boolean get() = status is McpStatus.Pending
    val isDisabled: Boolean get() = status is McpStatus.Disabled
    val isFailed: Boolean get() = status is McpStatus.Failed
    val isDisabledForAuth: Boolean get() = status is McpStatus.NeedsAuth
}

/** How an MCP server is doing (schema `Mcp.Status`). */
@Serializable(with = McpStatusSerializer::class)
sealed interface McpStatus {
    @Serializable
    @SerialName("connected")
    data object Connected : McpStatus

    @Serializable
    @SerialName("pending")
    data object Pending : McpStatus

    @Serializable
    @SerialName("disabled")
    data object Disabled : McpStatus

    /** The server could not be reached or refused the handshake, with [error] as the reason. */
    @Serializable
    @SerialName("failed")
    data class Failed(val error: String) : McpStatus

    /**
     * The server wants authorization.
     *
     * [error] is the server's own wording, so the row shows it. The *fix* is the integration named
     * on the [McpServer], not anything in this message.
     */
    @Serializable
    @SerialName("needs_auth")
    data class NeedsAuth(val error: String) : McpStatus

    data class Unknown(
        val declaredStatus: String?,
        override val raw: JsonObject,
    ) : McpStatus,
        UnknownVariant {
        override val discriminator: String? get() = declaredStatus
    }
}

internal object McpStatusSerializer : DiscriminatedUnionSerializer<McpStatus>(
    serialName = "dev.opencode.android.McpStatus",
    discriminator = "status",
    unknown = McpStatus::Unknown,
    variants = listOf(
        variant("connected", McpStatus.Connected.serializer()),
        variant("pending", McpStatus.Pending.serializer()),
        variant("disabled", McpStatus.Disabled.serializer()),
        variant("failed", McpStatus.Failed.serializer()),
        variant("needs_auth", McpStatus.NeedsAuth.serializer()),
    ),
)

/** The resources and templates every connected server publishes (schema `Mcp.ResourceCatalog`). */
@Serializable
data class McpResourceCatalog(
    val resources: List<McpResource> = emptyList(),
    val templates: List<McpResourceTemplate> = emptyList(),
) {
    val isEmpty: Boolean get() = resources.isEmpty() && templates.isEmpty()
}

/** A resource a server publishes (schema `Mcp.Resource`). */
@Serializable
data class McpResource(
    val server: String,
    val name: String,
    val uri: String,
    val description: String? = null,
    val mimeType: String? = null,
)

/** A parameterized resource (schema `Mcp.ResourceTemplate`). */
@Serializable
data class McpResourceTemplate(
    val server: String,
    val name: String,
    val uriTemplate: String,
    val description: String? = null,
    val mimeType: String? = null,
)

/**
 * A runtime MCP server configuration (schema `Mcp.LocalConfigEncoded` and `Mcp.RemoteConfigEncoded`).
 *
 * **The union is discriminated by `type`, and this is the only place it is built.** The persistent
 * form lives in a config file the app cannot write; the runtime form is what
 * `experimental.mcp.add` takes, and it is the same shape, so the form the user fills in and the
 * union it becomes are one type rather than two that can disagree.
 */
@Serializable(with = McpServerConfigSerializer::class)
sealed interface McpServerConfig {
    val disabled: Boolean
    val codemode: Boolean
    val timeout: McpTimeout
    val protocol: String

    /** A server started as a child process of the host (schema `Mcp.LocalConfigEncoded`). */
    @Serializable
    data class Local(
        val command: List<String> = emptyList(),
        val cwd: String? = null,
        val environment: Map<String, String> = emptyMap(),
        override val disabled: Boolean = false,
        override val codemode: Boolean = false,
        override val timeout: McpTimeout = McpTimeout(),
        override val protocol: String = McpProtocol.LEGACY,
    ) : McpServerConfig

    /** A server reached over HTTP (schema `Mcp.RemoteConfigEncoded`). */
    @Serializable
    data class Remote(
        val url: String,
        val headers: Map<String, String> = emptyMap(),
        override val disabled: Boolean = false,
        override val codemode: Boolean = false,
        override val timeout: McpTimeout = McpTimeout(),
        override val protocol: String = McpProtocol.LEGACY,
    ) : McpServerConfig

    data class Unknown(
        val declaredType: String?,
        override val raw: JsonObject,
    ) : McpServerConfig,
        UnknownVariant {
        override val discriminator: String? get() = declaredType
        override val disabled: Boolean get() = false
        override val codemode: Boolean get() = false
        override val timeout: McpTimeout get() = McpTimeout()
        override val protocol: String get() = McpProtocol.LEGACY
    }
}

internal object McpServerConfigSerializer : DiscriminatedUnionSerializer<McpServerConfig>(
    serialName = "dev.opencode.android.McpServerConfig",
    unknown = McpServerConfig::Unknown,
    variants = listOf(
        variant("local", McpServerConfig.Local.serializer()),
        variant("remote", McpServerConfig.Remote.serializer()),
    ),
)

/** The three server timeouts, in seconds (schema `Mcp.*ConfigEncoded.timeout`). */
@Serializable
data class McpTimeout(
    val startup: Long? = null,
    val catalog: Long? = null,
    val execution: Long? = null,
) {
    val isEmpty: Boolean get() = startup == null && catalog == null && execution == null
}

/** The MCP protocol revisions the server negotiates (schema `Mcp.Protocol`). */
object McpProtocol {
    /** The 2025-11-25 revision and below. The default. */
    const val LEGACY = "legacy"

    /** Probe for 2026-07-28 and fall back to [LEGACY]. */
    const val AUTO = "auto"

    /** Require 2026-07-28; the server fails the handshake otherwise. */
    const val EXPLICIT = "2026-07-28"

    /** The three, in the order the picker lists them. */
    val ALL: List<String> = listOf(LEGACY, AUTO, EXPLICIT)
}

/** `PUT /api/experimental/mcp/{server}` (schema `Mcp.Add`). */
@Serializable
data class McpAddRequest(val config: McpServerConfig)

/**
 * A provider and how it is activated (features doc §9; schema `Provider.Info`).
 *
 * **Read-only, and the reasons are visible rather than hidden.** Activation, `settings`, `headers`
 * and `body` are all config (features doc §9), so this app shows them and links to the Phase 9
 * editor instead of offering a switch that would not take effect. [endpoint] is derived from
 * `settings.baseURL` when the provider has one, which is the only part a user recognises.
 */
@Serializable
data class ProviderInfo(
    val id: String,
    val name: String,
    val activation: String,
    @SerialName("package") val packageName: String,
    val canonical: String? = null,
    val integrationID: String? = null,
    val settings: ProviderSettings = ProviderSettings(),
    val headers: Map<String, String> = emptyMap(),
    val body: JsonObject? = null,
) {
    val isEnabled: Boolean get() = activation == ProviderActivation.ENABLED
    val isDisabled: Boolean get() = activation == ProviderActivation.DISABLED
    val isAuto: Boolean get() = activation == ProviderActivation.AUTO

    /** The custom endpoint, when the config names one; `null` for a built-in cloud provider. */
    val endpoint: String? get() = settings.baseUrl

    /** Whether a credential is needed before this provider can serve a turn. */
    val needsIntegration: Boolean get() = integrationID != null
}

/** Activation states of a provider (schema `Provider.Info.activation`). */
object ProviderActivation {
    /** Used when the server decides, which is the default. */
    const val AUTO = "auto"

    /** Always offered, even without a credential. */
    const val ENABLED = "enabled"

    /** Never offered. */
    const val DISABLED = "disabled"
}

/**
 * A provider's settings (schema `Provider.Settings`).
 *
 * The schema allows any additional property, so [extra] keeps every key verbatim and the fields a
 * user reads are derived from it. Declaring `baseURL` as a property instead would drop it the moment
 * a config spelled it `baseUrl`, and a custom endpoint is the single most useful thing on a
 * provider row — so it is read out of the map under either spelling.
 */
@Serializable(with = ProviderSettingsSerializer::class)
data class ProviderSettings(
    val extra: Map<String, JsonElement> = emptyMap(),
) {
    /** The custom endpoint, under either spelling of the key. */
    val baseUrl: String? get() = string("baseURL") ?: string("baseUrl")

    /** A numeric timeout, or `null` when it is `false` (no timeout) or absent. */
    val timeout: Double? get() = (extra["timeout"] as? JsonPrimitive)?.content?.toDoubleOrNull()

    /** Whether the config disabled the timeout with `timeout: false`. */
    val hasNoTimeout: Boolean get() = extra["timeout"] == JsonPrimitive(false)

    /** `summary` or `native` (schema `Provider.Compaction`), flattened to its tag. */
    val compaction: String? get() = (extra["compaction"] as? JsonObject)
        ?.get("type")
        ?.let { (it as? JsonPrimitive)?.content }

    /** `http` or `websocket` (schema `Provider.Transport`). */
    val transport: String? get() = string("transport")

    /** Whether the provider runs on a custom endpoint rather than a hosted API. */
    val isCustom: Boolean get() = baseUrl != null

    private fun string(key: String): String? =
        (extra[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}

/**
 * Reads `Provider.Settings` as the open object the schema declares.
 *
 * `Map<String, JsonElement>` is the honest shape for `additionalProperties: {}`: keeping every key
 * means a re-encode is byte-identical, so the fixture contract tests that re-encode what they decode
 * can compare the two and would notice a dropped setting.
 */
internal object ProviderSettingsSerializer : KSerializer<ProviderSettings> {
    override val descriptor: SerialDescriptor =
        SerialDescriptor("dev.opencode.android.ProviderSettings", JsonObject.serializer().descriptor)

    override fun deserialize(decoder: Decoder): ProviderSettings {
        val input = decoder as? JsonDecoder
            ?: throw SerializationException("Provider settings are only decoded from JSON")
        val obj = input.decodeJsonElement() as? JsonObject
            ?: throw SerializationException("Provider settings are a JSON object")
        return ProviderSettings(extra = obj)
    }

    override fun serialize(encoder: Encoder, value: ProviderSettings) {
        val output = encoder as? JsonEncoder
            ?: throw SerializationException("Provider settings are only encoded to JSON")
        output.encodeJsonElement(JsonObject(value.extra))
    }
}

/**
 * A plugin and its state (features doc §18; schema `Plugin.Info`).
 *
 * [id] is optional in the schema, so [key] falls back to the source's own identity: a package's
 * target, a local plugin's path, and `"builtin"` for the two source kinds that have no name. A list
 * needs a stable key per row, and using the index would reorder every plugin on one update.
 */
@Serializable
data class PluginInfo(
    val id: String? = null,
    val source: PluginSource,
    val features: PluginFeatures = PluginFeatures(),
    val state: PluginState,
) {
    /** A stable identity for list keys and for the `plugin.update {targets}` list. */
    val key: String get() = id ?: source.target ?: source.type

    /** The name the row shows, which is the source's own when the server gave no id. */
    val name: String get() = id ?: source.target ?: source.type

    /** A package plugin whose registry has a newer version. */
    val isOutdated: Boolean get() = (source as? PluginSource.Package)?.outdated == true

    /** A package plugin that is being updated right now. */
    val isUpdating: Boolean get() = (source as? PluginSource.Package)?.updating == true

    /** The error a failed plugin reports, or `null` when it is active. */
    val failure: String? get() = (state as? PluginState.Failed)?.error

    /** Whether this plugin can be updated: only a package with an outdated version. */
    val isUpdatable: Boolean get() = isOutdated && source is PluginSource.Package
}

/** Where a plugin came from (schema `Plugin.Source`). */
@Serializable(with = PluginSourceSerializer::class)
sealed interface PluginSource {
    val type: String

    /** The identity `plugin.update {targets}` and `plugin.check {target}` use. */
    val target: String? get() = null

    @Serializable
    @SerialName("builtin")
    data object Builtin : PluginSource {
        override val type: String = "builtin"
    }

    /**
     * An npm package (schema `Plugin.Source.package`).
     *
     * [outdated] and [updating] are declared `const true` in the schema, so they are `false` when
     * absent rather than nullable: "not known to be outdated" and "known to be current" are the same
     * thing to a caller deciding whether to offer an update.
     */
    @Serializable
    @SerialName("package")
    data class Package(
        @SerialName("target") val targetName: String,
        val version: String? = null,
        val outdated: Boolean = false,
        val updating: Boolean = false,
    ) : PluginSource {
        override val type: String = "package"
        override val target: String get() = targetName
    }

    /** A plugin loaded from a path (schema `Plugin.Source.local`). */
    @Serializable
    @SerialName("local")
    data class Local(val path: String) : PluginSource {
        override val type: String = "local"
        override val target: String get() = path
    }

    /** A plugin loaded through the SDK (schema `Plugin.Source.sdk`). */
    @Serializable
    @SerialName("sdk")
    data object Sdk : PluginSource {
        override val type: String = "sdk"
    }

    data class Unknown(
        val declaredType: String?,
        override val raw: JsonObject,
    ) : PluginSource,
        UnknownVariant {
        override val discriminator: String? get() = declaredType
        override val type: String get() = declaredType ?: "unknown"
    }
}

internal object PluginSourceSerializer : DiscriminatedUnionSerializer<PluginSource>(
    serialName = "dev.opencode.android.PluginSource",
    unknown = PluginSource::Unknown,
    variants = listOf(
        variant("builtin", PluginSource.Builtin.serializer()),
        variant("package", PluginSource.Package.serializer()),
        variant("local", PluginSource.Local.serializer()),
        variant("sdk", PluginSource.Sdk.serializer()),
    ),
)

/**
 * What a plugin extends (schema `Plugin.Features`).
 *
 * Every property is declared `const true`, so absent means *not* offered, and the three booleans
 * are the whole type: a plugin that does none of the three is listed but not updatable and not
 * routable.
 */
@Serializable
data class PluginFeatures(
    val server: Boolean = false,
    val tui: Boolean = false,
    val rpc: Boolean = false,
) {
    val isEmpty: Boolean get() = !server && !tui && !rpc
}

/** Whether a plugin loaded (schema `Plugin.State`). */
@Serializable(with = PluginStateSerializer::class)
sealed interface PluginState {
    @Serializable
    @SerialName("active")
    data object Active : PluginState

    @Serializable
    @SerialName("failed")
    data class Failed(
        val error: String,
        val ref: String? = null,
    ) : PluginState

    data class Unknown(
        val declaredStatus: String?,
        override val raw: JsonObject,
    ) : PluginState,
        UnknownVariant {
        override val discriminator: String? get() = declaredStatus
    }
}

internal object PluginStateSerializer : DiscriminatedUnionSerializer<PluginState>(
    serialName = "dev.opencode.android.PluginState",
    discriminator = "status",
    unknown = PluginState::Unknown,
    variants = listOf(
        variant("active", PluginState.Active.serializer()),
        variant("failed", PluginState.Failed.serializer()),
    ),
)

/**
 * A well-known integration source, as `POST /api/experimental/integration/wellknown` takes it.
 *
 * **A [Secret]-free URL, but still validated.** The route adds a *server* the user did not configure,
 * so the scheme check is the same one OAuth navigation uses: only http and https, and a host is
 * required. A `file://` or `intent://` here would make the server read a local file or hand a URL to
 * another app.
 */
object WellknownSourceUrl {
    /** [raw] when it may be added, or `null` when it may not. Same rule as [SafeNavigationUrl]. */
    fun parse(raw: String): String? = SafeNavigationUrl.parse(raw)
}
