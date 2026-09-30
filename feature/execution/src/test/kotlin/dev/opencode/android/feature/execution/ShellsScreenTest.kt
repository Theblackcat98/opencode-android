package dev.opencode.android.feature.execution

import android.content.Context
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.opencode.android.core.data.execution.ShellOutputState
import dev.opencode.android.core.designsystem.theme.OpenCodeTheme
import dev.opencode.android.core.model.ShellInfo
import dev.opencode.android.core.model.ShellStatus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The shell panel's own wiring: the row that opens a command, and what the output pane says about its text.
 *
 * **These are the things the baselines cannot show.** A row that does nothing when touched looks exactly
 * like one that opens its command — the screenshots pass an empty callback for it — and the panel's row was
 * given `onOpen` and never used it, so only the command that `run` opened automatically could ever be read.
 * Likewise "the server cut some of this output" is a sentence, and a baseline of a pane that lacks it and
 * one that has it differ by a line nobody would notice was missing.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h2000dp-xhdpi")
class ShellsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun info(id: String, command: String) = ShellInfo(
        id = id,
        status = ShellStatus.Running,
        command = command,
        cwd = "/work/app",
        shell = "/usr/bin/zsh",
        file = "/work/app/.out/$id.out",
        metadata = emptyMap(),
        time = ShellInfo.Time(started = 1),
    )

    private fun show(state: ShellsUiState, onOpen: (String) -> Unit = {}) = compose.setContent {
        OpenCodeTheme {
            ShellsScreen(
                state = state,
                onDraftChange = {},
                onRun = {},
                onOpen = onOpen,
                onClose = {},
                onRequestKill = {},
                onConfirmKill = {},
                onCancelKill = {},
                onDismissError = {},
            )
        }
    }

    private fun stateWith(output: ShellOutputState?) = ShellsUiState(
        directory = "/work/app",
        openID = "sh_1",
        rows = listOf(
            ShellRow(info("sh_1", "echo one"), output = output),
            ShellRow(info("sh_2", "cargo build")),
        ),
    )

    @Test
    fun `touching a row opens that command`() {
        val opened = mutableListOf<String>()
        show(stateWith(ShellOutputState()), onOpen = { opened += it })

        compose.onNodeWithTag(ExecutionTags.ROW + "sh_2").performClick()

        assertEquals(listOf("sh_2"), opened)
    }

    @Test
    fun `the row whose output is showing is the selected one`() {
        show(stateWith(ShellOutputState()))

        compose.onNodeWithTag(ExecutionTags.ROW + "sh_1").assertIsSelected()
    }

    @Test
    fun `the pane shows the text the state holds`() {
        show(stateWith(ShellOutputState(text = "one\ntwo\nthree\n", cursor = 14, size = 14)))

        compose.onNodeWithTag(ExecutionTags.OUTPUT).assertTextEquals("one\ntwo\nthree\n")
    }

    @Test
    fun `a pane with nothing yet says so rather than showing an empty box`() {
        show(stateWith(ShellOutputState()))

        compose.onNodeWithTag(ExecutionTags.OUTPUT).assertTextEquals(context.getString(R.string.shells_no_output))
    }

    @Test
    fun `truncated output is said in words`() {
        show(stateWith(ShellOutputState(text = "tail\n", cursor = 5, size = 5, truncated = true)))

        compose.onNodeWithText(context.getString(R.string.shells_output_truncated)).assertExists()
        // And it is not a reason to draw a progress bar: nothing is being fetched.
        compose.onNodeWithTag(ExecutionTags.PROGRESS).assertDoesNotExist()
    }

    @Test
    fun `output that is whole says nothing about truncation`() {
        show(stateWith(ShellOutputState(text = "one\n", cursor = 4, size = 4)))

        compose.onNodeWithText(context.getString(R.string.shells_output_truncated)).assertDoesNotExist()
        compose.onNodeWithTag(ExecutionTags.OUTPUT_ERROR).assertDoesNotExist()
    }

    @Test
    fun `a stream that stopped says why`() {
        show(stateWith(ShellOutputState(text = "one\n", cursor = 4, size = 4, error = "connection reset")))

        compose.onNodeWithTag(ExecutionTags.OUTPUT_ERROR)
            .assertTextEquals(context.getString(R.string.shells_output_failed, "connection reset"))
    }

    @Test
    fun `the bar shows only while the client is behind the server`() {
        show(stateWith(ShellOutputState(text = "abc", cursor = 3, size = 90)))

        compose.onNodeWithTag(ExecutionTags.PROGRESS).assertExists()
    }
}
