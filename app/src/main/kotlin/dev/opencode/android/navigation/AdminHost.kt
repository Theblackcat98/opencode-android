package dev.opencode.android.navigation

import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.opencode.android.core.data.config.DefinitionKind
import dev.opencode.android.feature.admin.AdminCatalog
import dev.opencode.android.feature.admin.CatalogScreen
import dev.opencode.android.feature.admin.CatalogUiState
import dev.opencode.android.feature.admin.ConfigEditorScreen
import dev.opencode.android.feature.admin.ConfigEditorViewModel
import dev.opencode.android.feature.admin.ConfigScreen
import dev.opencode.android.feature.admin.ConfigViewModel
import dev.opencode.android.feature.admin.DefinitionScreen
import dev.opencode.android.feature.admin.DefinitionViewModel
import dev.opencode.android.feature.admin.InstructionsScreen
import dev.opencode.android.feature.admin.InstructionsViewModel
import dev.opencode.android.feature.admin.MaintenanceScreen
import dev.opencode.android.feature.admin.MaintenanceViewModel
import dev.opencode.android.feature.admin.PermissionsScreen
import dev.opencode.android.feature.admin.PermissionsViewModel
import dev.opencode.android.feature.admin.R
import dev.opencode.android.feature.admin.TemplateSheet
import dev.opencode.android.feature.insights.InsightsScreen
import dev.opencode.android.feature.insights.InsightsViewModel
import dev.opencode.android.feature.insights.PairDeviceScreen
import dev.opencode.android.feature.insights.PairDeviceViewModel
import dev.opencode.android.feature.insights.QuickAskScreen
import dev.opencode.android.feature.insights.QuickAskViewModel
import dev.opencode.android.feature.insights.RpcConsoleScreen
import dev.opencode.android.feature.insights.RpcConsoleViewModel
import kotlinx.serialization.Serializable

/**
 * The config explorer, which is the Manage row's "configuration" destination.
 *
 * **The directory is the whole identity.** Every configuration read is location-scoped, so a panel
 * without one could not say which checkout's `opencode.jsonc` it was editing.
 */
@Serializable
data class ConfigRoute(val serverId: String? = null, val directory: String)

/** The `opencode.jsonc` editor, with the guided templates. */
@Serializable
data class ConfigEditorRoute(val serverId: String? = null, val directory: String, val path: String? = null)

/** The definition editor, for an agent, a command, a skill or `AGENTS.md`. */
@Serializable
data class DefinitionRoute(
    val serverId: String? = null,
    val directory: String,
    val kind: DefinitionKind = DefinitionKind.AGENT,
    val name: String = "",
)

/** The catalog browsers: agents, commands, skills and references. */
@Serializable
data class CatalogRoute(
    val serverId: String? = null,
    val directory: String,
    val tab: AdminCatalog = AdminCatalog.AGENTS,
)

/** The permissions admin, with the session's own rules when one is named. */
@Serializable
data class PermissionsRoute(
    val serverId: String? = null,
    val directory: String,
    val projectID: String? = null,
    val sessionId: String? = null,
)

/** A session's durable instruction entries. */
@Serializable
data class InstructionsRoute(val serverId: String? = null, val sessionId: String)

/** The maintenance screen. */
@Serializable
data class MaintenanceRoute(val serverId: String? = null)

/**
 * The Phase 9 destinations and their composition (plan §6; see `IntegrationsHost` for the same pattern).
 *
 * **Every screen is a thin host, because none of them owns state.** The plan's deviations are the
 * reason: a feature may not import another feature, and the catalog browsers read the composer's
 * catalogs — `agent.list` from the sessions module and `command/skill/reference.list` from the
 * composer's — while the screens live in `feature/admin`. So [CatalogHost] takes the four lists as
 * parameters and the app module wires them, which is the same crossing [McpHost] makes for an MCP
 * server's OAuth.
 *
 * **Resume re-reads on every return to the screen.** `config.updated`, `agent.updated`,
 * `command.updated`, `skill.updated` and `reference.updated` all invalidate a catalog, and a user who
 * edits a file and comes back expects the explorer to reflect it rather than a stale merge.
 */
@Composable
fun ConfigHost(
    directory: String,
    onNavigateBack: () -> Unit,
    onOpenEditor: (String?) -> Unit,
    onOpenDefinitions: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ConfigViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(directory) { viewModel.open(directory) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.resume() }

    AdminScaffold(
        title = stringResource(R.string.admin_config_title),
        modifier = modifier,
        onNavigateBack = onNavigateBack,
    ) { padding ->
        ConfigScreen(
            state = state,
            onReload = viewModel::resume,
            onOpenEditor = { onOpenEditor(null) },
            onOpenDefinitions = onOpenDefinitions,
            onShellChange = viewModel::selectShell,
            onShellRequest = viewModel::requestShell,
            onShellConfirm = viewModel::confirmShell,
            onShellCancel = viewModel::cancelShell,
            onDismissError = viewModel::dismissError,
            modifier = Modifier.padding(padding),
        )
    }
}

/** The `opencode.jsonc` editor and the guided templates. */
@Composable
fun ConfigEditorHost(
    directory: String,
    path: String?,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ConfigEditorViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(directory, path) { viewModel.open(directory, path) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.resume() }

    AdminScaffold(
        title = stringResource(R.string.admin_config_editor_title),
        modifier = modifier,
        onNavigateBack = onNavigateBack,
    ) { padding ->
        ConfigEditorScreen(
            state = state,
            onPathChange = viewModel::setPath,
            onDraftChange = viewModel::setDraft,
            onOpenTemplate = viewModel::openTemplate,
            onSave = viewModel::requestSave,
            onConfirm = viewModel::confirmSave,
            onCancel = viewModel::cancelSave,
            onDismissOutcome = viewModel::dismissOutcome,
            onDismissError = viewModel::dismissError,
            modifier = Modifier.padding(padding),
        )
    }
    state.template?.let { draft ->
        TemplateSheet(
            draft = draft,
            outcome = state.templateOutcome,
            onChange = viewModel::updateTemplate,
            onApply = viewModel::applyTemplate,
            onInsert = viewModel::insertTemplate,
            onClose = viewModel::closeTemplate,
        )
    }
}

/** The definition editor for an agent, a command, a skill or `AGENTS.md`. */
@Composable
fun DefinitionHost(
    directory: String,
    kind: DefinitionKind,
    name: String,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DefinitionViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(directory, kind, name) { viewModel.open(directory, kind, name) }
    // No ON_RESUME re-read: this is a draft editor, and re-opening would clobber unsaved edits.
    // The definition is re-read when its identity changes (the effect above), which is the only
    // refresh that does not destroy work.

    AdminScaffold(
        title = stringResource(R.string.admin_definition_title),
        modifier = modifier,
        onNavigateBack = onNavigateBack,
    ) { padding ->
        DefinitionScreen(
            state = state,
            onKindChange = viewModel::setKind,
            onNameChange = viewModel::setName,
            onFrontMatterChange = viewModel::setFrontMatter,
            onBodyChange = viewModel::setBody,
            onSave = viewModel::requestSave,
            onConfirm = viewModel::confirmSave,
            onCancel = viewModel::cancelSave,
            onDismissOutcome = viewModel::dismissOutcome,
            onDismissError = viewModel::dismissError,
            modifier = Modifier.padding(padding),
        )
    }
}

/**
 * The catalog browsers, with the four lists wired from the modules that own them.
 *
 * **The composition is here and not in the feature**, for the reason the class note gives. The agents
 * come from the sessions module's `ServerDataSet.agents` and the other three from
 * `composerCatalogs`, so a `feature/admin` screen cannot reach them — the same boundary [McpHost]
 * crosses for an MCP server's OAuth, and the same reason the app module is the composition root.
 */
@Composable
fun CatalogHost(
    state: CatalogUiState,
    onTabChange: (AdminCatalog) -> Unit,
    onSearchChange: (String) -> Unit,
    onReload: () -> Unit = {},
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AdminScaffold(
        title = stringResource(R.string.admin_catalog_title),
        modifier = modifier,
        onNavigateBack = onNavigateBack,
    ) { padding ->
        CatalogScreen(
            state = state,
            onTabChange = onTabChange,
            onSearchChange = onSearchChange,
            onReload = onReload,
            modifier = Modifier.padding(padding),
        )
    }
}

/** The permissions admin: saved approvals, and a session's own rules. */
@Composable
fun PermissionsHost(
    directory: String,
    projectID: String?,
    sessionId: String?,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PermissionsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(directory, projectID, sessionId) { viewModel.open(directory, projectID, sessionId) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.resume() }

    AdminScaffold(
        title = stringResource(R.string.admin_permissions_title),
        modifier = modifier,
        onNavigateBack = onNavigateBack,
    ) { padding ->
        PermissionsScreen(
            state = state,
            onRequestRemove = viewModel::requestRemove,
            onConfirmRemove = viewModel::confirmRemove,
            onCancelRemove = viewModel::cancelRemove,
            onStartEditing = viewModel::startEditing,
            onCancelEditing = viewModel::cancelEditing,
            onAddRule = viewModel::addRule,
            onRuleChange = viewModel::updateRule,
            onRemoveRule = viewModel::removeRule,
            onSaveRules = viewModel::saveRules,
            onDismissError = viewModel::dismissError,
            modifier = Modifier.padding(padding),
        )
    }
}

/** A session's durable instruction entries. */
@Composable
fun InstructionsHost(
    sessionId: String,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: InstructionsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(sessionId) { viewModel.open(sessionId) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.resume() }

    AdminScaffold(
        title = stringResource(R.string.admin_instructions_title),
        modifier = modifier,
        onNavigateBack = onNavigateBack,
    ) { padding ->
        InstructionsScreen(
            state = state,
            onKeyChange = viewModel::setKey,
            onValueChange = viewModel::setValue,
            onPut = viewModel::put,
            onRequestRemove = viewModel::requestRemove,
            onConfirmRemove = viewModel::confirmRemove,
            onCancelRemove = viewModel::cancelRemove,
            onDismissError = viewModel::dismissError,
            modifier = Modifier.padding(padding),
        )
    }
}

/** Reload, loaded locations, the V1 migration and the update banner. */
@Composable
fun MaintenanceHost(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MaintenanceViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.open() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.resume() }

    AdminScaffold(
        title = stringResource(R.string.admin_maintenance_title),
        modifier = modifier,
        onNavigateBack = onNavigateBack,
    ) { padding ->
        MaintenanceScreen(
            state = state,
            onReload = viewModel::reload,
            onRequestEvict = viewModel::requestEvict,
            onConfirmEvict = viewModel::confirmEvict,
            onCancelEvict = viewModel::cancelEvict,
            onDismissOutcome = viewModel::dismissOutcome,
            onDismissError = viewModel::dismissError,
            modifier = Modifier.padding(padding),
        )
    }
}

/** The usage dashboard: the server's session statistics. Server-wide, so it takes no directory. */
@Serializable
data class UsageRoute(val serverId: String? = null)

@Composable
fun UsageHost(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: InsightsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.open() }
    // Usage numbers change while the screen is away (the server keeps counting), so returning
    // re-reads rather than showing the stale cards.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    AdminScaffold(
        title = stringResource(R.string.admin_usage_title),
        modifier = modifier,
        onNavigateBack = onNavigateBack,
    ) { padding ->
        InsightsScreen(
            state = state,
            onRangeChange = viewModel::setRange,
            onProjectChange = viewModel::setProject,
            onToggleToolDetail = viewModel::setToolDetail,
            onDismissError = viewModel::dismissError,
            modifier = Modifier.padding(padding),
        )
    }
}

/** Quick ask: one stateless answer from the server's model. Server-wide, so it takes no directory. */
@Serializable
data class QuickAskRoute(val serverId: String? = null)

@Composable
fun QuickAskHost(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: QuickAskViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // No ON_RESUME effect: this is a form, not a data screen. There is nothing to re-read, and
    // re-asking on return would fire a model call the user did not make.
    AdminScaffold(
        title = stringResource(R.string.admin_quick_ask_title),
        modifier = modifier,
        onNavigateBack = onNavigateBack,
    ) { padding ->
        QuickAskScreen(
            state = state,
            onPromptChange = viewModel::setPrompt,
            onModelChange = viewModel::setModel,
            onAsk = viewModel::ask,
            onDismissError = viewModel::dismissError,
            modifier = Modifier.padding(padding),
        )
    }
}

/**
 * Pair another device: one code and its QR, for a second client to redeem. Server-wide, so it takes no
 * directory: the server mints the code for itself.
 */
@Serializable
data class PairDeviceRoute(val serverId: String? = null)

@Composable
fun PairDeviceHost(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PairDeviceViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // No ON_RESUME effect: the screen shows one minted pairing code, and re-generating on return
    // would invalidate the code (and its QR) the user may be in the middle of redeeming.
    AdminScaffold(
        title = stringResource(R.string.admin_pair_device_title),
        modifier = modifier,
        onNavigateBack = onNavigateBack,
    ) { padding ->
        PairDeviceScreen(
            state = state,
            onGenerate = viewModel::generate,
            onDismissError = viewModel::dismissError,
            modifier = Modifier.padding(padding),
        )
    }
}

/** The plugin RPC console. Server-wide, so it takes no directory: the call names its own plugin. */
@Serializable
data class RpcConsoleRoute(val serverId: String? = null)

@Composable
fun RpcConsoleHost(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RpcConsoleViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // No ON_RESUME effect: this is a form, not a data screen. There is nothing to re-read, and
    // re-firing the last call on return would run a plugin method the user did not invoke.
    AdminScaffold(
        title = stringResource(R.string.admin_rpc_title),
        modifier = modifier,
        onNavigateBack = onNavigateBack,
    ) { padding ->
        RpcConsoleScreen(
            state = state,
            onRpcIDChange = viewModel::setRpcID,
            onMethodChange = viewModel::setMethod,
            onInputChange = viewModel::setInput,
            onCall = viewModel::call,
            onDismissError = viewModel::dismissError,
            modifier = Modifier.padding(padding),
        )
    }
}

/** The Phase 9 top bar, shared by the seven screens so they cannot drift apart. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdminScaffold(
    title: String,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (androidx.compose.foundation.layout.PaddingValues) -> Unit,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(text = title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.admin_navigate_back))
                    }
                },
                actions = actions,
            )
        },
        content = content,
    )
}
