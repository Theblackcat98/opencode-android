package dev.opencode.android.feature.execution

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
import dev.opencode.android.core.designsystem.theme.OpenCodeTheme
import dev.opencode.android.core.model.Project
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screenshot test for the project settings sheet (screen-flow node `project_settings`).
 *
 * The sheet is stateless ([ProjectSettingsSheet] takes [ProjectSettingsUiState] plus lambdas),
 * so it renders here with a fake project. All values are placeholders — no real credentials.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h2000dp-xhdpi")
class ProjectSettingsScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun projectSettingsSheet() = capture("docs-screen-project_settings") {
        ProjectSettingsSheet(
            state = ProjectSettingsUiState(
                project = Project(
                    id = "prj_1",
                    canonical = "/work/app",
                    name = "My App",
                    icon = Project.Icon(
                        color = "#4a90d9",
                        override = "🚀",
                    ),
                    commands = Project.Commands(start = "npm run dev"),
                    time = Project.Time(
                        created = 1_700_000_000_000,
                        updated = 1_700_000_000_000,
                        active = 1_700_000_000_000,
                    ),
                    sandboxes = emptyList(),
                ),
                name = "My App",
                color = "#4a90d9",
                emoji = "🚀",
                iconUrl = "",
                startCommand = "npm run dev",
                canonical = "/work/app",
            ),
            onNameChange = {},
            onColorChange = {},
            onEmojiChange = {},
            onIconUrlChange = {},
            onStartCommandChange = {},
            onCanonicalChange = {},
            onSave = {},
            onDismiss = {},
        )
    }

    @Test
    fun projectSettingsSheetDark() = capture("docs-screen-project_settings-dark", dark = true) {
        ProjectSettingsSheet(
            state = ProjectSettingsUiState(
                project = Project(
                    id = "prj_1",
                    canonical = "/work/app",
                    name = "My App",
                    time = Project.Time(
                        created = 1_700_000_000_000,
                        updated = 1_700_000_000_000,
                        active = 1_700_000_000_000,
                    ),
                    sandboxes = emptyList(),
                ),
                name = "My App (renamed)",
                canonical = "/work/app",
            ),
            onNameChange = {},
            onColorChange = {},
            onEmojiChange = {},
            onIconUrlChange = {},
            onStartCommandChange = {},
            onCanonicalChange = {},
            onSave = {},
            onDismiss = {},
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
    }
}
