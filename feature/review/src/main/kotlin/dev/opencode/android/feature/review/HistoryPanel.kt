package dev.opencode.android.feature.review

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Switch
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.opencode.android.core.data.server.SessionContextInspector

/**
 * The history panel: jump between prompts, search, export, import, and the context inspector
 * (plan §6, "History tools").
 *
 * **It is one panel because the five are one question** — "what happened, and what does the model
 * still see" — and a user asking it has the session open. Five separate destinations would each need
 * the session's id and each would answer a fraction of it.
 *
 * **The context inspector is a section, not a second screen.** The list it shows is a summary with
 * sizes in it; a user reading it is comparing it with the transcript above, and a screen change would
 * put the two on opposite sides of a navigation stack.
 */
@Composable
fun HistoryPanel(
    state: HistoryUiState,
    onPreviousPrompt: () -> Unit,
    onNextPrompt: () -> Unit,
    onSearchChange: (String) -> Unit,
    onOpenContext: () -> Unit,
    onCloseContext: () -> Unit,
    onSanitizeChange: (Boolean) -> Unit,
    onExportJson: () -> Unit,
    onExportMarkdown: () -> Unit,
    onImport: () -> Unit,
    onDismissTransfer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.history_jump),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = onPreviousPrompt,
                enabled = state.canGoBack,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    stringResource(R.string.history_previous_prompt),
                )
            }
            IconButton(
                onClick = onNextPrompt,
                enabled = state.canGoForward,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    stringResource(R.string.history_next_prompt),
                )
            }
        }

        state.cursor?.let { cursor ->
            state.prompts.getOrNull(cursor)?.let { prompt ->
                Text(
                    text = prompt.preview,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }

        OutlinedTextField(
            value = state.search,
            onValueChange = onSearchChange,
            label = { Text(stringResource(R.string.history_search)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )

        if (state.search.isNotBlank() && state.hits.isEmpty()) {
            Text(
                text = stringResource(R.string.history_search_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }

        state.hits.forEach { hit ->
            ListItem(
                headlineContent = {
                    Text(text = hit.preview, maxLines = 2, overflow = TextOverflow.Ellipsis)
                },
                overlineContent = { Text(hit.type) },
            )
            HorizontalDivider()
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onOpenContext) { Text(stringResource(R.string.context_title)) }
        }

        // The transfer actions are their own row because three buttons plus the context row do not
        // fit a 360 dp phone in one line, and a button that has been pushed off the end of a row is
        // a button that does not exist.
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.transferring) {
                CircularProgressIndicator(modifier = Modifier.padding(8.dp))
            } else {
                TextButton(onClick = onExportJson, enabled = state.transferUsable) {
                    Text(stringResource(R.string.history_export_json))
                }
                TextButton(onClick = onExportMarkdown, enabled = state.transferUsable) {
                    Text(stringResource(R.string.history_export_markdown))
                }
                TextButton(onClick = onImport, enabled = state.transferUsable) {
                    Text(stringResource(R.string.history_import))
                }
            }
        }

        if (state.transferUsable) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    selected = state.exportSanitize,
                    onClick = { onSanitizeChange(!state.exportSanitize) },
                    label = { Text(stringResource(R.string.history_export_sanitize)) },
                )
            }
            Text(
                text = stringResource(R.string.history_export_body),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        state.transfer?.let { result ->
            TransferNotice(result = result, onDismiss = onDismissTransfer)
        }

        if (state.contextOpen) {
            HorizontalDivider()
            ContextSection(state = state, onClose = onCloseContext)
        }
    }
}

/**
 * The context inspector: what the model still sees, with the sizes that make it visible.
 *
 * Public rather than private so a screenshot can photograph the real rows. A baseline drawn from a
 * test-local copy of a row is a baseline of the copy, and the copy is what stops matching the first
 * time the row's type changes.
 */
@Composable
fun ContextSection(
    state: HistoryUiState,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.context_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                if (state.context.isNotEmpty()) {
                    Text(
                        text = stringResource(
                            R.string.context_body,
                            state.context.size,
                            state.context.sumOf { it.characters },
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            TextButton(onClick = onClose) { Text(stringResource(R.string.action_dismiss)) }
        }
        if (state.context.isEmpty() && !state.contextLoading) {
            Text(
                text = stringResource(R.string.context_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            items(state.context, key = { it.id }) { entry ->
                ContextRow(entry = entry)
            }
        }
    }
}

/** One context entry: who spoke, what they said, how big it was. */
@Composable
private fun ContextRow(entry: SessionContextInspector.Entry) {
    ListItem(
        headlineContent = {
            Text(
                text = entry.preview.ifEmpty { entry.type },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            val files = if (entry.files.isEmpty()) {
                null
            } else {
                stringResource(R.string.context_files, entry.files.size)
            }
            Text(listOfNotNull(entry.type, entry.characters.toString(), files).joinToString(" · "))
        },
    )
    HorizontalDivider()
}

/** What the last transfer did, in the server's words or the app's. */
@Composable
private fun TransferNotice(result: TransferResult, onDismiss: () -> Unit) {
    val text = when (result) {
        is TransferResult.Exported -> stringResource(R.string.history_export_done, result.messages)
        is TransferResult.Imported -> stringResource(R.string.history_import_done, result.sessionID)
        is TransferResult.Refused -> stringResource(R.string.experimental_unavailable)
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = if (result is TransferResult.Refused) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_dismiss)) }
    }
}

/**
 * The experimental switches (plan §4.2, §5.2).
 *
 * **Each row says what it will do before it says what it is.** A switch labelled "experimental
 * routes" tells a user nothing; one labelled "Edit files on the server" tells them the write lands
 * on the machine the agent is working on, which is the part that matters before they turn it on.
 */
@Composable
fun ExperimentalSettingsContent(
    fileWrites: Boolean,
    sessionTransfer: Boolean,
    onFileWritesChange: (Boolean) -> Unit,
    onSessionTransferChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onDismiss: () -> Unit = {},
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.experimental_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_dismiss)) }
        }
        ExperimentalRow(
            title = stringResource(R.string.experimental_fs_write),
            body = stringResource(R.string.experimental_fs_write_body),
            enabled = fileWrites,
            onChange = onFileWritesChange,
        )
        HorizontalDivider()
        ExperimentalRow(
            title = stringResource(R.string.experimental_export),
            body = stringResource(R.string.experimental_export_body),
            enabled = sessionTransfer,
            onChange = onSessionTransferChange,
        )
    }
}

/** One switch with the sentence that explains it. */
@Composable
private fun ExperimentalRow(
    title: String,
    body: String,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(body) },
        trailingContent = {
            Switch(checked = enabled, onCheckedChange = onChange)
        },
        modifier = Modifier.clickable { onChange(!enabled) },
    )
}
