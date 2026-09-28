package dev.opencode.android.core.data.repository

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

enum class ServerHealth {
    CONNECTED,
    CONNECTING,
    DISCONNECTED,
    ERROR,
}

data class ServerProfile(
    val id: String,
    val name: String,
    val baseUrl: String,
    val isDefault: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val lastSeenAt: Long? = null,
    val health: ServerHealth = ServerHealth.DISCONNECTED,
    val hasCredential: Boolean = false,
) {
    /**
     * True when using unencrypted HTTP over a non-loopback network (e.g. LAN or public IP).
     * Used to display the "unencrypted" badge in the UI per docs/ANDROID_APP_PLAN.md.
     */
    val isCleartext: Boolean
        get() {
            if (!baseUrl.startsWith("http://", ignoreCase = true)) return false
            val host = baseUrl.toHttpUrlOrNull()?.host?.lowercase() ?: return true
            return !isLoopbackHost(host)
        }

    companion object {
        fun isLoopbackHost(host: String): Boolean {
            val h = host.lowercase().trim('[', ']')
            return h == "localhost" ||
                h == "127.0.0.1" ||
                h == "::1" ||
                h == "10.0.2.2" // Android emulator host loopback alias
        }
    }
}
