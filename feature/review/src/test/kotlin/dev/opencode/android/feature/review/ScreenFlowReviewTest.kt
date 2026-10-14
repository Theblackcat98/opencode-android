package dev.opencode.android.feature.review

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
import dev.opencode.android.core.data.review.FileTree
import dev.opencode.android.core.data.review.ParsedFile
import dev.opencode.android.core.data.review.ReviewNavigator
import dev.opencode.android.core.data.review.ReviewScope
import dev.opencode.android.core.data.review.ReviewedFiles
import dev.opencode.android.core.data.server.VcsState
import dev.opencode.android.core.designsystem.theme.OpenCodeTheme
import dev.opencode.android.core.model.FileDiffStatus
import dev.opencode.android.core.model.VcsBranch
import dev.opencode.android.core.model.VcsFileStatus
import dev.opencode.android.core.model.VcsInfo
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screen-flow screenshot for `docs/app_screen_flow.html`.
 *
 * Captures the review diff screen with the `docs-screen-review` naming the
 * `update-screen-flow-shots.mjs` tool expects. Fake data mirrors the existing
 * [ReviewScreenshotTest] fixtures.
 *
 * Dynamic colour is off so the baselines are stable across the machines CI runs on.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h900dp-xhdpi")
class ScreenFlowReviewTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun docsScreenReview() = capture("docs-screen-review") {
        ReviewScreen(
            state = reviewState().copy(scope = ReviewScope.Uncommitted),
            onSelectScope = {},
            onOpenFile = {},
            onNextFile = {},
            onPreviousFile = {},
            onNextHunk = {},
            onPreviousHunk = {},
            onToggleReviewed = {},
            onToggleWrap = {},
            onToggleSplit = {},
            onSelectLines = { _, _, _ -> },
            onOpenBasePicker = {},
            onSelectBase = {},
            onNavigateBack = {},
        )
    }

    // ------------------------------------------------------------------ fixtures

    private fun capture(name: String, content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, 1f),
            ) {
                OpenCodeTheme(darkTheme = false) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background,
                    ) { content() }
                }
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    private fun parsed(file: String, patch: String, additions: Int, deletions: Int): ParsedFile =
        dev.opencode.android.core.data.review.UnifiedDiff.parse(
            patch = patch,
            file = file,
            additions = additions,
            deletions = deletions,
            status = FileDiffStatus.Modified,
        )

    private fun reviewState(): ReviewUiState {
        val files = listOf(
            parsed(
                "src/main/kotlin/ReviewViewModel.kt",
                "@@ -10,6 +10,8 @@ class ReviewViewModel {\n-    fun load() {\n+    fun load(context: String) {\n+        // the scope decides the route\n         val files = api.load()\n",
                2,
                1,
            ),
            parsed(
                "src/main/kotlin/UnifiedDiff.kt",
                "@@ -1,2 +1,3 @@\n+/** A parsed unified patch. */\n object UnifiedDiff {\n",
                1,
                0,
            ),
        )
        return ReviewUiState(
            sessionID = "ses_1",
            directory = "/home/dev/project",
            scope = ReviewScope.All,
            scopes = ReviewScope.ALL,
            files = files,
            tree = FileTree.build(files.map { it.file }),
            position = ReviewNavigator.start(files),
            reviewed = ReviewedFiles(setOf(files[1].key)),
            wrap = false,
            split = false,
            vcs = VcsState(
                directory = "/home/dev/project",
                info = VcsInfo(provider = "git", branch = VcsBranch(current = "phase-6", default = "main")),
                files = files.map {
                    VcsFileStatus(
                        file = it.file,
                        additions = it.additions,
                        deletions = it.deletions,
                        status = it.status,
                    )
                },
                base = dev.opencode.android.core.model.VcsBase(
                    name = "main",
                    ref = "refs/heads/main",
                    source = dev.opencode.android.core.model.VcsBaseSource.Default,
                ),
            ),
            branches = listOf("main", "develop", "phase-6"),
        )
    }
}
