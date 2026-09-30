package dev.opencode.android.feature.sessions.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.opencode.android.core.designsystem.theme.OpenCodeTheme
import dev.opencode.android.feature.sessions.R
import dev.opencode.android.feature.sessions.ui.timeline.MAX_FAILURE_LINES
import dev.opencode.android.feature.sessions.ui.timeline.TimelineMessageItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A failed tool call has to say why it failed.
 *
 * The bug this pins: an `edit` whose arguments the server rejected drew a red card that said "Failed" and
 * showed the attempted diff, while the reason sat unread in `state.error.message`. The fixtures are built
 * from what that run recorded, and the screenshots are the review artifact; the other tests assert what a
 * picture cannot, that the text can be reached in full and is part of the accessibility description.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h420dp-xhdpi")
class ToolFailureScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun failedEdit() = capture("tool-failed-edit") {
        TimelineMessageItem(TimelineFixtures.failedEdit())
    }

    @Test
    fun failedShellAndUnknownToolInDarkTheme() = capture("tool-failed-shell-dark", dark = true) {
        TimelineMessageItem(TimelineFixtures.failedShell())
        TimelineMessageItem(TimelineFixtures.failedUnknownTool())
    }

    @Test
    fun aLongReasonFoldsAtTheCapAndExpandsInFull() {
        show { TimelineMessageItem(TimelineFixtures.failedEdit()) }
        val showAll = string(R.string.tool_failure_show_all)
        val showLess = string(R.string.tool_failure_show_less)

        compose.onNodeWithText(showAll, useUnmergedTree = true).assertExistsAndNo(showLess)
        assertEquals(MAX_FAILURE_LINES, reasonLines())

        compose.onNodeWithText(showAll, useUnmergedTree = true).performClick()
        compose.onNodeWithText(showLess, useUnmergedTree = true).assertExistsAndNo(showAll)
        assertTrue("expanded, the whole message is laid out", reasonLines() > MAX_FAILURE_LINES)

        compose.onNodeWithText(showLess, useUnmergedTree = true).performClick()
        compose.onNodeWithText(showAll, useUnmergedTree = true).assertExistsAndNo(showLess)
        assertEquals(MAX_FAILURE_LINES, reasonLines())
    }

    @Test
    fun aShortReasonIsNotOfferedAsExpandable() {
        show { TimelineMessageItem(TimelineFixtures.failedUnknownTool()) }

        compose.onNode(hasText("The custom tool refused the request"), useUnmergedTree = true).assertExists()
        compose.onNodeWithText(string(R.string.tool_failure_show_all), useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun theReasonIsPartOfWhatAScreenReaderIsTold() {
        show { TimelineMessageItem(TimelineFixtures.failedEdit()) }

        compose.onNode(hasContentDescription("Edit file, Failed", substring = true)).assertExists()
        compose.onNode(hasContentDescription("- path: Missing key", substring = true)).assertExists()
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertExistsAndNo(absent: String) {
        assertExists()
        compose.onNodeWithText(absent, useUnmergedTree = true).assertDoesNotExist()
    }

    /** How many lines the reason's text is laid out in, which is what the cap limits. */
    private fun reasonLines(): Int {
        val layouts = mutableListOf<TextLayoutResult>()
        val node = compose.onNode(hasText(TimelineFixtures.EDIT_REJECTED), useUnmergedTree = true).fetchSemanticsNode()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        return layouts.single().lineCount
    }

    private fun string(id: Int): String = RuntimeEnvironment.getApplication().getString(id)

    private fun show(content: @Composable () -> Unit) {
        compose.setContent {
            OpenCodeTheme(darkTheme = false, dynamicColor = false) {
                Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                    Column(modifier = Modifier.fillMaxSize().padding(8.dp)) { content() }
                }
            }
        }
    }

    /** Renders [content] in the app theme and writes the image Roborazzi compares against. */
    private fun capture(name: String, dark: Boolean = false, content: @Composable () -> Unit) {
        RuntimeEnvironment.setFontScale(1f)
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
