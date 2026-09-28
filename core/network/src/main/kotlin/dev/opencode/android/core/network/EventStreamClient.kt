package dev.opencode.android.core.network

import dev.opencode.android.core.model.event.Event
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLException
import kotlin.coroutines.coroutineContext
import kotlin.random.Random

/** What the event stream is doing right now. Every screen renders this. */
sealed interface ConnectionState {
    /** Never started, or stopped by the app. */
    data object Idle : ConnectionState

    /** An attempt is in flight. [attempt] counts attempts since the last good connection. */
    data class Connecting(val attempt: Int) : ConnectionState

    /** `server.connected` arrived, so the stream is live. */
    data class Connected(
        val connectedAt: Long,
        val lastActivityAt: Long,
    ) : ConnectionState

    /**
     * The stream is not live. [willRetry] is false when only an explicit action, such as
     * re-pairing, can recover. [retryInMillis] counts down while a retry is pending.
     */
    data class Disconnected(
        val reason: String,
        val willRetry: Boolean,
        val cause: DisconnectCause? = null,
        val retryInMillis: Long? = null,
    ) : ConnectionState

    /** Nothing is attempted while the app is in the background, or the network is down. */
    data class Suspended(val reason: String) : ConnectionState
}

enum class DisconnectCause {
    /** The server closed the stream cleanly, or something between cut it. */
    SERVER_CLOSED,

    /** The stream carried nothing for the watchdog timeout. */
    WATCHDOG,

    /** The password or token was rejected, so only re-pairing recovers. */
    AUTHORIZATION_REQUIRED,

    /** The address did not answer. */
    CONNECTION_REFUSED,

    /** A certificate could not be validated. */
    TLS_ERROR,

    /** The host name did not resolve. */
    UNKNOWN_HOST,

    /** A request timed out. */
    TIMEOUT,
    /** The server answered with an error status. */
    SERVER_ERROR,

    /** Anything else, including transport failures without a more specific class. */
    UNKNOWN,
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
    /** Events were dropped because a consumer could not keep up. */
    EVENTS_DROPPED,
}

/** One line of the connection history shown on the server status screen. */
data class ConnectionLogEntry(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = 0L,
    val type: ConnectionEventType,
    val message: String,
)

/** One received event, kept for the developer event inspector. */
data class InspectedEvent(
    val id: String,
    val index: Long,
    val receivedAt: Long,
    val type: String,
    val rawJson: String,
    val event: Event,
)

/**
 * Reconnect backoff: it doubles from [initialDelayMillis] to [maxDelayMillis] per failed attempt
 * and is then jittered by [jitterRatio] in both directions, so clients that lost the same server
 * do not reconnect in lockstep.
 */
class BackoffPolicy(
    private val initialDelayMillis: Long = DEFAULT_INITIAL_MILLIS,
    private val maxDelayMillis: Long = DEFAULT_MAX_MILLIS,
    private val jitterRatio: Double = DEFAULT_JITTER_RATIO,
    private val random: Random = Random.Default,
) {
    init {
        require(initialDelayMillis > 0) { "initialDelayMillis must be positive" }
        require(maxDelayMillis >= initialDelayMillis) { "maxDelayMillis must not be below the initial delay" }
        require(jitterRatio in 0.0..1.0) { "jitterRatio must be between 0 and 1" }
    }

    /** The wait before attempt number [attempt], counting the first attempt as 1. */
    fun delayMillis(attempt: Int): Long {
        require(attempt >= 1) { "attempt must be 1 or greater, was $attempt" }
        val exponent = (attempt - 1).coerceAtMost(MAX_EXPONENT)
        val base = (initialDelayMillis shl exponent).coerceAtMost(maxDelayMillis)
        val jitterSpan = (base * jitterRatio).toLong()
        if (jitterSpan == 0L) return base
        return (base + random.nextLong(-jitterSpan, jitterSpan + 1))
            .coerceIn(0L, maxDelayMillis + jitterSpan)
    }

    companion object {
        const val DEFAULT_INITIAL_MILLIS = 1_000L
        const val DEFAULT_MAX_MILLIS = 30_000L
        const val DEFAULT_JITTER_RATIO = 0.2
        private const val MAX_EXPONENT = 32
    }
}

/**
 * The live connection to one server's global event stream, `GET /api/event` (features doc §36).
 *
 * The stream is live-only, so this client carries the app's whole reliability story:
 *
 * - `server.connected` must arrive first; a stream that starts with anything else is not usable
 *   and is dropped.
 * - `: heartbeat` comments count as activity. No activity for [watchdogTimeoutMillis] means the
 *   connection is dead even while the socket is open, so it is dropped and reconnected.
 * - A failed or dropped connection retries with exponential backoff, immediately when the network
 *   comes back, and only while the app is in the foreground.
 * - Every `server.connected` fires [resyncSignals]: the stream has no replay, so stores must
 *   re-read state over REST after every reconnect (plan §2, finding 5).
 * - The reader never blocks. Events go to a bounded buffer, so a slow consumer loses events,
 *   which are counted in the log, instead of stalling the socket the way a suspending emit would.
 */
class EventStreamClient(
    baseUrl: String,
    private val credentialProvider: suspend (HttpUrl) -> String?,
    okHttpClient: OkHttpClient,
    private val connectivityMonitor: NetworkConnectivityMonitor? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val watchdogTimeoutMillis: Long = DEFAULT_WATCHDOG_TIMEOUT_MILLIS,
    private val watchdogCheckIntervalMillis: Long = DEFAULT_WATCHDOG_CHECK_MILLIS,
    private val backoffPolicy: BackoffPolicy = BackoffPolicy(),
    private val now: () -> Long = System::currentTimeMillis,
    private val maxLogEntries: Int = DEFAULT_MAX_LOG_ENTRIES,
    private val maxInspectedEvents: Int = DEFAULT_MAX_INSPECTED_EVENTS,
    private val maxBufferedEvents: Int = DEFAULT_MAX_BUFFERED_EVENTS,
) {
    private val baseHttpUrl: HttpUrl = requireNotNull(baseUrl.toHttpUrlOrNull()) {
        "The event stream base URL is not a valid URL: $baseUrl"
    }

    private val sseClient: OkHttpClient = okHttpClient.newBuilder()
        // The stream is idle between events, so the read timeout is the watchdog's job, not OkHttp's.
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _logs = MutableStateFlow<List<ConnectionLogEntry>>(emptyList())
    val logs: StateFlow<List<ConnectionLogEntry>> = _logs.asStateFlow()

    private val _inspectedEvents = MutableStateFlow<List<InspectedEvent>>(emptyList())
    val inspectedEvents: StateFlow<List<InspectedEvent>> = _inspectedEvents.asStateFlow()

    private val _events = MutableSharedFlow<Event>(
        replay = 0,
        extraBufferCapacity = maxBufferedEvents,
    )
    val events: SharedFlow<Event> = _events.asSharedFlow()

    private val _resyncSignals = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val resyncSignals: SharedFlow<Unit> = _resyncSignals.asSharedFlow()

    private val foreground = MutableStateFlow(true)
    private val online = MutableStateFlow(true)
    private val running = MutableStateFlow(false)

    /** Conflated, so a burst of triggers never queues a backlog of reconnect attempts. */
    private val retryNow = Channel<Unit>(Channel.CONFLATED)

    private val lock = Any()
    private val eventIndex = AtomicLong(0)
    private val droppedEvents = AtomicLong(0)

    @Volatile
    private var loopJob: Job? = null

    @Volatile
    private var monitorJob: Job? = null

    @Volatile
    private var currentCall: Call? = null

    /** Set when the client itself cancelled the socket, so that is not reported as a failure. */
    @Volatile
    private var cancelReason: String? = null

    /**
     * Starts streaming into [scope] and follows network changes while it runs. Calling it while
     * already running only asks for an immediate retry.
     */
    fun start(scope: CoroutineScope) {
        val started = synchronized(lock) {
            foreground.value = true
            val alreadyRunning = running.value
            if (!alreadyRunning) {
                running.value = true
                loopJob = scope.launch { connectionLoop() }
                monitorJob = connectivityMonitor?.let { monitor ->
                    online.value = monitor.isCurrentlyOnline()
                    scope.launch { monitor.isOnline.distinctUntilChanged().collect { online.value = it } }
                }
            }
            alreadyRunning
        }
        if (started) {
            retryNow.trySend(Unit)
        }
    }

    /** Stops streaming and releases the socket. The client can be started again afterwards. */
    fun stop(reason: String = STOPPED_REASON) {
        val job = synchronized(lock) {
            running.value = false
            monitorJob.also { monitorJob = null }
            loopJob.also { loopJob = null }
        }
        cancelCall(reason)
        job?.cancel()
        _state.value = ConnectionState.Disconnected(reason = reason, willRetry = false)
    }

    /** Drops the current connection and reconnects without waiting out the backoff. */
    fun reconnectNow() {
        retryNow.trySend(Unit)
    }

    /**
     * Follows the app lifecycle. In this phase the stream runs in the foreground only (plan §6);
     * Phase 4 moves it to a foreground service.
     */
    fun setForeground(visible: Boolean) {
        foreground.value = visible
        if (visible) {
            retryNow.trySend(Unit)
        } else {
            cancelCall(BACKGROUND_REASON)
        }
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    fun clearInspectedEvents() {
        _inspectedEvents.value = emptyList()
    }

    private fun cancelCall(reason: String) {
        val call = synchronized(lock) {
            cancelReason = reason
            currentCall.also { currentCall = null }
        }
        call?.cancel()
    }

    private suspend fun connectionLoop() {
        var attempt = 0

        while (coroutineContext.isActive && running.value) {
            if (!foreground.value) {
                _state.value = ConnectionState.Suspended(BACKGROUND_REASON)
                log(ConnectionEventType.DISCONNECTED, BACKGROUND_REASON)
                foreground.first { it }
                continue
            }
            if (!online.value) {
                _state.value = ConnectionState.Suspended(NO_NETWORK_REASON)
                log(ConnectionEventType.DISCONNECTED, NO_NETWORK_REASON)
                online.first { it }
                if (!coroutineContext.isActive || !running.value) break
                log(ConnectionEventType.CONNECTING, "The network is available again")
                continue
            }

            attempt += 1
            _state.value = ConnectionState.Connecting(attempt)
            log(ConnectionEventType.CONNECTING, "Connecting to the event stream (attempt $attempt)")

            val outcome = streamOnce()
            if (!coroutineContext.isActive || !running.value) break

            when (outcome) {
                is StreamOutcome.Ended -> {
                    if (outcome.serverConnectedSeen) attempt = 0
                    val reason = "The server closed the event stream"
                    log(ConnectionEventType.DISCONNECTED, reason)
                    _state.value = pendingRetry(reason, DisconnectCause.SERVER_CLOSED)
                }

                is StreamOutcome.Cancelled -> {
                    val reason = outcome.reason
                    when (reason) {
                        WATCHDOG_REASON -> _state.value = pendingRetry(reason, DisconnectCause.WATCHDOG)
                        else -> _state.value = ConnectionState.Suspended(reason)
                    }
                    if (!running.value) break
                    if (!foreground.value || !online.value) continue
                }

                is StreamOutcome.Failed -> {
                    val failure = outcome.failure
                    log(ConnectionEventType.ERROR, "Event stream failed: ${failure.detail}")
                    if (failure.cause == DisconnectCause.AUTHORIZATION_REQUIRED) {
                        // A backoff cannot fix a rejected credential, so wait for the app to
                        // re-pair and ask for a retry instead of hammering the server.
                        _state.value = ConnectionState.Disconnected(
                            reason = failure.reason,
                            willRetry = false,
                            cause = failure.cause,
                        )
                        attempt = 0
                        retryNow.receive()
                        continue
                    }
                    _state.value = pendingRetry(failure.reason, failure.cause)
                }
            }

            if (!coroutineContext.isActive || !running.value) break
            awaitRetry(attempt)
        }
    }

    private fun pendingRetry(reason: String, cause: DisconnectCause) = ConnectionState.Disconnected(
        reason = reason,
        willRetry = true,
        cause = cause,
    )

    /** Waits out the backoff for [attempt], returning early when a retry is requested. */
    private suspend fun awaitRetry(attempt: Int) {
        val delayMillis = backoffPolicy.delayMillis(attempt.coerceAtLeast(1))
        _state.value = (_state.value as? ConnectionState.Disconnected)
            ?.copy(retryInMillis = delayMillis)
            ?: return
        log(ConnectionEventType.CONNECTING, "Reconnecting in ${delayMillis}ms")
        withTimeoutOrNull(delayMillis) { retryNow.receive() }
    }

    /** One connection attempt. It reports what happened instead of throwing. */
    private suspend fun streamOnce(): StreamOutcome = withContext(ioDispatcher) {
        val credential = try {
            credentialProvider(baseHttpUrl)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext StreamOutcome.Failed(
                StreamFailure(DisconnectCause.UNKNOWN, "Could not read the credential", e.toString()),
            )
        }

        val request = Request.Builder()
            .url(baseHttpUrl.newBuilder().encodedPath(EVENT_PATH).build())
            .header("Accept", "text/event-stream")
            .header("Cache-Control", "no-cache")
            .apply {
                if (!credential.isNullOrBlank()) {
                    header(
                        AuthInterceptor.HEADER_AUTHORIZATION,
                        Credentials.basic(AuthInterceptor.AUTH_USER, credential),
                    )
                }
            }
            .build()

        val call = sseClient.newCall(request)
        synchronized(lock) { cancelReason = null }
        currentCall = call

        val response = try {
            call.execute()
        } catch (e: Exception) {
            currentCall = null
            return@withContext afterReadFailure(e)
        }

        response.use { opened ->
            if (!opened.isSuccessful) {
                val status = opened.code
                val body = runCatching { opened.body?.string() }.getOrNull().orEmpty().take(ERROR_BODY_LIMIT)
                currentCall = null
                if (status == 401 || status == 403) {
                    return@withContext StreamOutcome.Failed(
                        StreamFailure(
                            cause = DisconnectCause.AUTHORIZATION_REQUIRED,
                            reason = "The server rejected the password or token",
                            detail = "HTTP $status from $EVENT_PATH: $body",
                        ),
                    )
                }
                return@withContext StreamOutcome.Failed(
                    StreamFailure(
                        cause = DisconnectCause.SERVER_ERROR,
                        reason = "The server refused the event stream (HTTP $status)",
                        detail = "HTTP $status from $EVENT_PATH: $body",
                    ),
                )
            }

            val body = opened.body
            if (body == null) {
                currentCall = null
                return@withContext StreamOutcome.Failed(
                    StreamFailure(DisconnectCause.SERVER_CLOSED, "The event stream had no body", "No body"),
                )
            }

            val reader = SseParser.reader(body.byteStream())
            val parser = SseParser()
            var serverConnectedSeen = false
            val lastActivity = AtomicLong(now())
            val watchdog = launch {
                while (isActive) {
                    delay(watchdogCheckIntervalMillis)
                    val idleMillis = now() - lastActivity.get()
                    if (idleMillis >= watchdogTimeoutMillis) {
                        log(
                            ConnectionEventType.WATCHDOG_TIMEOUT,
                            "No event stream activity for ${idleMillis}ms, dropping the connection",
                        )
                        cancelCall(WATCHDOG_REASON)
                        return@launch
                    }
                }
            }

            try {
                while (true) {
                    val line = try {
                        reader.readLine()
                    } catch (_: Exception) {
                        null
                    } ?: break

                    val message = parser.parseLine(line) ?: continue
                    val activityAt = now()
                    lastActivity.set(activityAt)
                    when (message) {
                        is SseMessage.Heartbeat -> log(ConnectionEventType.HEARTBEAT, "Heartbeat")
                        is SseMessage.Comment -> Unit
                        is SseMessage.Data -> {
                            val event = decodeEvent(message.payload) ?: continue
                            if (!serverConnectedSeen) {
                                if (event.type != SERVER_CONNECTED) {
                                    return@withContext StreamOutcome.Failed(
                                        StreamFailure(
                                            cause = DisconnectCause.SERVER_ERROR,
                                            reason = "The event stream did not start with $SERVER_CONNECTED",
                                            detail = "First event was '${event.type}'",
                                        ),
                                    )
                                }
                                serverConnectedSeen = true
                                onServerConnected(activityAt)
                            }
                            recordEvent(event, message.payload, activityAt)
                            updateLastActivity(activityAt)
                        }
                    }
                }
            } finally {
                watchdog.cancel()
                currentCall = null
                parser.flush()
            }

            val cancelled = cancelReason
            when {
                cancelled == WATCHDOG_REASON -> StreamOutcome.Cancelled(WATCHDOG_REASON)
                cancelled != null -> StreamOutcome.Cancelled(cancelled)
                else -> StreamOutcome.Ended(serverConnectedSeen)
            }
        }
    }

    private fun afterReadFailure(error: Throwable): StreamOutcome {
        val cancelled = cancelReason
        return if (cancelled != null) {
            StreamOutcome.Cancelled(cancelled)
        } else {
            val failure = StreamFailure.classify(error)
            StreamOutcome.Failed(failure)
        }
    }

    private fun onServerConnected(at: Long) {
        _state.value = ConnectionState.Connected(connectedAt = at, lastActivityAt = at)
        log(ConnectionEventType.CONNECTED, "Connected: the server sent $SERVER_CONNECTED")
        log(ConnectionEventType.RESYNC, "Firing the resync signal on $SERVER_CONNECTED")
        _resyncSignals.tryEmit(Unit)
    }

    private fun updateLastActivity(at: Long) {
        val current = _state.value
        if (current is ConnectionState.Connected && current.lastActivityAt != at) {
            _state.value = current.copy(lastActivityAt = at)
        }
    }

    private fun recordEvent(event: Event, rawJson: String, receivedAt: Long) {
        val inspected = InspectedEvent(
            id = event.id.ifBlank { UUID.randomUUID().toString() },
            index = eventIndex.incrementAndGet(),
            receivedAt = receivedAt,
            type = event.type,
            rawJson = rawJson,
            event = event,
        )
        _inspectedEvents.update { current ->
            val next = ArrayList<InspectedEvent>(minOf(current.size + 1, maxInspectedEvents))
            next.add(inspected)
            current.take(maxInspectedEvents - 1).forEach(next::add)
            next
        }
        if (!_events.tryEmit(event)) {
            val total = droppedEvents.incrementAndGet()
            if (total % REPORT_DROPPED_EVERY == 1L) {
                log(
                    ConnectionEventType.EVENTS_DROPPED,
                    "$total events dropped: a consumer is not keeping up",
                )
            }
        }
        log(ConnectionEventType.EVENT_RECEIVED, event.type)
    }

    /**
     * Decodes one frame. A frame that does not decode means the server contract changed, so the
     * stream stays up, the frame is logged, and the next one is read as usual.
     */
    private fun decodeEvent(payload: String): Event? = try {
        Event.decode(payload)
    } catch (e: Exception) {
        log(ConnectionEventType.ERROR, "Skipped an event that did not decode: ${e.message}")
        null
    }

    private fun log(type: ConnectionEventType, message: String) {
        val entry = ConnectionLogEntry(timestamp = now(), type = type, message = message)
        _logs.update { current ->
            val next = ArrayList<ConnectionLogEntry>(minOf(current.size + 1, maxLogEntries))
            next.add(entry)
            current.take(maxLogEntries - 1).forEach(next::add)
            next
        }
    }

    private sealed interface StreamOutcome {
        /** The stream ended by itself, which is not an error but still needs a reconnect. */
        data class Ended(val serverConnectedSeen: Boolean) : StreamOutcome

        /** The client dropped the socket on purpose: lifecycle, network loss, or the watchdog. */
        data class Cancelled(val reason: String) : StreamOutcome

        data class Failed(val failure: StreamFailure) : StreamOutcome
    }

    private data class StreamFailure(
        val cause: DisconnectCause,
        val reason: String,
        val detail: String,
    ) {
        companion object {
            fun classify(error: Throwable): StreamFailure {
                val cause = when (error) {
                    is UnknownHostException -> DisconnectCause.UNKNOWN_HOST
                    is ConnectException,
                    is NoRouteToHostException,
                    is PortUnreachableException,
                    -> DisconnectCause.CONNECTION_REFUSED

                    is SocketTimeoutException,
                    is InterruptedIOException,
                    -> DisconnectCause.TIMEOUT

                    is SSLException -> DisconnectCause.TLS_ERROR
                    is IOException -> DisconnectCause.UNKNOWN
                    else -> DisconnectCause.UNKNOWN
                }
                val message = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
                return StreamFailure(
                    cause = cause,
                    reason = reasonFor(cause, message),
                    detail = "${error.javaClass.simpleName}: $message",
                )
            }

            private fun reasonFor(cause: DisconnectCause, detail: String): String = when (cause) {
                DisconnectCause.CONNECTION_REFUSED -> "Nothing answered on that address."
                DisconnectCause.TLS_ERROR -> "The server's TLS certificate was rejected."
                DisconnectCause.UNKNOWN_HOST -> "The server's host name did not resolve."
                DisconnectCause.TIMEOUT -> "The connection timed out."
                DisconnectCause.WATCHDOG -> "The event stream went quiet."
                DisconnectCause.SERVER_CLOSED -> "The server closed the event stream."
                DisconnectCause.SERVER_ERROR -> "The server refused the event stream: $detail"
                DisconnectCause.AUTHORIZATION_REQUIRED -> "The server rejected the password or token."
                DisconnectCause.UNKNOWN -> detail
            }
        }
    }
    companion object {
        const val EVENT_PATH = "/api/event"
        const val SERVER_CONNECTED = "server.connected"
        const val DEFAULT_WATCHDOG_TIMEOUT_MILLIS = 45_000L
        const val DEFAULT_WATCHDOG_CHECK_MILLIS = 2_000L
        const val DEFAULT_MAX_LOG_ENTRIES = 200
        const val DEFAULT_MAX_INSPECTED_EVENTS = 500
        const val DEFAULT_MAX_BUFFERED_EVENTS = 512
        const val STOPPED_REASON = "Stopped by the app"
        const val BACKGROUND_REASON = "The app is in the background"
        const val NO_NETWORK_REASON = "No network"
        const val WATCHDOG_REASON = "The event stream went quiet"
        private const val ERROR_BODY_LIMIT = 500
        private const val REPORT_DROPPED_EVERY = 50L
    }
}
