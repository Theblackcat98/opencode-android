package dev.opencode.android.feature.execution

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The shell command box and the worktree form keep what is typed or pasted while the view model's echo of
 * it is still on its way.
 *
 * The state these fields show reaches the composition a frame after it was written, so a field that
 * displays it is rewound to an older text than the one it just reported: a command pasted or dictated at
 * speed runs with characters missing or swapped. [LateEcho] is a state that trails on purpose, by a number
 * of edits the test picks.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h2000dp-xhdpi")
class ExecutionFieldEchoTest {

    @get:Rule
    val compose = createComposeRule()

    private class Field(@StringRes val label: Int, val text: String) {
        val echo = LateEcho()
    }

    private val command = Field(R.string.shells_command, "git log --oneline --since=yesterday | head -n 20")
    private val from = Field(R.string.worktrees_from, "origin/release/2026.09")
    private val branch = Field(R.string.worktrees_branch, "feature/rename-the-worktree")
    private val name = Field(R.string.worktrees_name, "rename-the-worktree")

    private fun matcher(field: Field): SemanticsMatcher {
        val label = ApplicationProvider.getApplicationContext<Context>().getString(field.label)
        return hasSetTextAction() and hasText(label)
    }

    private fun showShells() {
        compose.setContent {
            ShellsScreen(
                state = ShellsUiState(directory = "/work/app", draft = command.echo.text),
                onDraftChange = command.echo::report,
                onRun = {},
                onOpen = {},
                onClose = {},
                onRequestKill = {},
                onConfirmKill = {},
                onCancelKill = {},
                onDismissError = {},
            )
        }
    }

    private fun showWorktrees() {
        compose.setContent {
            WorktreesScreen(
                state = WorktreesUiState(
                    draftFrom = from.echo.text,
                    draftBranch = branch.echo.text,
                    draftName = name.echo.text,
                ),
                onFromChange = from.echo::report,
                onBranchChange = branch.echo::report,
                onNameChange = name.echo::report,
                onCreate = {},
                onRefresh = {},
                onRequestRemove = {},
                onRemove = {},
                onForceRemove = {},
                onCancelRemove = {},
                onMoveSession = null,
            )
        }
    }

    private fun onScreen(field: Field): String? = compose.onNode(matcher(field))
        .fetchSemanticsNode()
        .config
        .getOrNull(SemanticsProperties.EditableText)
        ?.text

    /** Runs [enter] on each of [fields] in turn and fails once, naming every field that ended up wrong. */
    private fun enterEach(vararg fields: Field, enter: (Field) -> Unit) {
        val problems = fields.mapNotNull { field ->
            enter(field)
            val shown = onScreen(field)
            val reported = field.echo.lastReported
            if (shown == field.text && reported == field.text) {
                null
            } else {
                "\"${field.text}\": the field shows \"$shown\" and last reported \"$reported\""
            }
        }
        assertTrue(problems.joinToString(separator = "\n"), problems.isEmpty())
    }

    /** Types a character at a time, letting the echo trail the typing by [lag] characters. */
    private fun typeWithLag(field: Field, lag: Int) {
        field.text.forEach { char ->
            compose.onNode(matcher(field)).performTextInput(char.toString())
            while (field.echo.pending > lag) compose.runOnIdle { field.echo.publishOldest() }
        }
        compose.runOnIdle { while (field.echo.pending > 0) field.echo.publishOldest() }
        compose.waitForIdle()
    }

    private fun pasteThenEcho(field: Field) {
        compose.onNode(matcher(field)).performTextInput(field.text)
        compose.runOnIdle { field.echo.publishOldest() }
        compose.waitForIdle()
    }

    @Test
    fun `the shell command box keeps a command typed ahead of the echo`() {
        showShells()
        enterEach(command) { typeWithLag(it, lag = 1) }
    }

    @Test
    fun `the shell command box keeps a command when the echo trails by many edits`() {
        showShells()
        enterEach(command) { typeWithLag(it, lag = 7) }
    }

    @Test
    fun `the shell command box keeps a command pasted in one edit`() {
        showShells()
        enterEach(command) { pasteThenEcho(it) }
    }

    @Test
    fun `the worktree form keeps its three fields typed ahead of the echo`() {
        showWorktrees()
        enterEach(from, branch, name) { typeWithLag(it, lag = 2) }
    }

    @Test
    fun `the worktree form keeps its three fields pasted in one edit`() {
        showWorktrees()
        enterEach(from, branch, name) { pasteThenEcho(it) }
    }
}
