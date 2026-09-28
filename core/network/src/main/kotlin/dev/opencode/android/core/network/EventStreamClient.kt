package dev.opencode.android.core.network

import dev.opencode.android.core.model.event.Event
import dev.opencode.android.core.model.event.EventPayload
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min
import kotlin.random.Random

sealed interface ConnectionState {
    data class Disconnected(val reason: String? = null, val willRetry: Boolean = false) : ConnectionState
    data class Connecting(val attempt: Int) : ConnectionState
    data class Connected(val connectedAt: Long, val lastActivityAt: Long) : ConnectionState
}

enum class ConnectionEventType {
    CONNECTING,
    CONNECTED,
    HEARTBEAT,
    EVENT_RECEIVED,
    DISCONNECTED,
    ERROR,
    WATCHDOG_TIMEOUT,
    RESYNC,
}

data class ConnectionLogEntry(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val type: ConnectionEventType,
    val message: String,
)

data class InspectedEvent(
    val id: String,
    val index: Long,
    val receivedAt: Long,
    val type: String,
    val rawJson: String,
    val event: Event,
)

/**
 * EventStreamClient handles the persistent connection to the OpenCode global event stream
 * (`GET /api/event`).
 *
 * Requirements:
 * - Parses `data:` frames and treats `: heartbeat` comments as activity.
 * - Requires `server.connected` as the first event.
 * - Reconnects when idle for 45s (watchdog), with exponential backoff (1s up to 30s, with jitter).
 * - Reacts to network changes through [NetworkConnectivityMonitor] and app foreground state.
 * - Exposes connection state as [StateFlow] and maintains a connection history log.
 * - Emits a resync signal on each `server.connected`.
 */
class EventStreamClient(
    private val baseUrl: String,
    private val credentialProvider: suspend () -> String?,
    private val okHttpClient: OkHttpClient,
    private val connectivityMonitor: NetworkConnectivityMonitor? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val watchdogTimeoutMs: Long = DEFAULT_WATCHDOG_TIMEOUT_MS,
    private val maxHistoryLogs: Int = 100,
    private val maxInspectedEvents: Int = 200,
) {
    private val sseHttpClient = okHttpClient.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS) // Indefinite read timeout for SSE stream
        .build()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected())
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _resyncSignal = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val resyncSignal: SharedFlow<Unit> = _resyncSignal.asSharedFlow()

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 64)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    private val _inspectedEvents = MutableStateFlow<List<InspectedEvent>>(emptyList())
    val inspectedEvents: StateFlow<List<InspectedEvent>> = _inspectedEvents.asStateFlow()

    private val _connectionLogs = MutableStateFlow<List<ConnectionLogEntry>>(emptyList())
    val connectionLogs: StateFlow<List<ConnectionLogEntry>> = _connectionLogs.asStateFlow()

    private var activeJob: Job? = null
    private var networkMonitorJob: Job? = null
    private var currentCall: Call? = null
    private val eventIndexCounter = AtomicLong(0)

    @Volatile
    private var lastActivityTimestamp: Long = 0L

    @Volatile
    private var isStarted = false

    fun start(scope: CoroutineScope) {
        if (isStarted) return
        isStarted = true

        // Monitor network state if available
        connectivityMonitor?.let { monitor ->
            networkMonitorJob = scope.launch {
                monitor.isOnline.collectLatest { online ->
                    if (online && _connectionState.value is ConnectionState.Disconnected) {
                        log(ConnectionEventType.CONNECTING, "Network became available, reconnecting...")
                        triggerReconnect(scope, immediate = true)
                    } else if (!online && _connectionState.value !is ConnectionState.Disconnected) {
                        log(ConnectionEventType.DISCONNECTED, "Network lost")
                        disconnectCurrentCall("Network lost")
                    }
                }
            }
        }

        activeJob = scope.launch {
            connectionLoop()
        }
    }

    fun stop() {
        isStarted = false
        networkMonitorJob?.cancel()
        networkMonitorJob = null
        activeJob?.cancel()
        activeJob = null
        disconnectCurrentCall("Client stopped")
        _connectionState.value = ConnectionState.Disconnected(reason = "Client stopped", willRetry = false)
    }

    private fun triggerReconnect(scope: CoroutineScope, immediate: Boolean) {
        activeJob?.cancel()
        activeJob = scope.launch {
            if (!immediate) {
                delay(INITIAL_BACKOFF_MS)
            }
            connectionLoop()
        }
    }

    private fun disconnectCurrentCall(reason: String) {
        currentCall?.cancel()
        currentCall = null
        log(ConnectionEventType.DISCONNECTED, reason)
    }

    private suspend fun connectionLoop() {
        var backoffMs = INITIAL_BACKOFF_MS
        var attempt = 0

        while (isStarted && kotlinx.coroutines.currentCoroutineContext().isActive) {
            attempt++
            _connectionState.value = ConnectionState.Connecting(attempt)
            log(ConnectionEventType.CONNECTING, "Connecting to event stream (attempt $attempt)...")

            try {
                connectAndStream()
                // If stream ended normally without exception
                backoffMs = INITIAL_BACKOFF_MS
                attempt = 0
            } catch (e: CancellationException) {
                // Expected cancellation when stopping or resetting
                _connectionState.value = ConnectionState.Disconnected("Connection cancelled", willRetry = false)
                break
            } catch (e: Exception) {
                val errorMsg = e.message ?: e.javaClass.simpleName
                log(ConnectionEventType.ERROR, "Connection error: $errorMsg")
                _connectionState.value = ConnectionState.Disconnected(reason = errorMsg, willRetry = true)
            }

            if (!isStarted) break

            // Apply backoff with jitter
            val jitter = Random.nextLong(0, 1000)
            val sleepDuration = backoffMs + jitter
            log(ConnectionEventType.CONNECTING, "Reconnecting in ${sleepDuration}ms...")
            delay(sleepDuration)
            backoffMs = min(backoffMs * 2, MAX_BACKOFF_MS)
        }
    }

    private suspend fun connectAndStream() = withContext(ioDispatcher) {
        val cleanBase = baseUrl.trimEnd('/')
        val eventUrl = "$cleanBase/api/event"

        val credential = credentialProvider()
        val requestBuilder = Request.Builder()
            .url(eventUrl)
            .header("Accept", "text/event-stream")
            .header("Cache-Control", "no-cache")

        if (!credential.isNullOrBlank()) {
            requestBuilder.header("Authorization", okhttp3.Credentials.basic(AuthInterceptor.AUTH_USER, credential))
        }

        val request = requestBuilder.build()
        val call = sseHttpClient.newCall(request)
        currentCall = call

        val response = call.execute()
        val body = response.body
        if (!response.isSuccessful) {
            val code = response.code
            val bodyString = body.string()
            response.close()
            throw IOException("HTTP $code connecting to /api/event: $bodyString")
        }

        val inputStream = body.byteStream()
        val reader = SseParser.streamReader(inputStream)
        val parser = SseParser()

        lastActivityTimestamp = System.currentTimeMillis()
        var receivedFirstEvent = false
        var watchdogJob: Job? = null

        try {
            // Launch watchdog coroutine to detect 45s idle timeout
            watchdogJob = CoroutineScope(kotlinx.coroutines.currentCoroutineContext()).launch {
                while (isActive) {
                    delay(WATCHDOG_CHECK_INTERVAL_MS)
                    val idleTime = System.currentTimeMillis() - lastActivityTimestamp
                    if (idleTime >= watchdogTimeoutMs) {
                        log(ConnectionEventType.WATCHDOG_TIMEOUT, "Watchdog timeout: idle for ${idleTime}ms >= ${watchdogTimeoutMs}ms")
                        call.cancel()
                        break
                    }
                }
            }

            var line: String? = reader.readLine()
            while (line != null && isActive) {
                val message = parser.parseLine(line)
                if (message != null) {
                    lastActivityTimestamp = System.currentTimeMillis()

                    when (message) {
                        is SseMessage.Heartbeat -> {
                            log(ConnectionEventType.HEARTBEAT, "Received heartbeat")
                            if (receivedFirstEvent) {
                                val current = _connectionState.value
                                if (current is ConnectionState.Connected) {
                                    _connectionState.value = current.copy(lastActivityAt = lastActivityTimestamp)
                                }
                            }
                        }
                        is SseMessage.Data -> {
                            val rawJson = message.payload
                            val rawObj = try {
                                dev.opencode.android.core.model.json.OpenCodeJson.decodeFromString<kotlinx.serialization.json.JsonObject>(rawJson)
                            } catch (e: Exception) {
                                null
                            }
                            val rawType = rawObj?.let { dev.opencode.android.core.model.event.eventTypeOf(it) }

                            if (!receivedFirstEvent) {
                                if (rawType != "server.connected") {
                                    throw IOException("Expected initial event 'server.connected', but received '${rawType ?: "unknown"}'")
                                }
                            }

                            val event = try {
                                Event.decode(rawJson)
                            } catch (e: Exception) {
                                log(ConnectionEventType.ERROR, "Failed to decode event: ${e.message}")
                                null
                            }

                            if (event != null) {
                                if (!receivedFirstEvent) {
                                    receivedFirstEvent = true
                                    val now = System.currentTimeMillis()
                                    _connectionState.value = ConnectionState.Connected(
                                        connectedAt = now,
                                        lastActivityAt = now,
                                    )
                                    log(ConnectionEventType.CONNECTED, "Connected: received initial server.connected event")
                                    log(ConnectionEventType.RESYNC, "Firing resync signal on server.connected")
                                    _resyncSignal.emit(Unit)
                                }

                                recordInspectedEvent(event, rawJson)
                                _events.emit(event)
                                log(ConnectionEventType.EVENT_RECEIVED, "Received event: ${event.type}")
                            }
                        }
                        is SseMessage.Comment -> {
                            // Non-heartbeat comment
                        }
                    }
                }
                line = reader.readLine()
            }
        } finally {
            watchdogJob?.cancel()
            parser.reset()
            try {
                body.close()
            } catch (_: Exception) {}
        }
    }

    private fun recordInspectedEvent(event: Event, rawJson: String) {
        val entry = InspectedEvent(
            id = event.id.ifBlank { UUID.randomUUID().toString() },
            index = eventIndexCounter.incrementAndGet(),
            receivedAt = System.currentTimeMillis(),
            type = event.type,
            rawJson = rawJson,
            event = event,
        )
        val currentList = _inspectedEvents.value.toMutableList()
        currentList.add(0, entry) // Most recent first
        if (currentList.size > maxInspectedEvents) {
            _inspectedEvents.value = currentList.subList(0, maxInspectedEvents)
        } else {
            _inspectedEvents.value = currentList
        }
    }

    private fun log(type: ConnectionEventType, message: String) {
        val entry = ConnectionLogEntry(
            type = type,
            message = message,
        )
        val currentList = _connectionLogs.value.toMutableList()
        currentList.add(0, entry) // Most recent first
        if (currentList.size > maxHistoryLogs) {
            _connectionLogs.value = currentList.subList(0, maxHistoryLogs)
        } else {
            _connectionLogs.value = currentList
        }
    }

    fun clearLogs() {
        _connectionLogs.value = emptyList()
    }

    fun clearInspectedEvents() {
        _inspectedEvents.value = emptyList()
    }

    companion object {
        const val DEFAULT_WATCHDOG_TIMEOUT_MS = 45_000L
        const val WATCHDOG_CHECK_INTERVAL_MS = 2_000L
        const val INITIAL_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 30_000L
    }
}
