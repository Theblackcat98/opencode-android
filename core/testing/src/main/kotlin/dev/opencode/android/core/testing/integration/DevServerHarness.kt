package dev.opencode.android.core.testing.integration

import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.util.concurrent.TimeUnit

/**
 * Access to the dev server environment started by `scripts/dev-server.sh`.
 */
object DevServerHarness {
    val url: String?
        get() = System.getProperty("opencode.it.url")
            ?: System.getenv("OPENCODE_URL")

    val password: String?
        get() = System.getProperty("opencode.it.password")
            ?: System.getenv("OPENCODE_PASSWORD")

    val directory: String?
        get() = System.getProperty("opencode.it.directory")
            ?: System.getenv("OPENCODE_DIRECTORY")

    val version: String?
        get() = System.getProperty("opencode.it.version")
            ?: System.getenv("OPENCODE_VERSION")

    val fakeProviderUrl: String?
        get() = System.getProperty("opencode.it.fakeProviderUrl")
            ?: System.getenv("FAKE_PROVIDER_URL")

    val isAvailable: Boolean
        get() = !url.isNullOrBlank() && !password.isNullOrBlank()

    fun client(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /** A client for long-lived calls such as the event stream, which must not time out. */
    fun streamingClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    fun request(path: String): Request {
        val serverUrl = url ?: error("Integration server URL not configured")
        val pass = password ?: error("Integration server password not configured")
        val cleanBase = serverUrl.trimEnd('/')
        val cleanPath = if (path.startsWith('/')) path else "/$path"
        return Request.Builder()
            .url("$cleanBase$cleanPath")
            .header("Authorization", Credentials.basic("opencode", pass))
            .header("Accept", "application/json")
            .build()
    }

    fun execute(path: String): Response = client().newCall(request(path)).execute()

    fun postJson(path: String, json: String = "{}"): Response {
        val post = request(path).newBuilder()
            .post(json.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return client().newCall(post).execute()
    }

    /**
     * Mints a pairing code with `POST /api/pair`, the route the CLI's `opencode pair` also uses.
     *
     * It is not in the published OpenAPI spec, so `null` means this build has no such route and
     * the caller skips the pairing scenario instead of failing on an unrelated capability.
     */
    fun createPairingCode(): String? = postJson("/api/pair").use { response ->
        if (response.code != 200) return@use null
        val body = response.body.string()
        Regex("\"code\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)
    }

    private val JSON_MEDIA_TYPE = "application/json".toMediaType()
}
