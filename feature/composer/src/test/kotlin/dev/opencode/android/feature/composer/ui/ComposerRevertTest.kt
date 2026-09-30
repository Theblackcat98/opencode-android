package dev.opencode.android.feature.composer.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A staged undo belongs to the session it was staged in.
 *
 * **The banner that says "Undo staged" and the send that commits first read the same fact, and it has to be
 * this session's.** The client used to keep one staged revert for the whole server, filled by every session's
 * `session.revert.staged`, so an undo in one session put the banner (and a commit-before-send) on every other
 * session the user opened afterwards. The server's own projection of the session (`SessionInfo.revert`) is
 * per session and survives a restart, which is what the composer follows now.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ComposerRevertTest {

    private lateinit var server: ComposerServer
    private val viewModels = ComposerViewModels()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server = ComposerServer()
    }

    @After
    fun tearDown() {
        // The view models first: clearing them cancels `viewModelScope`, so no collector is left to resume
        // on a `Dispatchers.Main` that `resetMain()` has already taken away.
        viewModels.clear()
        server.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `a revert staged on another session does not show its banner here`() = runTest {
        val composer = openComposer()

        server.set.apply(staged(session = "ses_other", message = "msg_elsewhere"))

        assertFalse("nothing was staged in ses_1: ${composer.state.value.stagedRevert}", composer.state.value.isStaged)
        assertNull(composer.state.value.stagedRevert)
    }

    @Test
    fun `an undo of another session being cleared leaves this session's banner alone`() = runTest {
        val composer = openComposer()
        server.set.apply(staged(session = "ses_1", message = "msg_mine"))
        composer.state.await("this session's staged revert") { it.isStaged }

        server.set.apply(server.event("session.revert.cleared", """{"sessionID":"ses_other"}"""))

        assertEquals("msg_mine", composer.state.value.stagedRevert?.messageID)
    }

    @Test
    fun `a send commits only a revert that was staged on the session it goes to`() = runTest {
        val composer = openComposer(draft = "carry on")
        server.set.apply(staged(session = "ses_other", message = "msg_elsewhere"))

        composer.send()

        server.awaitCall("the prompt") { it.method == "POST" && it.path == "/api/session/ses_1/prompt" }
        assertTrue(
            "ses_1 has nothing staged, so a commit would be aimed at a revert that is not there: ${server.calls}",
            server.calls.none { it.path.endsWith("/revert/commit") },
        )
    }

    @Test
    fun `a send commits the revert staged on its own session first`() = runTest {
        server.accepted("/api/session/ses_1/revert/commit")
        val composer = openComposer(draft = "carry on")
        server.set.apply(staged(session = "ses_1", message = "msg_mine"))
        composer.state.await("this session's staged revert") { it.isStaged }

        composer.send()

        server.awaitCall("the prompt") { it.method == "POST" && it.path == "/api/session/ses_1/prompt" }
        val paths = server.calls.map { it.path }
        val commit = paths.indexOf("/api/session/ses_1/revert/commit")
        val prompt = paths.indexOf("/api/session/ses_1/prompt")
        assertTrue("the commit goes before the prompt: $paths", commit in 0 until prompt)
    }

    @Test
    fun `asking for a redo changes nothing until it is confirmed, and declining keeps the undo`() = runTest {
        val composer = openComposer()
        server.set.apply(staged(session = "ses_1", message = "msg_mine"))
        composer.state.await("this session's staged revert") { it.isStaged }

        composer.askRedo()

        // Awaited, not read: `askRedo` writes a `MutableStateFlow` that a `combine` turns into `state`, so
        // `.value` read straight afterwards is the state from before the question, and which test failed
        // depended on which ran next.
        val asked = composer.state.await("the question to open") { it.confirmingRedo }
        assertTrue("the undo is still staged", asked.isStaged)
        assertTrue("nothing has been asked of the server: ${server.calls}", server.calls.none { it.method == "DELETE" })

        composer.dismissRedo()

        assertFalse(composer.state.value.confirmingRedo)
        assertTrue("the undo is still staged", composer.state.value.isStaged)
        assertTrue("and still nothing was sent: ${server.calls}", server.calls.none { it.method == "DELETE" })
    }

    @Test
    fun `a confirmed redo clears the staged revert of its own session and nothing else`() = runTest {
        server.accepted("/api/session/ses_1/revert")
        val composer = openComposer()
        server.set.apply(staged(session = "ses_1", message = "msg_mine"))
        composer.state.await("this session's staged revert") { it.isStaged }
        composer.askRedo()

        composer.redo()

        val call = server.awaitCall("the redo") { it.method == "DELETE" }
        assertEquals("/api/session/ses_1/revert", call.path)
        assertFalse("the question is over once it is answered", composer.state.value.confirmingRedo)
        // The banner leaves with the server's own event, not with the answer to the request.
        server.set.apply(server.event("session.revert.cleared"))
        composer.state.await("the banner to go") { !it.isStaged }
    }

    @Test
    fun `a redo the server refuses says so and leaves the undo staged`() = runTest {
        server.failing("/api/session/ses_1/revert")
        val composer = openComposer()
        server.set.apply(staged(session = "ses_1", message = "msg_mine"))
        composer.state.await("this session's staged revert") { it.isStaged }

        composer.redo()

        composer.state.await("the failure") { it.error != null }
        assertTrue("the undo is still staged", composer.state.value.isStaged)
    }

    @Test
    fun `the redo command asks first, spends its own text and sends nothing`() = runTest {
        val composer = openComposer(draft = "/redo")
        server.set.apply(staged(session = "ses_1", message = "msg_mine"))
        composer.state.await("this session's staged revert") { it.isStaged }

        assertTrue("/redo is sendable", composer.state.value.canSend)
        composer.send()

        val asked = composer.state.await("the question") { it.confirmingRedo }
        assertEquals("the command is not left in the box", "", asked.text)
        assertTrue("nothing has been asked of the server: ${server.calls}", server.calls.none { it.method == "DELETE" })
    }

    private fun staged(session: String, message: String) = server.event(
        "session.revert.staged",
        """{"sessionID":"$session","revert":{"messageID":"$message"}}""",
    )

    private suspend fun openComposer(draft: String = "unsent draft"): ComposerViewModel {
        val composer = viewModels.composer(server, draft)
        composer.open(server.sessionID)
        composer.state.await("open to restore the draft") { it.text == draft && it.sessionID == server.sessionID }
        return composer
    }
}
