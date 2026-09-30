package dev.opencode.android.feature.requests.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.opencode.android.core.data.action.ActionError
import dev.opencode.android.core.data.action.ActionErrorKind
import dev.opencode.android.core.data.server.PendingRequest
import dev.opencode.android.core.model.PermissionRequest
import dev.opencode.android.feature.requests.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The global inbox says when an answer was not taken.
 *
 * Its host sends every answer through a composer that never opens a session, and that composer records a
 * refusal (`404` gone, `409` already settled, the server unreachable) in its own state. The screen drew only the
 * requests, so a refused "Allow once" looked like a button that did nothing, on a request that was still
 * blocking the agent. The failure is now above the list, worded as the composer words it, with the requests
 * still under it because the server never echoed an answer for them.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h2000dp-xhdpi")
class InboxAnswerFailureTest {

    @get:Rule
    val compose = createComposeRule()

    private var dismissed = 0

    @Test
    fun `a refused answer is said above the request that is still waiting`() {
        show(error = ActionError(ActionErrorKind.NOT_FOUND, "No such request"))

        compose.onNodeWithText(string(R.string.requests_inbox_answer_failed)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.action_error_not_found)).assertIsDisplayed()
        // The request is still there to be answered again or rejected.
        compose.onNodeWithText(string(R.string.permission_title, "shell")).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.permission_once)).assertIsDisplayed()
    }

    @Test
    fun `a screen with no failure has no banner`() {
        show(error = null)

        val banners = compose.onAllNodesWithText(string(R.string.requests_inbox_answer_failed)).fetchSemanticsNodes()
        assertEquals(0, banners.size)
        compose.onNodeWithText(string(R.string.permission_title, "shell")).assertIsDisplayed()
    }

    @Test
    fun `dismissing the failure calls back and does not answer anything`() {
        val replies = mutableListOf<String>()
        show(error = ActionError(ActionErrorKind.CONFLICT, "Already answered"), replies = replies)

        compose.onNodeWithText(string(R.string.action_dismiss)).performClick()

        assertEquals(1, dismissed)
        assertEquals(emptyList<String>(), replies)
    }

    @Test
    fun `every kind of failure is worded, and the two that carry the server's words carry them`() {
        var error by mutableStateOf(ActionError(ActionErrorKind.entries.first(), WORDS))
        compose.setContent {
            MaterialTheme {
                PendingRequestsScreen(
                    requests = emptyList(),
                    sessionTitles = emptyMap(),
                    actions = RequestActions(),
                    onNavigateBack = {},
                    error = error,
                )
            }
        }

        for (kind in ActionErrorKind.entries) {
            error = ActionError(kind, WORDS)
            compose.waitForIdle()

            val expected = if (kind.takesArgument) string(kind.messageRes(), WORDS) else string(kind.messageRes())
            compose.onNodeWithText(expected).assertIsDisplayed()
        }
    }

    @Test
    fun `a failure is still said when the request it was about has since gone`() {
        show(error = ActionError(ActionErrorKind.OFFLINE, "no route"), requests = emptyList())

        compose.onNodeWithText(string(R.string.requests_inbox_answer_failed)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.action_error_offline)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.requests_inbox_empty_title)).assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w320dp-h2000dp-xhdpi")
    fun `the failure and its dismissal are whole at the narrowest phone and double font`() {
        RuntimeEnvironment.setFontScale(2f)
        show(error = ActionError(ActionErrorKind.CONFLICT, "Already answered"))

        compose.onNodeWithText(string(R.string.requests_inbox_answer_failed)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.action_error_conflict)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.action_dismiss)).assertIsDisplayed()
    }

    private fun show(
        error: ActionError?,
        requests: List<PendingRequest> = listOf(PendingRequest.Permission(permission())),
        replies: MutableList<String> = mutableListOf(),
    ) {
        compose.setContent {
            MaterialTheme {
                PendingRequestsScreen(
                    requests = requests,
                    sessionTitles = mapOf("ses_1" to "A session"),
                    actions = RequestActions(onReplyOnce = { replies += "once ${it.request.id}" }),
                    onNavigateBack = {},
                    error = error,
                    onDismissError = { dismissed++ },
                )
            }
        }
    }

    private fun string(id: Int, vararg args: Any): String = RuntimeEnvironment.getApplication().getString(id, *args)

    private fun permission() = PermissionRequest(
        id = "per_1",
        sessionID = "ses_1",
        action = "shell",
        resources = listOf("git push origin main"),
    )

    private companion object {
        const val WORDS = "the server's own words"
    }
}
