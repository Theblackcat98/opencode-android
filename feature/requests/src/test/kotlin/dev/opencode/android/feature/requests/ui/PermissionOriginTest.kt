package dev.opencode.android.feature.requests.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.opencode.android.core.data.server.PendingRequest
import dev.opencode.android.core.model.PermissionRequest
import dev.opencode.android.core.model.PermissionSource
import dev.opencode.android.feature.requests.R
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A permission request says who raised it, in the places a user answers one (manual test H11).
 *
 * `POST /api/session/{id}/permission` lets a plugin or a client raise a request, and it blocks the agent like a
 * tool's. It has no `source`, and it used to be drawn exactly like a tool's ("Allow external_directory? It would
 * touch /etc/hosts") with nothing saying who asks and its `metadata.title` ignored. The card is the one
 * renderer, so the session's dock and the global inbox are both asserted here, and the label has to be
 * whole and clear of the buttons at the narrowest phone and the largest font.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h2000dp-xhdpi")
class PermissionOriginTest {

    @get:Rule
    val compose = createComposeRule()

    private val replies = mutableListOf<String>()
    private val actions = RequestActions(
        onReplyOnce = { replies += "once ${it.request.id}" },
        onReject = { request, _ -> replies += "reject ${request.request.id}" },
    )

    @Test
    fun `a request no tool raised carries the label and its metadata title`() {
        show { PermissionCard(request = pluginRequest(), actions = actions) }

        compose.onNodeWithText(label()).assertIsDisplayed()
        compose.onNodeWithText(TITLE).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.permission_title, "external_directory")).assertIsDisplayed()
        compose.onNodeWithText("/etc/hosts").assertIsDisplayed()
    }

    @Test
    fun `a request a tool raised does not carry the label`() {
        show { PermissionCard(request = toolRequest(), actions = actions) }

        compose.onNodeWithText(string(R.string.permission_title, "shell")).assertIsDisplayed()
        assertEquals("no label on a tool's request", 0, compose.onAllNodesWithText(label()).fetchSemanticsNodes().size)
        assertEquals("no title where there is none", 0, compose.onAllNodesWithText(TITLE).fetchSemanticsNodes().size)
    }

    @Test
    fun `a tool's request shows the title it was given and still no label`() {
        show { PermissionCard(request = toolRequest(title = "Push the branch"), actions = actions) }

        compose.onNodeWithText("Push the branch").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithText(label()).fetchSemanticsNodes().size)
    }

    @Test
    fun `the label does not change what the buttons do`() {
        show { PermissionCard(request = pluginRequest(), actions = actions) }

        compose.onNodeWithText(string(R.string.permission_once)).performClick()
        compose.onNodeWithText(string(R.string.permission_reject)).performClick()

        assertEquals(listOf("once per_plugin", "reject per_plugin"), replies)
    }

    @Test
    fun `the session dock labels a request no tool raised`() {
        show { RequestDock(requests = listOf(PendingRequest.Permission(pluginRequest())), actions = actions) }

        compose.onNodeWithText(label()).assertIsDisplayed()
        compose.onNodeWithText(TITLE).assertIsDisplayed()
    }

    @Test
    fun `the inbox labels the request no tool raised and only that one`() {
        show {
            PendingRequestsScreen(
                requests = listOf(PendingRequest.Permission(pluginRequest()), PendingRequest.Permission(toolRequest())),
                sessionTitles = mapOf("ses_1" to "A session"),
                actions = actions,
                onNavigateBack = {},
            )
        }

        compose.onNodeWithText(label()).assertIsDisplayed()
        assertEquals("one request is a plugin's", 1, compose.onAllNodesWithText(label()).fetchSemanticsNodes().size)
    }

    @Test
    @Config(qualifiers = "w320dp-h2000dp-xhdpi")
    fun `the label is whole at the narrowest phone and double font`() {
        RuntimeEnvironment.setFontScale(2f)
        show { PermissionCard(request = pluginRequest(), actions = actions) }

        compose.onNodeWithText(label()).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.permission_once)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.permission_reject)).assertIsDisplayed()
    }

    private fun show(content: @Composable () -> Unit) {
        compose.setContent { MaterialTheme { content() } }
    }

    private fun label(): String = string(R.string.permission_not_from_tool)

    private fun string(id: Int, vararg args: Any): String = RuntimeEnvironment.getApplication().getString(id, *args)

    /** What `session.permission.create` produces: no source, and the title the caller gave it. */
    private fun pluginRequest() = PermissionRequest(
        id = "per_plugin",
        sessionID = "ses_1",
        action = "external_directory",
        resources = listOf("/etc/hosts"),
        save = listOf("/etc/*"),
        metadata = JsonObject(mapOf("title" to JsonPrimitive(TITLE))),
    )

    /** What a tool call raises: the tool part it is waiting on is its source. */
    private fun toolRequest(title: String? = null) = PermissionRequest(
        id = "per_tool",
        sessionID = "ses_1",
        action = "shell",
        resources = listOf("git push origin main"),
        save = listOf("git push *"),
        metadata = title?.let { JsonObject(mapOf("title" to JsonPrimitive(it))) },
        source = PermissionSource(PermissionSource.TOOL, "msg_1", "call_1"),
    )

    private companion object {
        const val TITLE = "Read the hosts file for a network check"
    }
}
