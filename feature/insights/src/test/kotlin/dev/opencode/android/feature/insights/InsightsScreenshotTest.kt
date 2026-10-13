package dev.opencode.android.feature.insights

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.captureRoboImage
import dev.opencode.android.core.data.capability.RouteAvailability
import dev.opencode.android.core.designsystem.theme.OpenCodeTheme
import dev.opencode.android.core.model.ModelRef
import dev.opencode.android.core.model.Project
import dev.opencode.android.core.model.SessionStats
import dev.opencode.android.core.model.SessionStatsTools
import dev.opencode.android.core.model.TokenUsage
import dev.opencode.android.core.model.ToolTotals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screenshot test for the usage dashboard (screen-flow node `usage`).
 *
 * [InsightsScreen] is stateless, so it renders here with fake stats. All values are
 * placeholders — no real usage data, no real project names.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h2000dp-xhdpi")
class InsightsScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun usageDashboard() = capture("docs-screen-usage") {
        InsightsScreen(
            state = InsightsUiState(
                range = StatsRange(30),
                projects = listOf(
                    Project(
                        id = "prj_1",
                        canonical = "/work/app",
                        name = "My App",
                        time = Project.Time(1_700_000_000_000, 1_700_000_000_000, 1_700_000_000_000),
                        sandboxes = emptyList(),
                    ),
                ),
                stats = fakeStats(),
                availability = RouteAvailability.Present,
            ),
            onRangeChange = {},
            onProjectChange = {},
            onToggleToolDetail = {},
            onDismissError = {},
        )
    }

    @Test
    fun usageDashboardDark() = capture("docs-screen-usage-dark", dark = true) {
        InsightsScreen(
            state = InsightsUiState(
                range = StatsRange(30),
                stats = fakeStats(),
                availability = RouteAvailability.Present,
            ),
            onRangeChange = {},
            onProjectChange = {},
            onToggleToolDetail = {},
            onDismissError = {},
        )
    }

    private fun capture(
        name: String,
        dark: Boolean = false,
        fontScale: Float = 1f,
        content: @Composable () -> Unit,
    ) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
            ) {
                OpenCodeTheme(darkTheme = dark) {
                    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        content()
                    }
                }
            }
        }
        compose.onRoot().captureRoboImage("$ROBO_PATH/$name.png")
    }

    private companion object {
        const val ROBO_PATH = "src/test/screenshots"

        fun fakeStats() = SessionStats(
            range = SessionStats.Range(1_700_000_000_000, 1_702_592_000_000),
            sessions = 42,
            subagents = 8,
            prompts = 312,
            steps = 1_204,
            tokens = TokenUsage(
                input = 1_250_000,
                output = 380_000,
                reasoning = 95_000,
                cache = TokenUsage.Cache(read = 800_000, write = 120_000),
            ),
            cost = 4.27,
            tools = SessionStatsTools.Summary(
                ToolTotals(calls = 486, succeeded = 471, failed = 12, unfinished = 3),
            ),
            activeDays = 21,
            streak = 6,
            activity = listOf(
                SessionStats.Activity("2026-09-14", 38),
                SessionStats.Activity("2026-09-15", 52),
                SessionStats.Activity("2026-09-16", 12),
                SessionStats.Activity("2026-09-17", 64),
                SessionStats.Activity("2026-09-18", 47),
            ),
            models = listOf(
                SessionStats.ModelUsage(
                    model = ModelRef("placeholder-provider", "placeholder-model"),
                    steps = 800,
                    tokens = TokenUsage(900_000, 280_000, 70_000, TokenUsage.Cache(600_000, 90_000)),
                    cost = 3.10,
                ),
                SessionStats.ModelUsage(
                    model = ModelRef("placeholder-provider", "other-model"),
                    steps = 404,
                    tokens = TokenUsage(350_000, 100_000, 25_000, TokenUsage.Cache(200_000, 30_000)),
                    cost = 1.17,
                ),
            ),
        )
    }
}
