package dev.opencode.android.core.network

import okhttp3.HttpUrl
import java.util.concurrent.ConcurrentHashMap

/**
 * The credentials the app currently holds, keyed by a server's base URL, for [AuthInterceptor].
 *
 * The interceptor runs on an OkHttp dispatcher thread, so it cannot read the Keystore-backed store
 * (which decrypts, and may hit disk). The app therefore keeps the current tokens in memory here and
 * writes them whenever a credential is saved, replaced or removed, and whenever a connection is
 * (re)established from the store.
 *
 * A server the app has no credential for is simply absent: the interceptor then sends no
 * `Authorization` header, which is correct for a server whose authentication is disabled.
 *
 * Entries are never logged, and a base URL that is no longer known is removed with [forget], so a
 * removed server cannot keep its token in memory.
 */
class ServerCredentialCache(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) : CredentialProvider {

    private val entries = ConcurrentHashMap<String, String>()

    override fun credentialFor(url: HttpUrl): String? {
        val rootWithPort = "${url.scheme}://${url.host}:${url.port}".lowercase()
        val rootNoPort = "${url.scheme}://${url.host}".lowercase()
        var path = url.encodedPath.trimEnd('/')
        while (true) {
            val keyWithPort = if (path.isEmpty()) rootWithPort else "$rootWithPort$path".lowercase()
            entries[keyWithPort]?.let { return it }
            val keyNoPort = if (path.isEmpty()) rootNoPort else "$rootNoPort$path".lowercase()
            entries[keyNoPort]?.let { return it }
            if (path.isEmpty()) break
            val nextSlash = path.lastIndexOf('/')
            path = if (nextSlash > 0) path.substring(0, nextSlash) else ""
        }
        return null
    }

    /** Stores [credential] for the server at [baseUrl], replacing any earlier one. */
    fun put(baseUrl: String, credential: String?) {
        val key = baseUrl.toCredentialKey() ?: return
        if (credential.isNullOrBlank()) {
            entries.remove(key)
            return
        }
        evictOverflow()
        entries[key] = credential
    }

    /** Drops the credential of the server at [baseUrl], for example when it is removed or re-paired. */
    fun forget(baseUrl: String) {
        baseUrl.toCredentialKey()?.let(entries::remove)
    }

    /** True when the cache holds a credential for [baseUrl]. Used by tests and diagnostics. */
    fun holds(baseUrl: String): Boolean = baseUrl.toCredentialKey()?.let(entries::containsKey) == true

    fun clear() = entries.clear()

    /**
     * Normalizes a base URL to the key the cache is keyed by. The scheme, host, port and path
     * prefix all matter: the same host on another port is another server, and a server behind a
     * reverse-proxy subpath is again another server.
     */
    private fun String.toCredentialKey(): String? = trimEnd('/').takeIf { it.isNotBlank() }?.lowercase()

    private fun evictOverflow() {
        if (entries.size < maxEntries) return
        val oldest = entries.keys.firstOrNull() ?: return
        entries.remove(oldest)
    }

    private companion object {
        const val DEFAULT_MAX_ENTRIES = 32
    }
}
