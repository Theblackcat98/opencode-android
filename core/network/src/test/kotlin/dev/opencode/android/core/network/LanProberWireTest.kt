package dev.opencode.android.core.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The prober against a real HTTP server, so what it sends and what it reads are the wire itself. Each
 * test scans 127.0.0.1 on the port of a server it controls, or on a port it has just proved is closed.
 */
class LanProberWireTest {

    private lateinit var server: MockWebServer
    private lateinit var prober: LanProber

    private val unauthorized = """{"_tag":"UnauthorizedError","message":"Authentication required"}"""

    private val info = """
        {
            "version": "2.0.18",
            "pid": 12345,
            "urls": ["http://127.0.0.1:4096"],
            "paths": {"tmp": "/tmp/opencode"}
        }
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        prober = LanProber(OkHttpClient())
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun answer(status: Int, body: String) = MockResponse.Builder()
        .code(status)
        .setHeader("Content-Type", "application/json")
        .body(body)
        .build()

    private fun scan(vararg ports: Int, timeoutMs: Long = 2_000): List<LanFound> = runBlocking {
        prober.scan(listOf("127.0.0.1"), ports.toList(), timeoutMs = timeoutMs).toList()
    }

    /** A port nothing listens on: bound, read and released, so a connection to it is refused. */
    private fun closedPort(): Int = ServerSocket(0).use { it.localPort }

    @Test
    fun aServerAnsweringTheUnauthorizedTagIsFound() {
        server.enqueue(answer(401, unauthorized))

        assertEquals(listOf(LanFound("127.0.0.1", server.port)), scan(server.port))
    }

    @Test
    fun aServerAnsweringTheInfoRouteIsFound() {
        server.enqueue(answer(200, info))

        assertEquals(listOf(LanFound("127.0.0.1", server.port)), scan(server.port))
    }

    @Test
    fun aUnauthorizedAnswerWithoutTheTagIsNotFound() {
        server.enqueue(answer(401, """{"message":"Not you"}"""))

        assertEquals(emptyList<LanFound>(), scan(server.port))
    }

    @Test
    fun aNotFoundIsNotFound() {
        server.enqueue(answer(404, "Not found"))

        assertEquals(emptyList<LanFound>(), scan(server.port))
    }

    @Test
    fun aClosedPortIsNotFoundAndTheScanStillFinishes() {
        assertEquals(emptyList<LanFound>(), scan(closedPort()))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun theOpenPortIsFoundAndTheClosedOneBesideItIsNot() {
        server.enqueue(answer(401, unauthorized))

        assertEquals(listOf(LanFound("127.0.0.1", server.port)), scan(closedPort(), server.port))
    }

    @Test
    fun onProbedRunsOncePerAddressTriedWhetherOrNotItAnswered() {
        server.enqueue(answer(401, unauthorized))
        var probed = 0

        runBlocking {
            prober.scan(listOf("127.0.0.1"), listOf(closedPort(), server.port), onProbed = { probed++ }).toList()
        }

        assertEquals(2, probed)
    }

    @Test
    fun theProbeSendsOneUnauthenticatedGetToTheInfoRoute() {
        server.enqueue(answer(401, unauthorized))

        scan(server.port)

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/info", request.target)
        assertNull("no credential is sent", request.headers["Authorization"])
        assertEquals(1, server.requestCount)
    }

    @Test
    fun aRedirectIsNotFollowed() {
        server.enqueue(MockResponse.Builder().code(302).setHeader("Location", "/elsewhere").build())
        server.enqueue(answer(401, unauthorized))

        assertEquals(emptyList<LanFound>(), scan(server.port))
        assertEquals("the redirect target was never requested", 1, server.requestCount)
    }

    @Test
    fun cancellingTheScanCancelsTheRequestInFlightAndFindsNothing() {
        // The answer is held back for five seconds, so a scan that kept running would still be waiting
        // on it when this test ends.
        server.enqueue(
            MockResponse.Builder()
                .code(401)
                .setHeader("Content-Type", "application/json")
                .body(unauthorized)
                .headersDelay(5, TimeUnit.SECONDS)
                .build(),
        )
        val callEnded = CountDownLatch(1)
        val endedWith = AtomicReference<IOException?>()
        val cancellable = LanProber(
            OkHttpClient.Builder()
                .eventListener(
                    object : EventListener() {
                        override fun callFailed(call: Call, ioe: IOException) {
                            endedWith.set(ioe)
                            callEnded.countDown()
                        }
                    },
                )
                .build(),
        )
        val found = mutableListOf<LanFound>()

        runBlocking {
            val scanning = launch {
                cancellable.scan(listOf("127.0.0.1"), listOf(server.port), timeoutMs = 10_000).toList(found)
            }
            // The request has reached the server, so it is in flight when the scan is cancelled.
            withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) }

            val cancelledAt = System.nanoTime()
            withTimeout(2_000) { scanning.cancelAndJoin() }
            val stoppedAfterMs = (System.nanoTime() - cancelledAt) / 1_000_000

            assertTrue("the scan stopped promptly, in ${stoppedAfterMs}ms", stoppedAfterMs < 1_000)
        }

        assertTrue("the OkHttp call itself was cancelled, not just abandoned", callEnded.await(2, TimeUnit.SECONDS))
        assertNotNull("the call ended with a failure, which is the cancellation", endedWith.get())
        assertEquals("nothing is reported after cancellation", emptyList<LanFound>(), found)
    }
}
