package dev.opencode.android.core.network

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Helpers for passing the OpenCode `location[directory]` parameter or `x-opencode-directory` header
 * (features doc §2.6).
 */
object LocationParam {
    const val QUERY_KEY = "location[directory]"
    const val HEADER_KEY = "x-opencode-directory"

    fun appendToUrl(url: String, directory: String?): String {
        if (directory.isNullOrBlank()) return url
        val delimiter = if (url.contains('?')) "&" else "?"
        val encodedDirectory = URLEncoder.encode(directory, StandardCharsets.UTF_8.name())
        val encodedKey = URLEncoder.encode(QUERY_KEY, StandardCharsets.UTF_8.name())
        return "$url$delimiter$encodedKey=$encodedDirectory"
    }

    fun applyToHttpUrl(builder: HttpUrl.Builder, directory: String?): HttpUrl.Builder {
        if (!directory.isNullOrBlank()) {
            builder.addQueryParameter(QUERY_KEY, directory)
        }
        return builder
    }

    fun applyToRequest(builder: Request.Builder, directory: String?): Request.Builder {
        if (!directory.isNullOrBlank()) {
            val encoded = URLEncoder.encode(directory, StandardCharsets.UTF_8.name())
            builder.header(HEADER_KEY, encoded)
        }
        return builder
    }
}

/**
 * An interceptor that adds the `location[directory]` query parameter or `x-opencode-directory` header
 * to requests when a directory is specified.
 */
class LocationInterceptor(
    private val directoryProvider: () -> String?,
    private val useHeader: Boolean = false,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val directory = directoryProvider()
        if (directory.isNullOrBlank()) {
            return chain.proceed(chain.request())
        }

        val request = chain.request()
        val newRequest = if (useHeader) {
            val encoded = URLEncoder.encode(directory, StandardCharsets.UTF_8.name())
            request.newBuilder()
                .header(LocationParam.HEADER_KEY, encoded)
                .build()
        } else {
            val newUrl = request.url.newBuilder()
                .addQueryParameter(LocationParam.QUERY_KEY, directory)
                .build()
            request.newBuilder()
                .url(newUrl)
                .build()
        }

        return chain.proceed(newRequest)
    }
}
