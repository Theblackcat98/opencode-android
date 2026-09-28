package dev.opencode.android.core.network

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

/**
 * The `location[directory]` parameter and the `x-opencode-directory` header, which scope almost
 * every endpoint to a directory (features doc §2.6).
 *
 * Both forms carry the same percent-encoded absolute path, so a directory with a space or a `#`
 * survives the trip.
 */
object LocationParam {
    const val QUERY_KEY = "location[directory]"
    const val HEADER_KEY = "x-opencode-directory"

    /** Percent-encodes one path segment set, per RFC 3986, so a space is `%20` and not `+`. */
    fun encode(value: String): String = buildString {
        value.encodeToByteArray().forEach { byte ->
            val code = byte.toInt() and 0xFF
            val char = code.toChar()
            if (char.isLetterOrDigit() || char in UNRESERVED) {
                append(char)
            } else {
                append('%')
                append(HEX[code shr 4])
                append(HEX[code and 0x0F])
            }
        }
    }

    /** Appends the parameter to a URL string. A blank directory leaves the URL untouched. */
    fun appendToUrl(url: String, directory: String?): String {
        if (directory.isNullOrBlank()) return url
        val parsed = url.toHttpUrlOrNull() ?: return url
        return applyToHttpUrl(parsed.newBuilder(), directory).build().toString()
    }

    fun applyToHttpUrl(builder: HttpUrl.Builder, directory: String?): HttpUrl.Builder {
        if (directory.isNullOrBlank() || builder.build().queryParameter(QUERY_KEY) != null) return builder
        return builder.addQueryParameter(QUERY_KEY, directory)
    }

    fun applyToRequest(builder: Request.Builder, directory: String?): Request.Builder {
        if (directory.isNullOrBlank()) return builder
        return builder.header(HEADER_KEY, encode(directory))
    }

    private const val UNRESERVED = "-._~"
    private const val HEX = "0123456789ABCDEF"
}

/**
 * Adds the location parameter to every request that does not already carry it.
 *
 * One instance belongs to one server's client, so the directory is fixed at construction; the
 * per-call alternative is [LocationParam.applyToHttpUrl] on a single request.
 */
class LocationInterceptor(
    private val directoryProvider: () -> String?,
    private val useHeader: Boolean = false,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val directory = directoryProvider()
        if (directory.isNullOrBlank()) {
            return chain.proceed(request)
        }

        val located = if (useHeader) {
            LocationParam.applyToRequest(request.newBuilder(), directory).build()
        } else {
            request.newBuilder()
                .url(LocationParam.applyToHttpUrl(request.url.newBuilder(), directory).build())
                .build()
        }
        return chain.proceed(located)
    }
}
