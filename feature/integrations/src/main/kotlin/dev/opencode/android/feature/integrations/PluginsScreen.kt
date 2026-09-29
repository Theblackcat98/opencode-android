package dev.opencode.android.feature.integrations

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.opencode.android.core.data.sync.SyncStatus
import dev.opencode.android.core.model.PluginSource
import dev.opencode.android.core.model.PluginState
import dev.opencode.android.core.model.ProviderInfo
import dev.opencode.android.core.model.WebSearchResponse

/**
 * The plugins screen: the list, the check and the update (features doc §18).
 *
 * **Only an outdated *package* can be ticked.** `plugin.update` takes package targets and
 * re-downloads them, so a checkbox on a built-in plugin, a local one or one that is already current
 * is a request the server cannot act on. The row states its reason instead of hiding the control,
 * because a missing checkbox with no explanation is the dead-control defect.
 */
@Composable
fun PluginsScreen(
    state: PluginsUiState,
    onToggle: (String) -> Unit,
    onCheck: () -> Unit,
    onUpdate: () -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        PluginActions(
            state = state,
            onCheck = onCheck,
            onUpdate = onUpdate,
            onSelectAll = onSelectAll,
            onClearSelection = onClearSelection,
        )
        HorizontalDivider()
        LazyColumn(modifier = Modifier.weight(1f)) {
            item {
                when (val status = state.plugins.status) {
                    is SyncStatus.Failed -> ErrorLine(
                        text = status.error.message ?: stringResource(R.string.plugins_loading),
                        onDismiss = onDismissError,
                    )

                    SyncStatus.Loading -> Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator()
                        Text(stringResource(R.string.plugins_loading))
                    }

                    else -> if (state.rows.isEmpty()) EmptyNote(stringResource(R.string.plugins_empty))
                }
                state.status?.let { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                state.error?.let { ErrorLine(text = it.message, onDismiss = onDismissError) }
            }
            items(state.rows, key = { it.key }) { plugin ->
                PluginRow(
                    plugin = plugin,
                    selected = plugin.key in state.selected,
                    enabled = plugin.isUpdatable && !state.updating,
                    onToggle = { onToggle(plugin.key) },
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun PluginActions(
    state: PluginsUiState,
    onCheck: () -> Unit,
    onUpdate: () -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onCheck, enabled = !state.checking && !state.updating) {
                Icon(Icons.Filled.Refresh, contentDescription = null)
                Text(
                    text = stringResource(if (state.checking) R.string.plugins_checking else R.string.plugins_check),
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
            Button(onClick = onUpdate, enabled = state.canUpdate) {
                Text(stringResource(if (state.updating) R.string.plugins_updating else R.string.plugins_update))
            }
        }
        if (state.outdatedCount > 0) {
            Text(
                text = stringResource(R.string.plugins_outdated_badge, state.outdatedCount),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.selected.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onSelectAll) { Text(stringResource(R.string.plugins_select_all)) }
                TextButton(onClick = onClearSelection) { Text(stringResource(R.string.plugins_clear)) }
            }
        }
    }
}

/** One plugin: where it came from, what it extends, and whether it loaded. */
@Composable
private fun PluginRow(plugin: dev.opencode.android.core.model.PluginInfo, selected: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    ListItem(
        headlineContent = { Text(plugin.name) },
        supportingContent = {
            Column {
                Text(
                    text = when (val source = plugin.source) {
                        is PluginSource.Package -> stringResource(R.string.plugins_source_package, source.target)
                        is PluginSource.Local -> stringResource(R.string.plugins_source_local, source.path)
                        PluginSource.Builtin -> stringResource(R.string.plugins_source_builtin)
                        PluginSource.Sdk -> stringResource(R.string.plugins_source_sdk)
                        is PluginSource.Unknown -> stringResource(R.string.plugins_source_builtin)
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                val features = buildList {
                    if (plugin.features.server) add("server")
                    if (plugin.features.tui) add("tui")
                    if (plugin.features.rpc) add("rpc")
                }
                if (features.isNotEmpty()) {
                    Text(
                        text = features.joinToString(", "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (plugin.state is PluginState.Failed) {
                    Text(
                        text = stringResource(R.string.plugins_failed, plugin.failure.orEmpty()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        trailingContent = {
            if (plugin.isUpdatable) {
                Checkbox(checked = selected, onCheckedChange = { onToggle() }, enabled = enabled)
            } else {
                // Nothing to tick, and the reason is on the row: a built-in, a local plugin or one
                // that is already current cannot be updated by this route.
                Text(
                    text = stringResource(R.string.plugins_read_only),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        modifier = Modifier.testTag(IntegrationsTags.plugin(plugin.key)),
    )
}

/**
 * The providers screen, read-only (features doc §9).
 *
 * **No activation switch, and the screen says where the control is instead.** Activation, `settings`,
 * `headers` and `body` are all configuration (features doc §9), so a switch here would either not
 * take effect or would need the experimental config writer that Phase 9 owns. The row links to the
 * editor rather than pretending to be one.
 */
@Composable
fun ProvidersScreen(
    state: ProvidersUiState,
    onSearchChange: (String) -> Unit,
    onSelect: (ProviderInfo?) -> Unit,
    onOpenProvider: (ProviderInfo) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        OutlinedTextField(
            value = state.search,
            onValueChange = onSearchChange,
            label = { Text(stringResource(R.string.providers_search)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        )
        val selected = state.selected
        if (selected != null) {
            ProviderDetail(provider = selected, onClose = { onSelect(null) })
            HorizontalDivider()
        }
        LazyColumn(modifier = Modifier.weight(1f)) {
            item {
                when (val status = state.providers.status) {
                    is SyncStatus.Failed -> ErrorLine(
                        text = status.error.message ?: stringResource(R.string.providers_loading),
                        onDismiss = {},
                    )

                    SyncStatus.Loading -> Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator()
                        Text(stringResource(R.string.providers_loading))
                    }

                    else -> if (state.rows.isEmpty()) EmptyNote(stringResource(R.string.providers_empty))
                }
            }
            items(state.rows, key = { it.id }) { provider ->
                ListItem(
                    headlineContent = { Text(provider.name) },
                    supportingContent = {
                        Column {
                            Text(
                                text = stringResource(R.string.providers_activation, provider.activation),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                text = stringResource(R.string.providers_package, provider.packageName),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                            provider.endpoint?.let {
                                Text(
                                    text = stringResource(R.string.providers_endpoint, it),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                            if (provider.needsIntegration) {
                                Text(
                                    text = stringResource(R.string.providers_needs_integration),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    modifier = Modifier
                        .testTag(IntegrationsTags.provider(provider.id))
                        .clickable { onOpenProvider(provider) },
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun ProviderDetail(provider: ProviderInfo, onClose: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text = provider.name, style = MaterialTheme.typography.titleLarge)
        DetailLine("id", provider.id)
        DetailLine("activation", provider.activation)
        DetailLine("package", provider.packageName)
        provider.canonical?.let { DetailLine("canonical", it) }
        provider.endpoint?.let { DetailLine("baseURL", it) }
        provider.settings.transport?.let { DetailLine("transport", it) }
        provider.settings.compaction?.let { DetailLine("compaction", it) }
        // The whole point of the detail: why there is no switch here.
        Text(
            text = stringResource(R.string.providers_config_only),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onClose) { Text(stringResource(R.string.connect_cancel)) }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = label, style = MaterialTheme.typography.labelMedium)
        SelectionContainer {
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

/**
 * The web-search screen: the providers and a test query (features doc §24).
 *
 * **The answer names the provider that ran, and the results are not links the app opens.** A
 * search result's title and content are prose from a web page the server fetched, so they are shown
 * as text and the address is shown as text too — a result row is not a button, because a
 * result-set is untrusted input and a tap should not be a navigation this client arranged.
 */
@Composable
fun WebSearchScreen(
    state: WebSearchUiState,
    onSelect: (String?) -> Unit,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            androidx.compose.material3.FilterChip(
                selected = state.selected == null,
                onClick = { onSelect(null) },
                label = { Text(stringResource(R.string.websearch_default)) },
            )
            state.rows.forEach { provider ->
                androidx.compose.material3.FilterChip(
                    selected = state.selected == provider.id,
                    onClick = { onSelect(provider.id) },
                    label = { Text(provider.name) },
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = state.query,
                onValueChange = onQueryChange,
                label = { Text(stringResource(R.string.websearch_query)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = onSearch, enabled = state.canSearch) {
                Text(stringResource(if (state.searching) R.string.websearch_searching else R.string.websearch_run))
            }
        }
        state.error?.let { ErrorLine(text = it.message, onDismiss = onDismissError) }
        state.response?.let { response ->
            WebSearchResults(response = response)
        }
        if (state.rows.isEmpty() && state.providers.hasValue) {
            EmptyNote(stringResource(R.string.websearch_empty))
        }
    }
}

@Composable
private fun WebSearchResults(response: WebSearchResponse) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Text(
                text = stringResource(R.string.websearch_served_by, response.providerID),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
        if (response.results.isEmpty()) {
            item { EmptyNote(stringResource(R.string.websearch_no_results)) }
        }
        items(response.results, key = { it.url }) { result ->
            ListItem(
                headlineContent = { Text(result.title ?: result.url) },
                supportingContent = {
                    Column {
                        SelectionContainer {
                            Text(
                                text = result.url,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                        result.content?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 4,
                            )
                        }
                    }
                },
            )
            HorizontalDivider()
        }
    }
}
