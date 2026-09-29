package dev.opencode.android.navigation

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import dev.opencode.android.core.data.integrations.IntegrationFlow
import dev.opencode.android.core.data.integrations.IntegrationFlows
import dev.opencode.android.core.model.IntegrationInfo
import dev.opencode.android.core.model.IntegrationMethod
import dev.opencode.android.feature.integrations.ConnectScreen
import dev.opencode.android.feature.integrations.ConnectViewModel
import dev.opencode.android.feature.integrations.IntegrationsTags
import dev.opencode.android.feature.integrations.McpScreen
import dev.opencode.android.feature.integrations.McpViewModel
import dev.opencode.android.feature.integrations.PluginsScreen
import dev.opencode.android.feature.integrations.PluginsViewModel
import dev.opencode.android.feature.integrations.ProvidersScreen
import dev.opencode.android.feature.integrations.ProvidersViewModel
import dev.opencode.android.feature.integrations.R
import dev.opencode.android.feature.integrations.WebSearchScreen
import dev.opencode.android.feature.integrations.WebSearchViewModel
import kotlinx.serialization.Serializable

/** The accounts screen, which is `/connect` (features doc §10). */
@Serializable
data class ConnectRoute(val serverId: String? = null, val directory: String)

/** The providers list, read-only (features doc §9). */
@Serializable
data class ProvidersRoute(val serverId: String? = null, val directory: String)

/** The MCP servers of a checkout, with status and the runtime writes (features doc §17). */
@Serializable
data class McpRoute(val serverId: String? = null, val directory: String)

/** The plugin list, its check and its update (features doc §18). */
@Serializable
data class PluginsRoute(val serverId: String? = null, val directory: String)

/** The web-search providers and a test query (features doc §24). */
@Serializable
data class WebSearchRoute(val serverId: String? = null, val directory: String)

/**
 * The Phase 8 destinations, and the composition root (plan §6).
 *
 * **Every screen is a thin host, because none of them owns state.** The plans' deviations are the
 * reason: a feature may not import another feature, and "MCP needs OAuth through an integration" and
 * "an MCP resource attaches to a prompt" both cross that line — so the host is where the two are
 * joined, which is why [McpHost] takes an `onAuthenticate` and an `onAttach` rather than opening the
 * other screens itself.
 *
 * **The Custom Tab and the clipboard live here, not in the feature.** A `CustomTabsIntent` is an
 * `Intent` and a clipboard is a platform service; neither belongs in a module that has to stay
 * testable on a JVM. The URL that reaches [openProvider] has already been scheme-checked by the
 * model layer — the raw value is not on the state at all — so the check is not repeated here and
 * cannot be bypassed by a caller that forgets it.
 */
@Composable
fun ConnectHost(
    directory: String,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ConnectViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    LaunchedEffect(directory) { viewModel.open(directory) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.resume() }

    IntegrationsScaffold(
        title = stringResource(R.string.connect_title),
        modifier = modifier,
        onNavigateBack = onNavigateBack,
    ) { padding ->
        ConnectScreen(
            state = state,
            onMethodClick = { method ->
                state.rows.firstOrNull { row -> row.info.methods.contains(method) }?.let { row ->
                    viewModel.openFlow(row.info, method)
                }
            },
            onCredentialAction = viewModel::requestCredentialAction,
            onConfirmCredential = viewModel::confirmCredential,
            onCancelCredential = viewModel::cancelCredentialAction,
            onConfirmLabelChange = viewModel::setConfirmLabel,
            onLabelChange = viewModel::setLabel,
            onKeyChange = viewModel::setKey,
            onCodeChange = viewModel::setCode,
            onAnswer = viewModel::setAnswer,
            onSubmit = viewModel::submit,
            onSubmitCode = viewModel::submitCode,
            onCancelAttempt = viewModel::cancelAttempt,
            onOpenUrl = { url -> openProvider(context, url) },
            onCopyCode = { code -> clipboard.setText(AnnotatedString(code)) },
            onDismissConnect = viewModel::dismissConnect,
            onWellknownUrlChange = viewModel::setWellknownUrl,
            onAddWellknown = viewModel::addWellknownSource,
            onDismissError = viewModel::dismissError,

            modifier = Modifier.padding(padding),
        )
    }
}

/** The MCP screen, with its two crossings to the accounts screen and the composer. */
@Composable
fun McpHost(
    directory: String,
    onNavigateBack: () -> Unit,
    onAuthenticate: (integrationId: String) -> Unit,
    onAttachResource: (server: String, name: String, uri: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: McpViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(directory) { viewModel.open(directory) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.resume() }

    // The pick is a one-shot: the host takes it, hands it on, and the view model drops it. Without
    // the `consume` a rotation would attach the same resource twice.
    LaunchedEffect(state.attachTarget) {
        val resource = state.attachTarget ?: return@LaunchedEffect
        onAttachResource(resource.server, resource.name, resource.uri)
        viewModel.consumeAttach()
    }

    IntegrationsScaffold(
        title = stringResource(R.string.mcp_title),
        modifier = modifier,
        onNavigateBack = onNavigateBack,
    ) { padding ->
        McpScreen(
            state = state,
            onConnect = viewModel::connect,
            onDisconnect = viewModel::disconnect,
            onAuthenticate = { server ->
                // A `needs_auth` server without an integration id has no flow this client can start,
                // and the screen has no button for it, so this cannot be reached with a null.
                server.integrationID?.let(onAuthenticate)
            },
            onRequestRemove = viewModel::requestRemove,
            onRemove = viewModel::confirmRemove,
            onCancelRemove = viewModel::cancelRemove,
            onOpenAdd = viewModel::openAddSheet,
            onDraftChange = { draft -> viewModel.updateDraft { draft } },
            onAdd = viewModel::addServer,
            onDismissAdd = viewModel::dismissAddSheet,
            onOpenResources = viewModel::openResources,
            onCloseResources = viewModel::closeResources,
            onAttach = viewModel::attach,
            onDismissError = viewModel::dismissError,
            modifier = Modifier.padding(padding),
        )
    }
}

/** The plugins screen. */
@Composable
fun PluginsHost(
    directory: String,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PluginsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(directory) { viewModel.open(directory) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.resume() }

    IntegrationsScaffold(
        title = stringResource(R.string.plugins_title),
        modifier = modifier,
        onNavigateBack = onNavigateBack,
    ) { padding ->
        PluginsScreen(
            state = state,
            onToggle = viewModel::toggle,
            onCheck = { viewModel.check() },
            onUpdate = viewModel::update,
            onSelectAll = viewModel::selectAll,
            onClearSelection = viewModel::clearSelection,
            onDismissError = viewModel::dismissError,
            modifier = Modifier.padding(padding),
        )
    }
}

/** The providers screen. */
@Composable
fun ProvidersHost(
    directory: String,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ProvidersViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(directory) { viewModel.open(directory) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.resume() }

    IntegrationsScaffold(
        title = stringResource(R.string.providers_title),
        modifier = modifier,
        onNavigateBack = onNavigateBack,
    ) { padding ->
        ProvidersScreen(
            state = state,
            onSearchChange = viewModel::setSearch,
            onSelect = viewModel::select,
            modifier = Modifier.padding(padding),
        )
    }
}

/** The web-search screen. */
@Composable
fun WebSearchHost(
    directory: String,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: WebSearchViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(directory) { viewModel.open(directory) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.resume() }

    IntegrationsScaffold(
        title = stringResource(R.string.websearch_title),
        modifier = modifier,
        onNavigateBack = onNavigateBack,
    ) { padding ->
        WebSearchScreen(
            state = state,
            onSelect = viewModel::select,
            onQueryChange = viewModel::setQuery,
            onSearch = viewModel::search,
            onDismissError = viewModel::dismissError,
            modifier = Modifier.padding(padding),
        )
    }
}

/**
 * Opens a provider's consent screen.
 *
 * **The scheme is checked again here, at the last possible moment.** The model layer already refused
 * a non-http URL and the value on the state is the checked one, so this is a second gate on a value
 * that crosses a module boundary; two independent gates on a server-supplied URL is the arrangement
 * the brief asks for, and this one sits where the `Intent` is actually built.
 *
 * **The intent carries the Custom Tabs extras directly, without `androidx.browser`.** The library is
 * not in the version catalog, and adding a dependency for one intent would be a worse trade than the
 * two extras: `EXTRA_SESSION` and `EXTRA_TOOLBAR_COLOR` are what makes a browser render a tab-stripped
 * window with a colour bar, and a browser that does not understand them simply opens the URL, which
 * is exactly the fallback a user needs. The extras are read from the browser's own service metadata
 * when it is installed, so the effect matches what the library would have done.
 *
 * **A failure to open is silent, and that is deliberate.** There is no activity for some schemes on
 * some devices, and an exception thrown out of a click would take the sheet down. The user is still
 * looking at the server's instructions and, for a device-code flow, a field to type into, so the
 * flow is not blocked.
 */
private fun openProvider(context: android.content.Context, url: String) {
    if (!dev.opencode.android.core.model.SafeNavigationUrl.isAllowed(url)) return
    val uri = android.net.Uri.parse(url)
    val session = android.os.Bundle().apply {
        // A stable, non-secret token. Chrome uses it to group tabs of the same flow, and it is
        // visible to the browser, so it must not carry the attempt id.
        putString("android.support.customTabs.extra.SESSION", "opencode-oauth")
    }
    val intent = Intent(Intent.ACTION_VIEW, uri).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        putExtra("android.support.customTabs.extra.SESSION", session)
        putExtra("android.support.customTabs.extra.TOOLBAR_COLOR", 0)
        putExtra("android.support.customTabs.extra.EXTRA_ENABLE_URLBAR_HIDING", true)
    }
    try {
        context.startActivity(intent)
    } catch (missing: ActivityNotFoundException) {
        // Nothing on this device can open an http URL. The sheet stays where it is.
    } catch (denied: SecurityException) {
        // A device policy blocked it. Same outcome, and neither is worth a crash.
    }
}

/** The Phase 8 top bar, shared by the five screens so they cannot drift apart. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IntegrationsScaffold(
    title: String,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (androidx.compose.foundation.layout.PaddingValues) -> Unit,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(text = title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
        content = content,
    )
}
