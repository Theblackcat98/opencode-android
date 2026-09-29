package dev.opencode.android.feature.integrations

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import dev.opencode.android.core.data.integrations.ConnectAttemptProgress
import dev.opencode.android.core.data.integrations.ConnectAttemptState
import dev.opencode.android.core.data.integrations.CredentialAction
import dev.opencode.android.core.data.integrations.McpServerDraft
import dev.opencode.android.core.data.sync.SyncedState
import dev.opencode.android.core.designsystem.theme.OpenCodeTheme
import dev.opencode.android.core.model.ConnectionInfo
import dev.opencode.android.core.model.FormField
import dev.opencode.android.core.model.IntegrationInfo
import dev.opencode.android.core.model.IntegrationMethod
import dev.opencode.android.core.model.McpResource
import dev.opencode.android.core.model.McpResourceTemplate
import dev.opencode.android.core.model.McpServer
import dev.opencode.android.core.model.McpStatus
import dev.opencode.android.core.model.PluginFeatures
import dev.opencode.android.core.model.PluginInfo
import dev.opencode.android.core.model.PluginSource
import dev.opencode.android.core.model.PluginState
import dev.opencode.android.core.model.ProviderInfo
import dev.opencode.android.core.model.WebSearchProviderInfo
import dev.opencode.android.core.model.WebSearchResponse
import dev.opencode.android.core.model.WebSearchResult
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screenshot tests for the Phase 8 screens (plan §5.3, "UI"; §5.4, dynamic type).
 *
 * **Every fixture value here is a placeholder, and that is a security property, not a style note.**
 * A screenshot of the key sheet would capture whatever `keyDraft` held, and a baseline is a
 * committed PNG in the repository. So the key field is captured *masked* with a clearly fake value,
 * the device code is a fake, and the URLs use `example.com`. A real credential typed into this
 * screen would end up in a file anyone can read, which is the one leak the Keystore work exists to
 * prevent — the `FLAG_SECURE` window flag covers the device's own screenshots, and a committed
 * baseline is not the device.
 *
 * The states captured are the ones a user reads and then acts on: the account list with a stored
 * credential, a key sheet mid-login, an OAuth attempt waiting on the browser, a device-code attempt,
 * a command attempt with its output and a copy button, a `needs_auth` MCP server, the add form, the
 * resource catalog, a failed plugin and a web-search result. Light and dark, and once at 1.5× font.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h2000dp-xhdpi")
class IntegrationsScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    // ------------------------------------------------------------------ connect

    @Test
    fun connectList() = capture("connect-list") { ConnectScreenFixture(connectState()) }

    @Test
    fun connectListDark() = capture("connect-list-dark", dark = true) { ConnectScreenFixture(connectState()) }

    @Test
    fun connectKeySheet() = capture("connect-key") {
        ConnectSheetFixture(connectState(active = ActiveConnect("anthropic", "Anthropic", keyMethod(), IntegrationFlowFixture.KEY)))
    }

    @Test
    fun connectKeySheetLargeFont() = capture("connect-key-large-font", fontScale = 1.5f) {
        ConnectSheetFixture(connectState(active = ActiveConnect("anthropic", "Anthropic", keyMethod(), IntegrationFlowFixture.KEY)))
    }

    @Test
    fun connectOauthWaiting() = capture("connect-oauth-waiting") {
        ConnectSheetFixture(
            connectState(
                active = ActiveConnect("anthropic", "Anthropic", oauthMethod(), IntegrationFlowFixture.OAUTH),
                progress = ConnectAttemptProgress(attemptID = "att_1", state = ConnectAttemptState.Pending),
                oauthAttemptUrl = "https://example.com/oauth/authorize",
                oauthInstructions = "Approve the request in the browser, then come back.",
                oauthMode = "auto",
            ),
        )
    }

    @Test
    fun connectDeviceCode() = capture("connect-device-code") {
        ConnectSheetFixture(
            connectState(
                active = ActiveConnect("github", "GitHub", oauthMethod(id = "oauth-device"), IntegrationFlowFixture.OAUTH),
                progress = ConnectAttemptProgress(attemptID = "att_2", state = ConnectAttemptState.Pending),
                oauthMode = "code",
                codeDraft = "WDJB-MJHT-2K9P",
            ),
        )
    }

    @Test
    fun connectCommandOutput() = capture("connect-command-output") {
        ConnectSheetFixture(
            connectState(
                active = ActiveConnect("github", "GitHub", commandMethod(), IntegrationFlowFixture.COMMAND),
                progress = ConnectAttemptProgress(
                    attemptID = "att_3",
                    state = ConnectAttemptState.Pending,
                    output = "Open https://example.com/login/device\nEnter code: ABCD-EFGH-IJKL",
                ),
            ),
        )
    }

    @Test
    fun connectCredentialRemoval() = capture("connect-remove-credential") {
        ConnectScreenFixture(
            connectState(
                confirm = PendingCredentialAction(
                    connection = ConnectionInfo.Credential("cred_1", "work key", "key"),
                    action = CredentialAction.REMOVE,
                ),
            ),
        )
    }

    // ------------------------------------------------------------------ MCP

    @Test
    fun mcpList() = capture("mcp-list") { McpScreenFixture(mcpState()) }

    @Test
    fun mcpNeedsAuth() = capture("mcp-needs-auth") {
        McpScreenFixture(mcpState(runtimeUsable = false))
    }

    @Test
    fun mcpLargeFont() = capture("mcp-large-font", fontScale = 1.5f) { McpScreenFixture(mcpState()) }

    @Test
    fun mcpAddForm() = capture("mcp-add-form") {
        McpAddSheetFixture(mcpState(addSheetOpen = true, draft = McpServerDraft(name = "files", command = "mcp-server-filesystem /work")))
    }

    @Test
    fun mcpAddFormRemote() = capture("mcp-add-remote") {
        McpAddSheetFixture(
            mcpState(
                addSheetOpen = true,
                draft = McpServerDraft(
                    name = "github",
                    kind = McpServerDraft.Kind.REMOTE,
                    url = "not a url",
                    headers = "Authorization=Bearer example",
                ),
            ),
        )
    }

    @Test
    fun mcpResources() = capture("mcp-resources") {
        McpResourcesFixture(mcpState(resourcesOpen = true))
    }

    // ------------------------------------------------------------------ plugins, providers, web search

    @Test
    fun pluginsList() = capture("plugins-list") { PluginsScreenFixture(pluginsState()) }

    @Test
    fun pluginsFailedDark() = capture("plugins-failed-dark", dark = true) { PluginsScreenFixture(pluginsState()) }

    @Test
    fun providersList() = capture("providers-list") { ProvidersScreenFixture(providersState()) }

    @Test
    fun providersLargeFont() = capture("providers-large-font", fontScale = 1.5f) { ProvidersScreenFixture(providersState()) }

    @Test
    fun webSearchResults() = capture("websearch-results") { WebSearchScreenFixture(webSearchState()) }

    // ------------------------------------------------------------------ fixtures

    private fun connectState(
        active: ActiveConnect? = null,
        progress: ConnectAttemptProgress = ConnectAttemptProgress(),
        oauthAttemptUrl: String? = null,
        oauthInstructions: String = "",
        oauthMode: String? = null,
        codeDraft: String = "",
        confirm: PendingCredentialAction? = null,
    ) = ConnectUiState(
        directory = "/work/app",
        integrations = SyncedState(
            value = listOf(IntegrationRow(integration())),
            status = dev.opencode.android.core.data.sync.SyncStatus.Ready,
        ),
        active = active,
        labelDraft = "work key",
        // A placeholder, and the field masks it. See the class comment.
        keyDraft = "placeholder-key-not-a-real-credential",
        formAnswers = mapOf("resourceName" to dev.opencode.android.core.model.FormValues.string("team-a")),
        codeDraft = codeDraft,
        progress = progress,
        oauthAttemptUrl = oauthAttemptUrl,
        oauthInstructions = oauthInstructions,
        oauthMode = oauthMode,
        confirm = confirm,
    )

    private fun mcpState(
        runtimeUsable: Boolean = true,
        addSheetOpen: Boolean = false,
        resourcesOpen: Boolean = false,
        draft: McpServerDraft = McpServerDraft(),
    ) = McpUiState(
        directory = "/work/app",
        servers = SyncedState(
            value = listOf(
                McpServer("files", McpStatus.Connected),
                McpServer("github", McpStatus.NeedsAuth("authorization required"), integrationID = "github"),
                McpServer("broken", McpStatus.Failed("spawn ENOENT")),
            ),
            status = dev.opencode.android.core.data.sync.SyncStatus.Ready,
        ),
        resources = SyncedState(
            value = dev.opencode.android.core.model.McpResourceCatalog(
                resources = listOf(
                    McpResource("files", "readme", "file:///readme.md", "The readme", "text/markdown"),
                ),
                templates = listOf(McpResourceTemplate("files", "row", "db://{table}/{id}")),
            ),
            status = dev.opencode.android.core.data.sync.SyncStatus.Ready,
        ),
        runtimeUsable = runtimeUsable,
        draft = draft,
        addSheetOpen = addSheetOpen,
        resourcesOpen = resourcesOpen,
    )

    private fun pluginsState() = PluginsUiState(
        directory = "/work/app",
        plugins = SyncedState(
            value = listOf(
                PluginInfo(
                    id = "hooks",
                    source = PluginSource.Package("opencode-plugin-hooks", "1.2.0", outdated = true),
                    features = PluginFeatures(server = true),
                    state = PluginState.Active,
                ),
                PluginInfo(
                    id = "lint",
                    source = PluginSource.Package("opencode-plugin-lint", "2.0.0"),
                    features = PluginFeatures(server = true, rpc = true),
                    state = PluginState.Active,
                ),
                PluginInfo(
                    id = "broken",
                    source = PluginSource.Local("/work/plugin"),
                    features = PluginFeatures(tui = true),
                    state = PluginState.Failed("the plugin threw while loading"),
                ),
            ),
            status = dev.opencode.android.core.data.sync.SyncStatus.Ready,
        ),
        selected = setOf("hooks"),
        status = "1 plugin can be updated",
    )

    private fun providersState() = ProvidersUiState(
        directory = "/work/app",
        providers = SyncedState(
            value = listOf(
                ProviderInfo(
                    id = "llama",
                    name = "Llama (local)",
                    activation = "auto",
                    packageName = "@ai-sdk/openai-compatible",
                    settings = dev.opencode.android.core.model.ProviderSettings(
                        mapOf("baseURL" to kotlinx.serialization.json.JsonPrimitive("http://127.0.0.1:11434/v1")),
                    ),
                ),
                ProviderInfo(
                    id = "anthropic",
                    name = "Anthropic",
                    activation = "enabled",
                    packageName = "@ai-sdk/anthropic",
                    integrationID = "anthropic",
                    canonical = "anthropic",
                ),
                ProviderInfo(id = "disabled-one", name = "Disabled provider", activation = "disabled", packageName = "@ai-sdk/x"),
            ),
            status = dev.opencode.android.core.data.sync.SyncStatus.Ready,
        ),
        selected = providersStateSelected,
    )

    private fun webSearchState() = WebSearchUiState(
        directory = "/work/app",
        providers = SyncedState(
            value = listOf(WebSearchProviderInfo("exa", "Exa"), WebSearchProviderInfo("tavily", "Tavily")),
            status = dev.opencode.android.core.data.sync.SyncStatus.Ready,
        ),
        selected = "exa",
        query = "kotlin coroutines structured concurrency",
        response = WebSearchResponse(
            providerID = "exa",
            results = listOf(
                WebSearchResult(
                    url = "https://example.com/kotlin/coroutines",
                    title = "Coroutines | Kotlin Documentation",
                    content = "Structured concurrency is a feature of Kotlin that makes concurrency "
                        + "easier to manage, with a disciplined approach that keeps code readable.",
                ),
            ),
        ),
    )

    private fun integration() = IntegrationInfo(
        id = "anthropic",
        name = "Anthropic",
        methods = listOf(
            IntegrationMethod.Key(form = listOf(FormField.StringField(key = "resourceName", title = "Resource name", required = true))),
            oauthMethod(),
            IntegrationMethod.Env(listOf("ANTHROPIC_API_KEY")),
        ),
        connections = listOf(
            ConnectionInfo.Credential("cred_1", "work key", "key"),
            ConnectionInfo.Env("ANTHROPIC_API_KEY"),
        ),
    )

    private fun keyMethod() = IntegrationMethod.Key(form = listOf(FormField.StringField(key = "resourceName", title = "Resource name", required = true)))

    private fun oauthMethod(id: String = "oauth-default") = IntegrationMethod.OAuth(id, "Sign in with Microsoft")

    private fun commandMethod() = IntegrationMethod.Command("cli", "Sign in with the CLI", listOf("gh", "auth", "login"))

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

        val providersStateSelected = ProviderInfo(
            id = "llama",
            name = "Llama (local)",
            activation = "auto",
            packageName = "@ai-sdk/openai-compatible",
        )
    }
}

/** Alias so the fixtures read as the flow they are showing rather than as an import. */
private object IntegrationFlowFixture {
    val KEY = dev.opencode.android.core.data.integrations.IntegrationFlow.KEY
    val OAUTH = dev.opencode.android.core.data.integrations.IntegrationFlow.OAUTH
    val COMMAND = dev.opencode.android.core.data.integrations.IntegrationFlow.COMMAND
}
