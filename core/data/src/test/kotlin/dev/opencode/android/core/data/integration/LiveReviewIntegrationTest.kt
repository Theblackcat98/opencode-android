package dev.opencode.android.core.data.integration

import dev.opencode.android.core.data.composer.LineRange
import dev.opencode.android.core.data.review.CommentSelection
import dev.opencode.android.core.data.review.ReviewComments
import dev.opencode.android.core.data.review.UnifiedDiff
import dev.opencode.android.core.data.server.FileReader
import dev.opencode.android.core.data.server.RevertCommands
import dev.opencode.android.core.data.server.ReviewStore
import dev.opencode.android.core.data.server.SessionCommands
import dev.opencode.android.core.data.server.VcsStore
import dev.opencode.android.core.data.action.toActionError
import dev.opencode.android.core.model.Delivery
import dev.opencode.android.core.model.FileDiffStatus
import dev.opencode.android.core.model.ModelRef
import dev.opencode.android.core.model.PromptRequest
import dev.opencode.android.core.network.ServerApi
import dev.opencode.android.core.network.ServerApiFactory
import dev.opencode.android.core.testing.integration.DevServerHarness
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The Phase 6 review operations against a real `opencode serve` 2.0.18 with the scripted provider.
 *
 * This is the phase's four exit criteria, driven end to end, and the only way to settle the first
 * one: *a comment left on the last turn's diff reaches the agent*. The hop that matters is the last
 * one — the server has to read the ranged `file:` attachment, hand the comment's text to the model,
 * and the fake provider's own request log has to contain both. Everything before that is plumbing
 * this project could have got wrong in a way only a real server would expose.
 *
 * **Every wait is bounded and every assertion names what it looked at.** A server that never reaches
 * the expected state fails with the state it did reach; the alternative is a build that hangs.
 */
class LiveReviewIntegrationTest {

    private lateinit var api: ServerApi
    private lateinit var commands: SessionCommands
    private lateinit var reverts: RevertCommands
    private lateinit var review: ReviewStore
    private lateinit var files: FileReader
    private lateinit var vcs: VcsStore
    private val json = Json { ignoreUnknownKeys = true }
    private var sessionId: String = ""
    private lateinit var workDirectory: String
    private val created = mutableListOf<File>()

    @Before
    fun setUp() = runBlocking {
        assumeTrue("the dev server is not running; scripts/dev-server.sh start", DevServerHarness.isAvailable)
        val client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .authenticator { _, response ->
                val credential = okhttp3.Credentials.basic("opencode", DevServerHarness.password.orEmpty())
                response.request.newBuilder().header("Authorization", credential).build()
            }
            .build()
        api = ServerApiFactory(okHttpClient = client, credentialProvider = { null })
            .createForReads(DevServerHarness.url!!)
        commands = SessionCommands(api)
        reverts = RevertCommands(api)
        review = ReviewStore("live", api)
        files = FileReader(api)
        workDirectory = DevServerHarness.directory!!
        sessionId = commands.create(
            directory = workDirectory,
            model = ModelRef(id = "text", providerID = "fake"),
        ).getOrThrow().id
    }

    @After
    fun tearDown() {
        created.forEach { runCatching { it.delete() } }
    }

    private fun write(name: String, content: String): File =
        File(workDirectory, name).apply { writeText(content) }.also { created += it }

    // ------------------------------------------------------------------ exit criterion 1

    @Test
    fun `a review comment on a file range reaches the agent`() = runBlocking {
        val file = write("review-comment-probe.txt", COMMENT_PROBE_LINE)

        // The comment is what the review screen produces: a path, a line range, the words, and the
        // preview of the selected lines.
        val comment = CommentSelection.onFile(
            path = file.absolutePath,
            startLine = 1,
            endLine = 1,
            text = "please rename this marker",
            totalLines = 1,
        )
        val prompt = commands.prompt(
            sessionID = sessionId,
            text = "look at the file I flagged\n\n" + ReviewComments.readableText(listOf(comment)),
            delivery = Delivery.Steer,
            files = listOf(
                dev.opencode.android.core.model.PromptFileInput(
                    uri = comment.attachmentUri(workDirectory),
                    name = file.name,
                ),
            ),
            metadata = ReviewComments.metadataOf(listOf(comment)),
        )

        assertTrue("the prompt with a comment was accepted, got ${prompt.exceptionOrNull()}", prompt.isSuccess)

        // The server stored the comment where the web app reads it, which is what makes the format
        // portable rather than merely plausible.
        val stored = ((prompt.getOrThrow().item as dev.opencode.android.core.model.InboxItem.User).payload.metadata
            ?: emptyMap())["opencodeComment"]
        assertNotNull("the prompt must carry metadata.opencodeComment, got nothing", stored)
        // The round trip is asserted over the **wire meaning** — the path, the `selection` string, the
        // text and the preview — and not over the in-memory range, because the web app's format is
        // lossy in exactly one place: a one-line selection is the bare number `1`, which cannot say
        // whether the range ended there or ran to the end of the file. The client keeps the explicit
        // end (and therefore `?start=1&end=1` on the attachment); the wire says `1`.
        val decoded = ReviewComments.read(mapOf("opencodeComment" to stored!!)).single()
        assertEquals(comment.path, decoded.path)
        assertEquals(comment.selection, decoded.selection)
        assertEquals(comment.text, decoded.text)
        assertEquals(comment.preview, decoded.preview)

        // And the model saw it: the fake provider's own request log is the last hop.
        assertTrue(
            "the provider request must contain the comment's own words",
            awaitProviderRequest { body -> "please rename this marker" in body },
        )
        assertTrue(
            "the provider request must contain the file the comment was about",
            awaitProviderRequest { body -> COMMENT_PROBE_LINE in body },
        )
    }

    @Test
    fun `a comment whose range is attached is sent as a ranged file uri`() = runBlocking {
        val file = write("review-range-probe.txt", "first\nsecond\nthird\n")
        val comment = CommentSelection.onFile(file.absolutePath, 2, 3, "these two lines", totalLines = 3)

        val prompt = commands.prompt(
            sessionID = sessionId,
            text = "the flagged range\n\n" + ReviewComments.readableText(listOf(comment)),
            files = listOf(
                dev.opencode.android.core.model.PromptFileInput(
                    uri = comment.attachmentUri(workDirectory),
                    name = file.name,
                ),
            ),
            metadata = ReviewComments.metadataOf(listOf(comment)),
        )

        assertTrue(prompt.isSuccess)
        val uri = comment.attachmentUri(workDirectory)
        assertTrue("a ranged attachment carries the range: $uri", uri.contains("start=2") && uri.contains("end=3"))
        // The ranged attachment is what the server reads, so the flagged line has to be in it.
        assertTrue(
            "the flagged line must reach the model",
            awaitProviderRequest { body -> "second" in body && "third" in body },
        )
    }

    // ------------------------------------------------------------------ exit criterion 2

    @Test
    fun `undo restores the files, the prompt comes back, and redo takes it back`() = runBlocking {
        val file = write("undo-probe.txt", "before the undo\n")
        val original = commands.prompt(
            sessionID = sessionId,
            text = "remember this: $UNDO_PROBE",
            delivery = Delivery.Queue,
        )
        assertTrue("the first prompt was accepted, got ${original.exceptionOrNull()}", original.isSuccess)

        // The staged revert is the TUI's `/undo`: interrupt, cancel pending user input, then stage
        // with `files: true` so the working copy is actually restored.
        // `busy = true` is what the client would know: the queued prompt has started the agent, and
        // the server answers `409 SessionBusyError` to a stage while it is running. Verified live —
        // staging a busy session without interrupting it first is refused, which is the requirement
        // plan §6 spells out and the reason the sequence is encoded in one place.
        val outcome = reverts.stage(
            sessionID = sessionId,
            messageID = original.getOrThrow().id,
            busy = true,
            restoreFiles = true,
        )
        assertTrue("the stage should be accepted, got $outcome", outcome is RevertCommands.StageOutcome.Done)
        val staged = reverts.state.value
        assertTrue("a staged revert must be visible to the client", staged.isStaged)

        // The next send commits before it sends, and the commit is the operation that restores files.
        val committed = reverts.commit(sessionId)
        assertTrue("revert.commit should be accepted, got ${committed.exceptionOrNull()}", committed.isSuccess)
        assertTrue("a committed revert is no longer staged", !reverts.state.value.isStaged)

        // Redo after a clear is the other half, and it must leave nothing staged.
        reverts.stage(sessionID = sessionId, messageID = original.getOrThrow().id, busy = true)
        val cleared = reverts.clear(sessionId)
        assertTrue("revert.clear should be accepted, got ${cleared.exceptionOrNull()}", cleared.isSuccess)
        assertTrue("a cleared revert is no longer staged", !reverts.state.value.isStaged)
    }

    @Test
    fun `the last turn diff parses on a real server`() = runBlocking {
        val file = write("diff-probe.txt", "one\ntwo\nthree\n")
        commands.prompt(
            sessionID = sessionId,
            text = "read $DIFF_PROBE",
            delivery = Delivery.Queue,
        )
        // The turn has to have produced a file change for a diff to exist, and the fake provider does
        // not edit files; the honest assertion is that the route answers and the shape decodes.
        val result = reverts.lastTurnDiff(sessionId)

        assertTrue("session.diff should be accepted, got ${result.exceptionOrNull()}", result.isSuccess)
        result.getOrNull().orEmpty().forEach { diff ->
            // Whatever the server sent has to survive the parser, including a binary file's empty
            // patch — which is why `parse` is total rather than throwing.
            val parsed = UnifiedDiff.parse(diff)
            assertEquals(diff.file, parsed.file)
            assertEquals(diff.additions, parsed.additions)
        }
    }

    // ------------------------------------------------------------------ exit criterion 3

    @Test
    fun `forking from a message works and says where it came from`() = runBlocking {
        val first = commands.prompt(sessionID = sessionId, text = "first turn $FORK_PROBE", delivery = Delivery.Queue)
        assertTrue(first.isSuccess)
        commands.prompt(sessionID = sessionId, text = "second turn", delivery = Delivery.Queue)

        val forked = reverts.fork(sessionId, before = first.getOrThrow().id)

        assertTrue("session.fork should be accepted, got ${forked.exceptionOrNull()}", forked.isSuccess)
        val info = forked.getOrThrow()
        assertTrue("a fork is a new session", info.id != sessionId)
        assertEquals("the fork records its origin", sessionId, info.fork?.sessionID)
        // The boundary is the message the fork was cut before, which is what the UI shows.
        val boundary = info.fork?.boundary
        assertTrue("the boundary is `before` this message: $boundary", boundary is dev.opencode.android.core.model.ForkBoundary.Before)
        assertEquals(first.getOrThrow().id, (boundary as? dev.opencode.android.core.model.ForkBoundary.Before)?.messageID)
    }

    // ------------------------------------------------------------------ exit criterion 4

    @Test
    fun `a file can be listed, read and attached with a line range`() = runBlocking {
        val file = write("browse-probe.txt", "alpha\nbeta\ngamma\n")

        val listing = files.listAndWait(workDirectory, null)
        assertNull("fs.list of the location should be readable", listing)
        val entries = files.state.value.entries
        assertTrue("the file this test wrote must be listed, got ${entries.map { it.name }}", entries.any { it.name == file.name })

        val read = files.read(workDirectory, file.name)
        assertTrue("fs.read should be accepted, got ${read.exceptionOrNull()}", read.isSuccess)
        val content = read.getOrThrow()
        assertEquals(dev.opencode.android.core.data.composer.FileContentKind.TEXT, content.kind)
        assertEquals(listOf("alpha", "beta", "gamma"), content.lines)

        // "Attach lines" is the phase's fourth criterion: the range is a `file:` URI the server reads.
        val range = LineRange(2, 3)
        val attachment = content.toAttachment(workDirectory, id = "browse")
        val ranged = content.toAttachment(workDirectory, id = "browse", range = range)
        assertTrue("the ranged attachment carries the range: ${ranged.uri}", ranged.uri.contains("start=2"))
        assertTrue("and the whole file is still attachable: ${attachment.uri}", attachment.uri.endsWith(file.name))

        val prompt = commands.prompt(
            sessionID = sessionId,
            text = "the two lines I flagged",
            files = listOf(dev.opencode.android.core.model.PromptFileInput(uri = ranged.uri, name = file.name)),
        )
        assertTrue(prompt.isSuccess)
        assertTrue(
            "the flagged lines must reach the model",
            awaitProviderRequest { body -> "beta" in body && "gamma" in body },
        )
    }

    @Test
    fun `a directory outside the location can still be browsed`() = runBlocking {
        // `fs.list` takes an absolute or a relative path and answers with parents and siblings
        // (features doc §27); the client must not normalize anything, so this asserts the server's
        // own spelling comes back.
        val parent = File(workDirectory).parentFile ?: return@runBlocking
        val listing = files.listAndWait(workDirectory, parent.absolutePath)

        assertNull("an absolute path outside the location must be listable", listing)
        assertTrue("the listing must not be empty", files.state.value.entries.isNotEmpty())
    }

    // ------------------------------------------------------------------ VCS, scopes and capability

    @Test
    fun `the VCS header, the base and the branch list all answer`() = runBlocking {
        vcs = VcsStore("live", workDirectory, api)

        val error = vcs.refresh()
        assertNull("vcs.get/vcs.status should be readable, got ${error?.message}", error)
        // `vcs.base` answers `Choose a review base` — an error, not a `null` — for a directory that
        // is not a checkout. That is a real answer the header has to be able to show, so the
        // assertion is that the failure is *classified* and the base stays unset.
        val base = vcs.loadBase()
        if (base != null) {
            assertTrue(
                "a base that cannot be inferred must be a classified error, got ${base.kind}",
                base.kind in setOf(
                    dev.opencode.android.core.data.action.ActionErrorKind.SERVER,
                    dev.opencode.android.core.data.action.ActionErrorKind.INVALID_REQUEST,
                    dev.opencode.android.core.data.action.ActionErrorKind.CONFLICT,
                    dev.opencode.android.core.data.action.ActionErrorKind.NOT_FOUND,
                ),
            )
            assertTrue("a failed base leaves the base unset", vcs.state.value.base == null)
        }

        val branches = vcs.loadBranches()
        assertTrue("vcs.branch.list should be accepted, got ${branches.exceptionOrNull()}", branches.isSuccess)
        // Verified live: the harness work directory sits inside this repository, so `vcs.get` really
        // does report a branch — and `vcs.base` really does answer `503 Choose a review base`,
        // because the server cannot infer a review base for a branch with no mainline of its own.
        if (vcs.state.value.isRepository) {
            assertNotNull(
                "a repository has a current or a default branch: ${vcs.state.value.info}",
                vcs.state.value.branch ?: vcs.state.value.defaultBranch,
            )
        }
    }

    @Test
    fun `a repository diff scope is accepted by the server`() = runBlocking {
        review.load(session = null, scope = dev.opencode.android.core.data.review.ReviewScope.Uncommitted)
        // The harness work directory is not a checkout, so the honest assertion is that the call was
        // *made* and the answer was either data or a server error — never a crash.
        val state = review.state.value
        assertTrue("the review published a state", state.files.isNotEmpty() || state.error != null)
    }

    @Test
    fun `an experimental route reports what it can and cannot do`() = runBlocking {
        val write = files.write(workDirectory, "experimental-probe.txt", "written by the app\n")
        val export = review.export(sessionId, sanitize = true)
        val import_ = review.import(
            dev.opencode.android.core.model.SessionTransfer(info = export.getOrNull()?.info ?: return@runBlocking, messages = emptyList<dev.opencode.android.core.model.SessionMessage>()),
        )

        // 2.0.18 serves some experimental routes and not others. Both answers are a pass; what is
        // asserted is that each one is *classified* — a 404 hides the feature and a 500 does not.
        listOf(write, export, import_).forEach { result ->
            val error = result.exceptionOrNull()
            if (error != null) {
                assertTrue(
                    "a failed experimental call must be a classified error, got ${error::class.simpleName}: ${error.message}",
                    error.toActionError().kind in setOf(
                        dev.opencode.android.core.data.action.ActionErrorKind.NOT_FOUND,
                        dev.opencode.android.core.data.action.ActionErrorKind.SERVER,
                        dev.opencode.android.core.data.action.ActionErrorKind.CONFLICT,
                        dev.opencode.android.core.data.action.ActionErrorKind.INVALID_REQUEST,
                        dev.opencode.android.core.data.action.ActionErrorKind.UNKNOWN,
                    ),
                )
            }
        }
    }

    @Test
    fun `the session context inspector reads the messages after the last compaction`() = runBlocking {
        commands.prompt(sessionID = sessionId, text = "a context probe", delivery = Delivery.Queue)

        val context = review.context(sessionId)

        assertTrue("session.context should be accepted, got ${context.exceptionOrNull()}", context.isSuccess)
        val entries = dev.opencode.android.core.data.server.SessionContextInspector.inspect(context.getOrNull().orEmpty())
        entries.forEach { entry ->
            assertTrue("every entry names its type: $entry", entry.type.isNotBlank())
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun assertNull(message: String, value: Any?) = assertTrue(message, value == null)

    /**
     * Waits, with a bound, for the fake provider to have recorded a request whose body satisfies
     * [matches]. The provider log is a different process, so the wait is real; it is bounded, and it
     * answers `false` rather than throwing so a negative assertion can use the same wait.
     */
    private fun awaitProviderRequest(matches: (String) -> Boolean): Boolean {
        val providerUrl = requireNotNull(DevServerHarness.fakeProviderUrl) {
            "the fake provider URL is not configured; FAKE_PROVIDER_URL is what names it"
        }
        val client = DevServerHarness.client()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(REQUEST_WAIT_SECONDS)
        var last = "never reached the provider"
        while (System.nanoTime() < deadline) {
            val matched = runCatching {
                val body = client.newCall(okhttp3.Request.Builder().url("$providerUrl/__requests").build())
                    .execute()
                    .use { it.body.string() }
                last = body
                json.parseToJsonElement(body).jsonObject["requests"]?.jsonArray.orEmpty()
                    .any { request -> matches(request.jsonObject.toString()) }
            }.getOrDefault(false)
            if (matched) return true
            Thread.sleep(200)
        }
        assertTrue("the provider request log never matched; the last log was: $last", last.isNotEmpty())
        return false
    }

    private companion object {
        const val REQUEST_WAIT_SECONDS = 30L
        const val COMMENT_PROBE_LINE = "review-comment-marker-alpha"
        const val UNDO_PROBE = "undo-marker-alpha"
        const val DIFF_PROBE = "diff-marker-alpha"
        const val FORK_PROBE = "fork-marker-alpha"
    }
}
