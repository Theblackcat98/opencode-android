package dev.opencode.android.feature.composer.ui

import dev.opencode.android.core.data.composer.PromptIntent
import dev.opencode.android.core.data.timeline.PendingInboxItem
import dev.opencode.android.core.model.CommandInfo
import dev.opencode.android.core.model.Delivery
import dev.opencode.android.core.model.InboxItem
import dev.opencode.android.core.model.UserPromptPayload
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
 * The composer's state follows the stores it is projected from, instead of reading them once.
 *
 * **Every test opens the session first and waits for it to finish, then changes a store and asserts on
 * the state with nothing else touched.** That order is the whole point. `open` ends by restoring the
 * draft, which writes the view model's own state and so recomputes the projection once, after the
 * catalogs it asked for have answered — a test that let `open` do the loading would pass against a view
 * model that never follows anything. A store that changes *after* `open` is what a reconnect resync, a
 * `model.updated` event, a session starting to run and a permission arriving all are, and each of those
 * used to leave the composer showing what it read on the way in: a red "No model available" banner
 * over a server that had models, and a Stop button that never appeared.
 *
 * The draft is what tells the test `open` is over, because it is the one thing `open` does last that
 * the state shows.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ComposerReactivityTest {

    private lateinit var server: ComposerServer

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server = ComposerServer()
    }

    @After
    fun tearDown() {
        server.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `models that were loaded before the screen opened are offered`() = runTest {
        server.models(listOf(ComposerServer.model("text")))

        val composer = openComposer()

        assertTrue(composer.state.value.hasAnyModel)
        assertEquals(listOf("text"), composer.state.value.models.map { it.id })
    }

    @Test
    fun `the no-model banner clears when the model list loads after the screen has opened`() = runTest {
        server.failing("/api/model")
        val composer = openComposer()
        assertEquals("/work", composer.state.value.directory)
        assertFalse("nothing is known about the models yet", composer.state.value.hasAnyModel)

        server.models(listOf(ComposerServer.model("text")))
        server.set.models(server.directory).sync()

        val loaded = composer.state.await("the loaded models to reach the composer") { it.hasAnyModel }
        assertEquals(listOf("text"), loaded.models.map { it.id })
    }

    @Test
    fun `a disabled model does not make the banner go away`() = runTest {
        server.failing("/api/model")
        val composer = openComposer()

        server.models(listOf(ComposerServer.model("text", enabled = false)))
        server.set.models(server.directory).sync()

        val loaded = composer.state.await("the loaded models to reach the composer") { it.models.isNotEmpty() }
        assertFalse("a list with nothing enabled in it is still no model", loaded.hasAnyModel)
    }

    @Test
    fun `models and agents that come back after a reconnect reach a screen that never left`() = runTest {
        server.failing("/api/model")
        server.failing("/api/agent")
        val composer = openComposer()
        assertTrue(composer.state.value.agents.isEmpty())

        server.models(listOf(ComposerServer.model("text")))
        server.agents(listOf(ComposerServer.agent("build")))
        server.set.resync()

        val recovered = composer.state.await("the resync to reach the composer") {
            it.hasAnyModel && it.agents.isNotEmpty()
        }
        assertEquals(listOf("build"), recovered.agents.map { it.id })
    }

    @Test
    fun `stop and steering follow the session's execution without another keystroke`() = runTest {
        val composer = openComposer()
        assertFalse(composer.state.value.busy)

        server.set.apply(server.event("session.execution.started"))
        composer.state.await("the running session to enable Stop") { it.busy }

        server.set.apply(server.event("session.execution.succeeded"))
        composer.state.await("the finished turn to disable Stop") { !it.busy }
    }

    @Test
    fun `a queued prompt shows while it is pending and leaves when it is dropped`() = runTest {
        val composer = openComposer()
        assertTrue(composer.state.value.pending.isEmpty())
        val item = PendingInboxItem(
            id = "msg_pending",
            created = 1L,
            item = InboxItem.User(UserPromptPayload("and then explain why"), Delivery.Queue),
        )

        server.set.timeline(server.sessionID).showPending(item)
        assertEquals(
            listOf("msg_pending"),
            composer.state.await("the pending prompt to reach the composer") { it.pending.isNotEmpty() }
                .pending.map { it.id },
        )

        server.set.timeline(server.sessionID).dropPending("msg_pending")
        composer.state.await("the dropped prompt to leave the composer") { it.pending.isEmpty() }
    }

    @Test
    fun `a permission that arrives while the screen is open reaches the dock and leaves when answered`() = runTest {
        val composer = openComposer()
        assertTrue(composer.state.value.requests.isEmpty())

        server.set.apply(
            server.event("permission.asked", """{"id":"per_1","sessionID":"ses_1","action":"bash","resources":[]}"""),
        )
        assertEquals(
            listOf("per_1"),
            composer.state.await("the permission to reach the dock") { it.requests.isNotEmpty() }
                .requests.map { it.id },
        )

        server.set.apply(
            server.event("permission.replied", """{"sessionID":"ses_1","requestID":"per_1","reply":"once"}"""),
        )
        composer.state.await("the answered permission to leave the dock") { it.requests.isEmpty() }
    }

    @Test
    fun `a revert staged from another client shows its banner and clears with it`() = runTest {
        val composer = openComposer()
        assertNull(composer.state.value.stagedRevert)

        server.set.apply(
            server.event("session.revert.staged", """{"sessionID":"ses_1","revert":{"messageID":"msg_1"}}"""),
        )
        val staged = composer.state.await("the staged revert to reach the composer") { it.isStaged }
        assertEquals("msg_1", staged.stagedRevert?.messageID)

        server.set.apply(server.event("session.revert.cleared"))
        composer.state.await("the cleared revert to leave the composer") { !it.isStaged }
    }

    @Test
    fun `a slash command typed before the catalog loaded completes once it has`() = runTest {
        server.failing("/api/command")
        val composer = openComposer(draft = "/dep")
        assertTrue("no command is known yet", composer.state.value.completions.none { it.label == "/deploy" })

        server.commands(listOf(CommandInfo("deploy", "Ship it")))
        server.set.composerCatalogs.commands(server.directory).sync()

        val completed = composer.state.await("the command to reach the completions") { state ->
            state.completions.any { it.label == "/deploy" }
        }
        assertEquals(listOf("deploy"), completed.serverCommands.map { it.name })
    }

    @Test
    fun `what a slash line means is decided by the commands the server has now`() = runTest {
        server.failing("/api/command")
        val composer = openComposer(draft = "/deploy now")
        assertNull("an unknown command is not one yet", composer.state.value.intent)

        server.commands(listOf(CommandInfo("deploy", "Ship it")))
        server.set.composerCatalogs.commands(server.directory).sync()

        val known = composer.state.await("the command to change the intent") { it.intent != null }
        assertEquals(PromptIntent.Command("deploy", "now"), known.intent)
    }

    @Test
    fun `a session that has not been opened has an idle composer whatever the stores hold`() = runTest {
        server.models(listOf(ComposerServer.model("text")))
        val composer = composer()

        server.set.apply(server.event("session.execution.started"))

        assertNull(composer.state.value.sessionID)
        assertFalse(composer.state.value.busy)
    }

    private fun composer(draft: String = DRAFT): ComposerViewModel = ComposerViewModel(
        active = MutableStateFlow(server.set),
        modelPreferences = FakeModelPreferences,
        memory = FakeComposerMemory(draft),
        attachmentReader = AttachmentReader(NoImages),
    )

    /** Opens the session and waits for `open` to finish, which is when the draft it restores is showing. */
    private suspend fun openComposer(draft: String = DRAFT): ComposerViewModel {
        val composer = composer(draft)
        composer.open(server.sessionID)
        composer.state.await("open to restore the draft") { it.text == draft }
        return composer
    }

    private companion object {
        const val DRAFT = "unsent draft"
    }
}
