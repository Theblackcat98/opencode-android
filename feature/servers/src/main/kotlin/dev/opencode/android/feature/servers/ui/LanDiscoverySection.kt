package dev.opencode.android.feature.servers.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.opencode.android.core.designsystem.text.SyncedTextField
import dev.opencode.android.core.designsystem.theme.OpenCodeThemeExtras
import dev.opencode.android.core.network.LanFound
import dev.opencode.android.feature.servers.R

/**
 * The "find servers on this network" section of the manual tab.
 *
 * It is stateless: [state] comes from [LanDiscoveryViewModel] and every change goes back out through a
 * lambda. The switch is a plain row, so a screen reader hears its label, its help and its on/off state
 * as one control. A scan that is running shows Stop in place of Scan, and a found server is a row that
 * fills the Server URL field above when tapped.
 */
@Composable
internal fun LanDiscoverySection(
    state: LanDiscoveryUiState,
    onEnabledChange: (Boolean) -> Unit,
    onPortsChange: (String) -> Unit,
    onScan: () -> Unit,
    onStop: () -> Unit,
    onUseServer: (LanFound) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(OpenCodeThemeExtras.spacing.md)) {
        Text(
            text = stringResource(R.string.lan_section_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )

        val label = stringResource(R.string.lan_switch_label)
        val help = stringResource(R.string.lan_switch_help)
        val switchDescription = stringResource(R.string.lan_switch_description, label, help)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .toggleable(value = state.enabled, role = Role.Switch, onValueChange = onEnabledChange)
                .semantics { contentDescription = switchDescription },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(OpenCodeThemeExtras.spacing.md),
        ) {
            Switch(checked = state.enabled, onCheckedChange = null)
            Column(modifier = Modifier.weight(1f)) {
                Text(text = label, style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = help,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (state.enabled) {
            val invalidPorts = state.invalidPorts
            SyncedTextField(
                value = state.portsInput,
                onValueChange = onPortsChange,
                label = { Text(stringResource(R.string.lan_ports_label)) },
                placeholder = { Text(stringResource(R.string.lan_ports_hint)) },
                supportingText = {
                    Text(
                        text = if (invalidPorts.isEmpty()) {
                            stringResource(R.string.lan_ports_help)
                        } else {
                            stringResource(R.string.lan_ports_invalid, invalidPorts.joinToString(", "))
                        },
                    )
                },
                isError = invalidPorts.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !state.scanning,
            )

            if (state.scanning) {
                OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.lan_stop_button))
                }
            } else {
                Button(onClick = onScan, enabled = state.canScan, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.lan_scan_button))
                }
            }

            val status = when {
                state.scanning -> stringResource(R.string.lan_progress, state.checked, state.total, state.found.size)

                state.notice == LanNotice.NO_PRIVATE_ADDRESS -> stringResource(R.string.lan_no_private_address)

                state.notice == LanNotice.STOPPED -> stringResource(R.string.lan_stopped, state.checked, state.total)

                state.notice == LanNotice.FINISHED && state.found.isEmpty() ->
                    stringResource(R.string.lan_finished_none)

                state.notice == LanNotice.FINISHED -> stringResource(R.string.lan_finished_found, state.found.size)

                else -> null
            }
            if (status != null) {
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (state.found.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.lan_found_title),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.semantics { heading() },
                )
                state.found.forEach { server ->
                    FoundServerRow(server = server, onUse = { onUseServer(server) })
                }
            }
        }
    }
}

/** One found address. It is a whole row, at least 48dp tall, and says what tapping it does. */
@Composable
private fun FoundServerRow(server: LanFound, onUse: () -> Unit) {
    val useLabel = stringResource(R.string.lan_use_server, server.url)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClickLabel = useLabel, onClick = onUse)
            .padding(horizontal = OpenCodeThemeExtras.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.lan_found_address, server.host, server.port),
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}
