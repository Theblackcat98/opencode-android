package dev.opencode.android.feature.integrations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.opencode.android.core.data.integrations.McpConfigProblem
import dev.opencode.android.core.data.sync.SyncStatus
import dev.opencode.android.core.model.McpProtocol
import dev.opencode.android.core.model.McpResource
import dev.opencode.android.core.model.McpServer
import dev.opencode.android.core.model.McpStatus
import dev.opencode.android.core.model.PluginSource
import dev.opencode.android.core.model.PluginState

/**
 * The MCP screen: the server list with status, the runtime writes, and the resource catalog.
 *
 * **The runtime buttons are gated on two independent things and the row says which is missing.**
 * [McpUiState.runtimeUsable] is the switch *and* the capability probe, and the reason a server has
 * no connect button is shown rather than left as an absence — a control that is not there and not
 * explained is the dead-button defect.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McpScreen(
    state: McpUiState,
    onConnect: (McpServer) -> Unit,
    onDisconnect: (McpServer) -> Unit,
    onAuthenticate: (McpServer) -> Unit,
    onRequestRemove: (McpServer) -> Unit,
    onRemove: () -> Unit,
    onCancelRemove: () -> Unit,
    onOpenAdd: () -> Unit,
    onDraftChange: (dev.opencode.android.core.data.integrations.McpServerDraft) -> Unit,
    onAdd: () -> Unit,
    onDismissAdd: () -> Unit,
    onOpenResources: () -> Unit,
    onCloseResources: () -> Unit,
    onAttach: (McpResource) -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        if (state.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        LazyColumn(modifier = Modifier.weight(1f)) {
            item {
                when (val status = state.servers.status) {
                    is SyncStatus.Failed -> ErrorLine(
                        text = status.error.message ?: stringResource(R.string.mcp_error),
                        onDismiss = onDismissError,
                    )

                    SyncStatus.Loading -> Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator()
                        Text(stringResource(R.string.mcp_loading))
                    }

                    else -> if (state.rows.isEmpty()) EmptyNote(stringResource(R.string.mcp_empty))
                }
            }
            items(state.rows, key = { it.name }) { server ->
                McpServerView(
                    server = server,
                    runtimeUsable = state.runtimeUsable,
                    busy = state.busy,
                    onConnect = { onConnect(server) },
                    onDisconnect = { onDisconnect(server) },
                    onAuthenticate = { onAuthenticate(server) },
                    onRemove = { onRequestRemove(server) },
                )
                HorizontalDivider()
            }
            item {
                McpActions(
                    runtimeUsable = state.runtimeUsable,
                    hasResources = state.catalog.isNotEmpty() || state.templates.isNotEmpty(),
                    onOpenResources = onOpenResources,
                    onOpenAdd = onOpenAdd,
                )
            }
        }
    }

    state.removeTarget?.let { target ->
        AlertDialog(
            onDismissRequest = onCancelRemove,
            title = { Text(stringResource(R.string.mcp_remove_title)) },
            text = { Text(stringResource(R.string.mcp_remove_body, target)) },
            confirmButton = { TextButton(onClick = onRemove) { Text(stringResource(R.string.connect_confirm)) } },
            dismissButton = {
                TextButton(onClick = onCancelRemove) { Text(stringResource(R.string.connect_cancel)) }
            },
        )
    }

    if (state.addSheetOpen) {
        ModalBottomSheet(onDismissRequest = onDismissAdd) {
            McpAddSheetContent(
                draft = state.draft,
                problems = state.problems,
                canAdd = state.canAdd,
                onChange = onDraftChange,
                onAdd = onAdd,
            )
        }
    }

    if (state.resourcesOpen) {
        ModalBottomSheet(onDismissRequest = onCloseResources) {
            McpResourceSheetContent(
                resources = state.catalog,
                templates = state.templates,
                onAttach = onAttach,
                onClose = onCloseResources,
            )
        }
    }
}

/** One server, its status in words, and the actions that server can take. */
@Composable
private fun McpServerView(
    server: McpServer,
    runtimeUsable: Boolean,
    busy: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onAuthenticate: () -> Unit,
    onRemove: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag(IntegrationsTags.server(server.name)),
    ) {
        Text(text = server.name, style = MaterialTheme.typography.titleMedium)
        Text(
            text = statusText(server),
            style = MaterialTheme.typography.bodySmall,
            color = statusColour(server),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                // A `needs_auth` server is fixed by opening the *integration's* login, which is only
                // possible when the server named one. Without it there is no flow this client could
                // start, so there is no button rather than a button that fails.
                server.isDisabledForAuth && server.integrationID != null ->
                    TextButton(onClick = onAuthenticate) { Text(stringResource(R.string.mcp_sign_in)) }

                server.isConnected -> TextButton(
                    onClick = onDisconnect,
                    enabled = runtimeUsable && !busy,
                ) {
                    Text(stringResource(R.string.mcp_disconnect))
                }

                else -> TextButton(onClick = onConnect, enabled = runtimeUsable && !busy) {
                    Text(stringResource(R.string.mcp_connect))
                }
            }
            TextButton(onClick = onRemove, enabled = runtimeUsable && !busy) {
                Text(stringResource(R.string.mcp_remove))
            }
        }
        if (!runtimeUsable) {
            Text(
                text = stringResource(R.string.mcp_runtime_off),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The status in words, including the server's own error where it gave one. */
@Composable
private fun statusText(server: McpServer): String = when (val status = server.status) {
    is McpStatus.Connected -> stringResource(R.string.mcp_status_connected)
    is McpStatus.Pending -> stringResource(R.string.mcp_status_pending)
    is McpStatus.Disabled -> stringResource(R.string.mcp_status_disabled)
    is McpStatus.Failed -> stringResource(R.string.mcp_status_failed, status.error)
    is McpStatus.NeedsAuth -> stringResource(R.string.mcp_status_needs_auth, status.error)
    is McpStatus.Unknown -> stringResource(R.string.mcp_status_unknown, status.declaredStatus.orEmpty())
}

/** A failed or `needs_auth` server is the one the user has to act on, so it is the coloured one. */
@Composable
private fun statusColour(server: McpServer) = when (server.status) {
    is McpStatus.Failed, is McpStatus.NeedsAuth -> MaterialTheme.colorScheme.error
    is McpStatus.Connected -> MaterialTheme.colorScheme.onSurfaceVariant
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** The actions under the list: the resource browser and "add a server". */
@Composable
private fun McpActions(
    runtimeUsable: Boolean,
    hasResources: Boolean,
    onOpenResources: () -> Unit,
    onOpenAdd: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TextButton(onClick = onOpenResources, enabled = hasResources) {
            Text(stringResource(R.string.mcp_resources))
        }
        TextButton(onClick = onOpenAdd, enabled = runtimeUsable) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Text(stringResource(R.string.mcp_add), modifier = Modifier.padding(start = 4.dp))
        }
    }
}

/**
 * The add-a-server form, which builds the wire union through
 * [dev.opencode.android.core.data.integrations.McpConfigForm].
 *
 * **The form never assembles a config itself.** It collects a draft, and the only thing that turns a
 * draft into a `local` or `remote` union is the same object the store calls, so a form that collected
 * the wrong fields would fail the store's validation rather than send a request the server rejects.
 * The add-a-server sheet's content, without the sheet's chrome, so it can be screenshotted.
 *
 * A `ModalBottomSheet` renders into a window of its own, which a Roborazzi capture does not see: the
 * first recording of this screen produced a PNG of the MCP list with the form nowhere in it. The
 * content is therefore a plain composable the host wraps, exactly as P7 arranged its terminal.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McpAddSheetContent(
    draft: dev.opencode.android.core.data.integrations.McpServerDraft,
    problems: List<McpConfigProblem>,
    canAdd: Boolean,
    onChange: (dev.opencode.android.core.data.integrations.McpServerDraft) -> Unit,
    onAdd: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.mcp_add), style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(
            value = draft.name,
            onValueChange = { onChange(draft.copy(name = it)) },
            label = { Text(stringResource(R.string.mcp_add_name)) },
            isError = McpConfigProblem.NAME_REQUIRED in problems,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.selectableGroup()) {
            dev.opencode.android.core.data.integrations.McpServerDraft.Kind.entries.forEach { kind ->
                FilterChip(
                    selected = draft.kind == kind,
                    onClick = { onChange(draft.copy(kind = kind)) },
                    label = { Text(stringResource(kind.labelRes())) },
                )
            }
        }
        if (draft.kind == dev.opencode.android.core.data.integrations.McpServerDraft.Kind.LOCAL) {
            OutlinedTextField(
                value = draft.command,
                onValueChange = { onChange(draft.copy(command = it)) },
                label = { Text(stringResource(R.string.mcp_add_command)) },
                supportingText = { Text(stringResource(R.string.mcp_add_command_help)) },
                isError = McpConfigProblem.COMMAND_REQUIRED in problems,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = draft.cwd,
                onValueChange = { onChange(draft.copy(cwd = it)) },
                label = { Text(stringResource(R.string.mcp_add_cwd)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = draft.environment,
                onValueChange = { onChange(draft.copy(environment = it)) },
                label = { Text(stringResource(R.string.mcp_add_env)) },
                supportingText = { Text(stringResource(R.string.mcp_add_pairs_help)) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
            )
        } else {
            OutlinedTextField(
                value = draft.url,
                onValueChange = { onChange(draft.copy(url = it)) },
                label = { Text(stringResource(R.string.mcp_add_url)) },
                supportingText = {
                    Text(
                        stringResource(
                            if (McpConfigProblem.URL_NOT_HTTP in problems) {
                                R.string.mcp_add_url_bad
                            } else {
                                R.string.mcp_add_url_help
                            },
                        ),
                    )
                },
                isError = McpConfigProblem.URL_REQUIRED in problems || McpConfigProblem.URL_NOT_HTTP in problems,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = draft.headers,
                onValueChange = { onChange(draft.copy(headers = it)) },
                label = { Text(stringResource(R.string.mcp_add_headers)) },
                supportingText = { Text(stringResource(R.string.mcp_add_pairs_help)) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
            )
        }
        TimeoutFields(draft = draft, onChange = onChange, invalid = McpConfigProblem.NOT_A_POSITIVE_INTEGER in problems)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = draft.codemode, onCheckedChange = { onChange(draft.copy(codemode = it)) })
            Text(
                text = stringResource(R.string.mcp_add_codemode),
                modifier = Modifier.padding(start = 12.dp),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            McpProtocol.ALL.forEach { protocol ->
                FilterChip(
                    selected = draft.protocol == protocol,
                    onClick = { onChange(draft.copy(protocol = protocol)) },
                    label = { Text(protocol) },
                )
            }
        }
        Text(
            text = stringResource(R.string.mcp_add_runtime_only),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onAdd, enabled = canAdd) { Text(stringResource(R.string.mcp_add_confirm)) }
    }
}

/** The three timeout boxes, blank meaning "the server's default". */
@Composable
private fun TimeoutFields(
    draft: dev.opencode.android.core.data.integrations.McpServerDraft,
    onChange: (dev.opencode.android.core.data.integrations.McpServerDraft) -> Unit,
    invalid: Boolean,
) {
    val labels = listOf(
        R.string.mcp_timeout_startup to draft.startupTimeout,
        R.string.mcp_timeout_catalog to draft.catalogTimeout,
        R.string.mcp_timeout_execution to draft.executionTimeout,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.forEachIndexed { index, (label, value) ->
            OutlinedTextField(
                value = value,
                onValueChange = { text ->
                    onChange(
                        when (index) {
                            0 -> draft.copy(startupTimeout = text)
                            1 -> draft.copy(catalogTimeout = text)
                            else -> draft.copy(executionTimeout = text)
                        },
                    )
                },
                label = { Text(stringResource(label)) },
                isError = invalid,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * The resource catalog, and the "attach to prompt" action (plan §6).
 *
 * **Attaching is a selection, not a write.** The resource is a URI the server already knows; what
 * the user wants is for it to be in the next prompt, and the composer owns prompt contents. So the
 * pick is published and the host hands it over, which is the same arrangement the composer's own
 * attach flows use.
 * The resource catalog's content, without the sheet's chrome, so it can be screenshotted.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McpResourceSheetContent(
    resources: List<McpResource>,
    templates: List<dev.opencode.android.core.model.McpResourceTemplate>,
    onAttach: (McpResource) -> Unit,
    onClose: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(stringResource(R.string.mcp_resources), style = MaterialTheme.typography.titleLarge)
        if (resources.isEmpty() && templates.isEmpty()) {
            EmptyNote(stringResource(R.string.mcp_resources_empty))
        }
        resources.forEach { resource ->
            ListItem(
                headlineContent = { Text(resource.name) },
                supportingContent = {
                    Column {
                        Text(
                            resource.uri,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                        resource.description?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                },
                trailingContent = {
                    TextButton(
                        onClick = { onAttach(resource) },
                        modifier = Modifier.testTag(
                            IntegrationsTags.server("resource:${resource.server}/${resource.name}"),
                        ),
                    ) {
                        Icon(Icons.Filled.Link, contentDescription = null)
                        Text(stringResource(R.string.mcp_attach), modifier = Modifier.padding(start = 4.dp))
                    }
                },
            )
            HorizontalDivider()
        }
        templates.forEach { template ->
            ListItem(
                headlineContent = { Text(template.name) },
                supportingContent = {
                    Text(
                        template.uriTemplate,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                },
            )
            HorizontalDivider()
        }
        TextButton(onClick = onClose) { Text(stringResource(R.string.connect_cancel)) }
    }
}

private fun dev.opencode.android.core.data.integrations.McpServerDraft.Kind.labelRes() = when (this) {
    dev.opencode.android.core.data.integrations.McpServerDraft.Kind.LOCAL -> R.string.mcp_kind_local
    dev.opencode.android.core.data.integrations.McpServerDraft.Kind.REMOTE -> R.string.mcp_kind_remote
}
