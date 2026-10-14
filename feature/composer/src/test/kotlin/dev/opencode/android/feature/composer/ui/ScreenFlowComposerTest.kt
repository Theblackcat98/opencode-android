package dev.opencode.android.feature.composer.ui

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
import dev.opencode.android.core.model.AgentInfo
import dev.opencode.android.core.model.ModelInfo
import dev.opencode.android.core.model.ModelRef
import dev.opencode.android.core.model.Project
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screenshot for the `docs/app_screen_flow.html` `new_session` node.
 *
 * The sheet wraps [NewSessionContent] in a `ModalBottomSheet`, which a capture does not see, so the
 * baseline renders the content directly — the form the user actually reads.
 *
 * Every value is a placeholder; no real models, agents or directories.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class ScreenFlowComposerTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun docsScreenNewSession() = capture("docs-screen-new_session") {
        val model = ModelRef(id = "gpt-5", providerID = "openai")
        NewSessionContent(
            state = NewSessionUiState(
                title = "Fix the login bug",
                location = LocationChoice.ProjectChoice(
                    id = "proj-1",
                    name = "opencode-android",
                    directory = "/home/user/opencode-android",
                ),
                agent = "build",
                model = model,
                models = listOf(
                    ModelInfo(
                        id = "gpt-5",
                        modelID = "gpt-5",
                        providerID = "openai",
                        name = "GPT-5",
                        limit = ModelInfo.Limit(context = 200_000, output = 8_192),
                    ),
                    ModelInfo(
                        id = "claude-opus-4",
                        modelID = "claude-opus-4-20250514",
                        providerID = "anthropic",
                        name = "Claude Opus 4",
                        limit = ModelInfo.Limit(context = 200_000, output = 8_192),
                    ),
                ),
                agents = listOf(
                    AgentInfo(id = "build", name = "Build", mode = AgentInfo.AgentMode.PRIMARY),
                    AgentInfo(id = "plan", name = "Plan", mode = AgentInfo.AgentMode.PRIMARY),
                ),
                projects = listOf(
                    Project(
                        id = "proj-1",
                        canonical = "/home/user/opencode-android",
                        name = "opencode-android",
                        time = Project.Time(
                            created = 1_729_000_000_000L,
                            updated = 1_729_000_060_000L,
                            active = 1_729_000_060_000L,
                        ),
                        sandboxes = emptyList(),
                    ),
                ),
                recentDirectories = listOf("/home/user/opencode-android"),
            ),
            favorites = emptyList(),
            recents = listOf(model),
            modelSearch = "",
            onTitleChange = {},
            onSelectProject = {},
            onSelectDirectory = {},
            onOpenBrowser = {},
            onPathDraftChange = {},
            onSelectAgent = {},
            onSelectModel = {},
            onCreate = {},
        )
    }

    private fun capture(
        name: String,
        content: @Composable () -> Unit,
    ) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, 1f),
            ) {
                OpenCodeTheme(darkTheme = false) {
                    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        content()
                    }
                }
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }
}
