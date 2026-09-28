package dev.opencode.android.feature.review

import dev.opencode.android.core.data.composer.FileContentKind
import dev.opencode.android.core.data.composer.FileReadResult
import dev.opencode.android.core.data.composer.LineRange
import dev.opencode.android.core.data.server.FileReader
import dev.opencode.android.core.data.server.ReviewStore
import dev.opencode.android.core.model.SessionMessage
import dev.opencode.android.core.model.json.OpenCodeJson
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The file browser, the line range and the two transfer routes, against a real `ServerApi` over a
 * [ReviewServer].
 *
 * **The claims are the ones a user would be misled by.** A range that quietly attaches the whole
 * file when the user picked three lines is a wrong request that looks right; a write that reports
 * success without re-reading is a claim about bytes nobody checked; an import that sends a transcript
 * it could not decode is a `400` the user cannot act on. Each is asserted against the wire.
 */
class FileBrowserOperationsTest {

    private lateinit var server: ReviewServer

    @Before
    fun setUp() {
        server = ReviewServer()
    }

    @After
    fun tearDown() = server.close()

    // ------------------------------------------------------------------ the line range

    @Test
    fun `a first tap selects one line and a second outside the range extends it`() {
        val first = LineSelection().tapped(12)
        assertEquals(LineSelection(12, null), first)
        assertEquals(LineRange(12, 12), first.range)

        val extended = first.tapped(18)
        assertEquals(LineSelection(12, 18), extended)
        assertEquals(LineRange(12, 18), extended.range)
    }

    @Test
    fun `a tap inside the range starts over, and a tap on the anchor collapses it`() {
        val range = LineSelection(12, 18)
        assertEquals(LineSelection(15, null), range.tapped(15))
        // A tap on the anchor collapses to one line rather than keeping a range the user did not
        // choose, so the attachment is one line.
        assertEquals(LineSelection(12, null), range.tapped(12))
        assertEquals(LineRange(12, 12), range.tapped(12).range)
    }

    @Test
    fun `a range tapped backwards is still a range the server can be given`() {
        // `LineRange` is what becomes `?start=&end=`, and a backwards pair is a request the server
        // would answer with nothing. The selection normalises so the wire is never backwards.
        val backwards = LineSelection(30, 10)
        assertEquals(LineRange(10, 30), backwards.range)
        assertTrue(backwards.contains(20))
        assertFalse(backwards.contains(31))
    }

    @Test
    fun `an empty selection has no range, and offers nothing to attach`() {
        assertNull(LineSelection().range)
        assertFalse(LineSelection().contains(1))
    }

    // ------------------------------------------------------------------ fs read and write

    @Test
    fun `a text file is read, classified and split into numbered lines`() = runTest {
        val body = "line one\nline two\nline three\n"
        server.answer("GET /api/fs/read/${ReviewFixtures.DIRECTORY}/A.kt", body)

        val result = FileReader(server.api).read(ReviewFixtures.DIRECTORY, "${ReviewFixtures.DIRECTORY}/A.kt")

        val file = result.getOrNull()
        assertNotNull("fs.read should answer: ${result.exceptionOrNull()}", file)
        assertEquals(FileContentKind.TEXT, file!!.kind)
        assertEquals(listOf("line one", "line two", "line three"), file.lines)
    }

    @Test
    fun `a write goes out as octet-stream and the answer is read back`() = runTest {
        server.answer(
            "GET /api/fs/read/${ReviewFixtures.DIRECTORY}/A.kt",
            "written by the app\n",
        )
        server.answer(
            "POST /api/experimental/fs/write",
            """{"location":{"id":"loc","directory":"${ReviewFixtures.DIRECTORY}"},"data":{"path":"${ReviewFixtures.DIRECTORY}/A.kt"}}""",
        )

        val reader = FileReader(server.api)
        val result = reader.write(ReviewFixtures.DIRECTORY, "${ReviewFixtures.DIRECTORY}/A.kt", "written by the app\n")

        assertTrue("the write should succeed: ${result.exceptionOrNull()}", result.isSuccess)
        assertEquals("written by the app\n", result.getOrNull()?.text)
        // The route answers 415 for `text/plain` (verified live against 2.0.18), so the media type is
        // the part of the request that has to be right and is asserted on the wire, not on a mock.
        assertNotNull(server.lastRequest("experimental/fs/write"))
        assertEquals(
            "application/octet-stream",
            server.lastMediaType("experimental/fs/write"),
        )
        // The path is a query parameter, and it is the server's spelling rather than a local join.
        assertTrue(
            "the path must be sent as the server spelled it, got ${server.lastRequest("experimental/fs/write")}",
            server.lastRequest("experimental/fs/write")?.contains("path=") == true,
        )
    }

    @Test
    fun `a write that the server refuses is a failure, not a silent success`() = runTest {
        server.answer("POST /api/experimental/fs/write", """{"error":"nope"}""", status = 500)

        val result = FileReader(server.api).write(ReviewFixtures.DIRECTORY, "A.kt", "x")

        assertTrue("a refused write must not report success", result.isFailure)
    }

    // ------------------------------------------------------------------ transfer

    @Test
    fun `an export sends sanitize as a string, because the route takes a string`() = runTest {
        server.answer(
            "GET /api/experimental/session/ses_1/export",
            """{"data":${transferJson()}}""",
        )

        val result = ReviewStore(serverId = "srv_1", api = server.api).export("ses_1", sanitize = true)

        assertTrue("the export should answer: ${result.exceptionOrNull()}", result.isSuccess)
        assertEquals("true", sanitizeOf(server.lastRequest("export")))
        assertEquals(2, result.getOrNull()?.messages?.size)
    }

    @Test
    fun `an unsanitized export says so on the wire`() = runTest {
        server.answer("GET /api/experimental/session/ses_1/export", """{"data":${transferJson()}}""")

        ReviewStore(serverId = "srv_1", api = server.api).export("ses_1", sanitize = false)

        assertEquals("false", sanitizeOf(server.lastRequest("export")))
    }

    @Test
    fun `an import sends the transcript it was given, and reports the new session id`() = runTest {
        server.answer("POST /api/experimental/session/import", """{"data":${sessionJson("ses_9")}}""")

        val transfer = OpenCodeJson.decodeFromString(
            dev.opencode.android.core.model.SessionTransfer.serializer(),
            transferJson(),
        )
        val result = ReviewStore(serverId = "srv_1", api = server.api).import(transfer)

        assertTrue("the import should answer: ${result.exceptionOrNull()}", result.isSuccess)
        assertEquals("ses_9", result.getOrNull()?.id)
        val body = server.lastBody("session/import").orEmpty()
        assertTrue("the import must carry the transcript, got: $body", body.contains("msg_1"))
    }

    @Test
    fun `a transcript this client cannot decode is refused before it is sent`() {
        // Not Markdown, not a `SessionTransfer`, and not truncated: each is a file a user may have
        // chosen, and each must produce a refusal rather than a request the server answers 400.
        val serializer = dev.opencode.android.core.model.SessionTransfer.serializer()
        listOf("# a transcript", "{}", "{\"info\":", "").forEach { text ->
            val decoded = runCatching { OpenCodeJson.decodeFromString(serializer, text) }.getOrNull()
            assertNull("this should not decode: $text", decoded)
        }
    }

    // ------------------------------------------------------------------ attachments

    @Test
    fun `a line range becomes the query on the file uri the agent receives`() {
        // The range leaves as `?start=&end=` on a `file:` URI (features doc §6), so its spelling is
        // the contract between what the user selected and what the server is told. A one-line range
        // carries no `end`, because `?start=12&end=12` asks the server for something else.
        assertEquals("#12-18", LineRange(12, 18).toSuffix())
        assertEquals("?start=12&end=18", LineRange(12, 18).toQuery())
        assertEquals("#12", LineRange(12).toSuffix())
        assertEquals("?start=12", LineRange(12).toQuery())
        assertEquals("", LineRange(0).toQuery())
    }

    // ------------------------------------------------------------------ helpers

    private fun sanitizeOf(request: String?): String? =
        request?.substringAfter("sanitize=", "")?.substringBefore('&')?.takeIf { it.isNotEmpty() }

    private fun transferJson(): String = """
        {"info":${sessionJson("ses_1")},"messages":[
          {"id":"msg_1","time":{"created":1},"role":"user","parts":[{"type":"text","text":"first prompt"}]},
          {"id":"msg_2","time":{"created":2,"completed":3},"role":"assistant","agent":"build",
           "model":{"id":"text","providerID":"fake"},
           "parts":[{"type":"text","text":"first answer"}],
           "snapshot":{"start":"a","end":"b","files":["src/a.kt"]}}
        ]}
    """.trimIndent()

    private fun sessionJson(id: String): String = """
        {"id":"$id","projectID":"prj_1","cost":0,
         "tokens":{"input":0,"output":0,"reasoning":0,"cache":{"read":0,"write":0}},
         "time":{"created":1,"updated":2},
         "location":{"id":"loc","directory":"${ReviewFixtures.DIRECTORY}"}}
    """.trimIndent()

    /** The classification is a pure function, and each answer has to be reachable from bytes. */
    @Test
    fun `the classifier decides from the bytes, and the extension only picks the language`() {
        val png = FileReader.classify("a.png", byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47), "image/png")
        assertEquals("a picture is a picture whatever its bytes decode as", FileContentKind.IMAGE, png.kind)

        // The type the server sent wins, in both directions: a `.png` that decodes is still a
        // picture, because a text viewer would mangle it. Only a body with no image type is
        // classified by its bytes.
        val textInAPng = FileReader.classify("a.png", "not a picture".toByteArray(), "image/png")
        assertEquals(FileContentKind.IMAGE, textInAPng.kind)

        val nul = FileReader.classify("a.kt", byteArrayOf('a'.code.toByte(), 0), "text/plain")
        assertEquals("a NUL byte is binary whatever the extension says", FileContentKind.BINARY, nul.kind)

        val empty = FileReader.classify("a.kt", ByteArray(0), "text/plain")
        assertEquals("an empty file is text: a viewer that shows nothing beats a download button", FileContentKind.TEXT, empty.kind)
        assertEquals(emptyList<String>(), empty.lines)

        val noNewline = FileReader.classify("a.kt", "one\ntwo".toByteArray(), "text/plain")
        assertEquals(listOf("one", "two"), noNewline.lines)
    }

    @Test
    fun `a user message formats for search and for the jump list alike`() {
        val message = SessionMessage.User(
            id = "msg_1",
            time = SessionMessage.CreatedTime(1),
            text = "add a wrap toggle\nto the diff viewer",
        )
        // The jump list previews the first non-blank line; the search previews the line that matched.
        // Both come from the same formatted message, so a hit and a jump cannot disagree.
        assertTrue(dev.opencode.android.core.data.transcript.TranscriptFormatter.message(message).contains("wrap toggle"))
    }
}
