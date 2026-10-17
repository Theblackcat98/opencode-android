package dev.opencode.android.feature.sessions.ui

import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import dev.opencode.android.core.designsystem.theme.OpenCodeTheme
import dev.opencode.android.feature.sessions.R
import dev.opencode.android.feature.sessions.ui.timeline.TimelineList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The turn-end divider is a polite live region only when it is the newest turn and no turn is running
 * (issue #29, A-1).
 *
 * The live region is what makes TalkBack say "Turn failed" when a turn ends. It is not on the older
 * dividers, because every divider is composed again on load and on scroll, and a live region on each
 * would read the whole history aloud. The fixture has one older turn that finished and one newer turn
 * that failed, so the two cases are told apart by their text.
 *
 * A turn that finished normally draws no divider at all; only the newest one keeps an invisible node, so the
 * announcement survives the divider being taken off the screen.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h2000dp-xhdpi")
class TurnDividerLiveRegionTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theNewestDividerIsAPoliteLiveRegionWhenNoTurnIsRunning() {
        show(sessionRunning = false)

        assertEquals(LiveRegionMode.Polite, liveRegionOf(R.string.divider_turn_failed))
    }

    @Test
    fun anOlderSucceededTurnDrawsNothingToAnnounce() {
        show(sessionRunning = false)

        compose.onNode(hasContentDescription(string(R.string.divider_turn_succeeded))).assertDoesNotExist()
    }

    @Test
    fun noDividerIsALiveRegionWhileATurnIsRunning() {
        show(sessionRunning = true)

        assertNull(liveRegionOf(R.string.divider_turn_failed))
        compose.onNode(hasContentDescription(string(R.string.divider_turn_succeeded))).assertDoesNotExist()
    }

    @Test
    fun theNewestSucceededTurnIsStillAnnouncedWithoutDrawingADivider() {
        show(sessionRunning = false, newest = "succeeded")

        compose.onNodeWithText(string(R.string.divider_turn_succeeded)).assertDoesNotExist()
        val node = compose.onNode(hasContentDescription(string(R.string.divider_turn_succeeded))).fetchSemanticsNode()
        assertEquals(LiveRegionMode.Polite, node.config.getOrNull(SemanticsProperties.LiveRegion))
    }

    private fun string(id: Int): String = RuntimeEnvironment.getApplication().getString(id)

    private fun show(sessionRunning: Boolean, newest: String = "failed") {
        val messages = listOf(
            TimelineFixtures.userMessage(),
            TimelineFixtures.idle(if (newest == "failed") "succeeded" else "failed"),
            TimelineFixtures.idle(newest),
        )
        compose.setContent {
            OpenCodeTheme(darkTheme = false, dynamicColor = false) {
                TimelineList(
                    messages = messages,
                    loadingOlder = false,
                    hasMore = false,
                    onLoadOlder = {},
                    sessionRunning = sessionRunning,
                )
            }
        }
    }

    private fun liveRegionOf(textRes: Int): LiveRegionMode? {
        val text = RuntimeEnvironment.getApplication().getString(textRes)
        return compose.onNodeWithText(text).fetchSemanticsNode().config.getOrNull(SemanticsProperties.LiveRegion)
    }
}
