package dev.opencode.android.feature.servers.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.opencode.android.core.network.ConnectionEventType
import dev.opencode.android.core.network.ConnectionLogEntry
import dev.opencode.android.feature.servers.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerStatusScreen(
    onNavigateBack: () -> Unit,
    onOpenInspector: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ServerStatusViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val profile = uiState.profile

    Scaffold(
        modifier = modifier.testTag("server_status_screen"),
        topBar = {
            TopAppBar(
                title = { Text(profile?.name ?: stringResource(R.string.status_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                actions = {
                    if (profile != null) {
                        IconButton(
                            onClick = { onOpenInspector(profile.id) },
                            modifier = Modifier.testTag("status_inspector_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Default.BugReport,
                                contentDescription = stringResource(R.string.inspector_title),
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (profile == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Header Card
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        ),
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                HealthStatusDot(health = profile.health)
                                Text(
                                    text = profile.name,
                                    style = MaterialTheme.typography.titleLarge,
                                )
                                if (profile.isDefault) {
                                    DefaultServerBadge()
                                }
                                if (profile.isCleartext) {
                                    UnencryptedBadge()
                                }
                            }

                            Text(
                                text = profile.baseUrl,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = viewModel::testConnection,
                                    enabled = !uiState.isTesting,
                                    modifier = Modifier.weight(1f),
                                ) {
                                    if (uiState.isTesting) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp,
                                            color = MaterialTheme.colorScheme.onPrimary,
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                    } else {
                                        Icon(Icons.Default.Refresh, contentDescription = null)
                                        Spacer(modifier = Modifier.width(6.dp))
                                    }
                                    Text(stringResource(R.string.status_test_connection))
                                }

                                if (!profile.isDefault) {
                                    OutlinedButton(
                                        onClick = viewModel::setDefault,
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Text(stringResource(R.string.servers_set_default))
                                    }
                                }
                            }

                            uiState.testMessage?.let { msg ->
                                Text(
                                    text = msg,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (msg.contains("successfully", ignoreCase = true)) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.error
                                    },
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                        }
                    }
                }

                // Server Technical Info (from GET /api/info)
                uiState.serverInfo?.let { info ->
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = "Server Details",
                                    style = MaterialTheme.typography.titleMedium,
                                )

                                InfoRow(label = stringResource(R.string.status_version), value = info.version)
                                InfoRow(label = stringResource(R.string.status_pid), value = info.pid.toString())
                                InfoRow(label = stringResource(R.string.status_tmp_path), value = info.paths.tmp)

                                if (info.urls.isNotEmpty()) {
                                    Text(
                                        text = stringResource(R.string.status_reachable_urls) + ":",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    info.urls.forEach { url ->
                                        Text(
                                            text = "• $url",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontFamily = FontFamily.Monospace,
                                            modifier = Modifier.padding(start = 8.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Connection History Log
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.status_connection_logs),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        if (uiState.connectionLogs.isNotEmpty()) {
                            TextButton(onClick = viewModel::clearLogs) {
                                Text(stringResource(R.string.status_clear_logs))
                            }
                        }
                    }
                }

                if (uiState.connectionLogs.isEmpty()) {
                    item {
                        Text(
                            text = "No connection events logged yet.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    items(uiState.connectionLogs, key = { it.id }) { log ->
                        ConnectionLogItem(log = log)
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun ConnectionLogItem(log: ConnectionLogEntry) {
    val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    val formattedTime = timeFormat.format(Date(log.timestamp))

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        ),
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = formattedTime,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            val badgeColor = when (log.type) {
                ConnectionEventType.CONNECTED, ConnectionEventType.RESYNC -> MaterialTheme.colorScheme.primaryContainer
                ConnectionEventType.CONNECTING, ConnectionEventType.HEARTBEAT -> MaterialTheme.colorScheme.secondaryContainer
                ConnectionEventType.ERROR, ConnectionEventType.WATCHDOG_TIMEOUT -> MaterialTheme.colorScheme.errorContainer
                ConnectionEventType.DISCONNECTED, ConnectionEventType.EVENT_RECEIVED -> MaterialTheme.colorScheme.surfaceVariant
            }

            Box(
                modifier = Modifier
                    .background(badgeColor, RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            ) {
                Text(
                    text = log.type.name,
                    style = MaterialTheme.typography.labelSmall,
                )
            }

            Text(
                text = log.message,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
