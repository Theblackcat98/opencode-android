package dev.opencode.android.core.network

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class AuthInterceptorTest {

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
        val authHeader = recorded.headers["Authorization"]
        // Base64 of "opencode:secret-token-123" is b3BlbmNvZGU6c2VjcmV0LXRva2VuLTEyMw==
        assertEquals("Basic b3BlbmNvZGU6c2VjcmV0LXRva2VuLTEyMw==", authHeader)
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
        val authHeader = recorded.headers["Authorization"]
        // Base64 of "opencode:tag-pass" is b3BlbmNvZGU6dGFnLXBhc3M=
        assertEquals("Basic b3BlbmNvZGU6dGFnLXBhc3M=", authHeader)
    }
}
