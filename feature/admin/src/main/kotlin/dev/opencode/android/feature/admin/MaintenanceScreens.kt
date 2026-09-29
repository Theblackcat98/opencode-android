package dev.opencode.android.feature.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.opencode.android.core.data.config.ConfigValues
import dev.opencode.android.core.model.InstructionEntry
import dev.opencode.android.core.model.LoadedLocation
import dev.opencode.android.core.model.MigrationStatus

/**
 * The maintenance screen (plan §6, "Maintenance").
 *
 * **Reload says what it cancels.** `location.reload` shuts down and rebuilds every loaded location, and
 * the route's own description says pending permissions and forms are cancelled while running sessions
 * continue. A button labelled "reload" that silently discards the answer a user was about to give is the
 * worst version of this, so the label carries the consequence.
 *
 * **Evicting names the directory and says the caches come back.** Nothing is lost — the route's
 * description is "dispose the cached services so its next use boots them fresh", and a resync rebuilds
 * them — but work in flight for that location is interrupted and there is no undo, so the confirmation
 * is explicit and the list is re-read afterwards rather than the row being removed optimistically.
 */
@Composable
fun MaintenanceScreen(
    state: MaintenanceUiState,
    onReload: () -> Unit,
    onRequestEvict: (LoadedLocation) -> Unit,
    onConfirmEvict: () -> Unit,
    onCancelEvict: () -> Unit,
    onDismissOutcome: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        LazyColumn(modifier = Modifier.weight(1f)) {
            state.updateAvailable?.let { version ->
                item(key = "update") { UpdateBanner(version = version) }
            }
            item(key = "reload") {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Button(
                        onClick = onReload,
                        enabled = !state.reloading,
                        modifier = Modifier.testTag(AdminTags.RELOAD_LOCATIONS),
                    ) { Text(stringResource(R.string.admin_reload_locations)) }
                    Text(
                        text = stringResource(R.string.admin_reload_help),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item(key = "migration") { MigrationCard(state = state) }
            item(key = "locations-heading") {
                SectionHeading(
                    stringResource(R.string.admin_loaded_locations),
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            if (state.loading && !state.locationsLoaded) {
                item(key = "locations-loading") {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator()
                        Text(stringResource(R.string.admin_locations_loading))
                    }
                }
            }
            state.error?.let { error ->
                item(key = "error") { ErrorLine(error = error, onDismiss = onDismissError) }
            }
            if (state.locationsLoaded && state.locations.isEmpty()) {
                item(key = "locations-empty") {
                    Text(
                        text = stringResource(R.string.admin_locations_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            items(items = state.locations, key = { it.directory }) { location ->
                ListItem(
                    headlineContent = {
                        Text(
                            text = shortPath(location.directory),
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    },
                    trailingContent = {
                        TextButton(
                            onClick = { onRequestEvict(location) },
                            modifier = Modifier.testTag(AdminTags.evict(location.directory)),
                        ) { Text(stringResource(R.string.admin_location_evict)) }
                    },
                    modifier = Modifier.testTag(AdminTags.location(location.directory)),
                )
            }
        }
        state.outcome?.let { outcome ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = stringResource(outcomeMessage(outcome)), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = onDismissOutcome) { Text(stringResource(R.string.admin_dismiss)) }
            }
        }
    }
    state.evictTarget?.let { target ->
        AlertDialog(
            onDismissRequest = onCancelEvict,
            title = { Text(stringResource(R.string.admin_evict_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(shortPath(target.directory), fontFamily = FontFamily.Monospace)
                    Text(stringResource(R.string.admin_evict_body), style = MaterialTheme.typography.bodySmall)
                    Text(
                        text = stringResource(R.string.admin_evict_consequence),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = onConfirmEvict, modifier = Modifier.testTag(AdminTags.CONFIRM_EVICT)) {
                    Text(stringResource(R.string.admin_evict_confirm))
                }
            },
            dismissButton = { TextButton(onClick = onCancelEvict) { Text(stringResource(R.string.admin_cancel)) } },
        )
    }
}

private fun outcomeMessage(outcome: String): Int = when (outcome) {
    "reloaded" -> R.string.admin_reload_done
    "evicted" -> R.string.admin_evict_done
    else -> R.string.admin_done
}

/**
 * The V1 migration's progress, and the only progress bar in the app that is not indeterminate.
 *
 * **`0/0` is never drawn.** `MigrationStatus.Running`'s `numerator` and `denominator` are individually
 * nullable and the server sends the label alone before it has counted anything, so a bar needs both and
 * a bar without both is a bar that lies about how far along it is.
 */
@Composable
private fun MigrationCard(state: MaintenanceUiState) {
    val status = state.migration
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Text(
            text = stringResource(R.string.admin_migration_title),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.semantics { heading() },
        )
        if (!state.migrationLoaded) {
            Text(
                text = stringResource(R.string.admin_migration_loading),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return
        }
        when (status) {
            is MigrationStatus.Running -> {
                Text(text = status.progress.label, style = MaterialTheme.typography.bodyMedium)
                val numerator = status.progress.numerator
                val denominator = status.progress.denominator
                if (numerator != null && denominator != null && denominator > 0) {
                    Text(
                        text = stringResource(R.string.admin_migration_count, numerator, denominator),
                        style = MaterialTheme.typography.labelSmall,
                    )
                    LinearProgressIndicator(
                        progress = { (numerator.toFloat() / denominator.toFloat()).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Text(
                        text = stringResource(R.string.admin_migration_no_count),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            is MigrationStatus.Completed -> Text(
                text = stringResource(R.string.admin_migration_completed),
                style = MaterialTheme.typography.bodyMedium,
            )

            is MigrationStatus.Required -> Text(
                text = stringResource(R.string.admin_migration_required),
                style = MaterialTheme.typography.bodyMedium,
            )

            is MigrationStatus.Error -> {
                Text(
                    text = stringResource(R.string.admin_migration_error),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                // The server's own text, which is the only thing that says what went wrong.
                Text(text = status.message, style = MaterialTheme.typography.bodySmall)
            }

            null, is MigrationStatus.Unknown -> Text(
                text = stringResource(R.string.admin_migration_unknown),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The update banner, with the command the user has to run.
 *
 * **The command is the payload.** The server is a binary on another machine and this app can only
 * observe that a newer version exists — so a banner that said "an update is available" and nothing else
 * would be an announcement with no action attached, and the plan's exit criterion is a banner *with the
 * host upgrade command*. The version is from `installation.update-available`, which is the event the
 * server emits.
 */
@Composable
private fun UpdateBanner(version: String) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(R.string.admin_update_title, version),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.semantics { heading() },
            )
            Text(text = stringResource(R.string.admin_update_help), style = MaterialTheme.typography.bodySmall)
            Text(
                text = UPGRADE_COMMAND,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

/**
 * The host upgrade command, as a constant rather than a formatted string.
 *
 * **`@opencode/cli@latest`, and the pinned form for a reproducible upgrade.** The second one matters:
 * OpenCode's release cadence is fast (2.0.0 to 2.0.18 was covered in this project's first phases) and
 * a user who follows a floating tag on a machine that is mid-session is the drift scenario plan §9
 * names. Both are shown so a user can choose.
 */
const val UPGRADE_COMMAND: String = "bun add -g @opencode/cli@latest   # or: bun add -g @opencode/cli@<version>"

/**
 * A session's durable instruction entries (features doc §26; plan §6).
 *
 * **The value field is JSON and says so, with a worked example.** The spec's body accepts any JSON, and
 * an entry is useful with an object or a list; a bare word becomes a string, which is the reading that
 * makes both "ask" and `{"tools": false}` work from one field.
 *
 * **Removing an entry is confirmed by key**, because an entry is announced to the running session at the
 * next step boundary: taking one back changes what the agent is being told mid-conversation, which is
 * not a tidy-up.
 */
@Composable
fun InstructionsScreen(
    state: InstructionsUiState,
    onKeyChange: (String) -> Unit,
    onValueChange: (String) -> Unit,
    onPut: () -> Unit,
    onRequestRemove: (String) -> Unit,
    onConfirmRemove: () -> Unit,
    onCancelRemove: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        if (!state.usable) {
            Text(
                text = stringResource(R.string.admin_instructions_off),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp),
            )
            return
        }
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                text = stringResource(R.string.admin_instructions_title),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = stringResource(R.string.admin_instructions_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LabelledField(
                label = stringResource(R.string.admin_instructions_key),
                value = state.key,
                onValueChange = onKeyChange,
                tag = AdminTags.INSTRUCTION_KEY,
            )
            LabelledField(
                label = stringResource(R.string.admin_instructions_value),
                value = state.value,
                onValueChange = onValueChange,
                singleLine = false,
                minLines = 2,
                supporting = if (state.value.isNotBlank() && !ConfigValues.isJson(state.value)) {
                    stringResource(R.string.admin_instructions_value_hint)
                } else {
                    null
                },
                tag = AdminTags.INSTRUCTION_VALUE,
            )
            Button(onClick = onPut, enabled = state.canPut, modifier = Modifier.testTag(AdminTags.PUT_INSTRUCTION)) {
                Text(stringResource(R.string.admin_instructions_put))
            }
        }
        HorizontalDivider()
        LazyColumn(modifier = Modifier.weight(1f)) {
            if (state.loading) {
                item(key = "loading") {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator()
                        Text(stringResource(R.string.admin_instructions_loading))
                    }
                }
            }
            state.error?.let { error ->
                item(key = "error") { ErrorLine(error = error, onDismiss = onDismissError) }
            }
            if (state.loaded && state.entries.isEmpty()) {
                item(key = "empty") {
                    Text(
                        text = stringResource(R.string.admin_instructions_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            items(items = state.entries, key = { it.key }) { entry ->
                InstructionRow(entry = entry, onRemove = { onRequestRemove(entry.key) })
            }
        }
    }
    state.removeTarget?.let { key ->
        AlertDialog(
            onDismissRequest = onCancelRemove,
            title = { Text(stringResource(R.string.admin_instructions_remove_title)) },
            text = { Text(stringResource(R.string.admin_instructions_remove_body, key)) },
            confirmButton = {
                TextButton(
                    onClick = onConfirmRemove,
                    modifier = Modifier.testTag(AdminTags.CONFIRM_REMOVE_INSTRUCTION),
                ) {
                    Text(stringResource(R.string.admin_instructions_remove_confirm))
                }
            },
            dismissButton = { TextButton(onClick = onCancelRemove) { Text(stringResource(R.string.admin_cancel)) } },
        )
    }
}

@Composable
private fun InstructionRow(entry: InstructionEntry, onRemove: () -> Unit) {
    ListItem(
        headlineContent = {
            Column {
                Text(text = entry.key, style = MaterialTheme.typography.bodyLarge)
                // The value is a configuration the user typed, not a credential, so it is shown — but
                // through the same one-line rendering a long value gets, so a large object does not push
                // every other row off the screen.
                Text(
                    text = ConfigValues.toText(entry.value).take(200),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        },
        trailingContent = {
            TextButton(onClick = onRemove, modifier = Modifier.testTag("instructions:remove:${entry.key}")) {
                Text(stringResource(R.string.admin_instructions_remove))
            }
        },
        modifier = Modifier.testTag(AdminTags.instruction(entry.key)),
    )
}
