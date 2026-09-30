package dev.opencode.android.feature.composer.ui

import dev.opencode.android.core.data.composer.Assembly
import dev.opencode.android.core.data.composer.ClientCommands
import dev.opencode.android.core.data.composer.ComposerInput
import dev.opencode.android.core.data.composer.PromptAssembler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The Send button and `send()` agree about what an app command is.
 *
 * **The button used to be enabled by what the composer thought of the text, and `send()` by what
 * [PromptAssembler] thought of it.** A `/compact` has no argument, so the composer's own rule ("a client
 * command needs some text") left Send disabled for every app command that takes none: `/compact`, `/undo`,
 * `/redo`, `/diff`, `/new`, `/sessions`, `/models`, `/agents` and `/editor`. Picking one from the palette
 * inserted its text, the caption said "Sends: an app action", and the button did nothing because it was
 * never enabled. `send()` itself worked, which is why nothing below the screen noticed.
 *
 * Each test drives the real view model over a real [dev.opencode.android.core.data.server.ServerDataSet]
 * and reads what reached the server, so a route spelled wrong or a call that never happens fails here and
 * not on a device.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ComposerSendTest {

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
        // on a `Dispatchers.Main` that `resetMain()` has already taken away. A test that sends a prompt
        // leaks one, which is what failed whichever test ran next.
        viewModels.clear()
        server.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `an app command that takes no argument enables Send and reaches the server when sent`() = runTest {
        server.compactionAccepted()
        val composer = openComposer(draft = "/compact")

        assertTrue("/compact needs no argument, so there is nothing more to type", composer.state.value.canSend)
        composer.send()

        composer.state.await("the compaction to be accepted and the box cleared") { it.text.isEmpty() }
        val posts = server.calls.filter { it.method == "POST" && it.path.endsWith("/compact") }
        assertEquals(listOf("/api/session/ses_1/compact"), posts.map { it.path })
    }

    @Test
    fun `every app command that takes no argument enables Send and every one that takes one does not`() = runTest {
        val takesNone = ClientCommands.ALL.filterNot { it.takesArguments }.map { it.name }
        val takesOne = ClientCommands.ALL.filter { it.takesArguments }.map { it.name }
        assertTrue("the table has both kinds, or this test proves nothing", takesNone.isNotEmpty())
        assertTrue("the table has both kinds, or this test proves nothing", takesOne.isNotEmpty())
        val composer = openComposer(draft = "sentinel")

        for (name in takesNone) {
            assertTrue("/$name takes no argument and must be sendable", composer.typed("/$name").canSend)
        }
        for (name in takesOne) {
            assertFalse("/$name is nothing without its argument", composer.typed("/$name").canSend)
            assertFalse("/$name followed by blanks is still nothing", composer.typed("/$name  ").canSend)
            assertTrue("/$name with its argument is a send", composer.typed("/$name what is this?").canSend)
        }
    }

    @Test
    fun `Send is enabled exactly when the assembler would do something with the box`() = runTest {
        val composer = openComposer(draft = "sentinel")
        val lines = listOf(
            "", "  ", "hello", "/", "/unknown", "/unknown words", "!", "! ", "!ls", "/btw", "/btw why", "/compact",
            "/compact now", "/undo", "/new", "  /redo  ",
        )
        for (line in lines) {
            val state = composer.typed(line)
            val assembled = PromptAssembler.assemble(ComposerInput(text = line, serverCommands = state.serverCommands))
            assertEquals(
                "'$line': the button says ${state.canSend} and the assembler said $assembled",
                assembled != Assembly.Empty,
                state.canSend,
            )
        }
    }

    @Test
    fun `a question command with no question stays unsendable and sends nothing`() = runTest {
        val composer = openComposer(draft = "/btw")

        assertFalse(composer.state.value.canSend)
        composer.send()

        assertTrue("no request may leave for an empty question", server.calls.none { it.path.endsWith("/generate") })
        assertNull("and no question sheet may open", composer.state.value.sideQuestion)
    }

    @Test
    fun `a question command with its question is sent to the side-question route`() = runTest {
        server.sideAnswer("because the guard runs first")
        val composer = openComposer(draft = "/btw why is it like that?")

        assertTrue(composer.state.value.canSend)
        composer.send()

        val answered = composer.state.await("the answer to reach the sheet") { it.sideQuestion?.answered == true }
        assertEquals("because the guard runs first", answered.sideQuestion?.answer)
        assertEquals(listOf("/api/session/ses_1/generate"), server.calls.filter { it.method == "POST" }.map { it.path })
    }

    @Test
    fun `undo from the palette asks for the confirmation before it stages anything`() = runTest {
        // The route answers newest first, and the timeline reads it oldest first.
        server.messages(
            "[${server.userMessage("msg_last", "the newest prompt", created = 2)}," +
                "${server.userMessage("msg_first", "an older prompt", created = 1)}]",
        )
        val composer = openComposer(draft = "/undo")
        server.set.timeline(server.sessionID).state
            .await("the messages to load") { state -> state.messages.isNotEmpty() }

        assertTrue(composer.state.value.canSend)
        composer.send()

        val effect = composer.effect.awaitFirst("the undo confirmation")
        assertTrue("expected a confirmation and got $effect", effect is ComposerEffect.ConfirmUndo)
        assertEquals(
            "the newest user message is what the palette's undo is aimed at",
            "msg_last",
            (effect as ComposerEffect.ConfirmUndo).messageID,
        )
        assertTrue(
            "nothing is staged until the user has confirmed",
            server.calls.none { it.path.endsWith("/revert/stage") },
        )
    }

    /** Types [line] into the box and returns the state the view model computed for it. */
    private suspend fun ComposerViewModel.typed(line: String): ComposerUiState {
        setText(line)
        return state.await("the box to show '$line'") { it.text == line }
    }

    private fun composer(draft: String): ComposerViewModel = viewModels.composer(server, draft)

    /** Opens the session and waits for `open` to finish, which is when the draft it restores is showing. */
    private suspend fun openComposer(draft: String): ComposerViewModel {
        val composer = composer(draft)
        composer.open(server.sessionID)
        composer.state.await("open to restore the draft") { it.text == draft }
        return composer
    }

    private suspend fun <T> kotlinx.coroutines.flow.Flow<T>.awaitFirst(what: String): T =
        withContext(Dispatchers.Default) { withTimeoutOrNull(EFFECT_MILLIS) { first() } }
            ?: throw AssertionError("Timed out waiting for $what")

    private companion object {
        const val EFFECT_MILLIS = 3_000L
    }
}
