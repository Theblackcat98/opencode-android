package dev.opencode.android.core.network

import okhttp3.OkHttpClient

/**
 * Builds the HTTP client for one server, honoring its TLS trust setting.
 *
 * Servers share the base client, so this only derives a copy when a server opts into trusting
 * user-installed CAs. The client is created once per connection, not once per request.
 */
class ServerTls(
    private val baseClient: OkHttpClient,
    private val userCertificateSource: UserCertificateSource,
) {
    /**
     * The client to use for a server that trusts, or does not trust, user-installed CAs.
     *
     * Returns the shared client unchanged when the setting is off or the device has no readable
     * user CAs, so the common case stays a single pooled client.
     */
    fun clientFor(trustUserCertificates: Boolean): OkHttpClient {
        if (!trustUserCertificates) return baseClient
        val userCertificates = userCertificateSource.certificates()
        if (userCertificates.isEmpty()) return baseClient
        val trustManager = UserCaTrust.trustManager(userCertificates = userCertificates)
        return baseClient.newBuilder()
            // A private socket factory also means no platform ALPN, so this connection is
            // HTTP/1.1. The OpenCode server serves SSE over HTTP/1.1 anyway.
            .sslSocketFactory(UserCaTrust.socketFactory(trustManager), trustManager)
            .build()
    }
}
