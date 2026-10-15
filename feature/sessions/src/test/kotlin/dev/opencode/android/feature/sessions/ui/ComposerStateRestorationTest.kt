package dev.opencode.android.feature.sessions.ui

import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.text.TextRange
import dev.opencode.android.core.designsystem.theme.OpenCodeTheme
import dev.opencode.android.feature.composer.ui.ComposerBar
import dev.opencode.android.feature.composer.ui.ComposerUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The input and caret must survive rotation and a resize that recreates the activity (issue #26). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h1000dp-xhdpi")
class ComposerStateRestorationTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theDraftAndCaretSurviveSavedStateRestoration() {
        var state by mutableStateOf(
            DrivingFixtures.composerState().copy(sessionID = "ses_restore", text = "keep this draft"),
        )
        val restoration = StateRestorationTester(compose)

        restoration.setContent {
            OpenCodeTheme(darkTheme = false, dynamicColor = false) {
                Surface {
                    ComposerBar(
                        state = state,
                        onTextChange = { text, _ -> state = state.copy(text = text) },
                        onSend = {},
                        onSendQueued = {},
                        onDeliveryChange = {},
                        onResumeChange = {},
                        onInterrupt = {},
                        onBackground = {},
                        onOpenInbox = {},
                        onOpenAgentPicker = {},
                        onOpenModelPicker = {},
                        onCycleAgent = {},
                        onCycleVariant = {},
                    )
                }
            }
        }

        compose.onNode(hasSetTextAction()).performTextInputSelection(TextRange(5))
        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()

        val semantics = compose.onNode(hasSetTextAction()).fetchSemanticsNode().config
        assertEquals("keep this draft", semantics[SemanticsProperties.EditableText].text)
        assertEquals(TextRange(5), semantics.getOrNull(SemanticsProperties.TextSelectionRange))
    }
}
