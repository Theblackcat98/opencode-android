package dev.opencode.android.feature.sessions.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.opencode.android.core.data.insights.SessionLogEntry
import dev.opencode.android.feature.sessions.R

/**
 * The event history, full screen (matrix H8).
 *
 * Each entry shows its event name, when it has one, and its fields as written. The footer says which of
 * three things is true, because the replay has three ends: still running, caught up at `log.synced`, or
 * stopped by an error. Only the second is "caught up".
 */
@Composable
fun SessionLogDialog(
    state: SessionLogUiState,
    onClose: () -> Unit,
    onDismissError: () -> Unit,
) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.event_history_title), style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = onClose) { Text(stringResource(R.string.event_history_close)) }
                }

                state.error?.let { error ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.fillMaxWidth().padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(error.message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            TextButton(onClick = onDismissError) { Text(stringResource(R.string.action_dismiss)) }
                        }
                    }
                }

                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.entries) { entry -> SessionLogRow(entry) }

                    item {
                        Footer(state)
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionLogRow(entry: SessionLogEntry) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            entry.event?.let { event ->
                Text(event, style = MaterialTheme.typography.titleSmall)
            }
            Text(
                entry.fields.toString(),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun Footer(state: SessionLogUiState) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            state.replaying -> {
                CircularProgressIndicator()
                Text(stringResource(R.string.event_history_replaying), style = MaterialTheme.typography.bodyMedium)
            }

            state.synced -> Text(
                stringResource(R.string.event_history_synced),
                style = MaterialTheme.typography.bodyMedium,
            )

            state.error != null || state.entries.isNotEmpty() -> Text(
                stringResource(R.string.event_history_stopped),
                style = MaterialTheme.typography.bodyMedium,
            )

            else -> Text(
                stringResource(R.string.event_history_empty),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
