package dev.opencode.android.feature.sessions.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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

            SheetAction(R.string.session_rename) { renaming = true; onDismiss() }
            SheetAction(R.string.session_copy_message) { onCopyMessage(); onDismiss() }
            SheetAction(R.string.session_copy_transcript) { onCopyTranscript(); onDismiss() }
            if (onOpenAttention != null) {
                SheetAction(R.string.session_attention) { onOpenAttention() }
            }
            SheetAction(R.string.session_delete) { confirmingDelete = true }
        }
    }

    if (renaming) {
        RenameDialog(
            current = title,
            onConfirm = { onRename(it); onDismiss() },
            onDismiss = { renaming = false },
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

/** One row of the sheet. */
@Composable
private fun SheetAction(labelRes: Int, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(labelRes), modifier = Modifier.fillMaxWidth())
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
