package dev.opencode.android.core.data.integration

import dev.opencode.android.core.model.ServerInfo
import dev.opencode.android.core.model.json.OpenCodeJson
import dev.opencode.android.core.network.AuthInterceptor
import dev.opencode.android.core.network.ConnectionEventType
import dev.opencode.android.core.network.ConnectionState
import dev.opencode.android.core.network.DisconnectCause
import dev.opencode.android.core.network.EventStreamClient
import dev.opencode.android.core.network.PairingClient
import dev.opencode.android.core.network.PairingLink
import dev.opencode.android.core.network.PairingRedemptionResult
import dev.opencode.android.core.network.ServerApiFactory
import dev.opencode.android.core.network.ServerValidationResult
import dev.opencode.android.core.network.ServerValidator
import dev.opencode.android.core.network.ValidationErrorType
import dev.opencode.android.core.network.VersionStatus
import dev.opencode.android.core.testing.integration.DevServerHarness
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Credentials
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Pairing and the event stream against a real `opencode serve` started by `scripts/dev-server.sh`.
 *
 * This is the half of the Phase 1 exit criteria a fake server cannot prove: that a code minted by
 * `POST /api/pair` is accepted exactly once by `GET /auth/connect/{code}`, that the token it returns
 * authenticates as the Basic password, and that a real server's stream starts with
 * `server.connected` and keeps the idle watchdog quiet with heartbeats.
 *
 * The whole class is skipped when no server is configured, so it never fails a plain unit-test run.
 */
class LivePairingIntegrationTest {

    private lateinit var scope: CoroutineScope
    private lateinit var apiFactory: ServerApiFactory

    @Before
    fun setUp() {
        assumeTrue(
            "No dev server configured. Start one with 'scripts/dev-server.sh start'.",
            DevServerHarness.isAvailable,
        )
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        apiFactory = ServerApiFactory(DevServerHarness.client())
    }

    @After
    fun tearDown() {
        if (::scope.isInitialized) scope.cancel()
    }

    @Test
    fun aCodeFromTheServerIsRedeemedOnceAndItsTokenAuthenticates() = runBlocking {
        val code = DevServerHarness.createPairingCode()
        assumeTrue("This build has no POST /api/pair route, so the pairing scenario is skipped", code != null)
        val link = PairingLink.parse("${DevServerHarness.url}/auth/connect/$code")
        assertNotNull("A code from the server must parse as a pairing link", link)

        val first = PairingClient(apiFactory).redeem(link!!)
        assertTrue("Redemption failed: $first", first is PairingRedemptionResult.Success)
        val token = (first as PairingRedemptionResult.Success).token
        assertTrue("The token must not be blank", token.isNotBlank())

        val authenticated = DevServerHarness.client().newCall(
            Request.Builder()
                .url("${DevServerHarness.url}/api/info")
                .header("Authorization", Credentials.basic(AuthInterceptor.AUTH_USER, token))
                .build(),
        ).execute()
        val body = authenticated.use { response ->
            assertEquals("The issued token must authenticate", 200, response.code)
            response.body.string()
        }
        assertEquals(2, OpenCodeJson.decodeFromString<ServerInfo>(body).majorVersion)

        // A code works once: replaying it must be rejected, which is what makes it safe to carry
        // over a QR code in the first place.
        val replay = PairingClient(apiFactory).redeem(link)
        assertTrue("A redeemed code must not work twice, got $replay", replay is PairingRedemptionResult.Failure)
    }

    @Test
    fun aWrongPasswordIsRejectedWithTheUnauthorizedClass() = runBlocking {
        val result = ServerValidator(apiFactory).validate(
            baseUrl = requireNotNull(DevServerHarness.url),
            credential = "definitely-not-the-password",
        )

        assertTrue("Expected a classified failure, got $result", result is ServerValidationResult.Failure)
        assertEquals(
            ValidationErrorType.UNAUTHORIZED,
            (result as ServerValidationResult.Failure).errorType,
        )
    }

    @Test
    fun theLiveServerIsOnATestedVersion() = runBlocking {
        val result = ServerValidator(apiFactory).validate(
            baseUrl = requireNotNull(DevServerHarness.url),
            credential = DevServerHarness.password,
        )

        assertTrue("Expected success, got $result", result is ServerValidationResult.Success)
        assertEquals(
            VersionStatus.TESTED,
            (result as ServerValidationResult.Success).versionStatus,
        )
    }

    @Test
    fun theLiveStreamStartsWithServerConnectedAndHeartbeats() = runBlocking {
        val stream = newStream()
        try {
            stream.start(scope)
            withTimeout(STREAM_TIMEOUT_MILLIS) { stream.state.first { it is ConnectionState.Connected } }

            val first = stream.inspectedEvents.value.first()
            assertEquals(
                "The stream must start with server.connected",
                EventStreamClient.SERVER_CONNECTED,
                first.type,
            )
            assertEquals(1L, stream.resyncCount.value)

            // A heartbeat arriving keeps the idle watchdog quiet, which is the whole point of
            // parsing the `: heartbeat` comments instead of dropping them.
            withTimeout(HEARTBEAT_TIMEOUT_MILLIS) {
                stream.logs.first { logs -> logs.any { it.type == ConnectionEventType.HEARTBEAT } }
            }
            assertTrue(
                "The watchdog fired on a live server that heartbeats",
                stream.logs.value.none { it.type == ConnectionEventType.WATCHDOG_TIMEOUT },
            )
        } finally {
            stream.stop()
        }
    }

    @Test
    fun reconnectingFiresAnotherResyncOnALiveServer() = runBlocking {
        val stream = newStream()
        try {
            stream.start(scope)
            withTimeout(STREAM_TIMEOUT_MILLIS) { stream.resyncCount.first { it >= 1L } }
            val firstConnectedAt = (stream.state.value as ConnectionState.Connected).connectedAt

            // The app's own "reconnect now" path, which is what a server restart leads to once the
            // dead socket is noticed.
            stream.reconnectNow()
            withTimeout(STREAM_TIMEOUT_MILLIS) { stream.resyncCount.first { it >= 2L } }

            assertTrue(
                "The reconnect must produce a new connection",
                (stream.state.value as ConnectionState.Connected).connectedAt >= firstConnectedAt,
            )
            assertTrue(stream.state.value is ConnectionState.Connected)
        } finally {
            stream.stop()
        }
    }

    @Test
    fun aLiveStreamWithARejectedCredentialAsksForRePairing() = runBlocking {
        val stream = EventStreamClient(
            baseUrl = requireNotNull(DevServerHarness.url),
            credentialProvider = { "not-the-password" },
            okHttpClient = DevServerHarness.streamingClient(),
        )
        try {
            stream.start(scope)
            val state = withTimeout(STREAM_TIMEOUT_MILLIS) {
                stream.state.first {
                    it is ConnectionState.Disconnected && it.cause == DisconnectCause.AUTHORIZATION_REQUIRED
                }
            }
            assertEquals(false, (state as ConnectionState.Disconnected).willRetry)
        } finally {
            stream.stop()
        }
    }

    private fun newStream() = EventStreamClient(
        baseUrl = requireNotNull(DevServerHarness.url),
        credentialProvider = { DevServerHarness.password },
        okHttpClient = DevServerHarness.streamingClient(),
        watchdogTimeoutMillis = EventStreamClient.DEFAULT_WATCHDOG_TIMEOUT_MILLIS,
        watchdogCheckIntervalMillis = 2_000,
    )

    private companion object {
        const val STREAM_TIMEOUT_MILLIS = 45_000L
        const val HEARTBEAT_TIMEOUT_MILLIS = 40_000L
    }
}
