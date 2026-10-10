package dev.opencode.android.feature.sessions.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.opencode.android.core.designsystem.theme.OpenCodeTheme
import dev.opencode.android.core.model.PromptFileAttachment
import dev.opencode.android.core.model.PromptFileSource
import dev.opencode.android.feature.sessions.R
import dev.opencode.android.feature.sessions.ui.timeline.TimelineMessageItem
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A tap on an attachment chip that has no preview says why, under the chip (timeline attachments, issue #6).
 *
 * A file that is not an image has no thumbnail, and neither does an image this device cannot decode, so the
 * chip used to do nothing when tapped and say nothing. The attachment here is a text file, which is the first
 * case. Image chips are not covered here: a tap on one opens the full-size picture, and that is unchanged.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h2000dp-xhdpi")
class AttachmentChipTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun aTapOnAChipWithNoPreviewSaysWhyUnderIt() {
        show()
        assertEquals(0, noteCount())

        compose.onNodeWithText(FILE_NAME).performClick()

        assertEquals(1, noteCount())
    }

    @Test
    fun aSecondTapTakesTheExplanationBackAway() {
        show()

        compose.onNodeWithText(FILE_NAME).performClick()
        compose.onNodeWithText(FILE_NAME).performClick()

        assertEquals(0, noteCount())
    }

    private fun show() {
        val message = TimelineFixtures.userMessage().copy(
            files = listOf(
                PromptFileAttachment(
                    data = "aGk=",
                    mime = "text/plain",
                    source = PromptFileSource.Inline,
                    name = FILE_NAME,
                ),
            ),
        )
        compose.setContent {
            OpenCodeTheme(darkTheme = false, dynamicColor = false) {
                TimelineMessageItem(message)
            }
        }
    }

    /** How many nodes carry the explanation: one when it is shown, none when it is not. */
    private fun noteCount(): Int {
        val note = RuntimeEnvironment.getApplication().getString(R.string.timeline_attachment_no_preview)
        return compose.onAllNodesWithText(note).fetchSemanticsNodes().size
    }

    private companion object {
        const val FILE_NAME = "notes.txt"
    }
}
