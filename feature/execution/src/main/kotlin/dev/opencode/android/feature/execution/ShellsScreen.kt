package dev.opencode.android.feature.execution

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.opencode.android.core.model.ShellStatus

/**
 * The shell panel: the commands running in a location, their output, and killing one
 * (features doc §30; plan §6, "Shell commands").
 *
 * **One list of rows and one output pane, because the output belongs to a row.** A separate screen per
 * command would make "which command am I reading" a navigation state; here the open row is part of the
 * panel's state and the pane is under the list, so a command that finishes while the user is reading
 * it does not take the pane away.
 *
 * **The output is monospaced and selectable, and is not interpreted.** It is the bytes a
 * non-interactive shell wrote, so a `grep --color=always` result and a progress bar's cursor moves are
 * both already in the text; stripping or colouring them would be an invention. `SelectionContainer` is
 * there so the output can be copied, which is the only way a user gets a line out of a build log.
 */
@Composable
fun ShellsScreen(
    state: ShellsUiState,
    onDraftChange: (String) -> Unit,
    onRun: () -> Unit,
    onOpen: (String) -> Unit,
    onClose: () -> Unit,
    onRequestKill: (String) -> Unit,
    onConfirmKill: () -> Unit,
    onCancelKill: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = state.draft,
                onValueChange = onDraftChange,
                label = { Text(stringResource(R.string.shells_command)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onRun, enabled = state.canRun) {
                Icon(Icons.Filled.Add, stringResource(R.string.shells_run))
            }
        }

        if (state.rows.isEmpty()) {
            EmptyShells(Modifier.weight(1f))
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).testTag(TAG_LIST),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                items(state.rows, key = { it.id }) { row ->
                    ShellRowItem(
                        row = row,
                        open = row.id == state.openID,
                        onOpen = { onOpen(row.id) },
                        onKill = { onRequestKill(row.id) },
                    )
                    HorizontalDivider()
                }
            }
            state.open?.let { row ->
                ShellOutputPane(
                    row = row,
                    onClose = onClose,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    state.killTarget?.let { target ->
        KillShellDialog(
            command = state.rows.firstOrNull { it.id == target }?.info?.command.orEmpty(),
            onConfirm = onConfirmKill,
            onDismiss = onCancelKill,
        )
    }

    state.error?.let { error ->
        AlertDialog(
            onDismissRequest = onDismissError,
            title = { Text(stringResource(R.string.shells_failed)) },
            text = { Text(error) },
            confirmButton = {
                TextButton(onClick = onDismissError) { Text(stringResource(R.string.action_close)) }
            },
        )
    }
}

/**
 * The confirmation that precedes a kill (plan §5.2).
 *
 * Killing a command the user did not start — the panel shows the agent's commands too — stops work that
 * is not theirs, and the agent's own `shell` tool is one of the things a panel can be looking at. So it
 * names the command rather than saying "stop this".
 */
@Composable
private fun KillShellDialog(
    command: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.shells_kill_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.shells_kill_body))
                Text(
                    text = command.take(280),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.shells_kill_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        },
    )
}

@Composable
private fun ShellRowItem(
    row: ShellRow,
    open: Boolean,
    onOpen: () -> Unit,
    onKill: () -> Unit,
) {
    ListItem(
        headlineContent = {
            Text(
                text = row.info.command,
                fontFamily = FontFamily.Monospace,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { contentDescription = row.info.command },
            )
        },
        supportingContent = {
            Text(
                text = shellStatusLabel(row),
                style = MaterialTheme.typography.bodySmall,
            )
        },
        leadingContent = {
            if (row.isRunning) {
                CircularProgressIndicator(
                    modifier = Modifier.width(24.dp).testTag(TAG_RUNNING),
                    strokeWidth = 2.dp,
                )
            } else {
                StatusDot(row)
            }
        },
        trailingContent = {
            IconButton(onClick = onKill) {
                Icon(Icons.Filled.Close, stringResource(R.string.shells_kill))
            }
        },
        modifier = Modifier.fillMaxWidth().testTag(TAG_ROW + row.id),
    )
}

@Composable
private fun StatusDot(row: ShellRow) {
    val label = shellStatusLabel(row)
    val colour = when {
        row.isRunning -> MaterialTheme.colorScheme.primary
        row.isFailed -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.outline
    }
    Surface(
        modifier = Modifier.width(12.dp).heightIn(min = 12.dp),
        color = colour,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(text = "", modifier = Modifier.semantics { contentDescription = label })
    }
}

/** The word a row's status gets, so every place that shows one says the same thing. */
@Composable
fun shellStatusLabel(row: ShellRow): String = when (row.info.status) {
    ShellStatus.Running -> stringResource(R.string.shells_running)

    ShellStatus.Exited ->
        row.info.exit
            ?.let { stringResource(R.string.shells_exited, it) }
            ?: stringResource(R.string.shells_exited_unknown)

    ShellStatus.Timeout -> stringResource(R.string.shells_timed_out)

    ShellStatus.Killed -> stringResource(R.string.shells_killed)

    else -> row.info.status.value
}

/**
 * The output pane.
 *
 * **The progress bar is the cursor against the server's own size**, not a guess: `ShellOutputState`
 * carries the `size` the last page reported and the `cursor` it ended at, and a reader can see whether
 * the client is behind. A bar that always ran would be a lie about exactly the case a user is watching
 * a build for.
 */
@Composable
fun ShellOutputPane(
    row: ShellRow,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val output = row.output ?: ShellOutputPlaceholder()
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.shells_output),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Stop, stringResource(R.string.shells_stop_following))
            }
        }
        if (!output.caughtUp || output.truncated) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().testTag(TAG_PROGRESS),
            )
        }
        Surface(
            modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 4.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.small,
        ) {
            SelectionContainer {
                Text(
                    text = output.text.ifEmpty { stringResource(R.string.shells_no_output) },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .padding(8.dp)
                        .horizontalScroll(rememberScrollState())
                        .testTag(TAG_OUTPUT),
                )
            }
        }
    }
}

@Composable
private fun ShellOutputPlaceholder() = dev.opencode.android.core.data.execution.ShellOutputState()

@Composable
private fun EmptyShells(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.shells_empty),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = stringResource(R.string.shells_empty_body),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** The tags the tests and the baselines address, so a screenshot names what it drew. */
object ExecutionTags {
    const val LIST: String = "shells-list"
    const val ROW: String = "shells-row-"
    const val RUNNING: String = "shells-running"
    const val OUTPUT: String = "shells-output"
    const val PROGRESS: String = "shells-progress"
}

private val TAG_LIST = ExecutionTags.LIST
private val TAG_ROW = ExecutionTags.ROW
private val TAG_RUNNING = ExecutionTags.RUNNING
private val TAG_OUTPUT = ExecutionTags.OUTPUT
private val TAG_PROGRESS = ExecutionTags.PROGRESS
