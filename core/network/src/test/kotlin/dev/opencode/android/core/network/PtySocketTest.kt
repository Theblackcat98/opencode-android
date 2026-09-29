package dev.opencode.android.core.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocketListener
import okhttp3.WebSocket
import okio.Buffer
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The PTY WebSocket client against a real socket (features doc §31; plan §2 finding 11).
 *
 * **A real handshake, not a mocked one.** The claims here are about the URL the client builds (the
 * `cursor` and the `ticket`), the `Authorization` header on the upgrade, the control frame the server
 * sends after the replay, and what happens when the socket closes with `4404`. A mocked `WebSocket`
 * would agree with whatever the test was written against, and every one of those is a thing the
 * *server* decides.
 */
class PtySocketTest {

    private lateinit var server: MockWebServer

    /** One accepted upgrade: what the client sent, recorded at the handshake. */
    private data class Upgrade(
        val path: String,
        val query: String?,
        val authorization: String?,
    )

    private val upgrades = LinkedBlockingQueue<Upgrade>()

    /** The server's end of the most recent socket, so a test can drive the protocol from it. */
    @Volatile
    private var peer: WebSocket? = null

    private val fromServer = LinkedBlockingQueue<String>()

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        // A socket left open makes `MockWebServer.close()` wait for a queue that never shuts down, which
        // hides the real failure behind a hang. Every test closes its own socket; this is the backstop
        // for one that threw before it got there.
        peer?.close(1000, "test over")
        server.close()
    }

    /** Accepts one socket, records the upgrade, and relays whatever the client types. */
    private fun serveWebSocket() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                upgrades += Upgrade(
                    path = request.url.encodedPath,
                    query = request.url.query,
                    authorization = request.headers["Authorization"],
                )
                return MockResponse.Builder().webSocketUpgrade(listener()).build()
            }
        }
    }

    private fun listener(): WebSocketListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            peer = webSocket
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            fromServer += text
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            fromServer += bytes.utf8()
        }
    }

    private fun client() = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()

    /** The server's base URL as the app has it: an `http` address, exactly as a server profile holds it. */
    private fun base() = "http://${server.hostName}:${server.port}"

    private fun socket(credential: String? = "secret", ticket: String? = null): PtySocket =
        PtySocket(
            url = PtySocket.terminalUrl(base(), "api/pty/pty_1/connect"),
            okHttpClient = client(),
            credential = credential,
            ticket = ticket,
            backoff = { 10 },
        ).also { stateUnderTest = it }

    /** The `0x00` + JSON control frame the features doc defines, built as the server builds it. */
    private fun control(cursor: Long): okio.ByteString =
        Buffer().write(byteArrayOf(0x00) + """{"cursor":$cursor}""".toByteArray()).readByteString()

    // ------------------------------------------------------------------ framing

    @Test
    fun `an output frame is output and a control frame is a cursor`() {
        val decoder = PtyFrameDecoder()
        assertEquals(listOf(PtyFrame.Output("hello")), decoder.decode("hello"))
        assertEquals(
            listOf(PtyFrame.Cursor(4096)),
            decoder.decode(byteArrayOf(0x00) + """{"cursor":4096}""".toByteArray()),
        )
    }

    @Test
    fun `an empty frame produces nothing and a bare zero byte is output`() {
        val decoder = PtyFrameDecoder()
        assertEquals(emptyList<PtyFrame>(), decoder.decode(ByteArray(0)))
        // Total by construction: nothing a peer sends may throw, because the alternative is losing the
        // terminal's output over a byte.
        assertEquals(listOf(PtyFrame.Output("")), decoder.decode(byteArrayOf(0)))
    }

    @Test
    fun `a control frame this build cannot read becomes output and is counted`() {
        val decoder = PtyFrameDecoder()
        val frame = byteArrayOf(0x00) + """{"somethingElse":1}""".toByteArray()
        assertEquals(listOf(PtyFrame.Output("""{"somethingElse":1}""")), decoder.decode(frame))
        assertEquals(1L, decoder.unreadableControls.value)
    }

    @Test
    fun `the replay chunk size is the protocol's 64 KiB`() {
        assertEquals(65_536, PtyFrameDecoder.REPLAY_CHUNK_BYTES)
    }

    // ------------------------------------------------------------------ the URL

    @Test
    fun `a first connection sends no cursor, so the server replays its buffer`() {
        val url = socket().requestUrl()
        assertNull(url.queryParameter("cursor"))
    }

    @Test
    fun `a ticket travels in the query`() {
        assertEquals("tkt_1", socket(ticket = "tkt_1").requestUrl().queryParameter("ticket"))
    }

    @Test
    fun `the location travels as the deepObject query every route takes`() {
        val url = PtySocket.terminalUrl(
            baseUrl = base(),
            path = "api/pty/pty_1/connect",
            query = mapOf("location[directory]" to "/work/app"),
        )
        assertEquals("/work/app", url.queryParameter("location[directory]"))
    }

    @Test
    fun `an https server is reached over wss and an http one over ws`() {
        // `HttpUrl` will not hold a `ws` scheme, so the swap happens on the string at request time —
        // and it is the one place that has to know, because an `https` server reached over `ws` fails
        // the handshake in a way that reads like a network problem.
        assertTrue(socket(ticket = null).socketUrlForTest().startsWith("ws://"))
        assertTrue(
            PtySocket(
                url = PtySocket.terminalUrl("https://host:4096", "api/pty/p/connect"),
                okHttpClient = client(),
                credential = null,
            ).socketUrlForTest().startsWith("wss://"),
        )
    }

    @Test
    fun `a base URL with a path prefix keeps it`() {
        assertEquals("/opencode/api/pty/pty_1/connect", PtySocket.terminalUrl("https://host/opencode", "api/pty/pty_1/connect").encodedPath)
    }

    // ------------------------------------------------------------------ the socket

    @Test
    fun `the upgrade carries basic auth and the output arrives in order`() = runBlocking {
        serveWebSocket()
        val socket = socket()
        val received = LinkedBlockingQueue<String>()
        // The collector and the socket's own coroutines run on a real dispatcher, because the test body
        // blocks on queues: a coroutine on the blocked `runBlocking` event loop would never get to
        // subscribe, and the flow's drop-oldest buffer would throw the output away.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val job = scope.launch { socket.output.collect { received += it } }
        try {
            socket.open(scope)
            val upgrade = upgrades.poll(5, TimeUnit.SECONDS)
            assertEquals("/api/pty/pty_1/connect", upgrade?.path)
            // Native clients authenticate with Basic auth on the upgrade (plan §2 finding 11), and the
            // credential is in the header rather than the URL so it cannot end up in a log.
            assertTrue(upgrade?.authorization?.startsWith("Basic ") == true)
            assertFalse("credential in the URL", upgrade?.query.orEmpty().contains("opencode:secret"))

            val peer = awaitPeer()
            peer.send("hello ")
            assertEquals("hello ", received.poll(5, TimeUnit.SECONDS))
            // A text frame and a binary frame of the same bytes decode identically; the server may send
            // either, and the client must not care which.
            peer.send("world".toByteArray().toByteString())
            assertEquals("world", received.poll(5, TimeUnit.SECONDS))
        } finally {
            socket.close()
            scope.cancel()
        }
    }

    @Test
    fun `the control frame makes the stream live and is the only source of the cursor`() = runBlocking {
        serveWebSocket()
        val socket = socket()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            socket.open(scope)
            awaitPeer().send(control(2048))
            val live = awaitState { it is PtyStreamState.Live } as PtyStreamState.Live
            assertEquals(2048L, live.cursor)
            assertEquals("cursor was ${socket.cursor.value}", 2048L, socket.cursor.value)
            // And the resume URL is built from it, which is the whole point of tracking it.
            assertEquals("2048", socket.requestUrl().queryParameter("cursor"))
        } finally {
            socket.close()
            scope.cancel()
        }
    }

    @Test
    fun `typed input is sent as a raw text frame`() = runBlocking {
        serveWebSocket()
        val socket = socket()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            socket.open(scope)
            awaitPeer()
            socket.send("ls -la\r")
            assertEquals("ls -la\r", pollUntil("the typed text") { fromServer.poll() })
        } finally {
            socket.close()
            scope.cancel()
        }
    }

    @Test
    fun `close 4404 means the terminal is gone and no reconnect is scheduled`() = runBlocking {
        serveWebSocket()
        val socket = socket()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            socket.open(scope)
            // The first upgrade is drained, so a second one in the queue really is a reconnect.
            assertNotNull("the first upgrade", upgrades.poll(5, TimeUnit.SECONDS))
            awaitPeer().close(PtySocket.TERMINAL_GONE, "no such pty")
            val closed = awaitState { it is PtyStreamState.Closed }
            assertTrue("was $closed", closed is PtyStreamState.Closed)
            // No second upgrade arrives: a terminal that has exited would otherwise be polled forever,
            // and the transport failure that follows the close must not be read as a network problem.
            assertNull("reconnected", upgrades.poll(1, TimeUnit.SECONDS))
        } finally {
            socket.close()
            scope.cancel()
        }
    }

    @Test
    fun `a refused upgrade is closed rather than retried`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) =
                MockResponse.Builder().code(401).body("nope").build()
        }
        val socket = socket(credential = "wrong")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            socket.open(scope)
            val closed = awaitState { it is PtyStreamState.Closed }
            assertTrue("was $closed", closed is PtyStreamState.Closed)
            // A credential no retry can fix; a 404 on the upgrade is a terminal that is gone.
            assertNull("retried", upgrades.poll(500, TimeUnit.MILLISECONDS))
        } finally {
            socket.close()
            scope.cancel()
        }
    }

    @Test
    fun `a dropped connection reconnects from the cursor the server reported`() = runBlocking {
        val accepted = LinkedBlockingQueue<Unit>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                upgrades += Upgrade(
                    request.url.encodedPath,
                    request.url.query,
                    request.headers["Authorization"],
                )
                accepted += Unit
                return MockResponse.Builder().webSocketUpgrade(listener()).build()
            }
        }
        val socket = socket()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            socket.open(scope)
            assertNotNull("the first upgrade", accepted.poll(5, TimeUnit.SECONDS))
            awaitPeer().send(control(1000))
            awaitValue("the cursor") { socket.cursor.value }

            // Drop it the way a lift does.
            awaitPeer().close(1001, "gone")
            assertNotNull("the reconnect", accepted.poll(5, TimeUnit.SECONDS))

            awaitValue("the second upgrade") { upgrades.size >= 2 }
            // The reconnect asks for what comes after the cursor, not the whole buffer again: a
            // terminal dropped in a lift must not re-emit twenty thousand lines already read.
            assertTrue(
                "query was ${upgrades.last().query}",
                upgrades.last().query.orEmpty().contains("cursor=1000"),
            )
        } finally {
            socket.close()
            scope.cancel()
        }
    }

    @Test
    fun `the backoff doubles and is capped`() {
        assertEquals(250L, PtySocket.defaultBackoff(1))
        assertEquals(500L, PtySocket.defaultBackoff(2))
        assertEquals(2_000L, PtySocket.defaultBackoff(4))
        assertEquals(10_000L, PtySocket.defaultBackoff(20))
    }

    private fun PtySocket.socketUrlForTest(): String = socketUrl()

    /**
     * The first state that matches, on a real deadline.
     *
     * **Polling, not suspending on the flow.** Everything here is real time — the handshake, the close
     * and the frame all happen on OkHttp's own threads — so a wait on a flow inside `runBlocking` is a
     * wait whose timeout depends on a scheduler that is not driving any of it. A bounded poll turns a
     * regression into a failure with a message instead of a hang.
     */
    private suspend fun awaitState(match: (PtyStreamState) -> Boolean): PtyStreamState =
        awaitValue("the stream state ${match}") { socketState()?.takeIf(match) }

    private var stateUnderTest: PtySocket? = null

    private fun socketState(): PtyStreamState? = stateUnderTest?.state?.value

    /** Polls a queue on a real deadline, so a regression is a failure rather than a hang. */
    private suspend fun <T> pollUntil(what: String, poll: () -> T?): T? = withTimeout(5_000) {
        while (true) {
            poll()?.let { return@withTimeout it }
            Thread.sleep(10)
        }
        @Suppress("UNREACHABLE_CODE")
        null
    }

    /** Polls a condition on a real deadline and returns the value that satisfied it. */
    private suspend fun <T> awaitValue(what: String, read: () -> T?): T = withTimeout(10_000) {
        while (true) {
            read()?.let { return@withTimeout it }
            Thread.sleep(10)
        }
        @Suppress("UNREACHABLE_CODE")
        throw AssertionError("unreachable")
    }

    /** The server's end of the socket, once the handshake has produced one. */
    /**
     * The server's end of the socket, once the handshake has produced one.
     *
     * **Real time, not the test scheduler's.** The handshake happens on OkHttp's own threads, and a
     * virtual clock would run through five seconds of `delay` before a single one of them had been
     * scheduled — so these tests run on [runBlocking] with real deadlines instead of on `runTest`.
     */
    private suspend fun awaitPeer(): WebSocket = withTimeout(5_000) {
        while (peer == null) Thread.sleep(10)
        peer!!
    }
}
