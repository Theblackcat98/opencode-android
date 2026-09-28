package dev.opencode.android.core.network

import app.cash.turbine.test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class EventStreamClientTest {

    private lateinit var server: MockWebServer
    private lateinit var scope: CoroutineScope
    private lateinit var client: EventStreamClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.close()
    }

    @Test
    fun connectsAndFiresTheResyncSignalOnServerConnected() = runBlocking<Unit> {
        server.enqueue(sseResponse(SERVER_CONNECTED_FRAME))
        client = newClient()

        client.state.first { it is ConnectionState.Connected }
        client.resyncSignals.test {
            client.start(scope)
            assertEquals(Unit, awaitItem())
        }
        assertEquals(1, client.inspectedEvents.value.size)
        assertEquals(EventStreamClient.SERVER_CONNECTED, client.inspectedEvents.value.first().type)
    }

    @Test
    fun requestsTheEventStreamWithTheCredentialAsBasicAuth() = runBlocking<Unit> {
        server.enqueue(sseResponse(SERVER_CONNECTED_FRAME))
        client = newClient(credential = "session-token")

        client.state.first { it is ConnectionState.Connected }

        val recorded = server.takeRequest()
        assertEquals(EventStreamClient.EVENT_PATH, recorded.url.encodedPath)
        assertEquals("text/event-stream", recorded.headers["Accept"])
        assertEquals(
            Credentials.basic(AuthInterceptor.AUTH_USER, "session-token"),
            recorded.headers["Authorization"],
        )
    }

    @Test
    fun sendsNoCredentialWhenTheServerHasNoPassword() = runBlocking<Unit> {
        server.enqueue(sseResponse(SERVER_CONNECTED_FRAME))
        client = newClient(credential = null)

        client.state.first { it is ConnectionState.Connected }

        assertEquals(null, server.takeRequest().headers["Authorization"])
    }

    @Test
    fun publishesEveryDecodedEvent() = runBlocking<Unit> {
        server.enqueue(
            sseResponse(
                SERVER_CONNECTED_FRAME +
                    eventFrame("session.created", """{"sessionID":"ses_1"}""") +
                    eventFrame("session.status", """{"status":"idle"}"""),
            ),
        )
        client = newClient()

        client.state.first { it is ConnectionState.Connected }
        waitForInspectedEvents(2)

        val types = client.inspectedEvents.value.map { it.type }
        assertEquals(listOf(EventStreamClient.SERVER_CONNECTED, "session.created", "session.status").reversed(), types)
        assertTrue(client.inspectedEvents.value.all { it.rawJson.isNotBlank() && it.index > 0 })
    }

    @Test
    fun refusesAStreamThatDoesNotStartWithServerConnected() = runBlocking<Unit> {
        server.enqueue(sseResponse(eventFrame("session.created", """{"sessionID":"ses_1"}""")))
        server.enqueue(sseResponse(SERVER_CONNECTED_FRAME))
        client = newClient(backoffMillis = 20)

        client.start(scope)

        withTimeout(10_000) {
            client.logs.first { logs ->
                logs.any { it.message.contains("did not start with server.connected") }
            }
        }
        // The unusable stream is dropped and the client recovers on the next attempt.
        client.state.first { it is ConnectionState.Connected }
    }

    @Test
    fun heartbeatsCountAsActivitySoTheWatchdogStaysQuiet() = runBlocking<Unit> {
        server.enqueue(
            sseResponse(
                SERVER_CONNECTED_FRAME + ": heartbeat\n\n".repeat(6),
                keepOpenMillis = 400,
            ),
        )
        server.enqueue(sseResponse(SERVER_CONNECTED_FRAME))
        client = newClient(watchdogMillis = 300, watchdogCheckMillis = 40)

        client.start(scope)

        withTimeout(10_000) {
            client.logs.first { logs -> logs.count { it.type == ConnectionEventType.HEARTBEAT } >= 3 }
        }
        delay(500)
        assertTrue(
            "The watchdog fired while heartbeats were arriving",
            client.logs.value.none { it.type == ConnectionEventType.WATCHDOG_TIMEOUT },
        )
        assertTrue(client.state.value is ConnectionState.Connected)
    }

    @Test
    fun theWatchdogDropsAQuietStreamAndReconnects() = runBlocking<Unit> {
        server.enqueue(sseResponse(SERVER_CONNECTED_FRAME, keepOpenMillis = 3_000))
        server.enqueue(sseResponse(SERVER_CONNECTED_FRAME + eventFrame("session.deleted", """{"sessionID":"s"}""")))
        client = newClient(watchdogMillis = 300, watchdogCheckMillis = 40, backoffMillis = 30)

        client.start(scope)

        withTimeout(15_000) {
            client.state.first { it is ConnectionState.Connected }
            client.logs.first { logs -> logs.any { it.type == ConnectionEventType.WATCHDOG_TIMEOUT } }
            client.inspectedEvents.first { events -> events.any { it.type == "session.deleted" } }
        }
        assertEquals(2, client.logs.value.count { it.type == ConnectionEventType.CONNECTED })
        assertTrue(
            "The watchdog drop was not reported as a quiet stream",
            client.logs.value.any { it.type == ConnectionEventType.DISCONNECTED && it.message == EventStreamClient.WATCHDOG_REASON },
        )
    }

    @Test
    fun reconnectsWhenTheServerClosesTheStream() = runBlocking<Unit> {
        server.enqueue(sseResponse(SERVER_CONNECTED_FRAME))
        server.enqueue(sseResponse(SERVER_CONNECTED_FRAME, keepOpenMillis = 3_000))
        client = newClient(backoffMillis = 30)

        client.start(scope)

        withTimeout(10_000) {
            client.logs.first { logs -> logs.count { it.type == ConnectionEventType.CONNECTED } >= 2 }
        }
        val closed = client.logs.value.any { it.message.contains("closed the event stream") }
        assertTrue("Expected a closed-stream log entry", closed)
        assertTrue(client.state.value is ConnectionState.Connected)
    }

    @Test
    fun aRejectedCredentialStopsRetryingUntilTheAppAsksAgain() = runBlocking<Unit> {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse.Builder().code(401).body("{}").build()
        }
        client = newClient(backoffMillis = 20)

        client.start(scope)

        withTimeout(10_000) {
            client.state.first { it is ConnectionState.Disconnected && it.cause == DisconnectCause.AUTHORIZATION_REQUIRED }
        }
        val failed = client.state.value as ConnectionState.Disconnected
        assertEquals(false, failed.willRetry)
        val attempts = server.requestCount

        delay(400)
        assertEquals("The client kept retrying a rejected credential", attempts, server.requestCount)

        client.reconnectNow()
        waitUntil { server.requestCount > attempts }
    }

    @Test
    fun reportsAConnectionRefusedServerWithoutHammeringIt() = runBlocking<Unit> {
        val closed = MockWebServer().apply { start() }
        val unusedUrl = closed.url("/").toString()
        closed.close()
        client = EventStreamClient(
            baseUrl = unusedUrl,
            credentialProvider = { null },
            okHttpClient = OkHttpClient(),
            watchdogTimeoutMillis = 5_000,
            backoffPolicy = BackoffPolicy(initialDelayMillis = 40, jitterRatio = 0.0, random = Random(1)),
        )

        client.start(scope)

        withTimeout(10_000) {
            client.state.first { it is ConnectionState.Disconnected && it.cause == DisconnectCause.CONNECTION_REFUSED }
        }
        val failing = client.state.value as ConnectionState.Disconnected
        assertEquals(true, failing.willRetry)
        assertTrue((failing.retryInMillis ?: 0) > 0)
        assertTrue(client.logs.value.any { it.message.contains("Nothing answered") })
    }

    @Test
    fun aSlowConsumerNeverStallsTheReader() = runBlocking<Unit> {
        val frames = (1..40).joinToString("") { eventFrame("session.status", """{"n":$it}""") }
        server.enqueue(sseResponse(SERVER_CONNECTED_FRAME + frames, keepOpenMillis = 2_000))
        client = newClient(bufferedEvents = 4)

        val received = mutableListOf<String>()
        val collector = scope.launch {
            client.events.collect { event ->
                received += event.type
                delay(50)
            }
        }

        client.start(scope)

        withTimeout(15_000) {
            client.inspectedEvents.first { events -> events.size >= 41 }
        }
        assertEquals("The reader stopped early", 41, client.inspectedEvents.value.size)
        assertTrue(
            "Drops were not reported",
            client.logs.value.any { it.type == ConnectionEventType.EVENTS_DROPPED },
        )
        collector.cancel()
    }

    @Test
    fun followsNetworkLossAndRecovery() = runBlocking<Unit> {
        val online = MutableStateFlow(true)
        server.enqueue(sseResponse(SERVER_CONNECTED_FRAME, keepOpenMillis = 5_000))
        client = newClient(
            connectivityMonitor = object : NetworkConnectivityMonitor {
                override val isOnline = online
                override fun isCurrentlyOnline() = online.value
            },
        )

        client.start(scope)
        client.state.first { it is ConnectionState.Connected }

        online.value = false
        withTimeout(10_000) { client.state.first { it is ConnectionState.Suspended } }
        assertTrue(client.logs.value.any { it.message.contains(EventStreamClient.NO_NETWORK_REASON) })

        online.value = true
        withTimeout(10_000) { client.state.first { it is ConnectionState.Connected } }
    }

    @Test
    fun runsInTheForegroundOnly() = runBlocking<Unit> {
        server.enqueue(sseResponse(SERVER_CONNECTED_FRAME, keepOpenMillis = 5_000))
        server.enqueue(sseResponse(SERVER_CONNECTED_FRAME, keepOpenMillis = 5_000))
        client = newClient(backoffMillis = 20)

        client.start(scope)
        client.state.first { it is ConnectionState.Connected }

        client.setForeground(false)
        withTimeout(10_000) {
            client.state.first { it is ConnectionState.Suspended && it.reason == EventStreamClient.BACKGROUND_REASON }
        }
        assertTrue(client.state.value !is ConnectionState.Connected)

        client.setForeground(true)
        withTimeout(10_000) { client.state.first { it is ConnectionState.Connected } }
        assertEquals(2, client.logs.value.count { it.type == ConnectionEventType.CONNECTED })
    }

    @Test
    fun keepsTheConnectionLogBounded() = runBlocking<Unit> {
        server.enqueue(sseResponse(SERVER_CONNECTED_FRAME + ": heartbeat\n\n".repeat(20)))
        client = newClient(maxLogEntries = 5)

        client.start(scope)
        withTimeout(10_000) {
            client.logs.first { logs -> logs.count { it.type == ConnectionEventType.HEARTBEAT } >= 5 }
        }
        waitUntil { client.logs.value.size == 5 }
        assertEquals(5, client.logs.value.size)
    }

    @Test
    fun clearLogsAndClearInspectedEventsEmptyTheBuffers() = runBlocking<Unit> {
        server.enqueue(sseResponse(SERVER_CONNECTED_FRAME + eventFrame("session.created", """{"sessionID":"s"}""")))
        client = newClient()

        client.state.first { it is ConnectionState.Connected }
        waitForInspectedEvents(2)

        client.clearLogs()
        client.clearInspectedEvents()

        assertTrue(client.logs.value.isEmpty())
        assertTrue(client.inspectedEvents.value.isEmpty())
    }

    @Test
    fun stopEndsTheStreamAndIsIdempotent() = runBlocking<Unit> {
        server.enqueue(sseResponse(SERVER_CONNECTED_FRAME, keepOpenMillis = 5_000))
        client = newClient()

        client.start(scope)
        client.state.first { it is ConnectionState.Connected }

        client.stop()
        client.stop()

        withTimeout(10_000) {
            client.state.first { it is ConnectionState.Disconnected && it.reason == EventStreamClient.STOPPED_REASON }
        }
    }

    @Test
    fun aStreamWithAnUndecodableFrameKeepsRunning() = runBlocking<Unit> {
        server.enqueue(
            sseResponse(
                SERVER_CONNECTED_FRAME +
                    "data: {not json}\n\n" +
                    eventFrame("session.created", """{"sessionID":"ses_1"}"""),
            ),
        )
        client = newClient()

        client.start(scope)

        withTimeout(10_000) {
            client.inspectedEvents.first { events -> events.any { it.type == "session.created" } }
        }
        assertTrue(client.logs.value.any { it.message.contains("did not decode") })
        assertTrue(client.state.value is ConnectionState.Connected)
    }

    @Test
    fun rejectsABaseUrlThatIsNotAUrl() {
        val failure = runCatching {
            EventStreamClient(
                baseUrl = "not a url",
                credentialProvider = { null },
                okHttpClient = OkHttpClient(),
            )
        }
        assertTrue(failure.isFailure)
    }

    private fun newClient(
        credential: String? = "token",
        watchdogMillis: Long = 30_000,
        watchdogCheckMillis: Long = 100,
        backoffMillis: Long = 5_000,
        bufferedEvents: Int = 512,
        maxLogEntries: Int = EventStreamClient.DEFAULT_MAX_LOG_ENTRIES,
        connectivityMonitor: NetworkConnectivityMonitor? = null,
    ) = EventStreamClient(
        baseUrl = server.url("/").toString(),
        credentialProvider = { credential },
        okHttpClient = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build(),
        connectivityMonitor = connectivityMonitor,
        watchdogTimeoutMillis = watchdogMillis,
        watchdogCheckIntervalMillis = watchdogCheckMillis,
        backoffPolicy = BackoffPolicy(
            initialDelayMillis = backoffMillis,
            maxDelayMillis = backoffMillis * 2,
            jitterRatio = 0.0,
            random = Random(1),
        ),
        maxBufferedEvents = bufferedEvents,
        maxLogEntries = maxLogEntries,
    )

    private fun sseResponse(body: String, keepOpenMillis: Long = 0): MockResponse {
        val payload = body
        val responseBody = object : MockResponseBody {
            override val contentLength: Long = -1L

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
            .body(responseBody)
            .build()
    }

    private fun eventFrame(type: String, data: String): String =
        "data: {\"id\":\"evt_$type\",\"created\":1790567234829,\"type\":\"$type\",\"data\":$data}\n\n"

    private suspend fun waitForInspectedEvents(count: Int) {
        withTimeout(10_000) { client.inspectedEvents.first { it.size >= count } }
    }

    private suspend fun waitUntil(condition: () -> Boolean) {
        withTimeout(10_000) {
            while (!condition()) {
                delay(20)
            }
        }
    }

    private companion object {
        val SERVER_CONNECTED_FRAME =
            "data: {\"id\":\"evt_connected\",\"type\":\"server.connected\",\"data\":{}}\n\n"
    }
}
