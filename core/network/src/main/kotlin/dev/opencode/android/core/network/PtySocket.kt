package dev.opencode.android.core.network

import dev.opencode.android.core.model.PtyCursor
import dev.opencode.android.core.model.json.OpenCodeJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.atomic.AtomicBoolean

/**
 * What a terminal's stream is doing.
 *
 * The three that matter are different from the event stream's: [Live] is only reached after the
 * server has sent its cursor frame, because until then the client does not know how much of the
 * replay it has actually received, and a screen that reported "connected" over a half-read replay
 * would be lying about the state of the terminal.
 */
sealed interface PtyStreamState {
    /** Nothing has been sent. */
    data object Idle : PtyStreamState

    /** An attempt is in flight, or the replay is arriving. */
    data class Connecting(val attempt: Int) : PtyStreamState

    /** The cursor frame arrived: the replay is complete and the terminal is following. */
    data class Live(val cursor: Long) : PtyStreamState

    /** The server no longer retains the last delivered cursor; the page must reset and replay retained output. */
    data class Desynchronized(val reason: String) : PtyStreamState

    /**
     * The stream closed and a reconnect is scheduled.
     *
     * [willRetry] is false for the close codes that no retry can fix, so a terminal that has exited
     * does not sit in a backoff loop against a server that will keep saying the same thing.
     */
    data class Reconnecting(val reason: String, val willRetry: Boolean, val retryInMillis: Long?) : PtyStreamState

    /** Closed by the app, or by a failure only the user can fix. */
    data class Closed(val reason: String) : PtyStreamState
}

/**
 * The PTY WebSocket protocol's framing (features doc §31; plan §2 finding 11).
 *
 * The wire is simpler than it looks and the simplicity is the whole risk: a frame is either terminal
 * output or a control message, and the only thing that distinguishes them is the first byte.
 *
 * ```text
 * server -> client   0x00 {"cursor": n}   one control frame, after the replay
 * server -> client   <raw UTF-8>          terminal output, replay chunked at 64 KiB
 * client -> server   <raw UTF-8>          what the user typed
 * close              4404                the terminal is gone or has exited
 * ```
 *
 * **Why a decoder and not a `startsWith` check.** A control frame can arrive split across TCP
 * segments, and a terminal's output can contain a `0x00` byte of its own. Deciding per frame is only
 * sound if the frame boundary is known, and the boundary is the WebSocket message, not the payload:
 * so a control message is a *whole message* that begins with `0x00`, and [PtyFrameDecoder] is fed
 * complete messages. That is why the decoder consumes a whole message and not a stream — one that
 * consumed a byte at a time would have to reimplement message framing to answer the same question.
 *
 * **An in-limit control frame it cannot read becomes output.** The cursor is one JSON object; anything else
 * beginning with `0x00` is not something this protocol defines, and failing the whole connection
 * over a byte would drop the output the user is watching. The unreadable bytes are handed on as
 * output so nothing is lost, and the frame is counted so a test can assert the decision was taken.
 * Frames larger than the 64 KiB replay limit are rejected before decoding.
 */
class PtyFrameDecoder {

    private val _unreadableControls = MutableStateFlow(0L)

    /** How many control frames arrived that this protocol does not define. Diagnostics only. */
    val unreadableControls: StateFlow<Long> = _unreadableControls.asStateFlow()

    /** The replay cap the server applies, and the unit the client sizes its own buffer at. */
    val replayChunkBytes: Int get() = REPLAY_CHUNK_BYTES

    /**
     * Decodes one complete inbound message.
     *
     * Total by construction: a binary message, an empty message and a message that is one `0x00`
     * byte and nothing else all produce frames. An over-limit message throws before it is copied or
     * decoded.
     */
    fun decode(frame: ByteArray): List<PtyFrame> {
        rejectIfTooLarge(frame.size)
        if (frame.isEmpty()) return emptyList()
        if (frame[0] != CONTROL_PREFIX) return listOf(PtyFrame.Output(frame.decodeToString()))
        val json = frame.decodeToString(startIndex = 1, endIndex = frame.size)
        val cursor = runCatching { OpenCodeJson.decodeFromString(PtyCursor.serializer(), json) }.getOrNull()
        if (cursor != null) return listOf(PtyFrame.Cursor(cursor.cursor))
        _unreadableControls.value = _unreadableControls.value + 1
        return listOf(PtyFrame.Output(json))
    }

    /** Checks an Okio binary message's size before the conversion to a byte array. */
    fun decode(frame: ByteString): List<PtyFrame> {
        rejectIfTooLarge(frame.size)
        return decode(frame.toByteArray())
    }

    /** Decodes an OkHttp text message after counting its UTF-8 bytes without allocating another buffer. */
    fun decode(text: String): List<PtyFrame> {
        var byteCount = 0
        var index = 0
        while (index < text.length) {
            val current = text[index]
            val nextBytes = when {
                current.code <= 0x7F -> 1

                current.code <= 0x7FF -> 2

                Character.isHighSurrogate(current) &&
                    index + 1 < text.length && Character.isLowSurrogate(text[index + 1]) -> {
                    index++
                    4
                }

                else -> 3
            }
            byteCount += nextBytes
            if (byteCount > MAX_INBOUND_FRAME_BYTES) {
                throw PtyFrameTooLargeException(byteCount, MAX_INBOUND_FRAME_BYTES)
            }
            index++
        }
        return decode(text.toByteArray(Charsets.UTF_8))
    }

    private fun rejectIfTooLarge(size: Int) {
        if (size > MAX_INBOUND_FRAME_BYTES) {
            throw PtyFrameTooLargeException(size, MAX_INBOUND_FRAME_BYTES)
        }
    }

    companion object {
        /** The byte that makes a frame a control frame. */
        const val CONTROL_PREFIX: Byte = 0x00

        /** The replay chunk size the server sends in (features doc §31). */
        const val REPLAY_CHUNK_BYTES: Int = 64 * 1024

        /** No inbound WebSocket message may exceed the protocol's replay chunk size. */
        const val MAX_INBOUND_FRAME_BYTES: Int = REPLAY_CHUNK_BYTES
    }
}

/** Rejected inbound frame size; the WebSocket listener closes the peer with RFC 6455 code 1009. */
internal class PtyFrameTooLargeException(
    val observedBytes: Int,
    val maxBytes: Int,
) : IllegalArgumentException("PTY frame exceeds $maxBytes bytes")

/** One decoded inbound message. */
sealed interface PtyFrame {
    /** Terminal output, exactly as the server sent it. */
    data class Output(val text: String) : PtyFrame

    /** The one control frame: the cursor the next reconnect resumes from. */
    data class Cursor(val cursor: Long) : PtyFrame
}

/**
 * A live terminal stream (features doc §31).
 *
 * **The cursor is the last output delivered to the page.** The server's cursor frame is an absolute
 * UTF-16 code-unit offset at the end of replay. Each subsequently acknowledged string advances it by
 * [String.length], matching the server's `chunk.length`; an output frame that has only been queued or
 * is still waiting for the page never advances the cursor.
 *
 * **Reconnect resumes rather than replays.** `?cursor=<n>` is what the server understands, and it
 * is what a phone needs: a terminal that is dropped in a lift should not re-emit twenty thousand
 * lines of scrollback the user has already read. A first connection sends no cursor at all, which is
 * the server's "replay the whole retained buffer" and the right thing for a terminal being opened.
 *
 * **Output uses a bounded, suspending channel.** A full channel stops the WebSocket reader from
 * accepting more frames instead of discarding an arbitrary part of the terminal byte stream. Replay
 * is held behind the server's cursor barrier, then each chunk is emitted only as its consumer accepts
 * it. If the requested cursor has fallen out of the server's retained buffer, the stream is marked
 * desynchronized so the owner can reset the page and request a full retained replay.
 */
class PtySocket(
    private val url: HttpUrl,
    private val okHttpClient: OkHttpClient,
    private val credential: String?,
    /** The ticket from `pty.connect.token`, for a client that cannot send an `Authorization` header. */
    private val ticket: String? = null,
    /** Injected so a test's backoff is instant; production passes the real clock. */
    private val backoff: (attempt: Int) -> Long = ::defaultBackoff,
) {
    private val _state = MutableStateFlow<PtyStreamState>(PtyStreamState.Idle)
    val state: StateFlow<PtyStreamState> = _state.asStateFlow()

    private data class QueuedFrame(val connectionGeneration: Long, val frame: PtyFrame)

    private val outputFrames = Channel<QueuedFrame>(capacity = OUTPUT_BUFFER)
    private val outputCollectorStarted = AtomicBoolean(false)

    /** The socket's sole lifetime-scoped consumer receives output after replay reaches its cursor barrier. */
    val output: Flow<String> = flow {
        check(outputCollectorStarted.compareAndSet(false, true)) {
            "PtySocket.output supports one collector for its lifetime"
        }
        var generation = -1L
        val replay = mutableListOf<String>()
        var replayCharacters = 0L
        var liveCursor: Long? = null
        for (queued in outputFrames) {
            if (queued.connectionGeneration < generation) continue
            if (queued.connectionGeneration > generation) {
                generation = queued.connectionGeneration
                replay.clear()
                replayCharacters = 0L
                liveCursor = null
            }
            when (val frame = queued.frame) {
                is PtyFrame.Output -> {
                    if (frame.text.isEmpty()) continue
                    val cursor = liveCursor
                    if (cursor == null) {
                        val nextSize = replayCharacters + frame.text.length
                        if (nextSize > MAX_REPLAY_BUFFERED_CHARACTERS.toLong()) {
                            markDesynchronized("PTY replay exceeded the bounded client buffer")
                            return@flow
                        }
                        replay += frame.text
                        replayCharacters = nextSize
                    } else {
                        val nextCursor = cursor + frame.text.length.toLong()
                        if (nextCursor < cursor) {
                            markDesynchronized("PTY output cursor overflowed")
                            return@flow
                        }
                        emit(frame.text)
                        liveCursor = nextCursor
                        _cursor.value = nextCursor
                    }
                }

                is PtyFrame.Cursor -> {
                    val serverCursor = frame.cursor
                    val replayStart = serverCursor - replayCharacters
                    val lastDelivered = _cursor.value
                    if (
                        serverCursor < 0 ||
                        replayStart < 0 ||
                        (lastDelivered != null && replayStart > lastDelivered) ||
                        (lastDelivered != null && serverCursor < lastDelivered)
                    ) {
                        markDesynchronized("PTY replay no longer contains the last delivered cursor")
                        return@flow
                    }

                    var cursorPosition = replayStart
                    var skip = lastDelivered?.minus(replayStart)?.coerceAtLeast(0L) ?: 0L
                    for (chunk in replay) {
                        val skipInChunk = minOf(skip, chunk.length.toLong()).toInt()
                        skip -= skipInChunk
                        cursorPosition += skipInChunk
                        val remainder = chunk.substring(skipInChunk)
                        if (remainder.isNotEmpty()) {
                            emit(remainder)
                            cursorPosition += remainder.length
                            _cursor.value = cursorPosition
                        }
                    }
                    if (skip != 0L || cursorPosition != serverCursor) {
                        markDesynchronized("PTY replay cursor did not match its output")
                        return@flow
                    }

                    _cursor.value = serverCursor
                    liveCursor = serverCursor
                    _state.value = PtyStreamState.Live(serverCursor)
                    reconnect?.cancel()
                    reconnect = null
                    attempt = 0
                    replay.clear()
                    replayCharacters = 0L
                }
            }
        }
    }

    private val _cursor = MutableStateFlow<Long?>(null)

    /** The last output offset accepted downstream, which is what a reconnect sends. */
    val cursor: StateFlow<Long?> = _cursor.asStateFlow()

    private val decoder = PtyFrameDecoder()
    private val closed = AtomicBoolean(false)

    /**
     * The server said this terminal is gone.
     *
     * A close of `4404` is followed by a transport failure on the same socket — the server has nothing
     * left to talk to — and without this flag that failure would be read as a network problem and
     * answered with a backoff loop against a terminal that no longer exists.
     */
    @Volatile
    private var terminalGone = false
    private var socket: WebSocket? = null
    private var attempt = 0

    @Volatile
    private var connectionGeneration = 0L
    private var reconnect: Job? = null

    /** Opens the stream. Calling it again while open is ignored. */
    fun open(scope: CoroutineScope) {
        if (socket != null || closed.get()) return
        connect(scope)
    }

    private fun connect(scope: CoroutineScope) {
        attempt += 1
        connectionGeneration += 1
        val generation = connectionGeneration
        _state.value = PtyStreamState.Connecting(attempt)
        val request = Request.Builder()
            .url(socketUrl())
            .apply { if (ticket == null) setBasicAuth(credential) }
            .build()
        val opened = okHttpClient.newWebSocket(request, Listener(scope, generation))
        socket = opened
    }

    /**
     * The URL, including the resume cursor, in its `http`/`https` form.
     *
     * A ticket goes in the query because a WebSocket request from a page cannot carry a header; a
     * credential goes in the `Authorization` header instead, because putting it in a URL would put it
     * in a log. Only one is ever attached, and the ticket wins when both are present because a ticket
     * is the only thing that can survive a client that cannot set headers.
     *
     * The scheme is still `http` here because OkHttp's [HttpUrl] will not hold a `ws` one; [socketUrl]
     * swaps it at the moment the request is built.
     */
    internal fun requestUrl(): HttpUrl {
        val builder = url.newBuilder()
        ticket?.let { builder.addQueryParameter("ticket", it) }
        val from = _cursor.value
        if (from != null) builder.addQueryParameter("cursor", from.toString())
        return builder.build()
    }

    /**
     * The same URL in the scheme a WebSocket upgrade needs.
     *
     * `HttpUrl` refuses to hold `ws`, so the swap is on the string, and it is the *only* place the
     * scheme changes: an `https` server is `wss` and an `http` one is `ws`, and getting that wrong is a
     * handshake failure that reads like a network problem.
     */
    internal fun socketUrl(): String {
        val http = requestUrl().toString()
        return if (url.isHttps) {
            http.replaceFirst("https://", "wss://")
        } else {
            http.replaceFirst("http://", "ws://")
        }
    }

    /** Sends what the user typed, as a raw UTF-8 text frame. */
    fun send(text: String): Boolean {
        if (text.isEmpty()) return true
        return socket?.send(text) ?: false
    }

    /** Closes the stream for good. A reconnect is not scheduled. */
    fun close(reason: String = "closed") {
        terminalGone = true
        if (!closed.compareAndSet(false, true)) return
        reconnect?.cancel()
        reconnect = null
        outputFrames.close()
        socket?.close(NORMAL_CLOSURE, reason)
        socket = null
        _state.value = PtyStreamState.Closed(reason)
    }

    private inner class Listener(
        private val scope: CoroutineScope,
        private val generation: Long,
    ) : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (generation == connectionGeneration) {
                socket = webSocket
            } else {
                webSocket.close(NORMAL_CLOSURE, "superseded")
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            receive(webSocket, generation) { decoder.decode(text) }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            receive(webSocket, generation) { decoder.decode(bytes) }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            if (generation != connectionGeneration) {
                webSocket.close(NORMAL_CLOSURE, null)
                return
            }
            // A normal close, and 4404 in particular, are the server telling the client this
            // terminal is over. `close(NORMAL_CLOSURE, …)` answers the handshake so the server is
            // not left waiting, and no reconnect is scheduled.
            val gone = code == TERMINAL_GONE
            terminalGone = gone
            webSocket.close(NORMAL_CLOSURE, null)
            if (gone) {
                socket = null
                outputFrames.close()
                _state.value = PtyStreamState.Closed(reason.ifEmpty { "closed ($code)" })
            } else if (closed.get()) {
                // An app-initiated close may already have published either Closed or Desynchronized.
                // The peer's acknowledgment must not overwrite that more useful state.
                socket = null
                outputFrames.close()
            } else {
                scheduleReconnect(scope, "closed ($code) ${reason.ifEmpty { code.toString() }}", retry = true)
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (generation != connectionGeneration) return
            socket = null
            if (closed.get() || terminalGone) return
            // A 401 or a 403 is a credential and a 404 is a terminal that is gone; no retry fixes
            // any of them, and retrying anyway is a request per backoff period against a server that
            // will keep saying the same thing. The features doc says the terminal case arrives as
            // close code `4404`, but an intermediary can surface the failed upgrade as an HTTP
            // failure, so the status is honoured as well.
            val status = response?.code
            if (status == 401 || status == 403 || status == 404) {
                outputFrames.close()
                _state.value = PtyStreamState.Closed("refused ($status)")
                return
            }
            scheduleReconnect(scope, t.message ?: t::class.java.simpleName, retry = true)
        }
    }

    private fun receive(webSocket: WebSocket, generation: Long, decode: () -> List<PtyFrame>) {
        if (generation != connectionGeneration || closed.get()) return
        try {
            dispatch(generation, decode())
        } catch (tooLarge: PtyFrameTooLargeException) {
            if (!closed.compareAndSet(false, true)) return
            reconnect?.cancel()
            reconnect = null
            socket = null
            outputFrames.close()
            val reason = "PTY frame too large (${tooLarge.observedBytes} > ${tooLarge.maxBytes} bytes)"
            _state.value = PtyStreamState.Closed(reason)
            webSocket.close(TOO_BIG, reason)
        }
    }

    private fun dispatch(generation: Long, frames: List<PtyFrame>) {
        frames.forEach { frame ->
            if (generation != connectionGeneration || closed.get()) return@forEach
            try {
                runBlocking { outputFrames.send(QueuedFrame(generation, frame)) }
            } catch (_: ClosedSendChannelException) {
                // Closing the socket wakes a reader that was suspended by backpressure.
            }
        }
    }

    private fun markDesynchronized(reason: String) {
        if (!closed.compareAndSet(false, true)) return
        terminalGone = true
        reconnect?.cancel()
        reconnect = null
        val activeSocket = socket
        socket = null
        outputFrames.close()
        _state.value = PtyStreamState.Desynchronized(reason)
        activeSocket?.close(NORMAL_CLOSURE, "reset required")
    }

    private fun scheduleReconnect(scope: CoroutineScope, reason: String, retry: Boolean) {
        if (!retry || closed.get() || !scope.isActive) return
        val wait = backoff(attempt)
        _state.value = PtyStreamState.Reconnecting(reason, willRetry = true, retryInMillis = wait)
        reconnect?.cancel()
        reconnect = scope.launch {
            delay(wait)
            if (!closed.get()) connect(scope)
        }
    }

    private fun Request.Builder.setBasicAuth(credential: String?) {
        if (!credential.isNullOrBlank()) {
            header(
                AuthInterceptor.HEADER_AUTHORIZATION,
                Credentials.basic(AuthInterceptor.AUTH_USER, credential),
            )
        }
    }

    companion object {
        /** The features doc's close code for a terminal that was not found or has exited. */
        const val TERMINAL_GONE: Int = 4404

        /** RFC 6455 normal closure. */
        const val NORMAL_CLOSURE: Int = 1000

        /** RFC 6455 message-too-big closure. */
        const val TOO_BIG: Int = 1009

        /** Maximum complete frames queued before the WebSocket reader suspends for its consumer. */
        const val OUTPUT_BUFFER: Int = 64

        /** Upper bound on queued wire payload, from the frame limit multiplied by channel capacity. */
        const val MAX_BUFFERED_OUTPUT_BYTES: Int = OUTPUT_BUFFER * PtyFrameDecoder.MAX_INBOUND_FRAME_BYTES

        /** The pinned server retains at most two million output characters. */
        const val MAX_REPLAY_BUFFERED_CHARACTERS: Int = 2 * 1024 * 1024

        /** 250 ms, doubling to a 10 s ceiling. The event stream's shape, for the same reason. */
        fun defaultBackoff(attempt: Int): Long =
            (250L shl (attempt - 1).coerceIn(0, 30)).coerceAtMost(10_000L)

        /**
         * Builds a terminal's URL from a server's base URL, its path and its query.
         *
         * **The scheme stays `http`/`https`.** A `ws` URL is what the upgrade needs, but OkHttp's
         * [HttpUrl] will not hold one, and a `terminalUrl` that returned an unusable value would be a
         * trap. [PtySocket.socketUrl] performs the swap when the request is built, which is the one
         * place that has to know.
         *
         * The path is added segment by segment so a path prefix on the base URL (`https://host/opencode`)
         * is kept — string concatenation would drop it, which is the same trap `toServerBaseUrl`
         * documents for Retrofit.
         */
        fun terminalUrl(baseUrl: String, path: String, query: Map<String, String> = emptyMap()): HttpUrl {
            val http = baseUrl.trimEnd('/').toHttpUrl()
            val builder = http.newBuilder()
            path.trimStart('/').split('/').forEach { builder.addPathSegment(it) }
            query.forEach { (key, value) -> builder.addQueryParameter(key, value) }
            return builder.build()
        }
    }
}
