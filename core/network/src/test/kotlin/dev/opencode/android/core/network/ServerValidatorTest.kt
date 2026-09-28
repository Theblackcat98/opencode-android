package dev.opencode.android.core.network

import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.tls.HeldCertificate
import okhttp3.tls.HandshakeCertificates
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.util.concurrent.TimeUnit

class ServerValidatorTest {

    private lateinit var server: MockWebServer
    private lateinit var validator: ServerValidator

    private val infoJson = """
        {
            "version": "2.0.18",
            "pid": 12345,
            "urls": ["http://192.168.1.50:4096"],
            "paths": {"tmp": "/tmp/opencode"}
        }
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        validator = ServerValidator(ServerApiFactory(OkHttpClient()))
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun returnsTheServerInfoForAV2Server() = runTest {
        server.enqueue(json(infoJson))

        val result = validator.validate(baseUrl(), "valid-pass")

        assertTrue("Expected Success, got $result", result is ServerValidationResult.Success)
        val success = result as ServerValidationResult.Success
        assertEquals("2.0.18", success.serverInfo.version)
        assertEquals(2, success.serverInfo.majorVersion)
        assertEquals(12345L, success.serverInfo.pid)
        assertEquals(listOf("http://192.168.1.50:4096"), success.serverInfo.urls)
        assertEquals(VersionStatus.TESTED, success.versionStatus)
    }

    @Test
    fun sendsTheCredentialAsBasicAuthWithTheFixedUserName() = runTest {
        server.enqueue(json(infoJson))

        validator.validate(baseUrl(), "valid-pass")

        val recorded = server.takeRequest()
        assertEquals(
            Credentials.basic(AuthInterceptor.AUTH_USER, "valid-pass"),
            recorded.headers["Authorization"],
        )
    }

    @Test
    fun sendsNoCredentialWhenTheServerHasNoPassword() = runTest {
        server.enqueue(json(infoJson))

        validator.validate(baseUrl(), null)

        assertEquals(null, server.takeRequest().headers["Authorization"])
    }

    @Test
    fun reportsANewerReleaseAsUntestedRatherThanFailing() = runTest {
        server.enqueue(json(infoJson.replace("2.0.18", "2.0.19")))

        val result = validator.validate(baseUrl())

        assertTrue(result is ServerValidationResult.Success)
        assertEquals(VersionStatus.NEWER_UNTESTED, (result as ServerValidationResult.Success).versionStatus)
    }

    @Test
    fun treatsAnOlderReleaseInTheTestedMajorAsTested() = runTest {
        server.enqueue(json(infoJson.replace("2.0.18", "2.0.3")))

        val result = validator.validate(baseUrl())

        assertEquals(VersionStatus.TESTED, (result as ServerValidationResult.Success).versionStatus)
    }

    @Test
    fun rejectsAV1Server() = runTest {
        server.enqueue(json(infoJson.replace("2.0.18", "1.8.0")))

        val result = validator.validate(baseUrl())

        val failure = result as ServerValidationResult.Failure
        assertEquals(ValidationErrorType.UNSUPPORTED_VERSION, failure.errorType)
        assertNotNull(failure.technicalDetail)
    }

    @Test
    fun rejectsAnUnparseableVersion() = runTest {
        server.enqueue(json(infoJson.replace("2.0.18", "nightly")))

        val result = validator.validate(baseUrl())

        assertEquals(
            ValidationErrorType.UNSUPPORTED_VERSION,
            (result as ServerValidationResult.Failure).errorType,
        )
    }

    @Test
    fun classifiesA401AsUnauthorized() = runTest {
        server.enqueue(MockResponse.Builder().code(401).body("{}").build())

        val result = validator.validate(baseUrl(), "wrong-pass")

        assertEquals(
            ValidationErrorType.UNAUTHORIZED,
            (result as ServerValidationResult.Failure).errorType,
        )
    }

    @Test
    fun classifiesA404AsNotAnOpenCodeServer() = runTest {
        server.enqueue(MockResponse.Builder().code(404).build())

        val result = validator.validate(baseUrl())

        assertEquals(
            ValidationErrorType.UNSUPPORTED_VERSION,
            (result as ServerValidationResult.Failure).errorType,
        )
    }

    @Test
    fun classifiesA500AsAServerError() = runTest {
        server.enqueue(MockResponse.Builder().code(500).body("boom").build())

        val result = validator.validate(baseUrl())

        val failure = result as ServerValidationResult.Failure
        assertEquals(ValidationErrorType.SERVER_ERROR, failure.errorType)
        assertTrue(failure.technicalDetail!!.contains("500"))
    }

    @Test
    fun classifiesANonJsonBodyAsMalformed() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body("<html>captive portal</html>").build())

        val result = validator.validate(baseUrl())

        assertEquals(
            ValidationErrorType.MALFORMED_RESPONSE,
            (result as ServerValidationResult.Failure).errorType,
        )
    }

    @Test
    fun classifiesARefusedConnection() = runTest {
        val closed = MockWebServer().apply { start() }
        val unusedUrl = closed.url("/").toString()
        closed.close()

        val result = validator.validate(unusedUrl)

        assertEquals(
            ValidationErrorType.CONNECTION_REFUSED,
            (result as ServerValidationResult.Failure).errorType,
        )
    }

    @Test
    fun classifiesAnUnresolvableHost() = runTest {
        val result = validator.validate("http://no-such-host.invalid:4096")

        val failure = result as ServerValidationResult.Failure
        assertEquals(ValidationErrorType.UNKNOWN_HOST, failure.errorType)
    }

    @Test
    fun classifiesATimeout() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                Thread.sleep(2_000)
                return json(infoJson)
            }
        }
        val impatient = ServerValidator(
            ServerApiFactory(OkHttpClient.Builder().callTimeout(300, TimeUnit.MILLISECONDS).build()),
        )

        val result = impatient.validate(baseUrl())

        assertEquals(ValidationErrorType.TIMEOUT, (result as ServerValidationResult.Failure).errorType)
    }

    @Test
    fun classifiesAnUntrustedCertificate() = runTest {
        val certificates = HandshakeCertificates.Builder()
            .heldCertificate(
                HeldCertificate.Builder()
                    .addSubjectAlternativeName("127.0.0.1")
                    .addSubjectAlternativeName("localhost")
                    .build(),
            )
            .build()
        val httpsServer = MockWebServer()
        httpsServer.useHttps(certificates.sslSocketFactory())
        httpsServer.start()
        try {
            val plain = ServerValidator(ServerApiFactory(OkHttpClient()))
            val result = plain.validate("https://127.0.0.1:${httpsServer.port}/")
            val failure = result as ServerValidationResult.Failure
            assertEquals(ValidationErrorType.TLS_ERROR, failure.errorType)
        } finally {
            httpsServer.close()
        }
    }

    @Test
    fun reportsAnUnusableAddressWithoutTouchingTheNetwork() = runTest {
        val result = validator.validate("not a url at all")

        assertEquals(
            ValidationErrorType.UNKNOWN_HOST,
            (result as ServerValidationResult.Failure).errorType,
        )
    }

    private fun baseUrl(): String = server.url("/").toString()

    private fun json(body: String) = MockResponse.Builder()
        .code(200)
        .setHeader("Content-Type", "application/json")
        .body(body)
        .build()
}
