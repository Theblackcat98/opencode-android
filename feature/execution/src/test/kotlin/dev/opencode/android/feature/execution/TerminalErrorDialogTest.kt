package dev.opencode.android.feature.execution

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The terminal's error dialog says something a person can read.
 *
 * **The bug this file exists for.** The failed-open dialog used to print [TerminalUiState.error]'s string
 * as it was, and the view model wrote the codes `unknown-terminal` and `socket-unavailable` into it, so
 * the user read "The terminal could not be opened — unknown-terminal". The two reasons this client has of
 * its own are now [TerminalError] objects that the dialog turns into `strings.xml` text, and what the
 * server said is still shown as the server said it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h2000dp-xhdpi")
class TerminalErrorDialogTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun show(error: TerminalError) {
        compose.setContent { TerminalErrorDialog(error = error, onDismiss = {}) }
    }

    @Test
    fun `a terminal that is gone is explained in words`() {
        show(TerminalError.Gone)

        compose.onNodeWithText(context.getString(R.string.terminal_failed)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.terminal_error_gone)).assertIsDisplayed()
    }

    @Test
    fun `a missing connection is explained in words`() {
        show(TerminalError.NoConnection)

        compose.onNodeWithText(context.getString(R.string.terminal_error_no_connection)).assertIsDisplayed()
    }

    @Test
    fun `the server's own words are shown as they came`() {
        show(TerminalError.Said("The server could not start /usr/bin/zsh"))

        compose.onNodeWithText("The server could not start /usr/bin/zsh").assertIsDisplayed()
    }
}
