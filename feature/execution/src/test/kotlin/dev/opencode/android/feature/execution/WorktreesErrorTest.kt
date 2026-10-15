package dev.opencode.android.feature.execution

import android.content.Context
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The panel shows a failed worktree write until the user dismisses it (issue 7).
 *
 * Before this the screen never read `state.error`, so a refused create left the form looking as if nothing had
 * happened. The state here is the one the view model produces on a refusal; the screenshot baselines use a state
 * with no error and are not affected.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h2000dp-xhdpi")
class WorktreesErrorTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `a failed write is shown beside the form and its dismiss action reports the tap`() {
        var dismissed = 0
        compose.setContent {
            WorktreesScreen(
                state = WorktreesUiState(
                    projectID = "prj_1",
                    draftFrom = "/srv/gone",
                    error = "Worktree directory unavailable: /srv/gone",
                ),
                onFromChange = {},
                onBranchChange = {},
                onNameChange = {},
                onCreate = {},
                onRefresh = {},
                onRequestRemove = {},
                onRemove = {},
                onForceRemove = {},
                onCancelRemove = {},
                onMoveSession = null,
                onDismissError = { dismissed++ },
            )
        }

        compose.onNodeWithTag(WorktreeTags.ERROR)
            .assertTextContains("Worktree directory unavailable: /srv/gone", substring = true)
        compose.onNodeWithContentDescription(
            ApplicationProvider.getApplicationContext<Context>().getString(R.string.action_close),
        ).performClick()

        assertEquals(1, dismissed)
    }
}
