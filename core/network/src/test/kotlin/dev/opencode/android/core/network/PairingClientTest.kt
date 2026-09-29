package dev.opencode.android.core.network

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PairingClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: PairingClient
    private lateinit var link: PairingLink

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = PairingClient(ServerApiFactory(OkHttpClient()))
        link = PairingLink(baseUrl = server.url("/").toString().trimEnd('/'), code = "abc123_XYZ")
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun redeemsACodeForAToken() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .setHeader("Content-Type", "application/json")
                .body("""{"token":"1793162917.Lp6Nt93EkpHGOFlrLEgx0nwrflc9EKPHXbFv_NJnTg4"}""")
                .build(),
        )

        val result = client.redeem(link)

        assertTrue("Expected success, got $result", result is PairingRedemptionResult.Success)
        assertEquals(
            "1793162917.Lp6Nt93EkpHGOFlrLEgx0nwrflc9EKPHXbFv_NJnTg4",
            (result as PairingRedemptionResult.Success).token,
        )
    }

    @Test
    fun asksForJsonAndSendsNoCredential() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .setHeader("Content-Type", "application/json")
                .body("""{"token":"t"}""")
                .build(),
        )

        client.redeem(link)

        val recorded = server.takeRequest()
        assertEquals("/auth/connect/abc123_XYZ", recorded.url.encodedPath)
        assertEquals("application/json", recorded.headers["Accept"])
        assertNull(recorded.headers["Authorization"])
    }

    @Test
    fun aUsedOrExpiredCodeIsRejected() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .code(401)
                .body("""{"_tag":"UnauthorizedError","message":"Pairing link expired or already used"}""")
                .build(),
        )

        val result = client.redeem(link)

        assertEquals(
            PairingErrorType.CODE_REJECTED,
            (result as PairingRedemptionResult.Failure).errorType,
        )
    }

    @Test
    fun aServerErrorIsNotReportedAsARejectedCode() = runBlocking {
        server.enqueue(MockResponse.Builder().code(503).build())

        val result = client.redeem(link)

        assertEquals(
            PairingErrorType.SERVER_ERROR,
            (result as PairingRedemptionResult.Failure).errorType,
        )
    }

    @Test
    fun aBrowserRedirectInsteadOfJsonIsMalformed() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .setHeader("Content-Type", "text/html")
                .body("<html>redirecting</html>")
                .build(),
        )

        val result = client.redeem(link)

        assertEquals(
            PairingErrorType.MALFORMED_RESPONSE,
            (result as PairingRedemptionResult.Failure).errorType,
        )
    }

    @Test
    fun anEmptyTokenIsMalformed() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .setHeader("Content-Type", "application/json")
                .body("""{"token":""}""")
                .build(),
        )

        val result = client.redeem(link)

        assertEquals(
            PairingErrorType.MALFORMED_RESPONSE,
            (result as PairingRedemptionResult.Failure).errorType,
        )
    }

    @Test
    fun anUnreachableServerIsReportedWithoutCrashing() = runBlocking {
        val closed = MockWebServer().apply { start() }
        val unused = PairingLink(baseUrl = closed.url("/").toString().trimEnd('/'), code = "code")
        closed.close()

        val result = client.redeem(unused)

        assertTrue(
            "Expected a failure, got $result",
            result is PairingRedemptionResult.Failure,
        )
        assertEquals(
            PairingErrorType.UNREACHABLE,
            (result as PairingRedemptionResult.Failure).errorType,
        )
    }

    @Test
    fun anUnusableAddressIsReportedWithoutCrashing() = runBlocking {
        val result = client.redeem(PairingLink(baseUrl = "not a url", code = "code"))

        assertEquals(
            PairingErrorType.UNREACHABLE,
            (result as PairingRedemptionResult.Failure).errorType,
        )
    }
}
