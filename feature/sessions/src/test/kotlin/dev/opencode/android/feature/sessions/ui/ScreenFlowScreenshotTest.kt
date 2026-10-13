package dev.opencode.android.feature.sessions.ui

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
import dev.opencode.android.core.data.server.SessionActivity
import dev.opencode.android.core.data.server.SessionRow
import dev.opencode.android.core.designsystem.theme.OpenCodeTheme
import dev.opencode.android.core.model.Location
import dev.opencode.android.core.model.ModelRef
import dev.opencode.android.core.model.Project
import dev.opencode.android.core.model.SessionInfo
import dev.opencode.android.core.model.SessionMetadata
import dev.opencode.android.core.model.SessionStatus
import dev.opencode.android.core.model.Time
import dev.opencode.android.core.model.TokenUsage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screenshot tests for the app screen flow (docs/app_screen_flow.html).
 *
 * Captures the sessions-phase screens with realistic fake data for the
 * `update-screen-flow-shots.mjs` tool. All values are placeholders.
 *
 * Screens that use `hiltViewModel()` directly (ServersScreen, AddServerScreen,
 * ServerStatusScreen, EditServerScreen) cannot render in isolation and are skipped.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h2000dp-xhdpi")
class ScreenFlowScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    // ------------------------------------------------------------------ home

    @Test
    fun homeScreen() = capture("docs-screen-home") {
        HomeScreen(
            state = HomeUiState(
                serverId = "server-1",
                groups = listOf(
                    ProjectGroup(
                        project = Project(id = "prj-1", canonical = "/work/app", name = "app"),
                        sessions = listOf(sessionRow("ses-1", "Fix the login bug"), sessionRow("ses-2", "Add dark mode")),
                        total = 2,
                        running = 1,
                    ),
                    ProjectGroup(
                        project = Project(id = "prj-2", canonical = "/work/docs", name = "docs"),
                        sessions = listOf(sessionRow("ses-3", "Update README")),
                        total = 1,
                        running = 0,
                    ),
                ),
                now = 1_700_000_000_000,
            ),
            serverName = "veritas",
            onSessionClick = {},
            onProjectSessionsClick = {},
        )
    }

    // ------------------------------------------------------------------ session list

    @Test
    fun sessionListScreen() = capture("docs-screen-session-list") {
        SessionListScreen(
            state = SessionListUiState(
                rows = listOf(
                    sessionRow("ses-1", "Fix the login bug"),
                    sessionRow("ses-2", "Add dark mode"),
                    sessionRow("ses-3", "Update README"),
                ),
                projects = listOf(
                    Project(id = "prj-1", canonical = "/work/app", name = "app"),
                    Project(id = "prj-2", canonical = "/work/docs", name = "docs"),
                ),
                now = 1_700_000_000_000,
            ),
            onSearchChange = {},
            onToggleRootsOnly = {},
            onProjectSelected = {},
            onDirectorySelected = {},
            onLoadMore = {},
            onSessionClick = {},
        )
    }

    // ------------------------------------------------------------------ session

    @Test
    fun sessionScreen() = capture("docs-screen-session") {
        SessionScreen(
            state = SessionUiState(
                title = "Fix the login bug",
                agent = "build",
                model = SessionModelUi(label = "placeholder-model", variant = null),
                directory = "/work/app",
                messages = emptyList(),
                now = 1_700_000_000_000,
            ),
            onNavigateBack = {},
            onLoadOlder = {},
            onFollowChange = {},
        )
    }

    // ------------------------------------------------------------------------------ helpers

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

    private fun sessionRow(id: String, title: String) = SessionRow(
        session = SessionInfo(
            id = id,
            projectID = "prj-1",
            agent = "build",
            model = ModelRef("placeholder-provider", "placeholder-model"),
            cost = 0.12,
            tokens = TokenUsage(input = 1000, output = 500, reasoning = 0, cacheRead = 0, cacheWrite = 0),
            time = Time(created = 1_700_000_000_000, updated = 1_700_000_000_000),
            title = title,
            location = Location.PublicRef(directory = "/work/app"),
            metadata = SessionMetadata(slug = "s", version = 1),
        ),
        activity = SessionActivity.Idle,
        status = SessionStatus.Idle,
        childCount = 0,
    )

    private companion object {
        const val ROBO_PATH = "src/test/screenshots"
    }
}
