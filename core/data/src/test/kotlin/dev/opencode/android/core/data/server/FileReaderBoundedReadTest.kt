package dev.opencode.android.core.data.server

import dev.opencode.android.core.data.action.ActionErrorKind
import dev.opencode.android.core.data.composer.AttachmentPolicy
import dev.opencode.android.core.data.composer.AttachmentProblem
import dev.opencode.android.core.data.composer.AttachmentVerdict
import dev.opencode.android.core.data.composer.FileContentKind
import dev.opencode.android.core.data.config.ConfigFileRead
import dev.opencode.android.core.data.config.ConfigSchema
import dev.opencode.android.core.data.config.ConfigSurface
import dev.opencode.android.core.data.config.RetrofitAdminApi
import dev.opencode.android.core.network.ServerApi
import dev.opencode.android.core.network.ServerApiFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockResponseBody
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okio.Buffer
import okio.BufferedSink
import okio.Source
import okio.Timeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.seconds

/**
 * Reading a file the phone cannot hold, against a real `ServerApi` over a `MockWebServer`.
 *
 * **The regression is a crash, and the claim is a bound.** Opening a 572 MB file in the browser killed
 * the app: `fs.read` was a buffering call, so the whole body was copied into memory, and then
 * copied again to be scanned, decoded and turned into a `String`. The bound that replaces it is
 * asserted from both ends — what the reader *returned* (a prefix no larger than the cap, flagged as
 * cut, with the real size) and what the server *was allowed to write* (a small fraction of the
 * body, because the reader dropped the connection instead of draining it). The second is the one a
 * mocked interface could never show, and it is why the big bodies here are streamed from a
 * generated `okio.Source`: nothing in this test ever holds the file.
 *
 * The cap is a constructor argument, so the character-boundary and image cases run at a few bytes
 * and can be written out by hand; the tests that use the real caps are the ones about size.
 */
class FileReaderBoundedReadTest {

    private lateinit var server: MockWebServer
    private lateinit var api: ServerApi

    /** What the server answers with next, by the path of the request. */
    private val answers = mutableMapOf<String, () -> MockResponse>()
    private val requests = mutableListOf<RecordedRequest>()

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        api = ServerApiFactory(OkHttpClient()).createForReads(server.url("/").toString())
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                synchronized(requests) { requests += request }
                val answer = answers[request.url.encodedPath] ?: return MockResponse.Builder().code(404).build()
                return answer()
            }
        }
    }

    @After
    fun tearDown() = server.close()

    // ------------------------------------------------------------------ the regression

    @Test
    fun `a file far larger than memory is read as a bounded prefix and the rest is never downloaded`() =
        runTest(timeout = 60.seconds) {
            val huge = GeneratedBody(total = HUGE)
            answers[readPath("/work/huge.txt")] = { huge.response(contentType = "application/octet-stream") }

            val result = FileReader(api).read("/work", "/work/huge.txt")

            val file = result.getOrElse { throw AssertionError("a huge file must be readable: $it", it) }
            assertEquals("a file with lines of ASCII is text", FileContentKind.TEXT, file.kind)
            assertTrue("the read must say it stopped early", file.truncated)
            assertTrue(
                "at most the cap may be held, held ${file.bytes.size}",
                file.bytes.size <= FileReader.TEXT_PREVIEW_MAX_BYTES,
            )
            assertEquals("the cap is the documented 2 MiB", 2L * 1024 * 1024, FileReader.TEXT_PREVIEW_MAX_BYTES)
            assertEquals("the total is Content-Length, not what was read", HUGE, file.totalBytes)
            assertEquals(HUGE, file.sizeBytes)
            assertEquals(
                "the text is the start of the file",
                GeneratedBody.expectedPrefix(file.bytes.size),
                file.text,
            )

            // The server's side of the claim: it was cut off, not drained. The body is 256 MiB, and a
            // reader that dropped the connection on time leaves the server holding a socket's worth
            // (a few MiB on loopback), so an eighth of the body is far more slack than a correct
            // reader needs and far less than a buffering one leaves — which is all of it.
            assertTrue("the server should have seen the connection close", huge.awaitFinished())
            assertTrue(
                "the reader must not download the file: the server produced ${huge.produced} of $HUGE bytes",
                huge.produced < HUGE / 8,
            )
        }

    @Test
    fun `a huge body with no length is cut at the cap and says its size is not known`() =
        runTest(timeout = 60.seconds) {
            val huge = GeneratedBody(total = HUGE, chunked = true)
            answers[readPath("/work/stream.log")] = { huge.response(contentType = "text/plain") }

            val file = FileReader(api).read("/work", "/work/stream.log").getOrThrow()

            assertEquals(FileContentKind.TEXT, file.kind)
            assertTrue(file.truncated)
            assertTrue(file.bytes.size <= FileReader.TEXT_PREVIEW_MAX_BYTES)
            assertNull("a chunked answer carries no size, and none is invented", file.totalBytes)
            assertEquals("an unknown size is 0, the attachment convention", 0L, file.sizeBytes)
            assertTrue(huge.awaitFinished())
            assertTrue("the server produced ${huge.produced} of $HUGE bytes", huge.produced < HUGE / 8)
        }

    // ------------------------------------------------------------------ a cut inside a character

    @Test
    fun `a cut in the middle of a multi-byte character leaves a text file text`() = runTest {
        // Every width, and every offset inside it: the cap falls after 1 byte of a 2-byte character,
        // after 1 or 2 of a 3-byte one, after 1, 2 or 3 of a 4-byte one.
        for (character in listOf("é", "€", "😀")) {
            val width = character.toByteArray(Charsets.UTF_8).size
            val body = ("abc" + character + "tail").toByteArray(Charsets.UTF_8)
            for (inside in 1 until width) {
                val cap = 3L + inside
                serve("/work/u.txt", body)

                val file = FileReader(api, textPreviewBytes = cap).read("/work", "/work/u.txt").getOrThrow()

                val where = "cap $cap inside a $width-byte character"
                assertEquals("$where must stay text", FileContentKind.TEXT, file.kind)
                assertTrue(where, file.truncated)
                assertEquals("$where: the half character is dropped, not shown as U+FFFD", "abc", file.text)
                assertEquals(where, 3, file.bytes.size)
                assertEquals("$where: the total still counts it", body.size.toLong(), file.totalBytes)
            }
            // And a cut that lands exactly between characters keeps the whole character.
            serve("/work/u.txt", body)
            val exact = FileReader(api, textPreviewBytes = 3L + width).read("/work", "/work/u.txt").getOrThrow()
            assertEquals(FileContentKind.TEXT, exact.kind)
            assertEquals("abc$character", exact.text)
        }
    }

    @Test
    fun `a partial character is forgiven only when the file was actually cut`() = runTest {
        // The same bytes, complete: "abc" and then the first byte of a two-byte character and nothing
        // after it. That is not a file that was cut, it is a file that is not UTF-8.
        val body = byteArrayOf('a'.code.toByte(), 'b'.code.toByte(), 'c'.code.toByte(), 0xC3.toByte())
        serve("/work/bad.txt", body)

        val file = FileReader(api).read("/work", "/work/bad.txt").getOrThrow()

        assertEquals("a malformed complete file is binary, as before", FileContentKind.BINARY, file.kind)
        assertFalse(file.truncated)
    }

    @Test
    fun `an invalid sequence inside the prefix is binary even when the file was cut`() = runTest {
        val body = "a".toByteArray() + byteArrayOf(0xFF.toByte()) + "bcd".toByteArray()
        serve("/work/bad.txt", body)

        val file = FileReader(api, textPreviewBytes = 3).read("/work", "/work/bad.txt").getOrThrow()

        assertEquals(FileContentKind.BINARY, file.kind)
        assertTrue(file.truncated)
        assertEquals("a truncated binary holds nothing it cannot show", 0, file.bytes.size)
        assertEquals(body.size.toLong(), file.totalBytes)
    }

    @Test
    fun `a NUL byte in the prefix makes a cut file binary`() = runTest {
        val body = "ab".toByteArray() + byteArrayOf(0) + "cdef".toByteArray()
        serve("/work/blob.dat", body)

        val file = FileReader(api, textPreviewBytes = 4).read("/work", "/work/blob.dat").getOrThrow()

        assertEquals(FileContentKind.BINARY, file.kind)
        assertTrue(file.truncated)
        assertNull(file.text)
    }

    // ------------------------------------------------------------------ pictures

    @Test
    fun `a picture over its cap is too large to preview and its body is not downloaded`() =
        runTest(timeout = 60.seconds) {
            val big = GeneratedBody(total = 64L * 1024 * 1024)
            answers[readPath("/work/photo.png")] = { big.response(contentType = "image/png") }

            val file = FileReader(api).read("/work", "/work/photo.png").getOrThrow()

            assertEquals("the server said image, so it is a picture", FileContentKind.IMAGE, file.kind)
            assertTrue("over the cap is the too-large state", file.truncated)
            assertEquals("half a picture is not held", 0, file.bytes.size)
            assertEquals(64L * 1024 * 1024, file.totalBytes)
            assertEquals("image/png", file.mime)
            assertEquals("the cap is the documented 8 MiB", 8L * 1024 * 1024, FileReader.IMAGE_PREVIEW_MAX_BYTES)
            assertTrue(big.awaitFinished())
            assertTrue("the server produced ${big.produced} of ${big.total} bytes", big.produced < big.total / 8)
        }

    @Test
    fun `a picture with no length is judged by what the stream holds`() = runTest {
        serve("/work/photo.png", ByteArray(40) { it.toByte() }, contentType = "image/png", chunked = true)

        val file = FileReader(api, imagePreviewBytes = 16).read("/work", "/work/photo.png").getOrThrow()

        assertEquals(FileContentKind.IMAGE, file.kind)
        assertTrue(file.truncated)
        assertEquals(0, file.bytes.size)
        assertNull("no Content-Length and cut short: the size is unknown", file.totalBytes)
    }

    @Test
    fun `a picture within its cap is kept whole`() = runTest {
        val pixels = ByteArray(16) { (it * 7).toByte() }
        serve("/work/icon.png", pixels, contentType = "image/png")

        val file = FileReader(api, imagePreviewBytes = 16).read("/work", "/work/icon.png").getOrThrow()

        assertEquals(FileContentKind.IMAGE, file.kind)
        assertFalse("exactly at the cap is not over it", file.truncated)
        assertArrayEquals(pixels, file.bytes)
        assertEquals(16L, file.totalBytes)
    }

    // ------------------------------------------------------------------ small files, as before

    @Test
    fun `a small text file is read whole and classified exactly as it was`() = runTest {
        val body = "line one\nline two\nline three\n"
        serve("/work/A.kt", body.toByteArray(), contentType = "text/plain")

        val file = FileReader(api).read("/work", "/work/A.kt").getOrThrow()

        assertEquals(FileContentKind.TEXT, file.kind)
        assertFalse(file.truncated)
        assertEquals(body, file.text)
        assertArrayEquals(body.toByteArray(), file.bytes)
        assertEquals(body.length.toLong(), file.totalBytes)
        assertEquals(body.length.toLong(), file.sizeBytes)
        assertEquals("text/plain", file.mime)
        assertEquals(listOf("line one", "line two", "line three"), file.lines)
    }

    @Test
    fun `the small cases that were decided before are decided the same way`() = runTest {
        serve("/work/empty.txt", ByteArray(0))
        val empty = FileReader(api).read("/work", "/work/empty.txt").getOrThrow()
        assertEquals("an empty file is text", FileContentKind.TEXT, empty.kind)
        assertEquals("", empty.text)
        assertFalse(empty.truncated)

        serve("/work/blob.bin", byteArrayOf('a'.code.toByte(), 0, 'b'.code.toByte()), contentType = "text/plain")
        assertEquals(FileContentKind.BINARY, FileReader(api).read("/work", "/work/blob.bin").getOrThrow().kind)

        serve("/work/latin.txt", byteArrayOf('a'.code.toByte(), 0xE9.toByte()), contentType = "text/plain")
        assertEquals(FileContentKind.BINARY, FileReader(api).read("/work", "/work/latin.txt").getOrThrow().kind)

        serve("/work/cfg.json", """{"a":1}""".toByteArray(), contentType = "application/json; charset=utf-8")
        val json = FileReader(api).read("/work", "/work/cfg.json").getOrThrow()
        assertEquals(FileContentKind.TEXT, json.kind)
        assertEquals("application/json", json.mime)

        serve("/work/picture.png", byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47), contentType = "image/png")
        val png = FileReader(api).read("/work", "/work/picture.png").getOrThrow()
        assertEquals("a picture is a picture whatever its bytes decode as", FileContentKind.IMAGE, png.kind)
        assertEquals(4, png.bytes.size)
    }

    @Test
    fun `a file exactly at the cap is whole, and one byte over is cut`() = runTest {
        val cap = 10L
        for (chunked in listOf(false, true)) {
            val how = if (chunked) "with no length" else "with a length"
            serve("/work/edge.txt", "0123456789".toByteArray(), contentType = "text/plain", chunked = chunked)
            val exact = FileReader(api, textPreviewBytes = cap).read("/work", "/work/edge.txt").getOrThrow()
            assertFalse("exactly the cap $how is not truncated", exact.truncated)
            assertEquals("0123456789", exact.text)

            serve("/work/edge.txt", "0123456789X".toByteArray(), contentType = "text/plain", chunked = chunked)
            val over = FileReader(api, textPreviewBytes = cap).read("/work", "/work/edge.txt").getOrThrow()
            assertTrue("one byte over the cap $how is truncated", over.truncated)
            assertEquals("0123456789", over.text)
            assertEquals(if (chunked) null else 11L, over.totalBytes)
        }
    }

    @Test
    fun `an answer with no length that ends before the cap has a known size`() = runTest {
        serve("/work/short.txt", "hello".toByteArray(), contentType = "text/plain", chunked = true)

        val file = FileReader(api).read("/work", "/work/short.txt").getOrThrow()

        assertFalse(file.truncated)
        assertEquals("a body read to its end has a size whether or not the header was there", 5L, file.totalBytes)
        assertEquals(5L, file.sizeBytes)
    }

    // ------------------------------------------------------------------ the wire and the failures

    @Test
    fun `the request keeps the server's spelling of an absolute path and names the location`() = runTest {
        serve("/work/A.kt", "x".toByteArray(), contentType = "text/plain")

        FileReader(api).read("/work", "/work/A.kt").getOrThrow()

        val request = synchronized(requests) { requests.single() }
        assertEquals("GET", request.method)
        assertEquals("/api/fs/read//work/A.kt", request.url.encodedPath)
        assertEquals("/work", request.url.queryParameter("location[directory]"))
    }

    @Test
    fun `a missing file is still a not-found failure`() = runTest {
        // Nothing is served, so the mock answers 404, which a suspend call used to raise as HttpException.
        val result = FileReader(api).read("/work", "/work/gone.txt")

        assertTrue(result.isFailure)
        assertEquals(ActionErrorKind.NOT_FOUND, (result.exceptionOrNull() as ActionFailure).error.kind)
    }

    @Test
    fun `cancelling a read that is waiting on the server drops the call at once`() = runBlocking {
        val asked = CountDownLatch(1)
        answers[readPath("/work/slow.txt")] = {
            asked.countDown()
            MockResponse.Builder().headersDelay(30, TimeUnit.SECONDS).build()
        }
        val scope = CoroutineScope(Dispatchers.Default)
        val reading = scope.launch { FileReader(api).read("/work", "/work/slow.txt") }
        assertTrue("the request should reach the server", asked.await(10, TimeUnit.SECONDS))

        val started = System.nanoTime()
        reading.cancelAndJoin()
        val took = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

        assertTrue("a cancelled read must not wait for the server: took $took ms", took < 5_000)
    }

    // ------------------------------------------------------------------ the viewer's state

    @Test
    fun `opening a file publishes it and clears the spinner`() = runTest {
        serve("/work/A.kt", "one\ntwo\n".toByteArray(), contentType = "text/plain")
        val reader = FileReader(api)

        val result = reader.open("/work", "/work/A.kt")

        assertTrue(result.isSuccess)
        val state = reader.state.value
        assertNull("the row's spinner is over", state.reading)
        assertEquals("/work/A.kt", state.content?.path)
        assertEquals(listOf("one", "two"), state.content?.lines)
        assertNull(state.readError)
    }

    @Test
    fun `a file that cannot be opened is published as an error and leaves no stale content`() = runTest {
        serve("/work/A.kt", "one\n".toByteArray(), contentType = "text/plain")
        val reader = FileReader(api)
        reader.open("/work", "/work/A.kt")
        assertNotNull(reader.state.value.content)

        val result = reader.open("/work", "/work/gone.txt")

        assertTrue(result.isFailure)
        val state = reader.state.value
        assertNull("the previous file is not left under a failed one", state.content)
        assertNull(state.reading)
        assertNotNull(state.readError)
    }

    @Test
    fun `navigating clears the file that was open`() = runTest {
        serve("/work/A.kt", "one\n".toByteArray(), contentType = "text/plain")
        answers["/api/fs/list"] = {
            MockResponse.Builder()
                .addHeader("Content-Type", "application/json")
                .body("""{"location":{"id":"loc","directory":"/work"},"data":[]}""")
                .build()
        }
        val reader = FileReader(api)
        assertTrue(reader.open("/work", "/work/A.kt").isSuccess)
        assertNotNull(reader.state.value.content)

        // `list` replaces the whole browser state, which is what a navigation does.
        reader.listAndWait("/work", "/work/sub")

        assertNull(reader.state.value.content)
        assertNull(reader.state.value.reading)
    }

    // ------------------------------------------------------------------ writing, and what it reads back

    @Test
    fun `a write larger than the cap reads back as a prefix with the size the server holds`() = runTest {
        val written = "0123456789".repeat(3)
        serveWrite("/work/big.txt")
        serve("/work/big.txt", written.toByteArray(), contentType = "text/plain")

        val readBack = FileReader(api, textPreviewBytes = 10).write("/work", "/work/big.txt", written).getOrThrow()

        assertTrue("the read-back obeys the same cap", readBack.truncated)
        assertEquals("what came back is the start of what was written", written.take(10), readBack.text)
        assertEquals("the size to compare with what was sent is the server's", 30L, readBack.sizeBytes)
        assertEquals(written.toByteArray().size.toLong(), readBack.sizeBytes)
    }

    @Test
    fun `a write of a normal size reads back whole and unflagged`() = runTest {
        serveWrite("/work/A.kt")
        serve("/work/A.kt", "written by the app\n".toByteArray(), contentType = "text/plain")

        val readBack = FileReader(api).write("/work", "/work/A.kt", "written by the app\n").getOrThrow()

        assertFalse(readBack.truncated)
        assertEquals("written by the app\n", readBack.text)
    }

    // ------------------------------------------------------------------ what a truncated file may be used for

    @Test
    fun `a truncated file is attached at its real size, so the 20 MiB limit still sees it`() =
        runTest(timeout = 60.seconds) {
            val huge = GeneratedBody(total = HUGE)
            answers[readPath("/work/huge.txt")] = { huge.response(contentType = "text/plain") }

            val file = FileReader(api).read("/work", "/work/huge.txt").getOrThrow()
            val attachment = file.toAttachment(location = "/work", id = "a1")

            assertEquals("the attachment is the file, not the preview", HUGE, attachment.sizeBytes)
            val verdict = AttachmentPolicy.verify(attachment, model = null)
            assertTrue(verdict is AttachmentVerdict.Blocked)
            assertEquals(AttachmentProblem.TOO_LARGE, (verdict as AttachmentVerdict.Blocked).problem)
        }

    @Test
    fun `the configuration editor is refused a file it would only see half of`() = runTest {
        val document = """{"model":"a/b","theme":"dark"}"""
        serve("/work/opencode.jsonc", document.toByteArray(), contentType = "application/json")
        fun surface(cap: Long) = ConfigSurface(
            admin = RetrofitAdminApi(api),
            files = FileReader(api, textPreviewBytes = cap),
            schema = ConfigSchema(buildJsonObject { }),
            scope = backgroundScope,
            serverId = "srv",
        )

        val whole = surface(cap = 1_000).readFile("/work", "/work/opencode.jsonc")
        assertEquals("a document that fits is opened as before", document, (whole as ConfigFileRead.Found).text)

        val cut = surface(cap = 10).readFile("/work", "/work/opencode.jsonc")
        assertTrue("half a document must not reach an editor that writes it back: $cut", cut is ConfigFileRead.Failed)
        assertNull(cut.text)
    }

    // ------------------------------------------------------------------ helpers

    private fun readPath(path: String): String = "/" + FileReader.readUrl(path)

    private fun serveWrite(path: String) {
        answers["/api/experimental/fs/write"] = {
            MockResponse.Builder()
                .addHeader("Content-Type", "application/json")
                .body("""{"location":{"id":"loc","directory":"/work"},"data":{"path":"$path"}}""")
                .build()
        }
    }

    /** Serves [bytes] at [path]: with a `Content-Length`, or chunked, which has none. */
    private fun serve(
        path: String,
        bytes: ByteArray,
        contentType: String = "application/octet-stream",
        chunked: Boolean = false,
    ) {
        answers[readPath(path)] = {
            val builder = MockResponse.Builder().addHeader("Content-Type", contentType)
            if (chunked) {
                builder.chunkedBody(Buffer().write(bytes), 7).build()
            } else {
                builder.body(Buffer().write(bytes)).build()
            }
        }
    }

    private companion object {
        /** 256 MiB: over a hundred times the cap, and still only ever a stream in this test. */
        const val HUGE: Long = 256L * 1024 * 1024
    }

    /**
     * A response body that is generated as it is written, so the test never holds it.
     *
     * The bytes are a repeating block of ASCII lines. [produced] counts what the server handed to
     * the socket, and [awaitFinished] is released when the server's write ends — either because the
     * body was complete or because the client dropped the connection under it.
     */
    private class GeneratedBody(val total: Long, private val chunked: Boolean = false) {
        private val counted = AtomicLong()
        private val finished = CountDownLatch(1)

        val produced: Long get() = counted.get()

        fun awaitFinished(): Boolean = finished.await(20, TimeUnit.SECONDS)

        fun response(contentType: String): MockResponse {
            val body = object : MockResponseBody {
                override val contentLength: Long = if (chunked) -1L else total

                override fun writeTo(sink: BufferedSink) {
                    try {
                        if (chunked) writeChunked(sink) else sink.writeAll(PatternSource())
                    } finally {
                        finished.countDown()
                    }
                }
            }
            val builder = MockResponse.Builder().addHeader("Content-Type", contentType).body(body)
            return if (chunked) {
                builder.removeHeader("Content-Length").addHeader("Transfer-Encoding", "chunked").build()
            } else {
                builder.build()
            }
        }

        private fun writeChunked(sink: BufferedSink) {
            val source = PatternSource()
            val chunk = Buffer()
            while (source.read(chunk, BLOCK.toLong()) != -1L) {
                sink.writeUtf8(chunk.size.toString(16)).writeUtf8("\r\n")
                sink.write(chunk, chunk.size)
                sink.writeUtf8("\r\n")
                sink.flush()
            }
            sink.writeUtf8("0\r\n\r\n")
        }

        /** A generated `okio.Source`: [total] bytes of the repeating block, and never more. */
        private inner class PatternSource : Source {
            private var offset = 0L

            override fun read(sink: Buffer, byteCount: Long): Long {
                if (offset >= total) return -1L
                val count = minOf(byteCount, total - offset, BLOCK.toLong()).toInt()
                sink.write(BLOCK_BYTES, 0, count)
                offset += count
                counted.addAndGet(count.toLong())
                return count.toLong()
            }

            override fun timeout(): Timeout = Timeout.NONE

            override fun close() = Unit
        }

        companion object {
            const val BLOCK: Int = 8192

            private val line = "the quick brown fox jumps over the lazy dog 0123456789\n".toByteArray()

            private val BLOCK_BYTES = ByteArray(BLOCK) { line[it % line.size] }

            /** The first [count] bytes of any [GeneratedBody], decoded. */
            fun expectedPrefix(count: Int): String =
                String(ByteArray(count) { BLOCK_BYTES[it % BLOCK] }, Charsets.UTF_8)
        }
    }
}
