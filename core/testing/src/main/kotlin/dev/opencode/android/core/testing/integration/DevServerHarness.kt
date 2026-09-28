package dev.opencode.android.core.testing.integration

import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
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

    fun execute(path: String): Response {
        val call = client().newCall(request(path))
        return call.execute()
    }
}
