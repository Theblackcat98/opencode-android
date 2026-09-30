package dev.opencode.android.feature.execution

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import dev.opencode.android.core.designsystem.theme.OpenCodeTheme
import dev.opencode.android.core.model.event.PtyInfo
import dev.opencode.android.core.model.event.PtyStatus
import dev.opencode.android.core.network.PtyStreamState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The terminal screen's own wiring: the list that opens a terminal, and the page that is cleared for a replay.
 *
 * **Both are things a screenshot cannot show.** A list row that does nothing looks identical to one that
 * opens a terminal, and the difference between a page that is reset before a replay and one that is not is
 * a screen drawn twice. The chrome the baselines photograph passes an empty callback for the row, so it was
 * never in a position to notice that the real screen's row had none either.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h2000dp-xhdpi")
class TerminalScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun pty(id: String, title: String) = PtyInfo(
        id = id,
        title = title,
        command = "/usr/bin/zsh",
        args = listOf("-l"),
        cwd = "/work/app",
        status = PtyStatus.Running,
        pid = 4242,
    )

    /** [state] is read inside the composition, so a test that holds it in Compose state can change it. */
    private fun screen(
        state: () -> TerminalUiState,
        channel: TerminalChannel = TerminalChannel(),
        onOpenTerminal: (String) -> Unit = {},
        onOutputConsumed: (Int) -> Unit = {},
    ) = compose.setContent {
        OpenCodeTheme {
            TerminalScreen(
                state = state(),
                channel = channel,
                onOpenTerminal = onOpenTerminal,
                onNewTerminal = {},
                onCreate = { _, _ -> },
                onClosePicker = {},
                onRunProjectStart = {},
                onRequestTicket = {},
                onKeys = {},
                onBridgeMessage = {},
                onOutputConsumed = onOutputConsumed,
                onReconnect = {},
                onRequestKill = {},
                onConfirmKill = {},
                onCancelKill = {},
                onDismissError = {},
            )
        }
    }

    @Test
    fun `tapping a listed terminal asks for that terminal to be opened`() {
        val opened = mutableListOf<String>()
        val state = TerminalUiState(
            directory = "/work/app",
            terminals = listOf(pty("pty_1", "first"), pty("pty_2", "second")),
        )
        screen(state = { state }, onOpenTerminal = { opened += it })

        compose.onNodeWithTag(TerminalTags.TERMINAL + "pty_2").performClick()

        // The row was given `onOpen` and never used it, so a terminal that was listed — every one, after the
        // activity was recreated — could not be opened by touching it.
        assertEquals(listOf("pty_2"), opened)
    }

    @Test
    fun `a new epoch empties the page before the output that follows it is written`() {
        val calls = mutableListOf<String>()
        val channel = object : TerminalChannel() {
            override fun evaluateInPage(method: String, json: String) {
                if (method != "state") calls += method
            }
        }
        var state by mutableStateOf(
            TerminalUiState(
                directory = "/work/app",
                open = pty("pty_1", "first"),
                stream = PtyStreamState.Live(0),
                epoch = 1,
            ),
        )
        val consumed = mutableListOf<Int>()
        screen(state = { state }, channel = channel, onOutputConsumed = { consumed += it })
        compose.waitForIdle()
        // The page loads and says `ready`; from here the channel writes straight through.
        compose.runOnIdle { channel.onPageReady() }
        calls.clear()

        // A new socket: the view model changes the epoch and hands over the replay in one update.
        state = state.copy(epoch = 2, pendingOutput = "the replay")
        compose.waitForIdle()

        assertEquals(listOf("reset", "write"), calls)
        assertEquals(listOf("the replay".length), consumed)
    }
}
