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
 * [OkHttpClient] (and therefore one connection pool, one dispatcher and the auth interceptor).
 *
 * A non-null [directory] is sent as `location[directory]` on every request, which is how almost
 * every endpoint is scoped (features doc §2.6).
 */
class ServerApiFactory(
    private val okHttpClient: OkHttpClient,
    private val serverTls: ServerTls = ServerTls(okHttpClient, UserCertificateSource { emptyList() }),
) {
    fun create(
        baseUrl: String,
        directory: String? = null,
        trustUserCertificates: Boolean = false,
    ): ServerApi {
        val base = baseUrl.toServerBaseUrl()
        val scoped = serverTls.clientFor(trustUserCertificates)
        val client = if (directory.isNullOrBlank()) {
            scoped
        } else {
            scoped.newBuilder()
                .addInterceptor(LocationInterceptor(directoryProvider = { directory }))
                .build()
        }
        return Retrofit.Builder()
            .baseUrl(base)
            .client(client)
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
    return url.newBuilder()
        .encodedPath(if (url.encodedPath.isBlank()) "/" else url.encodedPath)
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
