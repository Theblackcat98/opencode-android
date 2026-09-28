package dev.opencode.android.core.network

import app.cash.turbine.test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class EventStreamClientTest {

    private lateinit var server: MockWebServer
    private lateinit var okHttpClient: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        okHttpClient = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun connectsAndEmitsResyncSignalOnServerConnected() = runBlocking {
        val sseBody = "data: {\"id\":\"evt-1\",\"type\":\"server.connected\",\"data\":{}}\n\n"
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .setHeader("Content-Type", "text/event-stream")
                .body(sseBody)
                .build(),
        )

        val client = EventStreamClient(
            baseUrl = server.url("/").toString(),
            credentialProvider = { "secret" },
            okHttpClient = okHttpClient,
            watchdogTimeoutMs = 10_000L,
        )

        val scope = CoroutineScope(Dispatchers.Default)

        client.resyncSignal.test {
            client.start(scope)
            val signal = awaitItem()
            assertEquals(Unit, signal)
            assertTrue(client.connectionState.value is ConnectionState.Connected)
        }

        client.stop()
        scope.cancel()
    }

    @Test
    fun rejectsInitialEventIfNotServerConnected() = runBlocking {
        val sseBody = "data: {\"id\":\"evt-1\",\"type\":\"session.created\",\"data\":{}}\n\n"
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .setHeader("Content-Type", "text/event-stream")
                .body(sseBody)
                .build(),
        )

        val client = EventStreamClient(
            baseUrl = server.url("/").toString(),
            credentialProvider = { null },
            okHttpClient = okHttpClient,
            watchdogTimeoutMs = 10_000L,
        )

        val scope = CoroutineScope(Dispatchers.Default)
        client.start(scope)

        // Wait until error log appears
        withTimeout(5000) {
            client.connectionLogs.first { logs ->
                logs.any { it.message.contains("Expected initial event 'server.connected'") }
            }
        }

        client.stop()
        scope.cancel()
    }

    @Test
    fun heartbeatResetsWatchdogAndKeepsConnectionAlive() = runBlocking {
        val connectedEvent = "data: {\"id\":\"evt-1\",\"type\":\"server.connected\",\"data\":{}}\n\n"
        val heartbeat = ": heartbeat\n\n"
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .setHeader("Content-Type", "text/event-stream")
                .body(connectedEvent + heartbeat)
                .build(),
        )

        val client = EventStreamClient(
            baseUrl = server.url("/").toString(),
            credentialProvider = { null },
            okHttpClient = okHttpClient,
            watchdogTimeoutMs = 5000L,
        )

        val scope = CoroutineScope(Dispatchers.Default)
        client.start(scope)

        withTimeout(5000) {
            client.connectionLogs.first { logs ->
                logs.any { it.type == ConnectionEventType.HEARTBEAT }
            }
        }

        client.stop()
        scope.cancel()
    }

    @Test
    fun watchdogTriggersReconnectWhenIdle() = runBlocking {
        val connectedEvent = "data: {\"id\":\"evt-1\",\"type\":\"server.connected\",\"data\":{}}\n\n"
        val streamingBody = object : mockwebserver3.MockResponseBody {
            override val contentLength: Long = -1L
            override fun writeTo(sink: okio.BufferedSink) {
                sink.writeUtf8(connectedEvent)
                sink.flush()
                try {
                    Thread.sleep(3000)
                } catch (_: InterruptedException) {}
            }
        }

        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .setHeader("Content-Type", "text/event-stream")
                .body(streamingBody)
                .build(),
        )
        // Enqueue second response for reconnection attempt
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .setHeader("Content-Type", "text/event-stream")
                .body(connectedEvent)
                .build(),
        )

        val client = EventStreamClient(
            baseUrl = server.url("/").toString(),
            credentialProvider = { null },
            okHttpClient = okHttpClient,
            watchdogTimeoutMs = 500L, // 500ms watchdog
        )

        val scope = CoroutineScope(Dispatchers.Default)
        client.start(scope)

        // Wait for watchdog timeout to trigger
        withTimeout(10000) {
            client.connectionLogs.first { logs ->
                logs.any { it.type == ConnectionEventType.WATCHDOG_TIMEOUT }
            }
        }

        client.stop()
        scope.cancel()
    }

    @Test
    fun reactsToNetworkAvailability() = runBlocking {
        val isOnlineFlow = MutableStateFlow(true)
        val fakeMonitor = object : NetworkConnectivityMonitor {
            override val isOnline = isOnlineFlow
            override fun isCurrentlyOnline() = isOnlineFlow.value
        }

        val connectedEvent = "data: {\"id\":\"evt-1\",\"type\":\"server.connected\",\"data\":{}}\n\n"
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .setHeader("Content-Type", "text/event-stream")
                .body(connectedEvent)
                .build(),
        )

        val client = EventStreamClient(
            baseUrl = server.url("/").toString(),
            credentialProvider = { null },
            okHttpClient = okHttpClient,
            connectivityMonitor = fakeMonitor,
            watchdogTimeoutMs = 10_000L,
        )

        val scope = CoroutineScope(Dispatchers.Default)
        client.start(scope)

        withTimeout(5000) {
            client.connectionState.first { it is ConnectionState.Connected }
        }

        // Network goes offline
        isOnlineFlow.value = false

        withTimeout(5000) {
            client.connectionLogs.first { logs ->
                logs.any { it.message.contains("Network lost") }
            }
        }

        client.stop()
        scope.cancel()
    }
}
