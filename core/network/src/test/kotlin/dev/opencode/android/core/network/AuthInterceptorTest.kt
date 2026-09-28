package dev.opencode.android.core.network

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class AuthInterceptorTest {

    private val authUser = AuthInterceptor.AUTH_USER

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun addsBasicAuthHeaderWhenCredentialProvided() {
        server.enqueue(MockResponse.Builder().code(200).build())

        val client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(staticCredential = "secret-token-123"))
            .build()

        client.newCall(Request.Builder().url(server.url("/api/info")).build()).execute().close()

        val recorded = server.takeRequest()
        // Base64 of "opencode:secret-token-123" is b3BlbmNvZGU6c2VjcmV0LXRva2VuLTEyMw==
        assertEquals("Basic b3BlbmNvZGU6c2VjcmV0LXRva2VuLTEyMw==", recorded.headers["Authorization"])
    }

    @Test
    fun omitsAuthHeaderWhenNoCredentialProvided() {
        server.enqueue(MockResponse.Builder().code(200).build())

        val client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(staticCredential = null))
            .build()

        client.newCall(Request.Builder().url(server.url("/api/info")).build()).execute().close()

        val recorded = server.takeRequest()
        assertNull(recorded.headers["Authorization"])
    }

    @Test
    fun omitsAuthHeaderWhenCredentialIsBlank() {
        server.enqueue(MockResponse.Builder().code(200).build())

        val client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(staticCredential = "   "))
            .build()

        client.newCall(Request.Builder().url(server.url("/api/info")).build()).execute().close()

        val recorded = server.takeRequest()
        assertNull(recorded.headers["Authorization"])
    }

    @Test
    fun preservesExistingAuthHeader() {
        server.enqueue(MockResponse.Builder().code(200).build())

        val client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(staticCredential = "ignored-credential"))
            .build()

        val request = Request.Builder()
            .url(server.url("/api/info"))
            .header("Authorization", "Bearer custom-jwt")
            .build()

        client.newCall(request).execute().close()

        val recorded = server.takeRequest()
        assertEquals("Bearer custom-jwt", recorded.headers["Authorization"])
    }

    @Test
    fun tagCredentialOverridesStaticCredential() {
        server.enqueue(MockResponse.Builder().code(200).build())

        val client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(staticCredential = "static-pass"))
            .build()

        val request = Request.Builder()
            .url(server.url("/api/info"))
            .tag(ServerAuthCredential::class.java, ServerAuthCredential("tag-pass"))
            .build()

        client.newCall(request).execute().close()

        val recorded = server.takeRequest()
        // Base64 of "opencode:tag-pass" is b3BlbmNvZGU6dGFnLXBhc3M=
        assertEquals("Basic b3BlbmNvZGU6dGFnLXBhc3M=", recorded.headers["Authorization"])
    }

    @Test
    fun providerSuppliesTheCredentialOfTheServersOwnHostOnly() {
        val known = server.hostName
        server.enqueue(MockResponse.Builder().code(200).build())
        server.enqueue(MockResponse.Builder().code(200).build())

        val client = OkHttpClient.Builder()
            .addInterceptor(
                AuthInterceptor(
                    credentialProvider = CredentialProvider { url ->
                        if (url.host == known) "cached-token" else null
                    },
                ),
            )
            .build()

        val port = server.port
        client.newCall(Request.Builder().url("http://$known:$port/api/info").build()).execute().close()
        val otherHost = if (known == "127.0.0.1") "localhost" else "127.0.0.1"
        client.newCall(Request.Builder().url("http://$otherHost:$port/api/info").build()).execute().close()

        val knownRequest = server.takeRequest()
        val otherRequest = server.takeRequest()
        assertEquals(known, knownRequest.url.host)
        assertEquals(Credentials.basic(authUser, "cached-token"), knownRequest.headers["Authorization"])
        assertEquals(otherHost, otherRequest.url.host)
        assertNull(otherRequest.headers["Authorization"])
    }

    @Test
    fun aPerRequestTagWinsOverTheProvider() {
        server.enqueue(MockResponse.Builder().code(200).build())

        val client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(credentialProvider = CredentialProvider { "cached" }))
            .build()

        val request = Request.Builder()
            .url(server.url("/api/info"))
            .tag(ServerAuthCredential::class.java, ServerAuthCredential("request-scoped"))
            .build()

        client.newCall(request).execute().close()

        val recorded = server.takeRequest()
        assertEquals(Credentials.basic(authUser, "request-scoped"), recorded.headers["Authorization"])
    }

    @Test
    fun credentialProviderIsNotConsultedWhenTheRequestAlreadyAuthenticates() {
        server.enqueue(MockResponse.Builder().code(200).build())
        var consulted = false

        val client = OkHttpClient.Builder()
            .addInterceptor(
                AuthInterceptor(
                    credentialProvider = CredentialProvider {
                        consulted = true
                        "cached"
                    },
                ),
            )
            .build()

        val request = Request.Builder()
            .url(server.url("/api/info"))
            .header("Authorization", "Basic already-set")
            .build()
        client.newCall(request).execute().close()
        server.takeRequest()

        assertEquals(false, consulted)
    }
}
