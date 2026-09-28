package dev.opencode.android.core.network

import app.cash.turbine.test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
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
import dev.opencode.android.core.testing.Fixtures
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
        // The client holds a socket with no read timeout, so it is stopped before the server goes
        // away: otherwise `close()` waits on a connection nobody ends.
        if (::client.isInitialized) client.stop()
        scope.cancel()
        server.close()
    }

    /**
     * Every test body runs inside one timeout.
     *
     * These tests drive real sockets and wait on flows. An unbounded wait turns a broken
     * expectation into a hung Gradle test task with no output at all, which is far more expensive
     * to diagnose than a failure, so the whole body is bounded.
     */
    private fun eventStreamTest(body: suspend CoroutineScope.() -> Unit) = runBlocking<Unit> {
        try {
            withTimeout(AWAIT_MILLIS) { body() }
        } catch (timeout: TimeoutCancellationException) {
            // A bare "timed out" says nothing about where the client got to, and the connection
            // log is the only record of it.
            val log = if (::client.isInitialized) {
                client.logs.value.joinToString(separator = " | ") { "${it.type}: ${it.message}" }
            } else {
                "(the client was never built)"
            }
            throw AssertionError("Timed out after ${AWAIT_MILLIS}ms. Connection log: $log", timeout)
        }
    }

    @Test
    fun connectsAndFiresTheResyncSignalOnServerConnected() = eventStreamTest {
        server.enqueue(sseResponse(SERVER_CONNECTED_FRAME))
        client = newClient()

        // The signal has no replay, so the collector is attached before the stream is started.
        client.resyncSignals.test {
            client.start(scope)
            assertEquals(Unit, awaitItem())
        }
        awaitConnected()
        assertEquals(1, client.inspectedEvents.value.size)
        assertEquals(EventStreamClient.SERVER_CONNECTED, client.inspectedEvents.value.first().type)
    }

    @Test
    fun requestsTheEventStreamWithTheCredentialAsBasicAuth() = eventStreamTest {
        server.enqueue(sseResponse(SERVER_CONNECTED_FRAME))
        client = newClient(credential = "session-token")
        client.start(scope)
        awaitConnected()

        val recorded = server.takeRequest()
        assertEquals(EventStreamClient.EVENT_PATH, recorded.url.encodedPath)
        assertEquals("text/event-stream", recorded.headers["Accept"])
        assertEquals(
            Credentials.basic(AuthInterceptor.AUTH_USER, "session-token"),
            recorded.headers["Authorization"],
        )
    }

    @Test
    fun sendsNoCredentialWhenTheServerHasNoPassword() = eventStreamTest {
        server.enqueue(sseResponse(SERVER_CONNECTED_FRAME))
        client = newClient(credential = null)
        client.start(scope)
        awaitConnected()

        assertEquals(null, server.takeRequest().headers["Authorization"])
    }

    @Test
    fun publishesEveryDecodedEvent() = eventStreamTest {
        server.enqueue(
            sseResponse(
                SERVER_CONNECTED_FRAME +
                    eventFrame("session.created") +
                    eventFrame("model.updated"),
            ),
        )
        client = newClient()
        client.start(scope)
        awaitConnected()
        waitForInspectedEvents(3)

        val types = client.inspectedEvents.value.map { it.type }
        assertEquals(
            listOf(EventStreamClient.SERVER_CONNECTED, "session.created", "model.updated").reversed(),
            types,
        )
        assertTrue(client.inspectedEvents.value.all { it.rawJson.isNotBlank() && it.index > 0 })
    }

    @Test
    fun refusesAStreamThatDoesNotStartWithServerConnected() = eventStreamTest {
        server.enqueue(sseResponse(eventFrame("session.created")))
        server.enqueue(sseResponse(SERVER_CONNECTED_FRAME))
        client = newClient(backoffMillis = 20)

        client.start(scope)

        withTimeout(AWAIT_MILLIS) {
            client.logs.first { logs ->
                logs.any { it.message.contains("did not start with server.connected") }
            }
        }
        // The unusable stream is dropped and the client recovers on the next attempt.
        awaitConnected()
    }

    @Test
    fun heartbeatsCountAsActivitySoTheWatchdogStaysQuiet() = eventStreamTest {
        // The heartbeats are spread out in time on purpose: a stream that delivers all of them at
        // once would go quiet afterwards, and the watchdog would be right to fire.
        server.enqueue(
            sseResponse(
                SERVER_CONNECTED_FRAME,
                keepOpenMillis = HEARTBEAT_WINDOW_MILLIS,
                heartbeatEveryMillis = HEARTBEAT_PERIOD_MILLIS,
            ),
        )
        server.enqueue(sseResponse(SERVER_CONNECTED_FRAME))
        client = newClient(watchdogMillis = 600, watchdogCheckMillis = 40)

        client.start(scope)

        awaitConnected()
        withTimeout(AWAIT_MILLIS) {
            client.logs.first { logs -> logs.count { it.type == ConnectionEventType.HEARTBEAT } >= 3 }
        }
        delay(HEARTBEAT_WINDOW_MILLIS)
        assertTrue(
            "The watchdog fired while heartbeats were arriving",
            client.logs.value.none { it.type == ConnectionEventType.WATCHDOG_TIMEOUT },
        )
    }

    @Test
    fun theWatchdogDropsAQuietStreamAndReconnects() = eventStreamTest {
        server.enqueue(sseResponse(SERVER_CONNECTED_FRAME, keepOpenMillis = 3_000))
        server.enqueue(sseResponse(SERVER_CONNECTED_FRAME + eventFrame("session.created")))
        client = newClient(watchdogMillis = 300, watchdogCheckMillis = 40, backoffMillis = 30)

        client.start(scope)

        withTimeout(AWAIT_MILLIS) {
            awaitConnected()
            client.logs.first { logs -> logs.any { it.type == ConnectionEventType.WATCHDOG_TIMEOUT } }
            client.inspectedEvents.first { events -> events.any { it.type == "session.created" } }
        }
        assertEquals(2, client.logs.value.count { it.type == ConnectionEventType.CONNECTED })
        assertTrue(
            "The watchdog drop was not reported as a quiet stream",
            client.logs.value.any { it.type == ConnectionEventType.DISCONNECTED && it.message == EventStreamClient.WATCHDOG_REASON },
        )
    }

    @Test
    fun reconnectsWhenTheServerClosesTheStream() = eventStreamTest {
        server.enqueue(sseResponse(SERVER_CONNECTED_FRAME, finishes = true))
        server.enqueue(sseResponse(SERVER_CONNECTED_FRAME))
        client = newClient(backoffMillis = 30)

        client.start(scope)

        withTimeout(AWAIT_MILLIS) {
            client.logs.first { logs -> logs.count { it.type == ConnectionEventType.CONNECTED } >= 2 }
        }
        assertTrue(
            "Expected a closed-stream log entry",
            client.logs.value.any { it.message.contains("closed the event stream") },
        )
    }

    @Test
    fun aRejectedCredentialStopsRetryingUntilTheAppAsksAgain() = eventStreamTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse.Builder().code(401).body("{}").build()
        }
        client = newClient(backoffMillis = 20)

        client.start(scope)

        withTimeout(AWAIT_MILLIS) {
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
    fun reportsAConnectionRefusedServerWithoutHammeringIt() = eventStreamTest {
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

        val failing = withTimeout(AWAIT_MILLIS) {
            client.state.first {
                it is ConnectionState.Disconnected &&
                    it.cause == DisconnectCause.CONNECTION_REFUSED &&
                    (it.retryInMillis ?: 0L) > 0L
            }
        } as ConnectionState.Disconnected
        assertEquals(true, failing.willRetry)
        assertTrue(client.logs.value.any { it.message.contains("Nothing answered") })
    }

    @Test
    fun aSlowConsumerNeverStallsTheReader() = eventStreamTest {
        val frames = (1..40).joinToString("") { eventFrame("model.updated", it) }
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

        withTimeout(AWAIT_MILLIS) {
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
    fun followsNetworkLossAndRecovery() = eventStreamTest {
        val online = MutableStateFlow(true)
        server.enqueue(sseResponse(SERVER_CONNECTED_FRAME))
        server.enqueue(sseResponse(SERVER_CONNECTED_FRAME))
        client = newClient(
            connectivityMonitor = object : NetworkConnectivityMonitor {
                override val isOnline = online
                override fun isCurrentlyOnline() = online.value
            },
        )

        client.start(scope)
        awaitConnected()

        online.value = false
        withTimeout(AWAIT_MILLIS) { client.state.first { it is ConnectionState.Suspended } }
        assertTrue(client.logs.value.any { it.message.contains(EventStreamClient.NO_NETWORK_REASON) })

        online.value = true
        // Coming back online reconnects, so the second connection is waited for explicitly.
        awaitConnected(times = 2)
    }

    @Test
    fun runsInTheForegroundOnly() = eventStreamTest {
        server.enqueue(sseResponse(SERVER_CONNECTED_FRAME, keepOpenMillis = 5_000))
        server.enqueue(sseResponse(SERVER_CONNECTED_FRAME, keepOpenMillis = 5_000))
        client = newClient(backoffMillis = 20)

        client.start(scope)
        awaitConnected()

        client.setForeground(false)
        withTimeout(AWAIT_MILLIS) {
            client.state.first { it is ConnectionState.Suspended && it.reason == EventStreamClient.BACKGROUND_REASON }
        }
        assertTrue(client.logs.value.any { it.message.contains(EventStreamClient.BACKGROUND_REASON) })

        client.setForeground(true)
        awaitConnected(times = 2)
        assertEquals(2, client.logs.value.count { it.type == ConnectionEventType.CONNECTED })
    }

    @Test
    fun keepsTheConnectionLogBounded() = eventStreamTest {
        server.enqueue(sseResponse(SERVER_CONNECTED_FRAME + ": heartbeat\n\n".repeat(20)))
        client = newClient(maxLogEntries = 5)

        client.start(scope)
        withTimeout(AWAIT_MILLIS) {
            client.logs.first { logs -> logs.count { it.type == ConnectionEventType.HEARTBEAT } >= 5 }
        }
        waitUntil { client.logs.value.size == 5 }
        assertEquals(5, client.logs.value.size)
    }

    @Test
    fun clearLogsAndClearInspectedEventsEmptyTheBuffers() = eventStreamTest {
        server.enqueue(sseResponse(SERVER_CONNECTED_FRAME + eventFrame("session.created")))
        client = newClient()
        client.start(scope)
        awaitConnected()
        waitForInspectedEvents(2)
        // The log entry for a frame is written after the frame is recorded, so the wait is on the
        // log as well: clearing while the reader is still mid-frame would race.
        waitUntil { client.logs.value.count { it.type == ConnectionEventType.EVENT_RECEIVED } >= 2 }

        client.clearLogs()
        client.clearInspectedEvents()

        assertTrue(client.logs.value.isEmpty())
        assertTrue(client.inspectedEvents.value.isEmpty())
    }

    @Test
    fun stopEndsTheStreamAndIsIdempotent() = eventStreamTest {
        server.enqueue(sseResponse(SERVER_CONNECTED_FRAME, keepOpenMillis = 5_000))
        client = newClient()

        client.start(scope)
        awaitConnected()

        client.stop()
        client.stop()

        withTimeout(AWAIT_MILLIS) {
            client.state.first { it is ConnectionState.Disconnected && it.reason == EventStreamClient.STOPPED_REASON }
        }
    }

    @Test
    fun reconnectNowDropsALiveStreamAndConnectsAgain() = eventStreamTest {
        // Two long-lived responses: a "reconnect now" while connected has to drop the first socket
        // rather than wait for it to end on its own.
        server.enqueue(sseResponse(SERVER_CONNECTED_FRAME, keepOpenMillis = 5_000))
        server.enqueue(sseResponse(SERVER_CONNECTED_FRAME, keepOpenMillis = 5_000))
        client = newClient(backoffMillis = 30)
        client.start(scope)
        awaitConnected()

        client.reconnectNow()

        awaitConnected(times = 2)
        assertEquals(2, client.resyncCount.value)
        assertTrue(
            "The reconnect must be recorded, or the history shows a reconnect with no cause",
            client.logs.value.any { it.message.contains(EventStreamClient.RECONNECT_REASON) },
        )
    }

    @Test
    fun aStreamWithAnUndecodableFrameKeepsRunning() = eventStreamTest {
        server.enqueue(
            sseResponse(
                SERVER_CONNECTED_FRAME +
                    "data: {not json}\n\n" +
                    eventFrame("session.created"),
            ),
        )
        client = newClient()

        client.start(scope)

        withTimeout(AWAIT_MILLIS) {
            client.inspectedEvents.first { events -> events.any { it.type == "session.created" } }
        }
        assertTrue(client.logs.value.any { it.message.contains("did not decode") })
        assertTrue(
            "A frame that does not decode must not end the stream",
            client.logs.value.count { it.type == ConnectionEventType.CONNECTED } >= 1,
        )
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

    private fun sseResponse(
        body: String,
        keepOpenMillis: Long = 0,
        heartbeatEveryMillis: Long = 0,
        finishes: Boolean = false,
    ): MockResponse {
        val payload = body
        val responseBody = object : MockResponseBody {
            // A declared content length is what lets the client see the end of the stream: a
            // response of unknown length is chunked and, with a streaming body, never terminates.
            // `finishes` therefore models a server that closed the connection cleanly, which is what
            // a restart looks like; the default models a live server that just goes quiet.
            override val contentLength: Long = if (finishes) payload.toByteArray().size.toLong() else -1L

            override fun writeTo(sink: BufferedSink) {
                sink.writeUtf8(payload)
                sink.flush()
                if (heartbeatEveryMillis > 0) {
                    val deadline = System.currentTimeMillis() + keepOpenMillis
                    while (System.currentTimeMillis() < deadline) {
                        if (!sleep(heartbeatEveryMillis)) return
                        sink.writeUtf8(HEARTBEAT_FRAME)
                        sink.flush()
                    }
                } else if (keepOpenMillis > 0) {
                    sleep(keepOpenMillis)
                }
            }
        }
        return MockResponse.Builder()
            .code(200)
            .setHeader("Content-Type", "text/event-stream")
            .body(responseBody)
            .build()
    }

    /**
     * One SSE frame carrying an event recorded from a real 2.0.18 server.
     *
     * The recorded bodies are used rather than hand-written ones because a hand-written payload that
     * does not decode is silently skipped by the client, which turns a broken expectation into a
     * wait for an event that can never arrive.
     */
    private fun eventFrame(type: String, occurrence: Int = 1): String {
        val recorded = recordedEvents.firstOrNull { it.contains("\"type\":\"$type\"") }
            ?: error("No recorded $type event in the fixtures")
        val withId = recorded.replaceFirst(Regex("\"id\":\"[^\"]+\""), "\"id\":\"evt_${type}_$occurrence\"")
        return "data: $withId\n\n"
    }

    /**
     * Waits until the client has seen `server.connected` at least once.
     *
     * The connection log is used rather than [ConnectionState] because the state is transient: a
     * response that ends immediately can be seen as `Connected` and then `Disconnected` before a
     * collector subscribes, and a `StateFlow` only ever holds the latest value.
     */
    private suspend fun awaitConnected(times: Int = 1) {
        withTimeout(AWAIT_MILLIS) {
            client.logs.first { logs -> logs.count { it.type == ConnectionEventType.CONNECTED } >= times }
        }
    }

    private suspend fun waitForInspectedEvents(count: Int) {
        withTimeout(AWAIT_MILLIS) { client.inspectedEvents.first { it.size >= count } }
    }

    /** Sleeps on the response-writing thread, returning false when the test is tearing down. */
    private fun sleep(millis: Long): Boolean = try {
        Thread.sleep(millis)
        true
    } catch (_: InterruptedException) {
        false
    }

    private suspend fun waitUntil(condition: () -> Boolean) {
        withTimeout(AWAIT_MILLIS) {
            while (!condition()) {
                delay(20)
            }
        }
    }

    private companion object {
        /** Real 2.0.18 events, recorded by the Phase 0 fixture harness. */
        val recordedEvents: List<String> by lazy { Fixtures.events() }
        /**
         * Every wait is bounded. An unbounded `first { }` on a state that never changes hangs the
         * whole Gradle test task with no output, which is worse than a failure.
         */
        const val AWAIT_MILLIS = 20_000L
        const val HEARTBEAT_WINDOW_MILLIS = 2_000L
        const val HEARTBEAT_PERIOD_MILLIS = 150L
        const val HEARTBEAT_FRAME = ": heartbeat\n\n"
        val SERVER_CONNECTED_FRAME =
            "data: {\"id\":\"evt_connected\",\"type\":\"server.connected\",\"data\":{}}\n\n"
    }
}
