package dev.opencode.android.feature.review

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.opencode.android.core.data.composer.FileContentKind
import dev.opencode.android.core.data.composer.FileReadResult
import dev.opencode.android.core.model.FileSystemEntry

/**
 * The file browser: `fs.list` navigation and `fs.read` viewing (plan §6, "File browser").
 *
 * **The server's spelling, all the way through.** A row's path is what `fs.list` gave back, "up" is
 * derived from the same spelling, and a `..` entry is a row like any other. Nothing here joins or
 * normalizes a path, because a joined path is a path the server never offered and the P2 rule is
 * that a path it gave is the path we send.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileBrowserScreen(
    directory: String?,
    path: String?,
    entries: List<FileSystemEntry>,
    reading: String?,
    content: FileReadResult?,
    loading: Boolean,
    error: String?,
    onEnter: (FileSystemEntry) -> Unit,
    onUp: () -> Unit,
    onRead: (FileSystemEntry) -> Unit,
    onAttach: (FileReadResult) -> Unit,
    onAttachLines: (FileReadResult) -> Unit,
    onShare: (FileReadResult) -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    canEdit: Boolean = false,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onNavigateBack, sheetState = sheetState, modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize().padding(bottom = 24.dp)) {
            Text(
                text = path?.ifBlank { null } ?: directory.orEmpty(),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            HorizontalDivider()

            if (path != null) {
                Text(
                    text = stringResource(R.string.files_up),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onUp() }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }

            when {
                loading -> Text(
                    text = stringResource(R.string.action_retry),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp),
                )

                error != null -> Text(
                    text = stringResource(R.string.files_unreadable),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp),
                )

                entries.isEmpty() -> Text(
                    text = stringResource(R.string.files_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp),
                )
            }

            LazyColumn(modifier = Modifier.weight(1f)) {
                items(entries, key = { it.path }) { entry ->
                    FileRow(entry = entry, reading = reading) {
                        if (entry.isDirectory) onEnter(entry) else onRead(entry)
                    }
                }
            }

            content?.let { file ->
                HorizontalDivider()
                FileActions(
                    file = file,
                    canEdit = canEdit,
                    onAttach = { onAttach(file) },
                    onAttachLines = { onAttachLines(file) },
                    onShare = { onShare(file) },
                )
            }
        }
    }
}

/** One entry: a directory opens, a file is read, and the row says which it is. */
@Composable
private fun FileRow(entry: FileSystemEntry, reading: String?, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = if (entry.isDirectory) Icons.Filled.Folder else Icons.Filled.InsertDriveFile,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = entry.name,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (reading == entry.path) {
            Text(
                text = "…",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** What the viewer offers for a file it has read. */
@Composable
private fun FileActions(
    file: FileReadResult,
    canEdit: Boolean,
    onAttach: () -> Unit,
    onAttachLines: () -> Unit,
    onShare: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = file.label,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (file.kind == FileContentKind.BINARY) {
            // A binary has no lines to select, so the range action is not offered at all rather than
            // offered and disabled: a control that cannot work is a control that confuses.
            Text(
                text = stringResource(R.string.files_binary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onAttach) { Text(stringResource(R.string.files_attach)) }
            if (file.kind == FileContentKind.TEXT) {
                TextButton(onClick = onAttachLines) { Text(stringResource(R.string.files_attach_lines)) }
            }
            TextButton(onClick = onShare) { Text(stringResource(R.string.files_share)) }
        }
        if (!canEdit) {
            Text(
                text = stringResource(R.string.files_edit_off),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The dialog that turns a selection in a file into a comment (plan §6, "Review comments"). */
@Composable
fun ReviewCommentDialog(
    draft: CommentDraft,
    onTextChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(
                    R.string.review_comment_add,
                    dev.opencode.android.core.data.composer.LineRange(draft.startLine, draft.endLine).toSuffix().removePrefix("#"),
                ),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = draft.path,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = draft.text,
                    onValueChange = onTextChange,
                    label = { Text(stringResource(R.string.review_comment_hint)) },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (draft.text.isEmpty()) {
                    Text(
                        text = stringResource(R.string.review_comment_empty),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onSubmit, enabled = draft.canSubmit) {
                Text(stringResource(R.string.review_comment_submit))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.review_comment_cancel)) }
        },
    )
}

/** The base-branch picker, which is `vcs.branch.list` (features doc §28). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BaseBranchSheet(
    branches: List<String>,
    selected: String?,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(
                text = stringResource(R.string.review_choose_base),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Text(
                text = stringResource(R.string.review_choose_base_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            TextButton(onClick = { onSelect(null) }) {
                Text(stringResource(R.string.review_base_default))
            }
            branches.forEach { branch ->
                TextButton(onClick = { onSelect(branch) }, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = if (branch == selected) "✓ $branch" else branch,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}
