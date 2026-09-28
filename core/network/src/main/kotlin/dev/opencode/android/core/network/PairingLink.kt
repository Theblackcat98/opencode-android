package dev.opencode.android.core.network

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Represents a parsed OpenCode pairing link (features doc §2.3).
 *
 * Link format: `http(s)://<host>:<port>/auth/connect/<code>`
 * Where `code` matches `[A-Za-z0-9_-]+`.
 */
data class PairingLink(
    val baseUrl: String,
    val code: String,
) {
    companion object {
        private val PAIRING_URL_REGEX = Regex(
            """^(https?://[^/]+)/auth/connect/([A-Za-z0-9_-]+)/?${'$'}""",
            RegexOption.IGNORE_CASE,
        )

        /**
         * Parses a pairing URL or QR code payload.
         * Returns `PairingLink` if valid, or `null` otherwise.
         */
        fun parse(input: String?): PairingLink? {
            if (input.isNullOrBlank()) return null
            val trimmed = input.trim()

            val match = PAIRING_URL_REGEX.find(trimmed) ?: return null
            val rawBaseUrl = match.groupValues[1]
            val code = match.groupValues[2]

            // Validate that the base URL is a syntactically valid HTTP/HTTPS URL
            val httpUrl = rawBaseUrl.toHttpUrlOrNull() ?: return null
            val scheme = httpUrl.scheme.lowercase()
            if (scheme != "http" && scheme != "https") return null

            // Construct normalized base URL: scheme://host[:port]
            val portPart = if (
                (scheme == "http" && httpUrl.port == 80) ||
                (scheme == "https" && httpUrl.port == 443)
            ) {
                ""
            } else {
                ":${httpUrl.port}"
            }
            val normalizedBaseUrl = "$scheme://${httpUrl.host}$portPart"

            return PairingLink(
                baseUrl = normalizedBaseUrl,
                code = code,
            )
        }
    }
}
