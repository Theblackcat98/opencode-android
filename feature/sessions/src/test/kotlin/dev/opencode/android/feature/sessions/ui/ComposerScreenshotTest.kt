package dev.opencode.android.feature.sessions.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.opencode.android.core.designsystem.theme.OpenCodeTheme
import dev.opencode.android.feature.composer.ui.ComposerBar
import dev.opencode.android.feature.composer.ui.ComposerContextRow
import dev.opencode.android.feature.composer.ui.ComposerProblemRow
import dev.opencode.android.feature.composer.ui.ComposerUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screenshots of the Phase 5 composer states (plan §5.3, "UI"; §5.4, dynamic type).
 *
 * These are the states a user reads while deciding whether to press send: what the box will do, what
 * it will carry, and what is stopping it. They are the states most likely to break at a large font
 * scale, because each one adds a row of words to a bar that is already at the bottom of the screen —
 * which is exactly what the 1.5× baseline is for, and what caught the clipped "Reject" button in P3.
 *
 * Dynamic colour is off so the baselines are stable across the machines CI runs on.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h2000dp-xhdpi")
class ComposerScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun mentionList() = capture("composer-mentions") { composer(ComposerFixtures.mentionList()) }

    @Test
    fun commandList() = capture("composer-commands") { composer(ComposerFixtures.commandList()) }

    @Test
    fun shellMode() = capture("composer-shell", dark = true) { composer(ComposerFixtures.shellMode()) }

    @Test
    fun carryingContext() = capture("composer-context") { composer(ComposerFixtures.carryingContext()) }

    @Test
    fun imageNeedsConfirmation() = capture("composer-image-confirm") {
        composer(ComposerFixtures.imageNeedsConfirmation())
    }

    @Test
    fun attachmentBlocked() = capture("composer-attachment-blocked", dark = true) {
        composer(ComposerFixtures.attachmentBlocked())
    }

    @Test
    fun stashed() = capture("composer-stashed") { composer(ComposerFixtures.stashed()) }

    @Test
    fun sideQuestionAnswered() = capture("composer-side-question") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            composer(ComposerFixtures.sideQuestionAnswered())
            ComposerContextRow(ComposerFixtures.sideQuestionAnswered())
        }
    }

    @Test
    fun sideQuestionWaiting() = capture("composer-side-question-waiting", fontScale = 1.5f) {
        composer(ComposerFixtures.sideQuestionWaiting())
    }

    @Test
    fun composerLargeFontScale() = capture("composer-large-font", fontScale = 1.5f) {
        composer(ComposerFixtures.carryingContext())
    }

    /** The composer with every control the phase added, in the theme and at the screen width. */
    @Composable
    private fun composer(state: ComposerUiState) {
        ComposerBar(
            state = state,
            onTextChange = { _, _ -> },
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
            onSelectCompletion = {},
            onRemoveAttachment = {},
            onAttach = {},
            onOpenSkills = {},
            onOlderHistory = {},
            onNewerHistory = {},
            onStash = {},
            onOpenStash = {},
            onOpenEditor = {},
            onSendConfirmed = {},
        )
    }

    private fun capture(
        name: String,
        dark: Boolean = false,
        fontScale: Float = 1f,
        content: @Composable () -> Unit,
    ) {
        RuntimeEnvironment.setFontScale(fontScale)
        compose.setContent {
            OpenCodeTheme(darkTheme = dark, dynamicColor = false) {
                Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        content()
                    }
                }
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }
}
