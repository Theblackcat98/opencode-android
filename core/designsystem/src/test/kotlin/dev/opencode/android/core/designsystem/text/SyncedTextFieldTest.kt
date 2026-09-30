package dev.opencode.android.core.designsystem.text

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.input.PasswordVisualTransformation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * [SyncedTextField] keeps what is typed, pasted or autofilled while the state that is fed back to it is
 * still catching up.
 *
 * **The bug this exists for**, found on a device: a 32-character password typed with `adb shell input text`
 * came out with its first two characters swapped, because the field showed `state.password` and that state
 * trails the keystrokes, so the field was rewound to an older text than the one it had just reported.
 * [LateEcho] is a state that trails on purpose, by a number of edits the test picks. The field is checked
 * in each of the shapes the app uses: single line, masked, and several lines with its own text style.
 *
 * **[a plain field fed by the same echo loses characters] is the control**: the same typing, the same echo
 * and an ordinary `OutlinedTextField`. It is here so that a harness that stopped reproducing the bug would
 * fail this class instead of quietly passing every other test in it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h2000dp-xhdpi")
class SyncedTextFieldTest {

    @get:Rule
    val compose = createComposeRule()

    /** 32 distinct alphanumerics, so any reordering or loss shows. */
    private val credential = "AbCdEfGhIjKlMnOpQrStUvWxYz012345"
    private val sentence = "Rename the worktree before the setup script runs, then check the branch."

    private class Field(val tag: String, val masked: Boolean = false) {
        val echo = LateEcho()
    }

    private val plain = Field("plain")
    private val masked = Field("masked", masked = true)
    private val multiLine = Field("multi-line")
    private val control = Field("control")

    private fun showSynced() {
        compose.setContent {
            Column {
                SyncedTextField(
                    value = plain.echo.text,
                    onValueChange = plain.echo::report,
                    modifier = Modifier.testTag(plain.tag),
                    singleLine = true,
                )
                SyncedTextField(
                    value = masked.echo.text,
                    onValueChange = masked.echo::report,
                    modifier = Modifier.testTag(masked.tag),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                SyncedTextField(
                    value = multiLine.echo.text,
                    onValueChange = multiLine.echo::report,
                    modifier = Modifier.testTag(multiLine.tag),
                    minLines = 3,
                )
            }
        }
    }

    private fun onScreen(field: Field): String? = compose.onNodeWithTag(field.tag)
        .fetchSemanticsNode()
        .config
        .getOrNull(SemanticsProperties.EditableText)
        ?.text

    /** What is wrong with [field] now that [expected] was typed into it, or `null` when nothing is. */
    private fun mismatch(field: Field, expected: String): String? {
        val shown = onScreen(field)
        // A masked field shows one dot per character, so what it shows is checked by length.
        val shownExpected = if (field.masked) "•".repeat(expected.length) else expected
        val reported = field.echo.lastReported
        if (shown == shownExpected && reported == expected) return null
        return "${field.tag}: expected \"$expected\", the field shows \"$shown\" and last reported \"$reported\""
    }

    private fun enterEach(enter: (Field, String) -> Unit) {
        val problems = listOf(plain to credential, masked to credential, multiLine to sentence)
            .mapNotNull { (field, text) ->
                enter(field, text)
                mismatch(field, text)
            }
        assertTrue(problems.joinToString(separator = "\n"), problems.isEmpty())
    }

    /** Types [text] a character at a time, letting the echo trail the typing by [lag] characters. */
    private fun typeWithLag(field: Field, text: String, lag: Int) {
        text.forEach { char ->
            compose.onNodeWithTag(field.tag).performTextInput(char.toString())
            while (field.echo.pending > lag) compose.runOnIdle { field.echo.publishOldest() }
        }
        compose.runOnIdle { while (field.echo.pending > 0) field.echo.publishOldest() }
        compose.waitForIdle()
    }

    @Test
    fun `a field keeps text typed one edit ahead of its echo`() {
        showSynced()
        enterEach { field, text -> typeWithLag(field, text, lag = 1) }
    }

    @Test
    fun `a field keeps text typed several edits ahead of its echo`() {
        showSynced()
        enterEach { field, text -> typeWithLag(field, text, lag = 5) }
    }

    @Test
    fun `a field keeps text that is echoed only once it is all typed`() {
        showSynced()
        enterEach { field, text -> typeWithLag(field, text, lag = text.length) }
    }

    @Test
    fun `a field keeps text when the echo skips the edits in between`() {
        showSynced()
        enterEach { field, text ->
            text.forEachIndexed { index, char ->
                compose.onNodeWithTag(field.tag).performTextInput(char.toString())
                // A StateFlow conflates: every third keystroke the screen hears only the newest text.
                if (index % 3 == 2) compose.runOnIdle { field.echo.publishNewest() }
            }
            compose.runOnIdle { field.echo.publishNewest() }
            compose.waitForIdle()
        }
    }

    @Test
    fun `a field keeps text pasted or autofilled in one edit`() {
        showSynced()
        enterEach { field, text ->
            compose.onNodeWithTag(field.tag).performTextInput(text)
            compose.runOnIdle { field.echo.publishOldest() }
            compose.waitForIdle()
        }
    }

    @Test
    fun `a field adopts a replacement made over what was typed`() {
        showSynced()
        enterEach { field, text ->
            typeWithLag(field, "typed first", lag = 1)
            compose.onNodeWithTag(field.tag).performTextReplacement(text)
            compose.runOnIdle { field.echo.publishOldest() }
            compose.waitForIdle()
        }
    }

    @Test
    fun `a field adopts a text its view model sets and then one it clears`() {
        showSynced()
        enterEach { field, text ->
            typeWithLag(field, "typed first", lag = 1)
            compose.runOnIdle { field.echo.replaceFromOutside(text) }
            compose.waitForIdle()
            assertEquals(null, mismatch(field, text))
            // The box cleared after a send.
            compose.runOnIdle { field.echo.replaceFromOutside("") }
            compose.waitForIdle()
            assertEquals(null, mismatch(field, ""))
            // What is typed afterwards is not mistaken for an echo of what came before.
            typeWithLag(field, text, lag = 2)
        }
    }

    @Test
    fun `a plain field fed by the same echo loses characters`() {
        compose.setContent {
            OutlinedTextField(
                value = control.echo.text,
                onValueChange = control.echo::report,
                modifier = Modifier.testTag(control.tag),
                singleLine = true,
            )
        }
        typeWithLag(control, credential, lag = 1)
        // If this starts to pass, the harness no longer reproduces the bug and none of the tests above
        // are telling anything.
        assertNotEquals(credential, control.echo.lastReported)
    }
}
