package dev.opencode.android.feature.servers.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.opencode.android.core.data.repository.ServerHealth
import dev.opencode.android.core.data.repository.ServerProfile
import dev.opencode.android.feature.servers.R

/**
 * The server registry: add, edit and remove profiles, choose a default, and see each server's
 * health (plan §6, Phase 1).
 *
 * The list is driven by the repository's Flow, and the health dots by the live connection state, so
 * nothing here has to refresh by hand.
 *
 * **Tapping a row opens the server's home, not its status.** The row is the way into a working
 * server, so it leads to the home and the sessions behind it; the status and settings screen is
 * reached from the row's overflow menu instead. [onHomeClick] therefore has no default and every
 * caller must supply it, so that a new call site cannot silently fall back to the status screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServersScreen(
    onAddServerClick: () -> Unit,
    onServerClick: (String) -> Unit,
    onHomeClick: (String) -> Unit,
    onEditServerClick: (String) -> Unit,
    onPairAgainClick: (String) -> Unit,
    onInspectorClick: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ServersViewModel = hiltViewModel(),
) {
    val servers by viewModel.servers.collectAsStateWithLifecycle()
    var serverToDelete by remember { mutableStateOf<ServerProfile?>(null) }
    val inspectorLabel = stringResource(R.string.inspector_title)

    Scaffold(
        modifier = modifier.testTag(ServersTags.SCREEN),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.servers_title)) },
                actions = {
                    IconButton(
                        onClick = onInspectorClick,
                        modifier = Modifier.testTag(ServersTags.INSPECTOR_BUTTON),
                    ) {
                        Icon(
                            imageVector = Icons.Default.BugReport,
                            contentDescription = inspectorLabel,
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAddServerClick,
                modifier = Modifier.testTag(ServersTags.ADD_BUTTON),
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = stringResource(R.string.servers_add_server),
                )
            }
        },
    ) { padding ->
        if (servers.isEmpty()) {
            EmptyServersView(
                onAddServerClick = onAddServerClick,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(servers, key = { it.id }) { server ->
                    ServerListItem(
                        server = server,
                        onStatus = { onServerClick(server.id) },
                        onHome = { onHomeClick(server.id) },
                        onEdit = { onEditServerClick(server.id) },
                        onSetDefault = { viewModel.setDefaultServer(server.id) },
                        onPairAgain = { onPairAgainClick(server.id) },
                        onDelete = { serverToDelete = server },
                    )
                }
            }
        }
    }

    serverToDelete?.let { server ->
        AlertDialog(
            onDismissRequest = { serverToDelete = null },
            title = { Text(stringResource(R.string.servers_delete_confirm_title)) },
            text = {
                Text(stringResource(R.string.servers_delete_confirm_message, server.name))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteServer(server.id)
                        serverToDelete = null
                    },
                ) {
                    Text(
                        text = stringResource(R.string.servers_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { serverToDelete = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}

object ServersTags {
    const val SCREEN = "servers_screen"
    const val ADD_BUTTON = "servers_add_button"
    const val INSPECTOR_BUTTON = "servers_inspector_button"

    fun item(id: String) = "server_item_$id"
}

@Composable
private fun ServerListItem(
    server: ServerProfile,
    onStatus: () -> Unit,
    onHome: () -> Unit,
    onEdit: () -> Unit,
    onSetDefault: () -> Unit,
    onPairAgain: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val healthLabel = stringResource(server.health.labelRes())
    val itemDescription = stringResource(R.string.servers_item_description, server.name, server.baseUrl, healthLabel)
    val optionsLabel = stringResource(R.string.servers_options)

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onHome)
            .semantics(mergeDescendants = true) { contentDescription = itemDescription }
            .testTag(ServersTags.item(server.id)),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HealthStatusDot(health = server.health)
            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(text = server.name, style = MaterialTheme.typography.titleMedium)
                    if (server.isDefault) DefaultServerBadge()
                    if (server.isCleartext) UnencryptedBadge()
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = server.baseUrl,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = optionsLabel,
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.servers_status)) },
                        onClick = {
                            menuExpanded = false
                            onStatus()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.servers_edit)) },
                        onClick = {
                            menuExpanded = false
                            onEdit()
                        },
                    )
                    if (server.health == ServerHealth.REAUTH_REQUIRED) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.servers_repair)) },
                            onClick = {
                                menuExpanded = false
                                onPairAgain()
                            },
                        )
                    }
                    if (!server.isDefault) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.servers_set_default)) },
                            onClick = {
                                menuExpanded = false
                                onSetDefault()
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = stringResource(R.string.servers_delete),
                                color = MaterialTheme.colorScheme.error,
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyServersView(
    onAddServerClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.servers_empty_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.servers_empty_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(24.dp))
        OnboardingGuideCard()
        Spacer(modifier = Modifier.height(16.dp))
        TextButton(onClick = onAddServerClick) {
            Text(stringResource(R.string.servers_add_server))
        }
    }
}
