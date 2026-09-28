package dev.opencode.android.core.network

import dev.opencode.android.core.model.json.OpenCodeJson
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import okhttp3.MediaType.Companion.toMediaType

/**
 * Builds a [ServerApi] per server.
 *
 * The app talks to several servers, each with its own base URL, credential and default directory,
 * so the Retrofit instance cannot be a singleton: every server gets its own, sharing one
 * [OkHttpClient] and therefore one connection pool and one dispatcher.
 *
 * Authentication is attached here, not on the shared client, so that a request-scoped
 * [ServerAuthCredential] is always the one that wins: a call that passes a credential (the pairing
 * check, a credential that is not in the cache yet) must not be overridden by whatever the cache
 * happens to hold for that host.
 *
 * A non-null [directory] is sent as `location[directory]` on every request, which is how almost
 * every endpoint is scoped (features doc §2.6).
 */
class ServerApiFactory(
    private val okHttpClient: OkHttpClient,
    private val serverTls: ServerTls = ServerTls(okHttpClient, UserCertificateSource { emptyList() }),
    private val credentialProvider: CredentialProvider? = null,
) {
    /**
     * A client with no fixed directory, for callers that name the location per request.
     *
     * Phase 2 uses this: `project.list`, `session.active` and `session.list` do not take a
     * location at all, so attaching one to every request would send a parameter the route
     * ignores. The routes that do take one declare `@Query(LocationParam.QUERY_KEY)`.
     */
    fun createForReads(baseUrl: String, trustUserCertificates: Boolean = false): ServerApi =
        create(baseUrl = baseUrl, directory = null, trustUserCertificates = trustUserCertificates)

    fun create(
        baseUrl: String,
        directory: String? = null,
        trustUserCertificates: Boolean = false,
    ): ServerApi {
        val base = baseUrl.toServerBaseUrl()
        var builder = serverTls.clientFor(trustUserCertificates).newBuilder()
            .addInterceptor(AuthInterceptor(credentialProvider = credentialProvider))
        if (!directory.isNullOrBlank()) {
            builder = builder.addInterceptor(LocationInterceptor(directoryProvider = { directory }))
        }
        return Retrofit.Builder()
            .baseUrl(base)
            .client(builder.build())
            .addConverterFactory(OpenCodeJson.asConverterFactory(JSON_MEDIA_TYPE))
            .build()
            .create(ServerApi::class.java)
    }
}

private val JSON_MEDIA_TYPE = "application/json".toMediaType()

/**
 * Normalizes a user-entered or scanned server address into a base URL Retrofit accepts: an
 * `http`/`https` scheme, a host, an optional port, an optional path prefix, and a trailing slash.
 *
 * The trailing slash is required, not cosmetic: Retrofit resolves an endpoint against the base URL
 * by string replacement, so a base of `https://host/opencode` would resolve `api/info` to
 * `https://host/api/info` and silently drop the prefix.
 *
 * A bare `host:port` is accepted as `http://host:port` because that is how a LAN address gets
 * typed, and a query or fragment is dropped because the API answers on fixed paths.
 */
fun String.toServerBaseUrl(): String {
    val trimmed = trim()
    val candidate = if (SCHEME_REGEX.containsMatchIn(trimmed)) trimmed else "http://$trimmed"
    val url = candidate.toHttpUrlOrNull()
        ?: throw IllegalArgumentException("Not a valid server address: $this")
    if (url.scheme != "http" && url.scheme != "https") {
        throw IllegalArgumentException("A server address must use http or https: $this")
    }
    if (url.host.isBlank()) {
        throw IllegalArgumentException("A server address needs a host: $this")
    }
    val path = url.encodedPath.trimEnd('/')
    return url.newBuilder()
        .encodedPath("$path/")
        .query(null)
        .fragment(null)
        .build()
        .toString()
}

/** True when [this] is `http://` on a host that is not loopback, which the UI marks as unencrypted. */
fun HttpUrl.isCleartextLan(): Boolean = scheme == "http" && !isLoopbackHost(host)

/** Loopback hosts, including the Android emulator's alias for the development machine. */
fun isLoopbackHost(host: String): Boolean = when (host.lowercase().trim('[', ']')) {
    "localhost", "127.0.0.1", "::1", "0:0:0:0:0:0:0:1", "10.0.2.2" -> true
    else -> false
}

private val SCHEME_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")
