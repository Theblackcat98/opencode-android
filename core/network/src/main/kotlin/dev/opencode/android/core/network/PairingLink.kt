package dev.opencode.android.core.network

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * A parsed `opencode pair` link (features doc §2.3).
 *
 * The format is `http(s)://<host>[:port]/auth/connect/<code>`, where the code matches
 * `[A-Za-z0-9_-]+`, works once, and expires after five minutes. A path prefix before
 * `/auth/connect/` is kept, so a server behind a reverse-proxy subpath still works.
 */
data class PairingLink(
    /** Normalized base URL: `scheme://host[:port][/prefix]`, with a default port left out. */
    val baseUrl: String,
    val code: String,
) {
    companion object {
        private const val DEFAULT_HTTP_PORT = 80
        private const val DEFAULT_HTTPS_PORT = 443

        private val PAIRING_URL_REGEX = Regex(
            "^([a-zA-Z][a-zA-Z0-9+.\\-]*://[^/?#\\s]+(?:/[^\\s?#]*)?)/auth/connect/([A-Za-z0-9_-]+)/?$",
        )

        /**
         * Parses a scanned QR payload, a pasted link, or a shared link.
         *
         * Whitespace around the payload is ignored, so a QR code with padding still works, and any
         * scheme other than `http` or `https` is rejected.
         */
        fun parse(input: String?): PairingLink? {
            if (input.isNullOrBlank()) return null
            val match = PAIRING_URL_REGEX.find(input.trim()) ?: return null
            val httpUrl = match.groupValues[1].toHttpUrlOrNull() ?: return null

            val scheme = httpUrl.scheme.lowercase()
            if (scheme != "http" && scheme != "https") return null

            val port = when (httpUrl.port) {
                DEFAULT_HTTP_PORT, DEFAULT_HTTPS_PORT -> ""
                else -> ":${httpUrl.port}"
            }
            val prefix = httpUrl.encodedPath.trimEnd('/')
            return PairingLink(
                baseUrl = "$scheme://${httpUrl.host}$port$prefix",
                code = match.groupValues[2],
            )
        }
    }
}
