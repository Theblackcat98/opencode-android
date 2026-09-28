package dev.opencode.android.core.network

import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Request tag that supplies the server credential for a single request.
 *
 * Retrofit passes it with `@Tag`, and callers that use OkHttp directly can set it on the request.
 */
data class ServerAuthCredential(val passwordOrToken: String?)

/**
 * Supplies the credential for a request URL when the request carries no [ServerAuthCredential] tag.
 *
 * Interceptors run on an OkHttp dispatcher thread, so implementations must not block and must not
 * suspend. The app backs this with an in-memory cache of the tokens held by the Keystore-backed
 * credential store (see `core:data`), keyed by the server's base URL.
 */
fun interface CredentialProvider {
    fun credentialFor(url: HttpUrl): String?
}

/**
 * Adds HTTP Basic authentication with the fixed username `opencode` and the server password or the
 * pairing session token, which the server accepts in the password position (features doc §2.2).
 *
 * A request that already carries an `Authorization` header is left untouched, and a blank
 * credential adds no header at all: a server without a password has auth disabled.
 */
class AuthInterceptor(
    private val staticCredential: String? = null,
    private val credentialProvider: CredentialProvider? = null,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        if (request.header(HEADER_AUTHORIZATION) != null) {
            return chain.proceed(request)
        }

        val credential = request.tag(ServerAuthCredential::class.java)?.passwordOrToken
            ?: staticCredential
            ?: credentialProvider?.credentialFor(request.url)

        return if (credential.isNullOrBlank()) {
            chain.proceed(request)
        } else {
            chain.proceed(
                request.newBuilder()
                    .header(HEADER_AUTHORIZATION, Credentials.basic(AUTH_USER, credential))
                    .build(),
            )
        }
    }

    companion object {
        const val AUTH_USER = "opencode"
        const val HEADER_AUTHORIZATION = "Authorization"
    }
}
