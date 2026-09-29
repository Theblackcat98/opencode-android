package dev.opencode.android.feature.integrations

import androidx.compose.runtime.Composable
import dev.opencode.android.core.data.integrations.McpServerDraft
import dev.opencode.android.core.model.McpResource
import dev.opencode.android.core.model.McpServer
import dev.opencode.android.core.model.ProviderInfo
import dev.opencode.android.core.model.ConnectionInfo

/**
 * The screens wired to no-ops, for a screenshot.
 *
 * **A separate composable from the real host, and that is the point.** A screenshot needs the
 * rendered screen at a chosen state, not a view model, a Hilt graph and a server; taking the
 * callbacks as parameters means every screen's shape is checkable without any of that, and it means
 * a screenshot can never fire a request by accident.
 *
 * The state is the argument, so one fixture serves every state a test wants to photograph. The
 * values in [IntegrationsScreenshotTest] are placeholders — a real credential typed into the key
 * sheet would end up in a committed PNG.
 */
@Composable
internal fun ConnectScreenFixture(state: ConnectUiState) {
    ConnectScreen(
        state = state,
        onMethodClick = {},
        onCredentialAction = { _: ConnectionInfo.Credential, _: dev.opencode.android.core.data.integrations.CredentialAction -> },
        onConfirmCredential = {},
        onCancelCredential = {},
        onConfirmLabelChange = {},
        onLabelChange = {},
        onKeyChange = {},
        onCodeChange = {},
        onAnswer = { _, _ -> },
        onSubmit = {},
        onSubmitCode = {},
        onCancelAttempt = {},
        onOpenUrl = {},
        onCopyCode = {},
        onDismissConnect = {},
        onWellknownUrlChange = {},
        onAddWellknown = {},
        onDismissError = {},
    )
}

/**
 * The connect sheet, photographed without a `ModalBottomSheet` around it.
 *
 * **A sheet is not screenshottable**, which is why the sheet's content is a separate composable —
 * see [ConnectSheetContent]. Calling the *screen* here would produce a PNG of the account list with
 * the key field nowhere in it, and that is a baseline which asserts nothing.
 */
@Composable
internal fun ConnectSheetFixture(state: ConnectUiState) {
    ConnectSheetContent(
        state = state,
        onLabelChange = {},
        onKeyChange = {},
        onCodeChange = {},
        onAnswer = { _, _ -> },
        onSubmit = {},
        onSubmitCode = {},
        onCancelAttempt = {},
        onOpenUrl = {},
        onCopyCode = {},
        onDismiss = {},
    )
}

/** The add-a-server form, photographed without its sheet's chrome. */
@Composable
internal fun McpAddSheetFixture(state: McpUiState) {
    McpAddSheetContent(
        draft = state.draft,
        problems = state.problems,
        canAdd = state.canAdd,
        onChange = { _: McpServerDraft -> },
        onAdd = {},
    )
}

/** The resource catalog, photographed without its sheet's chrome. */
@Composable
internal fun McpResourcesFixture(state: McpUiState) {
    McpResourceSheetContent(
        resources = state.catalog,
        templates = state.templates,
        onAttach = { _: McpResource -> },
        onClose = {},
    )
}

@Composable
internal fun McpScreenFixture(state: McpUiState) {
    McpScreen(
        state = state,
        onConnect = { _: McpServer -> },
        onDisconnect = { _: McpServer -> },
        onAuthenticate = { _: McpServer -> },
        onRequestRemove = { _: McpServer -> },
        onRemove = {},
        onCancelRemove = {},
        onOpenAdd = {},
        onDraftChange = { _: McpServerDraft -> },
        onAdd = {},
        onDismissAdd = {},
        onOpenResources = {},
        onCloseResources = {},
        onAttach = { _: McpResource -> },
        onDismissError = {},
    )
}

@Composable
internal fun PluginsScreenFixture(state: PluginsUiState) {
    PluginsScreen(
        state = state,
        onToggle = {},
        onCheck = {},
        onUpdate = {},
        onSelectAll = {},
        onClearSelection = {},
        onDismissError = {},
    )
}

@Composable
internal fun ProvidersScreenFixture(state: ProvidersUiState) {
    ProvidersScreen(
        state = state,
        onSearchChange = {},
        onSelect = { _: ProviderInfo? -> },
    )
}

@Composable
internal fun WebSearchScreenFixture(state: WebSearchUiState) {
    WebSearchScreen(
        state = state,
        onSelect = {},
        onQueryChange = {},
        onSearch = {},
        onDismissError = {},
    )
}
