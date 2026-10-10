package dev.opencode.android.feature.sessions.ui

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
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
 * The tab strip's spoken labels, its selected state, its 48dp targets, and which callback a tap on each target reaches.
 *
 * The strip is stateless, so the test holds the two callbacks' calls in lists and checks them. The app module, which
 * decides what a tap does, is covered by its own unit tests.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h900dp-xhdpi")
class SessionTabStripTest {

    @get:Rule
    val compose = createComposeRule()

    private val selected = mutableListOf<String>()
    private val closed = mutableListOf<String>()

    private val alpha = SessionTabUi(id = "a", title = "Alpha", selected = true)
    private val beta = SessionTabUi(id = "b", title = "Beta", selected = false)

    @Test
    fun aSingleTabDrawsNoStrip() {
        show(listOf(alpha))

        compose.onNodeWithContentDescription("Close tab Alpha").assertDoesNotExist()
    }

    @Test
    fun eachTabIsLabelledWithItsPlaceAndItsState() {
        show(listOf(alpha, beta))

        compose.onNodeWithContentDescription("Alpha, tab 1 of 2, selected").assertIsSelected()
        compose.onNodeWithContentDescription("Beta, tab 2 of 2").assertIsNotSelected()
    }

    @Test
    fun aTapOnATitleSelectsThatSessionAndClosesNothing() {
        show(listOf(alpha, beta))

        compose.onNodeWithContentDescription("Beta, tab 2 of 2").performClick()

        assertEquals(listOf("b"), selected)
        assertEquals(emptyList<String>(), closed)
    }

    @Test
    fun aTapOnACloseButtonClosesThatSessionAndSelectsNothing() {
        show(listOf(alpha, beta))

        compose.onNodeWithContentDescription("Close tab Beta").performClick()

        assertEquals(listOf("b"), closed)
        assertEquals(emptyList<String>(), selected)
    }

    @Test
    fun theTitleAndTheCloseButtonAreEachAtLeast48dpHigh() {
        show(listOf(alpha, beta))

        compose.onNodeWithContentDescription("Beta, tab 2 of 2").assertHeightIsAtLeast(48.dp)
        compose.onNodeWithContentDescription("Close tab Beta").assertHeightIsAtLeast(48.dp)
    }

    private fun show(tabs: List<SessionTabUi>) {
        compose.setContent {
            OpenCodeTheme {
                SessionTabStrip(
                    tabs = tabs,
                    onSelect = { selected += it },
                    onClose = { closed += it },
                )
            }
        }
    }
}
