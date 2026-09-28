package dev.opencode.android.core.model.event

import dev.opencode.android.core.model.json.UnknownVariant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * The typed `data` of an [Event], chosen by the envelope's `type`.
 *
 * The shapes mirror the `V2Event*` declarations in `@opencode/client@2.0.18`
 * (`api/opencode-2.0.x/events.json`); the OpenAPI spec leaves event payloads opaque, so
 * these types are the contract. Anything the client does not know decodes to [Unknown]
 * (which keeps the raw JSON), and `rpc.<rpcID>.<event>` types decode to [Rpc].
 */
sealed interface EventPayload {
    /** `server.connected`: the first frame of every stream; triggers a REST resync. */
    @Serializable
    data object ServerConnected : EventPayload

    // Catalog invalidation signals: empty payload, the client refetches.
    @Serializable
    data object AgentUpdated : EventPayload

    @Serializable
    data object CommandUpdated : EventPayload

    @Serializable
    data object ConfigUpdated : EventPayload

    @Serializable
    data object CredentialUpdated : EventPayload

    @Serializable
    data object IntegrationUpdated : EventPayload

    @Serializable
    data object LocationShutdown : EventPayload

    @Serializable
    data object ModelUpdated : EventPayload

    @Serializable
    data object ModelsDevRefreshed : EventPayload

    @Serializable
    data object PluginUpdated : EventPayload

    @Serializable
    data object ProviderUpdated : EventPayload

    @Serializable
    data object ReferenceUpdated : EventPayload

    @Serializable
    data object SkillUpdated : EventPayload

    @Serializable
    data object WebsearchUpdated : EventPayload

    /**
     * An `rpc.<rpcID>.<event>` frame. The envelope keeps the full `type` string; [rpcID]
     * is its first segment and [event] the remainder. Carried as raw JSON: plugin RPC
     * payloads are defined by the plugin, not the server contract. Never decoded through
     * kotlinx serialization (the wire form has no `rpcID`/`event` fields), only built by
     * [EventTypes].
     */
    data class Rpc(
        val rpcID: String,
        val event: String,
        val data: JsonObject,
    ) : EventPayload

    /** An event type this client does not know. Rendered as a generic fallback. */
    data class Unknown(
        val type: String,
        override val raw: JsonObject,
    ) : EventPayload,
        UnknownVariant {
        override val discriminator: String? get() = type
    }
}
