package dev.opencode.android.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.opencode.android.core.designsystem.markdown.CodeBlock
import dev.opencode.android.core.designsystem.theme.OpenCodeTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The shared components every overhauled screen is built from, in both themes. A change here moves
 * pixels on many screens at once, so it gets its own baseline before it shows up in theirs.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h900dp-xhdpi")
class ComponentScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun componentsLight() = capture("components-light", dark = false) { Gallery() }

    @Test
    fun componentsDark() = capture("components-dark", dark = true) { Gallery() }

    @Test
    fun anAccordionTogglesAndReportsItsState() {
        val expanded = mutableStateOf(false)
        compose.setContent {
            OpenCodeTheme(darkTheme = false) {
                OpenCodeAccordionRow(title = "bash ls", expanded = expanded.value, onToggle = { expanded.value = it }) {
                    Text("output")
                }
            }
        }
        compose.onNodeWithText("bash ls").assertIsOff().performClick()
        compose.onNodeWithText("bash ls").assertIsOn()
        compose.onNodeWithText("output").assertExists()
    }

    @Composable
    private fun Gallery() {
        OpenCodeTopAppBar(
            title = "Refactor the session screen",
            onBack = {},
            subtitle = { Text("build · claude-sonnet") },
            progress = 0.42f,
            actions = { IconButton(onClick = {}) { androidx.compose.material3.Icon(Icons.Filled.Edit, null) } },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OpenCodeStatusPill(StatusKind.Running, "running")
            OpenCodeStatusPill(StatusKind.Retrying, "retry 2")
            OpenCodeStatusPill(StatusKind.Success, "done")
            OpenCodeStatusPill(StatusKind.Failure, "failed")
            OpenCodeStatusPill(StatusKind.Idle, "idle")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OpenCodeAvatar("opencode-android")
            OpenCodeAvatar("clipway")
            OpenCodeAvatar("api-bee-proj")
            OpenCodeChip("build", onClick = {})
            OpenCodeChip("Steer", selected = true, onClick = {})
        }
        OpenCodeAccordionRow(
            title = "ls -la src",
            meta = "0.4s · exit 0",
            titleMono = true,
            icon = Icons.Filled.Terminal,
            expanded = true,
            onToggle = {},
        ) { Text("total 0", style = MaterialTheme.typography.bodySmall) }
        OpenCodeAccordionRow(title = "Ran 3 tools", meta = "read, edit, bash · 1.4s", expanded = false, onToggle = {}) {}
        CodeBlock(code = "fun main() {\n    println(\"hi\")\n}", language = "kotlin")
        OpenCodeNavigationBar(
            entries = listOf(
                NavigationBarEntry("home", "Home", Icons.Filled.Home),
                NavigationBarEntry("chat", "Chat", Icons.Filled.Chat, badge = 2),
                NavigationBarEntry("settings", "Settings", Icons.Filled.Settings),
            ),
            selected = "home",
            onSelect = {},
        )
    }

    private fun capture(name: String, dark: Boolean, content: @Composable () -> Unit) {
        RuntimeEnvironment.setFontScale(1f)
        compose.setContent {
            OpenCodeTheme(darkTheme = dark) {
                Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) { content() }
                }
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }
}
