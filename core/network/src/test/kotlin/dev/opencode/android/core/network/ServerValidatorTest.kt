package dev.opencode.android.core.network

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ServerValidatorTest {

    private lateinit var server: MockWebServer
    private lateinit var validator: ServerValidator

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        validator = ServerValidator()
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun returnsSuccessForValidServerInfo() = runTest {
        val json = """
            {
                "version": "2.0.18",
                "pid": 12345,
                "urls": ["http://192.168.1.50:4096"],
                "paths": {"tmp": "/tmp/opencode"}
            }
        """.trimIndent()
        server.enqueue(MockResponse.Builder().code(200).body(json).build())

        val result = validator.validate(server.url("/").toString(), "valid-pass")
        assertTrue("Expected Success, got $result", result is ServerValidationResult.Success)
        val success = result as ServerValidationResult.Success
        assertEquals("2.0.18", success.serverInfo.version)
        assertEquals(2, success.serverInfo.majorVersion)
        assertEquals(12345L, success.serverInfo.pid)
    }

    @Test
    fun returnsUnauthorizedFailureOn401() = runTest {
        server.enqueue(MockResponse.Builder().code(401).build())

        val result = validator.validate(server.url("/").toString(), "wrong-pass")
        assertTrue("Expected Failure, got $result", result is ServerValidationResult.Failure)
        val failure = result as ServerValidationResult.Failure
        assertEquals(ValidationErrorType.UNAUTHORIZED, failure.errorType)
        assertTrue(failure.userMessage.contains("re-pair"))
    }

    @Test
    fun returnsUnsupportedVersionForNonV2Server() = runTest {
        val json = """
            {
                "version": "1.8.0",
                "pid": 54321,
                "urls": ["http://127.0.0.1:4096"],
                "paths": {"tmp": "/tmp/opencode"}
            }
        """.trimIndent()
        server.enqueue(MockResponse.Builder().code(200).body(json).build())

        val result = validator.validate(server.url("/").toString())
        assertTrue("Expected Failure, got $result", result is ServerValidationResult.Failure)
        val failure = result as ServerValidationResult.Failure
        assertEquals(ValidationErrorType.UNSUPPORTED_VERSION, failure.errorType)
        assertTrue(failure.userMessage.contains("OpenCode V2.x"))
    }

    @Test
    fun returnsConnectionRefusedHelpForRefusedConnection() = runTest {
        // Use a closed port to trigger ConnectException
        val unusedPortServer = MockWebServer()
        unusedPortServer.start()
        val unusedUrl = unusedPortServer.url("/").toString()
        unusedPortServer.close()

        val result = validator.validate(unusedUrl)
        assertTrue("Expected Failure, got $result", result is ServerValidationResult.Failure)
        val failure = result as ServerValidationResult.Failure
        assertEquals(ValidationErrorType.CONNECTION_REFUSED_LOCALHOST, failure.errorType)
        assertTrue(
            failure.userMessage.contains("0.0.0.0") ||
            failure.userMessage.contains("localhost"),
        )
    }
}
