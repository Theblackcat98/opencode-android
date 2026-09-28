package dev.opencode.android.core.network

import okhttp3.Credentials
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Request tag to supply server auth credentials on a per-request basis.
 */
data class ServerAuthCredential(val passwordOrToken: String?)

/**
 * Supplies credentials for a server request if not attached as a tag.
 */
fun interface CredentialProvider {
    suspend fun getCredential(url: String): String?
}

/**
 * Interceptor that injects HTTP Basic authentication with the fixed username `opencode`
 * and the server password or pairing session token.
 *
 * When no credential is provided (or it is blank), no `Authorization` header is added,
 * matching OpenCode's behavior where auth is disabled if no password was set.
 */
class AuthInterceptor(
    private val staticCredential: String? = null,
    private val credentialProvider: (suspend (String) -> String?)? = null,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        // If the request already has an Authorization header, respect it.
        if (originalRequest.header("Authorization") != null) {
            return chain.proceed(originalRequest)
        }

        // Check for request tag first, then static credential, then provider
        val tagCredential = originalRequest.tag(ServerAuthCredential::class.java)?.passwordOrToken
        val credential = tagCredential ?: staticCredential ?: run {
            credentialProvider?.let { provider ->
                kotlinx.coroutines.runBlocking {
                    provider(originalRequest.url.toString())
                }
            }
        }

        return if (!credential.isNullOrBlank()) {
            val authenticatedRequest = originalRequest.newBuilder()
                .header("Authorization", Credentials.basic(AUTH_USER, credential))
                .build()
            chain.proceed(authenticatedRequest)
        } else {
            chain.proceed(originalRequest)
        }
    }

    companion object {
        const val AUTH_USER = "opencode"
    }
}
