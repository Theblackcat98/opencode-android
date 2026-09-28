package dev.opencode.android.core.data.repository

import dev.opencode.android.core.network.isCleartextLan
import dev.opencode.android.core.network.toServerBaseUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * How a saved server is doing, as the registry shows it in its health dot.
 *
 * The dot is derived from the live [dev.opencode.android.core.network.ConnectionState], not stored,
 * so it can only ever disagree with reality for as long as one reconnection takes.
 */
enum class ServerHealth {
    /** The event stream is live: `server.connected` arrived. */
    CONNECTED,

    /** A connection attempt is in flight. */
    CONNECTING,

    /** Not connected, and nothing is being tried: stopped, backgrounded, or offline. */
    DISCONNECTED,

    /** Not connected, and a retry is scheduled. */
    OFFLINE,

    /** The password or token was rejected. Only re-pairing recovers (plan §6, Phase 1). */
    REAUTH_REQUIRED,
}

/** One saved server. The credential itself is never held here; only whether there is one. */
data class ServerProfile(
    val id: String,
    val name: String,
    val baseUrl: String,
    val isDefault: Boolean = false,
    val createdAt: Long = 0L,
    val lastSeenAt: Long? = null,
    val health: ServerHealth = ServerHealth.DISCONNECTED,
    val hasCredential: Boolean = false,
    /** True when the server opted into trusting CA certificates the user installed on the device. */
    val trustUserCertificates: Boolean = false,
) {
    /** The host, or the empty string when the stored address cannot be parsed. */
    val host: String get() = baseUrl.toHttpUrlOrNull()?.host.orEmpty()

    /**
     * True when traffic to this server is `http://` on a host that is not loopback, which the UI
     * marks with a visible "unencrypted" badge (plan §2.4, §5.2). An unparsable address counts as
     * cleartext, so a malformed entry is never shown as safe.
     */
    val isCleartext: Boolean
        get() = baseUrl.toHttpUrlOrNull()?.isCleartextLan() ?: true

    /** True when the address is usable at all, which is what the manual entry form validates. */
    val hasValidAddress: Boolean
        get() = runCatching { baseUrl.toServerBaseUrl() }.isSuccess
}
