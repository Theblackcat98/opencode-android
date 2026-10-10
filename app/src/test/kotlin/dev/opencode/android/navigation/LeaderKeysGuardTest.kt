package dev.opencode.android.navigation

import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The leader keys never run while a text field, or a native view, has focus. Real key events go through Compose, into
 * the same [leaderKeys] modifier the app uses.
 *
 * The leader reads a key before the focused field, so the field's own handling cannot be what stops it. That is the
 * case that matters on a device, where the keyboard's input method can insert a letter and the key still reaches the
 * root. The second test is the control: it removes the field and presses the same keys, and Usage must run. That shows
 * the keys do reach the leader, so the silence in the first test is the guard's doing.
 *
 * The window is given focus first. Robolectric starts the activity without it, and Compose ignores a focus request
 * until the window has focus, which is also why the app's own launch-time request for the root did not take.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h2000dp-xhdpi")
class LeaderKeysGuardTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val ran = mutableListOf<String>()
    private val leader = LeaderKeyMachine()
    private val offered = paletteCommands(hasActiveServer = true)
    private val columnFocus = FocusRequester()
    private var hideField: () -> Unit = {}
    private var nativeView: View? = null

    /** A composer-like screen: a button, a controlled OutlinedTextField and a native view, all read through [leaderKeys]. */
    private fun setScreen() {
        compose.setContent {
            var text by remember { mutableStateOf(TextFieldValue("")) }
            var showField by remember { mutableStateOf(true) }
            hideField = { showField = false }
            Column(
                modifier = Modifier
                    .testTag("column")
                    .focusRequester(columnFocus)
                    .focusable()
                    .leaderKeys(leader, offered) { ran += it.id },
            ) {
                Button(onClick = {}, modifier = Modifier.testTag("button")) { Text("button") }
                if (showField) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        maxLines = 6,
                        modifier = Modifier.testTag("composer"),
                    )
                }
                AndroidView(
                    factory = { context ->
                        View(context).apply {
                            isFocusable = true
                            isFocusableInTouchMode = true
                            nativeView = this
                        }
                    },
                    modifier = Modifier.testTag("native").size(48.dp),
                )
            }
        }
        compose.runOnIdle { compose.activity.onWindowFocusChanged(true) }
        compose.waitForIdle()
    }

    private fun pressCtrlXThenU() {
        compose.onRoot().performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.X) } }
        compose.onRoot().performKeyInput { pressKey(Key.U) }
        compose.waitForIdle()
    }

    @Test
    fun `a focused composer-like field keeps Ctrl+X and U from running a command`() {
        setScreen()
        compose.onNodeWithTag("composer").performClick()
        compose.waitForIdle()
        assertTrue(
            "the field must have focus for the test to mean anything",
            compose.onNodeWithTag("composer").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Focused) == true,
        )

        pressCtrlXThenU()

        assertEquals(emptyList<String>(), ran)
    }

    @Test
    fun `with the field gone the same keys run Usage, so the keys do reach the leader`() {
        setScreen()
        compose.onNodeWithTag("composer").performClick()
        compose.waitForIdle()
        compose.runOnIdle { hideField() }
        compose.waitForIdle()
        // With the field gone nothing inside the column is focused, so the column takes focus to receive the keys.
        compose.runOnIdle { columnFocus.requestFocus() }
        compose.waitForIdle()
        assertTrue(
            "the column must have focus for the test to mean anything",
            compose.onNodeWithTag("column").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Focused) == true,
        )

        pressCtrlXThenU()

        assertEquals(listOf(PaletteCommandIds.USAGE), ran)
    }

    @Test
    fun `a native view with focus keeps the keys too, so the terminal keeps Ctrl+X`() {
        setScreen()
        compose.runOnIdle { nativeView!!.requestFocus() }
        compose.waitForIdle()
        val native = nativeView!!
        compose.runOnIdle { assertSame("the native view must have focus", native, native.rootView.findFocus()) }

        pressCtrlXThenU()

        assertEquals(emptyList<String>(), ran)
    }
}
