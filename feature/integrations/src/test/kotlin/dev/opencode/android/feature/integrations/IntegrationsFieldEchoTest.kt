package dev.opencode.android.feature.integrations

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import dev.opencode.android.core.data.integrations.IntegrationFlow
import dev.opencode.android.core.data.integrations.McpServerDraft
import dev.opencode.android.core.model.FormField
import dev.opencode.android.core.model.FormValues
import dev.opencode.android.core.model.IntegrationMethod
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The connect sheet's API key and form field, and the MCP add form, keep what is typed or pasted while the
 * view model's echo of it is still on its way.
 *
 * A key is the case that hurts: it is typed once, masked, so a reordered character is not seen, and the
 * server answers that the key is wrong. The state these fields show reaches the composition a frame after
 * it was written, so a field that displays it is rewound to an older text than the one it just reported.
 * [LateEcho] is a state that trails on purpose, by a number of edits the test picks.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h2000dp-xhdpi")
class IntegrationsFieldEchoTest {

    @get:Rule
    val compose = createComposeRule()

    /** 40 distinct characters, the length of a real token, so any reordering or loss shows. */
    private val apiKey = "sk-AbCdEfGhIjKlMnOpQrStUvWxYz0123456789"
    private val resourceName = "team-alpha-production-resource"
    private val serverName = "docs-search-server"
    private val serverUrl = "https://mcp.example.com/v1/sse"

    private val key = LateEcho("")
    private val resource = LateEcho("")
    private val draft = LateEcho(McpServerDraft(kind = McpServerDraft.Kind.REMOTE))

    private val keyField = hasTestTag(IntegrationsTags.KEY_FIELD)
    private val resourceField = hasTestTag(IntegrationsTags.formField(RESOURCE_KEY))

    private fun label(@StringRes id: Int): String =
        ApplicationProvider.getApplicationContext<Context>().getString(id)

    private fun showConnectSheet() {
        val method = IntegrationMethod.Key(
            form = listOf(FormField.StringField(key = RESOURCE_KEY, title = "Resource name", required = true)),
        )
        compose.setContent {
            ConnectSheetContent(
                state = ConnectUiState(
                    directory = "/work/app",
                    active = ActiveConnect("placeholder-integration", "Anthropic", method, IntegrationFlow.KEY),
                    keyDraft = key.value,
                    formAnswers = resource.value.takeIf { it.isNotEmpty() }
                        ?.let { mapOf(RESOURCE_KEY to FormValues.string(it)) }
                        .orEmpty(),
                ),
                onLabelChange = {},
                onKeyChange = key::report,
                onCodeChange = {},
                onAnswer = { _, answer -> resource.report((answer as? JsonPrimitive)?.content.orEmpty()) },
                onSubmit = {},
                onSubmitCode = {},
                onCancelAttempt = {},
                onOpenUrl = {},
                onCopyCode = {},
                onDismiss = {},
            )
        }
    }

    private fun showMcpAddForm() {
        compose.setContent {
            McpAddSheetContent(
                draft = draft.value,
                problems = emptyList(),
                canAdd = false,
                onChange = draft::report,
                onAdd = {},
            )
        }
    }

    private fun onScreen(field: SemanticsMatcher): String? = compose.onNode(field)
        .fetchSemanticsNode()
        .config
        .getOrNull(SemanticsProperties.EditableText)
        ?.text

    /** Types [text] a character at a time, letting the echo trail the typing by [lag] characters. */
    private fun <T> typeWithLag(field: SemanticsMatcher, echo: LateEcho<T>, text: String, lag: Int) {
        text.forEach { char ->
            compose.onNode(field).performTextInput(char.toString())
            while (echo.pending > lag) compose.runOnIdle { echo.publishOldest() }
        }
        compose.runOnIdle { while (echo.pending > 0) echo.publishOldest() }
        compose.waitForIdle()
    }

    private fun <T> pasteThenEcho(field: SemanticsMatcher, echo: LateEcho<T>, text: String) {
        compose.onNode(field).performTextInput(text)
        compose.runOnIdle { echo.publishOldest() }
        compose.waitForIdle()
    }

    /** The key field is masked, so what it shows is one dot per character and the text is what it reported. */
    private fun assertKeyHeld() {
        assertEquals("what the key field reported last", apiKey, key.lastReported)
        assertEquals("what the key field shows", "•".repeat(apiKey.length), onScreen(keyField))
    }

    @Test
    fun `the api key field keeps a key typed ahead of the echo`() {
        showConnectSheet()
        typeWithLag(keyField, key, apiKey, lag = 1)
        assertKeyHeld()
    }

    @Test
    fun `the api key field keeps a key when the echo trails by many edits`() {
        showConnectSheet()
        typeWithLag(keyField, key, apiKey, lag = 8)
        assertKeyHeld()
    }

    @Test
    fun `the api key field keeps a key pasted in one edit`() {
        showConnectSheet()
        pasteThenEcho(keyField, key, apiKey)
        assertKeyHeld()
    }

    @Test
    fun `the login form field keeps text typed ahead of the echo`() {
        showConnectSheet()
        typeWithLag(resourceField, resource, resourceName, lag = 2)
        assertEquals("what the field reported last", resourceName, resource.lastReported)
        assertEquals("what the field shows", resourceName, onScreen(resourceField))
    }

    @Test
    fun `the mcp add form keeps a name and an address typed ahead of the echo`() {
        showMcpAddForm()
        val nameField = hasText(label(R.string.mcp_add_name))
        val urlField = hasText(label(R.string.mcp_add_url))
        typeWithLag(nameField, draft, serverName, lag = 2)
        typeWithLag(urlField, draft, serverUrl, lag = 2)
        assertEquals("the name the form reported last", serverName, draft.lastReported.name)
        assertEquals("the address the form reported last", serverUrl, draft.lastReported.url)
        assertEquals("the name on screen", serverName, onScreen(nameField))
        assertEquals("the address on screen", serverUrl, onScreen(urlField))
    }

    private companion object {
        const val RESOURCE_KEY = "resourceName"
    }
}
