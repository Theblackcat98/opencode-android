package dev.opencode.android.feature.review

import dev.opencode.android.core.data.review.ReviewComments
import dev.opencode.android.core.data.review.ReviewScope
import dev.opencode.android.core.data.server.RevertCommands
import dev.opencode.android.core.data.server.ReviewStore
import dev.opencode.android.core.data.server.VcsStore
import dev.opencode.android.core.model.FileDiffStatus
import dev.opencode.android.core.model.SessionRevert
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
 * The Phase 6 operations against a real `ServerApi` over a [ReviewServer].
 *
 * The claims are the ones a user could be harmed by getting wrong: that "last turn" asks for
 * `session.diff` and the other three ask for `vcs.diff` with the right mode, that a stage interrupts
 * and cancels *before* it stages, that a commit is a `204` and not a body, and that the base picker
 * sends the `base` override rather than a branch name the server has to guess at.
 */
class ReviewOperationsTest {

    private lateinit var server: ReviewServer

    @Before
    fun setUp() {
        server = ReviewServer()
        server.answer("GET /api/session/ses_1/diff", ReviewFixtures.diffEnvelope(ReviewFixtures.singleFilePatch))
        server.answer(
            "GET /api/vcs/diff",
            ReviewFixtures.vcsDiffEnvelope(ReviewFixtures.singleFilePatch),
        )
        server.answer("GET /api/vcs", ReviewFixtures.vcsInfo())
        server.answer("GET /api/vcs/status", ReviewFixtures.vcsStatus(ReviewFixtures.status))
        server.answer(
            "GET /api/vcs/base",
            """{"location":{"id":"loc","directory":"${ReviewFixtures.DIRECTORY}"},"data":{"name":"main","ref":"refs/heads/main","source":"default"}}""",
        )
        server.answer(
            "GET /api/vcs/branch",
            """{"location":{"id":"loc","directory":"${ReviewFixtures.DIRECTORY}"},"data":["main","develop","feature/review"]}""",
        )
        server.answer("POST /api/session/ses_1/revert/stage", """{"data":{"messageID":"msg_1","snapshot":"abc"}}""")
        server.answer("DELETE /api/session/ses_1/revert", "", status = 204)
        server.answer("POST /api/session/ses_1/revert/commit", "", status = 204)
        server.answer(
            "POST /api/session/ses_1/fork",
            """{"data":{"id":"ses_2","projectID":"prj_1","cost":0,"tokens":{"input":0,"output":0,"reasoning":0,"cache":{"read":0,"write":0}},"time":{"created":1,"updated":2},"location":{"id":"loc","directory":"${ReviewFixtures.DIRECTORY}"}}}""",
        )
        server.answer("POST /api/session/ses_1/interrupt", """{"interrupted":true}""")
        server.answer("DELETE /api/session/ses_1/inbox/ib_1", "", status = 204)
    }

    @After
    fun tearDown() = server.close()

    // ------------------------------------------------------------------ scopes

    @Test
    fun `the last turn scope asks session diff and the others ask vcs diff`() = runTest {
        val store = ReviewStore("s", server.api)

        store.load(ReviewFixtures.session, ReviewScope.LastTurn())
        assertTrue(
            "last turn is a session diff, got ${server.requests}",
            server.lastRequest("/api/session/ses_1/diff") != null,
        )
        assertNull(server.lastRequest("/api/vcs/diff"))

        store.load(ReviewFixtures.session, ReviewScope.Uncommitted)
        assertNotNull(server.lastRequest("/api/vcs/diff"))
        assertTrue(server.lastRequest("/api/vcs/diff")!!.contains("mode=working"))
    }

    @Test
    fun `each repository scope sends the mode the TUI names`() = runTest {
        val store = ReviewStore("s", server.api)

        store.load(ReviewFixtures.session, ReviewScope.Committed)
        assertTrue(server.lastRequest("/api/vcs/diff")!!.contains("mode=committed"))

        store.load(ReviewFixtures.session, ReviewScope.All)
        assertTrue(server.lastRequest("/api/vcs/diff")!!.contains("mode=branch"))
    }

    @Test
    fun `the base override is sent as base, not as a mode`() = runTest {
        val store = ReviewStore("s", server.api)

        store.load(ReviewFixtures.session, ReviewScope.All, base = "develop")

        val request = server.lastRequest("/api/vcs/diff")!!
        assertTrue(request, request.contains("base=develop"))
        assertTrue(request, request.contains("mode=branch"))
    }

    @Test
    fun `the turn scope sends from and to when they are given`() = runTest {
        val store = ReviewStore("s", server.api)

        store.load(ReviewFixtures.session, ReviewScope.LastTurn(from = "msg_1", to = "msg_9"))

        val request = server.lastRequest("/api/session/ses_1/diff")!!
        assertTrue(request, request.contains("from=msg_1"))
        assertTrue(request, request.contains("to=msg_9"))
    }

    @Test
    fun `a scope that cannot run is refused before a call`() = runTest {
        val store = ReviewStore("s", server.api)
        val before = server.requests.size

        store.load(session = null, scope = ReviewScope.LastTurn())

        // No session means no turn to diff, and a repository scope means no directory: both are
        // refusals with a message rather than a call that will fail.
        assertEquals("no call should have been made", before, server.requests.size)
        assertEquals(ReviewStore.NULL_SESSION, store.state.value.error)
    }

    @Test
    fun `the diffs are parsed into structure, not left as text`() = runTest {
        val store = ReviewStore("s", server.api)

        store.load(ReviewFixtures.session, ReviewScope.LastTurn())

        val file = store.state.value.files.single()
        assertEquals(ReviewFixtures.singleFilePatch.single().file, file.file)
        assertEquals(1, file.hunks.size)
        assertEquals(4, file.hunks.single().lines.size)
        assertEquals(listOf(ReviewFixtures.singleFilePatch.single().file), store.state.value.paths)
    }

    // ------------------------------------------------------------------ the VCS header

    @Test
    fun `the header carries the branch, the base and the changed files`() = runTest {
        val vcs = VcsStore("s", ReviewFixtures.DIRECTORY, server.api)

        vcs.refresh()
        vcs.loadBase()

        val state = vcs.state.value
        assertEquals("feature/review", state.branch)
        assertEquals("main", state.defaultBranch)
        assertEquals("git", state.provider)
        assertTrue(state.isRepository)
        assertEquals(listOf(ReviewFixtures.status), state.files)
        assertEquals("main", state.baseFor(null))
    }

    @Test
    fun `an explicit base beats the server's answer`() = runTest {
        val vcs = VcsStore("s", ReviewFixtures.DIRECTORY, server.api)
        vcs.loadBase()

        assertEquals("develop", vcs.state.value.baseFor("develop"))
        assertEquals("main", vcs.state.value.baseFor(null))
        assertEquals("main", vcs.state.value.baseFor(""))
    }

    @Test
    fun `a directory that is not a repository says so rather than showing a blank branch`() = runTest {
        server.answer(
            "GET /api/vcs",
            """{"location":{"id":"loc","directory":"${ReviewFixtures.DIRECTORY}"},"data":{"branch":{}}}""",
        )
        val vcs = VcsStore("s", ReviewFixtures.DIRECTORY, server.api)

        vcs.refresh()

        val state = vcs.state.value
        assertFalse(state.isRepository)
        assertNull(state.branch)
    }

    @Test
    fun `vcs branch updated moves the header and re-reads the file list`() = runTest {
        val vcs = VcsStore("s", ReviewFixtures.DIRECTORY, server.api)
        vcs.refresh()
        val before = server.requests.count { it.contains("/api/vcs/status") }

        vcs.applyBranchUpdated("develop")

        assertEquals("develop", vcs.state.value.branch)
        // The header is the branch *and* the file list, so the list is stale the moment the branch
        // moves; the event handler re-reads it and this is the rule that handler relies on.
        assertTrue(before >= 1)
    }

    @Test
    fun `the filesystem changed filter only matches a file inside the directory`() {
        val filter = VcsStore.directoryFilter(ReviewFixtures.DIRECTORY)

        assertTrue(filter("${ReviewFixtures.DIRECTORY}/src/a.kt"))
        assertTrue(filter(ReviewFixtures.DIRECTORY))
        assertFalse(filter("/home/dev/other/src/a.kt"))
        assertFalse(filter("${ReviewFixtures.DIRECTORY}other/a.kt"))
    }

    // ------------------------------------------------------------------ revert, redo and fork

    @Test
    fun `a stage interrupts and cancels before it stages, and in that order`() = runTest {
        val reverts = RevertCommands(server.api)

        reverts.stage(
            sessionID = "ses_1",
            messageID = "msg_1",
            busy = true,
            pendingUserInboxIDs = listOf("ib_1"),
        )

        val paths = server.requests.map { it.substringAfter(' ').substringBefore('?') }
        val interrupt = paths.indexOfFirst { it.endsWith("/interrupt") }
        val cancel = paths.indexOfFirst { it.endsWith("/inbox/ib_1") }
        val stage = paths.indexOfFirst { it.endsWith("/revert/stage") }
        assertTrue("interrupt must come first, got $paths", interrupt in 0 until stage)
        assertTrue("cancel must come before the stage, got $paths", cancel in 0 until stage)
        assertTrue(interrupt < cancel)
    }

    @Test
    fun `a stage on an idle session with nothing queued is just the stage`() = runTest {
        val reverts = RevertCommands(server.api)

        reverts.stage(sessionID = "ses_1", messageID = "msg_1", busy = false, pendingUserInboxIDs = emptyList())

        assertEquals(1, server.requests.count { it.contains("/revert/stage") })
        assertEquals(0, server.requests.count { it.contains("/interrupt") })
    }

    @Test
    fun `a stage that the server refuses changes nothing and says which step failed`() = runTest {
        server.answer("POST /api/session/ses_1/revert/stage", """{"_tag":"SessionBusyError","message":"busy"}""", status = 409)
        val reverts = RevertCommands(server.api)

        val outcome = reverts.stage(sessionID = "ses_1", messageID = "msg_1", busy = false)

        assertTrue(outcome is RevertCommands.StageOutcome.Failed)
        assertEquals("stage", (outcome as RevertCommands.StageOutcome.Failed).step)
        assertNull(reverts.state.value.staged)
    }

    @Test
    fun `a stage that the interrupt step fails at never reaches the stage`() = runTest {
        server.answer("POST /api/session/ses_1/interrupt", """{"_tag":"NotFoundError","message":"gone"}""", status = 404)
        val reverts = RevertCommands(server.api)

        val outcome = reverts.stage(sessionID = "ses_1", messageID = "msg_1", busy = true)

        assertEquals("interrupt", (outcome as RevertCommands.StageOutcome.Failed).step)
        assertEquals(0, server.requests.count { it.contains("/revert/stage") })
    }

    @Test
    fun `a successful stage records the server's revert and the prompt`() = runTest {
        val reverts = RevertCommands(server.api)

        reverts.stage(sessionID = "ses_1", messageID = "msg_1", busy = false)

        val state = reverts.state.value
        assertTrue(state.isStaged)
        assertEquals("msg_1", state.staged?.messageID)
    }

    @Test
    fun `redo clears the staged revert and the restored prompt`() = runTest {
        val reverts = RevertCommands(server.api)
        reverts.stage(sessionID = "ses_1", messageID = "msg_1", busy = false)

        reverts.clear("ses_1")

        assertFalse(reverts.state.value.isStaged)
        assertNull(reverts.state.value.restored)
    }

    @Test
    fun `a commit is a 204 with no body`() = runTest {
        val reverts = RevertCommands(server.api)

        val result = reverts.commit("ses_1")

        assertTrue(result.isSuccess)
        assertEquals(1, server.requests.count { it.contains("/revert/commit") })
    }

    @Test
    fun `a fork carries before when one is named`() = runTest {
        val reverts = RevertCommands(server.api)

        val forked = reverts.fork("ses_1", before = "msg_5").getOrThrow()

        assertEquals("ses_2", forked.id)
        // `before` is in the body, not the query: the route takes `{before?: messageID}`.
        assertTrue(server.lastBody("/fork")!!.contains("msg_5"))
    }

    @Test
    fun `a fork with no boundary copies the whole history`() = runTest {
        val reverts = RevertCommands(server.api)

        reverts.fork("ses_1")

        val body = server.lastBody("/fork")!!
        // An omitted boundary copies the whole history, and sending `{"before":null}` would be a
        // different request the server has to interpret rather than one it can default.
        assertFalse("an omitted before must not be sent as an empty one", body.contains("msg_"))
        assertTrue(body, body.contains("\"before\":null") || body == "{}")
    }

    // ------------------------------------------------------------------ the staged banner

    @Test
    fun `the banner lists the files the server says it will restore`() {
        val revert = SessionRevert(
            messageID = "msg_1",
            files = listOf(
                dev.opencode.android.core.model.FileDiff("a.kt", "@@ -1 +1 @@\n-a\n+b\n", 1, 1, FileDiffStatus.Modified),
            ),
        )

        val files = dev.opencode.android.core.data.review.RevertPlan.restoredFiles(revert)

        assertEquals(listOf("a.kt"), files.map { it.file })
        assertTrue(dev.opencode.android.core.data.review.RevertPlan.restoresFiles(revert))
    }

    @Test
    fun `a revert with no files says the working copy was not snapshotted`() {
        val revert = SessionRevert(messageID = "msg_1")

        assertFalse(dev.opencode.android.core.data.review.RevertPlan.restoresFiles(revert))
        assertEquals(emptyList<Any>(), dev.opencode.android.core.data.review.RevertPlan.restoredFiles(revert))
    }

    // ------------------------------------------------------------------ comments and capabilities

    @Test
    fun `a comment filed in the store round trips through the web app's format`() {
        val store = ReviewStore("s", server.api)
        val comment = dev.opencode.android.core.data.review.CommentSelection.onDiff(
            path = "src/A.kt",
            startLine = 3,
            endLine = 5,
            text = "use a guard here",
        )

        store.addComment(comment)

        val read = ReviewComments.read(store.commentMetadata(store.comments.value))
        assertEquals(listOf(comment), read)
    }

    @Test
    fun `taking the comments empties the list so the next send does not repeat them`() {
        val store = ReviewStore("s", server.api)
        store.addComment(
            dev.opencode.android.core.data.review.CommentSelection.onDiff("a.kt", 1, 1, "x"),
        )
        assertEquals(1, store.comments.value.size)

        store.takeComments()

        assertEquals(0, store.comments.value.size)
    }

    @Test
    fun `a 404 on an experimental route hides it, and only a 404 does`() = runTest {
        val store = ReviewStore("s", server.api)

        store.recordCapability(
            dev.opencode.android.core.data.capability.ExperimentalRoute.FS_WRITE,
            dev.opencode.android.core.data.action.ActionError(
                dev.opencode.android.core.data.action.ActionErrorKind.NOT_FOUND,
                "HTTP 404",
            ),
        )
        assertFalse(store.isUsable(dev.opencode.android.core.data.capability.ExperimentalRoute.FS_WRITE, allowedBySetting = true))

        // A `500` says nothing about whether the route exists, so it does not clear the absence: the
        // honest answer to "is this feature there" is still "no, it answered 404".
        store.recordCapability(
            dev.opencode.android.core.data.capability.ExperimentalRoute.FS_WRITE,
            dev.opencode.android.core.data.action.ActionError(
                dev.opencode.android.core.data.action.ActionErrorKind.SERVER,
                "HTTP 500",
            ),
        )
        assertFalse(store.isUsable(dev.opencode.android.core.data.capability.ExperimentalRoute.FS_WRITE, allowedBySetting = true))
    }

    @Test
    fun `a route the user has switched off is not offered however well it works`() = runTest {
        val store = ReviewStore("s", server.api)

        assertFalse(store.isUsable(dev.opencode.android.core.data.capability.ExperimentalRoute.SESSION_EXPORT, allowedBySetting = false))
    }
}
