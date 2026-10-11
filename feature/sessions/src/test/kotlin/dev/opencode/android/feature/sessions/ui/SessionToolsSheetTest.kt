package dev.opencode.android.feature.sessions.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Difference
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.github.takahirom.roborazzi.captureRoboImage
import dev.opencode.android.core.designsystem.theme.OpenCodeTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The session's tools sheet: the workspace tiles first, then the session's own rows, the project's, the advanced
 * ones, and Delete last and apart. It replaced a nineteen-row list of text buttons with no grouping.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h1400dp-xhdpi")
class SessionToolsSheetTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theSheetIsGroupedWithDeleteLast() {
        compose.setContent {
            OpenCodeTheme(darkTheme = true) {
                SessionActionsSheet(
                    title = "Refactor the timeline reducer",
                    contextLabel = "Context 24% of 200k tokens",
                    childCount = 2,
                    onRename = {},
                    onDelete = {},
                    onCopyMessage = {},
                    onCopyTranscript = {},
                    onDismiss = {},
                    onOpenAttention = {},
                    onOpenHistory = {},
                    onWaitUntilIdle = {},
                    followDesktopOn = true,
                    onToggleFollowDesktop = {},
                    onAddSyntheticNote = {},
                    onOpenEventHistory = {},
                    onOpenExperimental = {},
                    workspaceSlot = {
                        SessionToolTile("Review", Icons.Outlined.Difference, {})
                        SessionToolTile("Files", Icons.Outlined.FolderOpen, {})
                        SessionToolTile("Shells", Icons.Outlined.Terminal, {}, enabled = false, disabledReason = "No directory")
                    },
                    projectSlot = { SessionMenuRow("Project settings", {}) },
                )
            }
        }
        compose.onNodeWithText("Delete session").assertExists()
        compose.onNode(isDialog()).captureRoboImage("src/test/screenshots/session-tools-sheet.png")
    }
}
