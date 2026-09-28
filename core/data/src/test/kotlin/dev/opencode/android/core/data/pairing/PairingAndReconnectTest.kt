package dev.opencode.android.core.data.pairing

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.opencode.android.core.data.connection.ServerConnectionManager
import dev.opencode.android.core.data.repository.AddServerErrorType
import dev.opencode.android.core.data.repository.AddServerOutcome
import dev.opencode.android.core.data.repository.DefaultServerRepository
import dev.opencode.android.core.data.repository.ServerHealth
import dev.opencode.android.core.data.security.InMemoryCredentialStore
import dev.opencode.android.core.database.OpenCodeDatabase
import dev.opencode.android.core.network.AuthInterceptor
import dev.opencode.android.core.network.ConnectionEventType
import dev.opencode.android.core.network.ConnectionState
import dev.opencode.android.core.network.DisconnectCause
import dev.opencode.android.core.network.NetworkConnectivityMonitor
import dev.opencode.android.core.network.PairingClient
import dev.opencode.android.core.network.PairingLink
import dev.opencode.android.core.network.ServerApiFactory
import dev.opencode.android.core.network.ServerCredentialCache
import dev.opencode.android.core.network.ServerTls
import dev.opencode.android.core.network.ServerValidator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockResponseBody
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okio.BufferedSink
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * The Phase 1 exit criteria as one flow over a real socket (plan §6, Phase 1): pairing end to end,
 * a rejected credential surfacing as a re-pair prompt, re-pairing in place, and an SSE
 * reconnection that fires a resync on every `server.connected`.
 *
 * It uses MockWebServer rather than the real server so it runs in ordinary CI; the live server is
 * covered separately by `LivePairingIntegrationTest`, which needs `scripts/dev-server.sh`. That is
 * also why it does not live in an `integration` package: the build reserves that name for the tests
 * that need a real `opencode serve`.
 */
@RunWith(AndroidJUnit4::class)
class PairingAndReconnectTest {

    private lateinit var server: MockWebServer
    private lateinit var db: OpenCodeDatabase
    private lateinit var repository: DefaultServerRepository
    private lateinit var connections: ServerConnectionManager
    private lateinit var credentialStore: InMemoryCredentialStore
    private lateinit var credentialCache: ServerCredentialCache
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, OpenCodeDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        server = MockWebServer()
        server.start()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val okHttp = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()
        val serverTls = ServerTls(okHttp) { emptyList() }
        val apiFactory = ServerApiFactory(okHttp, serverTls)
        credentialStore = InMemoryCredentialStore()
        credentialCache = ServerCredentialCache()
        repository = DefaultServerRepository(
            serverDao = db.serverDao(),
            credentialStore = credentialStore,
            serverValidator = ServerValidator(apiFactory),
            pairingClient = PairingClient(apiFactory),
            credentialCache = credentialCache,
        )
        connections = ServerConnectionManager(
            serverRepository = repository,
            credentialCache = credentialCache,
            serverTls = serverTls,
            okHttpClient = okHttp,
            connectivityMonitor = AlwaysOnline,
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
        server.close()
    }

    @Test
    fun aPairingLinkBecomesAConnectedServerWithAStoredCredential() = runBlocking {
        val token = "1793162917.Lp6Nt93EkpHGOFlrLEgx0nwrflc9EKPHXbFv_NJnTg4"
        var infoAuthHeader: String? = null
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.url.encodedPath) {
                REDEEM_PATH -> json("""{"token":"$token"}""")
                INFO_PATH -> {
                    infoAuthHeader = request.headers["Authorization"]
                    json(serverInfo())
                }

                EVENT_PATH -> sse(SERVER_CONNECTED)
                else -> notFound()
            }
        }

        val outcome = repository.addPairedServer(link(), serverName = "Workstation")
        val profile = (outcome as AddServerOutcome.Success).profile

        assertEquals("Workstation", profile.name)
        assertEquals(ServerHealth.CONNECTED, profile.health)
        assertTrue(profile.hasCredential)
        assertEquals(token, credentialStore.getCredential(profile.id))
        assertTrue(credentialCache.holds(profile.baseUrl))
        assertEquals(
            "The token must authenticate as the Basic password, with the fixed user",
            Credentials.basic(AuthInterceptor.AUTH_USER, token),
            infoAuthHeader,
        )

        val connection = connections.connectServer(profile, scope)
        withTimeout(TIMEOUT_MILLIS) {
            connection.connectionState.first { it is ConnectionState.Connected }
        }
        assertEquals(profile.id, connections.activeConnection.value?.serverProfile?.id)
        assertEquals("One server.connected means one resync", 1L, connection.resyncCount.value)
    }

    @Test
    fun aRejectedCredentialAsksForRePairingInsteadOfRetrying() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.url.encodedPath) {
                INFO_PATH, EVENT_PATH -> unauthorized()
                else -> notFound()
            }
        }

        val id = repository.addServer("Rotated", baseUrl(), "stale-token")
        val connection = connections.connectServer(repository.getServer(id)!!, scope)

        val state = withTimeout(TIMEOUT_MILLIS) {
            connection.connectionState.first {
                it is ConnectionState.Disconnected && it.cause == DisconnectCause.AUTHORIZATION_REQUIRED
            }
        }

        assertTrue(state is ConnectionState.Disconnected)
        assertEquals(false, (state as ConnectionState.Disconnected).willRetry)
        assertEquals(
            "A rejected credential must surface as a re-pair prompt",
            ServerHealth.REAUTH_REQUIRED,
            repository.getServer(id)!!.health,
        )
    }

    @Test
    fun rePairingReplacesOnlyTheCredentialAndKeepsTheProfile() = runBlocking {
        val rotated = "1793162917.SecondTokenAfterRotation"
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.url.encodedPath) {
                REDEEM_PATH -> json("""{"token":"$rotated"}""")
                INFO_PATH -> json(serverInfo())
                else -> notFound()
            }
        }

        val id = repository.addServer("Workstation", baseUrl(), "old-token")
        val outcome = repository.rePairServer(id, link())

        val profile = (outcome as AddServerOutcome.Success).profile
        assertEquals(id, profile.id)
        assertEquals("Workstation", profile.name)
        assertEquals(rotated, credentialStore.getCredential(id))
        assertEquals(ServerHealth.CONNECTED, profile.health)
    }

    @Test
    fun aRePairThatTheServerRejectsLeavesTheStoredCredentialAlone() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.url.encodedPath) {
                REDEEM_PATH -> json("""{"token":"also-stale"}""")
                INFO_PATH, EVENT_PATH -> unauthorized()
                else -> notFound()
            }
        }

        val id = repository.addServer("Workstation", baseUrl(), "current-token")
        val outcome = repository.rePairServer(id, link())

        assertEquals(
            AddServerErrorType.UNAUTHORIZED,
            (outcome as AddServerOutcome.Failure).errorType,
        )
        assertEquals("current-token", credentialStore.getCredential(id))
    }

    @Test
    fun aServerThatClosesTheStreamReconnectsAndResyncs() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.url.encodedPath) {
                REDEEM_PATH -> json("""{"token":"live-token"}""")
                INFO_PATH -> json(serverInfo())
                // Each attempt gets a short-lived stream that ends by itself, which is what a
                // server restart looks like to the client.
                // A declared content length ends the response, which is what a server restart
                // looks like to the client: the socket closes and it reconnects.
                EVENT_PATH -> sse(SERVER_CONNECTED + ": heartbeat\n\n".repeat(2), finishes = true)

                else -> notFound()
            }
        }

        val profile = (repository.addPairedServer(link()) as AddServerOutcome.Success).profile
        val connection = connections.connectServer(profile, scope)

        withTimeout(TIMEOUT_MILLIS) { connection.resyncCount.first { it >= 1L } }
        withTimeout(TIMEOUT_MILLIS) { connection.resyncCount.first { it >= 2L } }

        assertTrue(
            "Every server.connected must fire a resync, so a restart cannot leave stores stale",
            connection.resyncCount.value >= 2L,
        )
        // The connection history is checked rather than the health dot: the second response also
        // ends by itself, so a snapshot of the state here would be a race, not an assertion.
        assertTrue(
            "The reconnection must be visible in the connection history",
            connection.connectionLogs.value.count { it.type == ConnectionEventType.CONNECTED } >= 2,
        )
    }

    @Test
    fun aPairingLinkForAnotherAddressIsRefusedAsARepair() = runBlocking {
        val id = repository.addServer("Workstation", baseUrl(), "token")
        val elsewhere = PairingLink(baseUrl = "http://192.168.9.9:4096", code = "code-abc")

        val outcome = repository.rePairServer(id, elsewhere)

        assertTrue("Expected a refusal, got $outcome", outcome is AddServerOutcome.Failure)
        assertNotNull((outcome as AddServerOutcome.Failure).technicalDetail)
        assertEquals("token", credentialStore.getCredential(id))
    }

    @Test
    fun removingAServerDropsItsCachedToken() = runBlocking {
        val id = repository.addServer("Workstation", baseUrl(), "token")
        assertTrue(credentialCache.holds(baseUrl()))

        repository.removeServer(id)

        assertNull(repository.getServer(id))
        assertFalse(credentialCache.holds(baseUrl()))
    }

    @Test
    fun aServerThatIsNotListeningIsReportedWithoutSavingAnything() = runBlocking {
        val closed = MockWebServer().apply { start() }
        val dead = closed.url("/").toString().trimEnd('/')
        closed.close()

        val outcome = repository.addManualServer("Nothing", dead, null)

        assertEquals(AddServerErrorType.UNREACHABLE, (outcome as AddServerOutcome.Failure).errorType)
        assertTrue(repository.getAllServers().isEmpty())
    }

    private fun baseUrl() = server.url("/").toString().trimEnd('/')

    private fun link() = PairingLink(baseUrl = baseUrl(), code = "code-abc")

    private fun serverInfo() =
        """{"version":"2.0.18","pid":99,"urls":["${baseUrl()}"],"paths":{"tmp":"/tmp"}}"""

    private object AlwaysOnline : NetworkConnectivityMonitor {
        override val isOnline = flowOf(true)
        override fun isCurrentlyOnline() = true
    }

    private companion object {
        const val TIMEOUT_MILLIS = 30_000L
        const val REDEEM_PATH = "/auth/connect/code-abc"
        const val INFO_PATH = "/api/info"
        const val EVENT_PATH = "/api/event"
        const val SERVER_CONNECTED =
            "data: {\"id\":\"evt_connected\",\"type\":\"server.connected\",\"data\":{}}\n\n"
    }
}

private fun json(body: String): MockResponse = MockResponse.Builder()
    .code(200)
    .setHeader("Content-Type", "application/json")
    .body(body)
    .build()

private fun unauthorized(): MockResponse = MockResponse.Builder()
    .code(401)
    .setHeader("Content-Type", "application/json")
    .body("""{"_tag":"UnauthorizedError","message":"Authentication required"}""")
    .build()

private fun notFound(): MockResponse = MockResponse.Builder().code(404).build()

/**
 * An SSE response.
 *
 * [finishes] declares a content length so the client sees the end of the stream. Without it the
 * body is chunked and never terminates, which models a live server: the client only learns the
 * connection is gone from its idle watchdog.
 */
private fun sse(payload: String, keepOpenMillis: Long = 0, finishes: Boolean = false): MockResponse {
    val body = object : MockResponseBody {
        override val contentLength: Long =
            if (finishes) payload.toByteArray().size.toLong() else -1L

        override fun writeTo(sink: BufferedSink) {
            sink.writeUtf8(payload)
            sink.flush()
            if (keepOpenMillis > 0) {
                try {
                    Thread.sleep(keepOpenMillis)
                } catch (_: InterruptedException) {
                }
            }
        }
    }
    return MockResponse.Builder()
        .code(200)
        .setHeader("Content-Type", "text/event-stream")
        .body(body)
        .build()
}
