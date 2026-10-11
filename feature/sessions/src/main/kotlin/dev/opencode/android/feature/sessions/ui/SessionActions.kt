package dev.opencode.android.feature.sessions.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.opencode.android.feature.sessions.R

/**
 * Session management: rename, delete and copy (plan §6, Session management).
 *
 * **Deleting asks first and says what goes with it.** `session.remove` deletes the session *and its
 * children* with no undo, and a list row shows a child count as a badge, not as a warning; the
 * confirmation therefore names the subagents it found, or says there are none, so the user is never
 * surprised by a subtree disappearing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionActionsSheet(
    title: String,
    childCount: Int,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    onCopyMessage: () -> Unit,
    onCopyTranscript: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Opens the per-session attention controls, when the caller has them to offer.
     *
     * A callback rather than a slot because the controls themselves live in the requests feature and
     * a feature may not import another feature. The app module, which composes this screen, is the
     * only place that can hold both, and it supplies the callback; `null` hides the row.
     */
    onOpenAttention: (() -> Unit)? = null,
    /**
     * Opens the history panel: jump, search, export, import and the context inspector.
     *
     * It is a callback for the same reason [onOpenAttention] is — the panel is the review feature's
     * and a feature may not import another feature, so the app module supplies the way in.
     */
    onOpenHistory: (() -> Unit)? = null,
    /**
     * Starts waiting until the session's turn ends, when this server has the route to do it.
     *
     * The wait is the caller's to show, and `null` hides the row, the same as [onOpenHistory].
     */
    onWaitUntilIdle: (() -> Unit)? = null,
    /**
     * Whether the app follows the desktop TUI: a session the TUI selects is opened here while this is on.
     *
     * The row shows the current state, and it stays on the sheet when tapped, so the change is seen at once.
     */
    followDesktopOn: Boolean = false,
    /** Turns following on or off, and `null` hides the row, the same as [onWaitUntilIdle]. */
    onToggleFollowDesktop: (() -> Unit)? = null,
    /**
     * Opens the dialog that adds a synthetic note to the session.
     *
     * Always offered: `session.synthetic` is in the spec, not behind an experimental switch, so there is no route
     * availability to ask about. `null` hides the row, the same as [onWaitUntilIdle].
     */
    onAddSyntheticNote: (() -> Unit)? = null,
    /**
     * Opens the session's event history, the durable log replayed up to `log.synced`, when this server has the
     * log route. `null` hides the row, the same as [onWaitUntilIdle].
     */
    onOpenEventHistory: (() -> Unit)? = null,
    /**
     * Opens the experimental switches, when this server has any route the app could offer.
     *
     * The same arrangement as [onOpenHistory], and `null` hides the row: a server that serves
     * neither `experimental.fs.write` nor the transfer routes has nothing to switch on, and a
     * settings page of switches that do nothing is worse than no page.
     */
    onOpenExperimental: (() -> Unit)? = null,
    /**
     * Phase 7's rows, drawn after this feature's own.
     *
     * A slot for the same reason [onOpenHistory] is a callback: the terminals, shells, worktrees and
     * subagent tree belong to the execution feature, which the session screen may not import. The app
     * module supplies the rows and the default draws nothing.
     */
    executionSlot: @Composable () -> Unit = {},
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var renaming by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, modifier = modifier) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(stringResource(R.string.session_menu), style = MaterialTheme.typography.titleMedium)
            Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

            SheetAction(R.string.session_rename) {
                renaming = true
            }
            SheetAction(R.string.session_copy_message) {
                onCopyMessage()
                onDismiss()
            }
            SheetAction(R.string.session_copy_transcript) {
                onCopyTranscript()
                onDismiss()
            }
            if (onOpenAttention != null) {
                SheetAction(R.string.session_attention) { onOpenAttention() }
            }
            onOpenHistory?.let { open -> SheetAction(R.string.session_history) { open() } }
            onWaitUntilIdle?.let { wait -> SheetAction(R.string.session_wait_idle) { wait() } }
            onToggleFollowDesktop?.let { toggle -> FollowDesktopRow(on = followDesktopOn, onClick = toggle) }
            onAddSyntheticNote?.let { add -> SheetAction(R.string.session_synthetic_note) { add() } }
            onOpenEventHistory?.let { open -> SheetAction(R.string.session_event_history) { open() } }
            onOpenExperimental?.let { open -> SheetAction(R.string.session_experimental) { open() } }
            executionSlot()
            SheetAction(R.string.session_delete) { confirmingDelete = true }
        }
    }

    if (renaming) {
        RenameDialog(
            current = title,
            onConfirm = {
                onRename(it)
                onDismiss()
            },
            onDismiss = {
                renaming = false
                onDismiss()
            },
        )
    }

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text(stringResource(R.string.session_delete_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.session_delete_body))
                    if (childCount > 0) {
                        Text(
                            text = stringResource(R.string.session_delete_children, childCount),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingDelete = false
                        onDelete()
                    },
                ) {
                    Text(stringResource(R.string.session_delete_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) { Text(stringResource(R.string.action_dismiss)) }
            },
        )
    }
}

/** A rename, which is `session.update` with only the title. */
@Composable
fun RenameDialog(
    current: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.session_rename)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(stringResource(R.string.session_rename_hint)) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text.trim()) }, enabled = text.isNotBlank()) {
                Text(stringResource(R.string.session_rename_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_dismiss)) }
        },
    )
}

/**
 * The wait started from the sheet, shown until its turn ends or the user stops it.
 *
 * Dismissing the dialog is the same as stopping: a wait is a request the app holds, not a change on the
 * server, so there is nothing to confirm and nothing to undo.
 */
@Composable
fun WaitingForIdleDialog(onStop: () -> Unit) {
    AlertDialog(
        onDismissRequest = onStop,
        title = { Text(stringResource(R.string.session_wait_idle_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                Text(stringResource(R.string.session_wait_idle_body))
            }
        },
        confirmButton = {
            TextButton(onClick = onStop) { Text(stringResource(R.string.session_wait_idle_stop)) }
        },
    )
}

/**
 * Adds a synthetic note to the open session: text the user did not type, which the agent reads as injected context.
 *
 * **The confirm button is the confirmation, so there is no second dialog.** The explanation is on screen before
 * "Add note" can be pressed, and it says what the note becomes: an injected note in the transcript, not a message
 * from the user. Nothing is sent until that button is pressed.
 *
 * A failure keeps the dialog open with the text still in it, so a retry is one tap. The caller closes it once the
 * server has accepted the note. While [sending], the fields are locked and the dialog cannot be dismissed, so the
 * note cannot be edited or abandoned while its request is in flight.
 */
@Composable
fun SyntheticNoteDialog(
    onSend: (text: String, description: String?) -> Unit,
    onDismiss: () -> Unit,
    sending: Boolean,
    failure: String? = null,
) {
    var text by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { if (!sending) onDismiss() },
        title = { Text(stringResource(R.string.session_synthetic_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.session_synthetic_explain), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.session_synthetic_text_hint)) },
                    minLines = 3,
                    enabled = !sending,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.session_synthetic_description_hint)) },
                    singleLine = true,
                    enabled = !sending,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (sending) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                failure?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSend(text, description) }, enabled = text.isNotBlank() && !sending) {
                Text(stringResource(R.string.session_synthetic_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !sending) {
                Text(stringResource(R.string.session_synthetic_cancel))
            }
        },
    )
}

/**
 * The desktop-follow row. Its label is the state the app is in now, so a screen reader hears "on" or "off" before
 * the explanation, and the explanation says what on does.
 */
@Composable
private fun FollowDesktopRow(on: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth()) {
            Text(stringResource(if (on) R.string.session_follow_desktop_on else R.string.session_follow_desktop_off))
            Text(
                stringResource(R.string.session_follow_desktop_explain),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** One row of the sheet. */
@Composable
private fun SheetAction(labelRes: Int, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(labelRes), modifier = Modifier.fillMaxWidth())
    }
}

/**
 * One row of a session's overflow sheet, with an enabled flag.
 *
 * Public because the app module composes Phase 7's rows into this sheet, and a row the composition
 * root cannot draw is a row the composition root cannot add.
 */
@Composable
fun SessionMenuRow(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.fillMaxWidth())
    }
}

/** The back arrow, kept here so the session screen does not carry a bare glyph. */
@Composable
fun BackButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = stringResource(R.string.session_close),
        )
    }
}
