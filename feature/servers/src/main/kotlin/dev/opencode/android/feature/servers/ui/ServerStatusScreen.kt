package dev.opencode.android.feature.servers.ui

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.opencode.android.core.data.capability.RouteAvailability
import dev.opencode.android.core.network.ConnectionEventType
import dev.opencode.android.core.network.ConnectionLogEntry
import dev.opencode.android.core.network.ConnectionState
import dev.opencode.android.core.network.DisconnectCause
import dev.opencode.android.core.network.VersionStatus
import dev.opencode.android.feature.servers.R
import java.text.DateFormat
import java.util.Date

/**
 * Everything known about one server: its identity and reachable URLs, the live connection state,
 * the resync counter, and the connection history (plan §6, Phase 1).
 *
 * The history is not decoration: when a phone cannot stay connected, this is the only place that
 * says whether the cause was the network, the credential, or the server dropping the stream.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerStatusScreen(
    onNavigateBack: () -> Unit,
    onEditServer: (String) -> Unit,
    onOpenInspector: (String) -> Unit,
    onPairAgain: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ServerStatusViewModel = hiltViewModel(),
    host: HostLifecycleViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val hostState by host.state.collectAsStateWithLifecycle()
    val profile = uiState.profile

    LaunchedEffect(Unit) { host.open() }

    Scaffold(
        modifier = modifier.testTag(ServerStatusTags.SCREEN),
        topBar = {
            TopAppBar(
                title = { Text(profile?.name ?: stringResource(R.string.status_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.status_navigate_back),
                        )
                    }
                },
                actions = {
                    if (profile != null) {
                        IconButton(
                            onClick = { onOpenInspector(profile.id) },
                            modifier = Modifier.testTag(ServerStatusTags.INSPECTOR_BUTTON),
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
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item(key = "header") {
                    StatusHeaderCard(
                        uiState = uiState,
                        onTest = viewModel::testConnection,
                        onReconnect = viewModel::reconnectNow,
                        onEdit = { onEditServer(profile.id) },
                        onSetDefault = viewModel::setDefault,
                    )
                }

                if (uiState.connectionState.causeRequiresRePair()) {
                    item(key = "reauth") {
                        RePairCard(
                            onPairAgain = { onPairAgain(profile.id) },
                            onEdit = { onEditServer(profile.id) },
                        )
                    }
                }

                uiState.serverInfo?.let { info ->
                    item(key = "info") { ServerInfoCard(uiState = uiState) }
                }

                item(key = "host-lifecycle") {
                    HostLifecycleCard(
                        state = hostState,
                        onRequestShutdown = host::requestShutdown,
                        onCancelShutdown = host::cancelShutdown,
                        onConfirmShutdown = host::confirmShutdown,
                        onHandoff = host::handoff,
                        onDismissNotice = host::dismissNotice,
                    )
                }

                item(key = "resync") { ResyncCard(resyncCount = uiState.resyncCount) }

                item(key = "logs-header") {
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
                    item(key = "logs-empty") {
                        Text(
                            text = stringResource(R.string.status_no_logs),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    items(uiState.connectionLogs, key = { it.id }) { log -> ConnectionLogItem(log = log) }
                }
            }
        }
    }

    uiState.checkError?.let { error ->
        val currentProfile = profile
        ConnectionErrorDialog(
            error = error,
            technicalDetail = uiState.checkTechnicalDetail,
            onPairAgain = {
                viewModel.clearCheckError()
                if (currentProfile != null) onPairAgain(currentProfile.id)
            },
            onDismiss = viewModel::clearCheckError,
            onEditServer = if (currentProfile != null) {
                {
                    viewModel.clearCheckError()
                    onEditServer(currentProfile.id)
                }
            } else {
                null
            },
        )
    }
}

object ServerStatusTags {
    const val SCREEN = "server_status_screen"
    const val INSPECTOR_BUTTON = "status_inspector_button"
    const val TEST_CONNECTION_BUTTON = "status_test_connection_button"
    const val RECONNECT_BUTTON = "status_reconnect_button"
    const val HOST_CARD = "status_host_card"
    const val HOST_SHUTDOWN = "status_host_shutdown"
    const val HOST_SHUTDOWN_CONFIRM = "status_host_shutdown_confirm"
    const val HOST_HANDOFF = "status_host_handoff"
    const val HOST_NOTICE = "status_host_notice"
    const val HOST_ERROR = "status_host_error"
}

private fun ConnectionState.causeRequiresRePair(): Boolean =
    this is ConnectionState.Disconnected && cause == DisconnectCause.AUTHORIZATION_REQUIRED

@Composable
private fun StatusHeaderCard(
    uiState: ServerStatusUiState,
    onTest: () -> Unit,
    onReconnect: () -> Unit,
    onEdit: () -> Unit,
    onSetDefault: () -> Unit,
) {
    val profile = uiState.profile ?: return
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
                Text(text = profile.name, style = MaterialTheme.typography.titleLarge)
                if (profile.isDefault) DefaultServerBadge()
                if (profile.isCleartext) UnencryptedBadge()
            }

            Text(
                text = profile.baseUrl,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text(
                text = stringResource(
                    R.string.status_info_row,
                    stringResource(R.string.state_label),
                    stringResource(uiState.connectionState.labelRes()),
                ),
                style = MaterialTheme.typography.bodySmall,
            )

            val lastSeenAt = profile.lastSeenAt
            if (lastSeenAt != null) {
                Text(
                    text = stringResource(
                        R.string.status_info_row,
                        stringResource(R.string.status_last_seen),
                        ClockFormatter.dateTime(lastSeenAt),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onTest,
                    enabled = !uiState.isTesting,
                    modifier = Modifier
                        .weight(1f)
                        .testTag(ServerStatusTags.TEST_CONNECTION_BUTTON),
                ) {
                    if (uiState.isTesting) {
                        CircularProgressIndicator(
                            modifier = Modifier.height(16.dp),
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

                OutlinedButton(
                    onClick = onReconnect,
                    modifier = Modifier
                        .weight(1f)
                        .testTag(ServerStatusTags.RECONNECT_BUTTON),
                ) {
                    Text(stringResource(R.string.status_reconnect))
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onEdit, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.servers_edit))
                }
                if (!profile.isDefault) {
                    OutlinedButton(onClick = onSetDefault, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.servers_set_default))
                    }
                }
            }

            if (uiState.serverInfo != null && uiState.checkError == null) {
                Text(
                    text = stringResource(R.string.status_test_ok, uiState.serverInfo.version),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/** The re-pair prompt a rotated password leads to (plan §6, Phase 1, exit criteria). */
@Composable
private fun RePairCard(
    onPairAgain: () -> Unit,
    onEdit: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.status_reauth_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.status_reauth_body),
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onPairAgain) { Text(stringResource(R.string.servers_repair)) }
                OutlinedButton(onClick = onEdit) { Text(stringResource(R.string.servers_edit)) }
            }
        }
    }
}

@Composable
private fun ServerInfoCard(uiState: ServerStatusUiState) {
    val info = uiState.serverInfo ?: return
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.status_details_title),
                style = MaterialTheme.typography.titleMedium,
            )
            InfoRow(label = stringResource(R.string.status_version), value = info.version)
            InfoRow(label = stringResource(R.string.status_pid), value = info.pid.toString())
            InfoRow(label = stringResource(R.string.status_tmp_path), value = info.paths.tmp)

            if (info.urls.isNotEmpty()) {
                HorizontalDivider()
                Text(
                    text = stringResource(R.string.status_reachable_urls),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                info.urls.forEach { url ->
                    Text(
                        text = url,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }

            if (uiState.versionStatus == VersionStatus.NEWER_UNTESTED) {
                HorizontalDivider()
                Text(
                    text = stringResource(R.string.status_version_untested),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
    }
}

/**
 * The two persistent-terminal host actions, and nothing else about them (plan §6, "Persistent PTYs").
 *
 * **They are here because they are about the server, not about a session.** `shutdown` ends the
 * terminals of every session on the server, so it belongs on the one page that describes the server as
 * a whole; a session's own UI must never offer it. That is a placement rule, not a preference, and it
 * is the only reason this card is not on the session terminals pane.
 *
 * **`handoff` is offered before `shutdown`, and without a prompt.** A handoff moves the terminals to
 * another instance and destroys nothing; a prompt in front of it would teach the user to dismiss
 * prompts, which is exactly the habit that makes the second button on the shutdown dialog a reflex.
 *
 * **The card says why it may be absent.** Three different reasons produce an unavailable host —
 * this installation's switch is off, the server has no such routes, or the host is stopped — and each
 * has a different fix, so one "unavailable" would send the user to the wrong place.
 */
@Composable
private fun HostLifecycleCard(
    state: HostLifecycleState,
    onRequestShutdown: () -> Unit,
    onCancelShutdown: () -> Unit,
    onConfirmShutdown: () -> Unit,
    onHandoff: () -> Unit,
    onDismissNotice: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(ServerStatusTags.HOST_CARD),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.status_host_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = stringResource(R.string.status_host_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when {
                !state.allowedBySetting -> Text(
                    text = stringResource(R.string.status_host_off),
                    style = MaterialTheme.typography.bodySmall,
                )

                state.availability is RouteAvailability.Absent -> Text(
                    text = stringResource(R.string.status_host_absent),
                    style = MaterialTheme.typography.bodySmall,
                )

                else -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = onHandoff,
                            enabled = state.usable && !state.busy,
                            modifier = Modifier.testTag(ServerStatusTags.HOST_HANDOFF),
                        ) {
                            Text(stringResource(R.string.status_host_handoff))
                        }
                        // The colour is the theme's error colour rather than a second button style:
                        // this is the one destructive action on the page and it should not read as
                        // equivalent to handing the host over.
                        Button(
                            onClick = onRequestShutdown,
                            enabled = state.usable && !state.busy,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError,
                            ),
                            modifier = Modifier.testTag(ServerStatusTags.HOST_SHUTDOWN),
                        ) {
                            Text(stringResource(R.string.status_host_shutdown))
                        }
                    }
                    state.handoff?.let { handoff ->
                        Text(
                            text = stringResource(
                                R.string.status_host_handoff_instance,
                                handoff.instanceID,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                    state.notice?.let { notice ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = stringResource(
                                    when (notice) {
                                        "host-stopped" -> R.string.status_host_stopped
                                        else -> R.string.status_host_handed_off
                                    },
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f)
                                    .testTag(ServerStatusTags.HOST_NOTICE),
                            )
                            TextButton(onClick = onDismissNotice) {
                                Text(stringResource(R.string.status_host_dismiss))
                            }
                        }
                    }
                }
            }
            state.error?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag(ServerStatusTags.HOST_ERROR),
                )
            }
        }
    }

    if (state.shutdownArmed) {
        AlertDialog(
            onDismissRequest = onCancelShutdown,
            title = { Text(stringResource(R.string.status_host_shutdown_title)) },
            text = { Text(stringResource(R.string.status_host_shutdown_body)) },
            confirmButton = {
                TextButton(
                    onClick = onConfirmShutdown,
                    modifier = Modifier.testTag(ServerStatusTags.HOST_SHUTDOWN_CONFIRM),
                ) {
                    Text(stringResource(R.string.status_host_shutdown_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = onCancelShutdown) {
                    Text(stringResource(R.string.status_host_cancel))
                }
            },
        )
    }
}

/**
 * The resync counter.
 *
 * The stream has no replay, so every `server.connected` means a store must re-read its state over
 * REST. Showing the count makes the mechanism visible while debugging a connection.
 */
@Composable
private fun ResyncCard(resyncCount: Long) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.status_resync_count, resyncCount),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = stringResource(R.string.status_resync_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
        Text(text = value, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun ConnectionLogItem(log: ConnectionLogEntry) {
    val timeFormat = ClockFormatter.timeWithMillis(log.timestamp)
    val formattedTime = timeFormat.format(Date(log.timestamp))
    val badgeColor = when (log.type) {
        ConnectionEventType.CONNECTED, ConnectionEventType.RESYNC -> MaterialTheme.colorScheme.primaryContainer

        ConnectionEventType.CONNECTING, ConnectionEventType.HEARTBEAT -> MaterialTheme.colorScheme.secondaryContainer

        ConnectionEventType.ERROR, ConnectionEventType.WATCHDOG_TIMEOUT, ConnectionEventType.EVENTS_DROPPED ->
            MaterialTheme.colorScheme.errorContainer

        ConnectionEventType.DISCONNECTED, ConnectionEventType.EVENT_RECEIVED -> MaterialTheme.colorScheme.surfaceVariant
    }

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
            Box(
                modifier = Modifier
                    .background(badgeColor, RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            ) {
                Text(text = log.type.name, style = MaterialTheme.typography.labelSmall)
            }
            Text(
                text = log.message,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
