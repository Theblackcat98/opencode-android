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
import dev.opencode.android.feature.sessions.ui.timeline.TimelineMessageItem
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screenshot tests for the timeline renderers (plan §5.3, "UI").
 *
 * Every block in features doc §5 is captured, in light and dark, and once at a large font scale,
 * because a transcript has to stay readable in both and a large-scale layout is where a fixed
 * height or a truncated label shows up. The images are the review artifact: a renderer change that
 * moves a card is visible in the diff.
 *
 * Dynamic colour is off so the baseline is stable across the machines CI runs on; the OpenCode
 * palette is what these screens use in the field when dynamic colour is unavailable.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h2000dp-xhdpi")
class TimelineScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun userMessage() = capture("timeline-user") {
        TimelineFixtures.sampleMessages().forEach { message ->
            TimelineMessageItem(message)
        }
    }

    @Test
    fun assistantMarkdown() = capture("timeline-assistant-markdown") {
        TimelineMessageItem(TimelineFixtures.markdownAnswer())
    }

    @Test
    fun reasoningCollapsed() = capture("timeline-reasoning-collapsed") {
        TimelineMessageItem(TimelineFixtures.reasoningAnswer())
    }

    @Test
    fun toolCards() = capture("timeline-tool-cards") {
        TimelineFixtures.toolCards().forEach { TimelineMessageItem(it) }
    }

    @Test
    fun compactionAndIdle() = capture("timeline-compaction-idle") {
        TimelineMessageItem(TimelineFixtures.compaction())
        TimelineMessageItem(TimelineFixtures.idle("succeeded"))
        TimelineMessageItem(TimelineFixtures.idle("failed"))
    }

    @Test
    fun switchMarkers() = capture("timeline-switch-markers") {
        TimelineMessageItem(TimelineFixtures.agentSwitch())
        TimelineMessageItem(TimelineFixtures.modelSwitch())
        TimelineMessageItem(TimelineFixtures.locationSwitch())
    }

    @Test
    fun errorsAndRetries() = capture("timeline-errors-retries") {
        TimelineMessageItem(TimelineFixtures.failedStep())
    }

    @Test
    fun unknownTypes() = capture("timeline-unknown") {
        TimelineMessageItem(TimelineFixtures.unknownMessage())
    }

    @Test
    fun darkTheme() = capture("timeline-dark", dark = true) {
        TimelineFixtures.sampleMessages().forEach { message -> TimelineMessageItem(message) }
    }

    @Test
    fun largeFontScale() = capture("timeline-large-font", fontScale = 1.5f) {
        TimelineFixtures.sampleMessages().forEach { message -> TimelineMessageItem(message) }
    }

    @Test
    fun sessionListRows() = capture("session-list-rows") {
        SessionListScreen(
            state = TimelineFixtures.sessionListState(),
            onSearchChange = {},
            onToggleRootsOnly = {},
            onProjectSelected = {},
            onDirectorySelected = {},
            onLoadMore = {},
            onSessionClick = {},
        )
    }

    @Test
    fun sessionHeader() = capture("session-header", dark = true) {
        SessionScreen(
            state = TimelineFixtures.sessionState(),
            onNavigateBack = {},
            onLoadOlder = {},
            onFollowChange = {},
        )
    }

    @Test
    fun home() = capture("home") {
        HomeScreen(
            projects = TimelineFixtures.projects(),
            rows = TimelineFixtures.sessionListState().rows,
            serverName = "Development server",
            loading = false,
            onProjectClick = {},
            onSessionClick = {},
            onAllSessionsClick = {},
        )
    }

    /** Renders [content] in the app theme and writes the image Roborazzi compares against. */
    private fun capture(
        name: String,
        dark: Boolean = false,
        fontScale: Float = 1f,
        content: @Composable () -> Unit,
    ) {
        // Dynamic type is set on the runtime, not through the composition, so a card laid out at
        // 1.5x is the same card the system would lay out for a user who asked for it.
        RuntimeEnvironment.setFontScale(fontScale)
        compose.setContent {
            OpenCodeTheme(darkTheme = dark, dynamicColor = false) {
                Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                    // A column, because a Box would stack every card in the same place and a
                    // screenshot of that says nothing about any of them.
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
