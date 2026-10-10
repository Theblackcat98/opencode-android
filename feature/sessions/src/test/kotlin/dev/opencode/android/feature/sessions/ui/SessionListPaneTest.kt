package dev.opencode.android.feature.sessions.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertLeftPositionInRootIsEqualTo
import androidx.compose.ui.test.assertTopPositionInRootIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import dev.opencode.android.core.designsystem.theme.OpenCodeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The session list's spoken labels, its selected row, its 48dp targets, and what a tap on a row reaches.
 *
 * The pane is stateless, so the test records the selections in a list. The app module decides what a tap does and which
 * sessions are listed, and it is covered by its own unit tests.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w840dp-h900dp-xhdpi")
class SessionListPaneTest {

    @get:Rule
    val compose = createComposeRule()

    private val selected = mutableListOf<String>()

    private val alpha = RecentSessionUi(id = "a", title = "Alpha")
    private val beta = RecentSessionUi(id = "b", title = "Beta")

    @Test
    fun theSelectedRowIsLabelledSelectedAndTheOthersAreNot() {
        show(listOf(alpha, beta), selectedId = "a")

        compose.onNodeWithContentDescription("Alpha, selected").assertIsSelected()
        compose.onNodeWithContentDescription("Beta").assertIsNotSelected()
    }

    @Test
    fun aTapOnAnotherRowSelectsThatSession() {
        show(listOf(alpha, beta), selectedId = "a")

        compose.onNodeWithContentDescription("Beta").performClick()

        assertEquals(listOf("b"), selected)
    }

    @Test
    fun everyRowIsAtLeast48dpHigh() {
        show(listOf(alpha, beta), selectedId = "a")

        compose.onNodeWithContentDescription("Alpha, selected").assertHeightIsAtLeast(48.dp)
        compose.onNodeWithContentDescription("Beta").assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun anEmptyListSaysSoAndListsNothing() {
        show(emptyList(), selectedId = null)

        compose.onNodeWithText("No sessions yet.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Alpha").assertDoesNotExist()
    }

    @Test
    fun noRowIsSelectedWhenTheSelectedSessionIsNotListed() {
        show(listOf(alpha, beta), selectedId = "child-of-alpha")

        compose.onNodeWithContentDescription("Alpha").assertIsNotSelected()
        compose.onNodeWithContentDescription("Beta").assertIsNotSelected()
    }

    // The heading's own padding (12dp down, 16dp across) is inside its semantics node, so the node starts at the inset
    // plus that padding: the inset is what keeps the heading out from under the bar.
    @Test
    fun theHeadingIsDrawnBelowTheTopInsetSoItIsNotUnderTheStatusBar() {
        show(listOf(alpha, beta), selectedId = "a", windowInsets = WindowInsets(0.dp, 48.dp, 0.dp, 0.dp))

        compose.onNodeWithText("Sessions").assertTopPositionInRootIsEqualTo(60.dp)
    }

    @Test
    fun theHeadingIsDrawnPastTheStartInsetSoItIsNotUnderACutout() {
        show(listOf(alpha, beta), selectedId = "a", windowInsets = WindowInsets(40.dp, 0.dp, 0.dp, 0.dp))

        compose.onNodeWithText("Sessions").assertLeftPositionInRootIsEqualTo(56.dp)
    }

    private fun show(
        sessions: List<RecentSessionUi>,
        selectedId: String?,
        windowInsets: WindowInsets = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp),
    ) {
        compose.setContent {
            OpenCodeTheme {
                SessionListPane(
                    sessions = sessions,
                    selectedId = selectedId,
                    onSelect = { selected += it },
                    listState = rememberLazyListState(),
                    windowInsets = windowInsets,
                )
            }
        }
    }
}
