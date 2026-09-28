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
        FileBrowserContent(
            directory = directory,
            path = path,
            entries = entries,
            reading = reading,
            content = content,
            loading = loading,
            error = error,
            canEdit = canEdit,
            onEnter = onEnter,
            onUp = onUp,
            onRead = onRead,
            onAttach = onAttach,
            onAttachLines = onAttachLines,
            onShare = onShare,
        )
    }
}

/**
 * The browser's own content, without the sheet.
 *
 * Separate from the sheet for the same reason the comment dialog's body is separate: a
 * `ModalBottomSheet` animates in, so at the first frame a screenshot would be an empty rectangle —
 * and this app's baselines are its review artifact.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileBrowserContent(
    directory: String?,
    path: String?,
    entries: List<FileSystemEntry>,
    reading: String?,
    content: FileReadResult?,
    loading: Boolean,
    error: String?,
    canEdit: Boolean = false,
    onEnter: (FileSystemEntry) -> Unit = {},
    onUp: () -> Unit = {},
    onRead: (FileSystemEntry) -> Unit = {},
    onAttach: (FileReadResult) -> Unit = {},
    onAttachLines: (FileReadResult) -> Unit = {},
    onShare: (FileReadResult) -> Unit = {},
) {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
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

/**
 * The dialog that turns a selection in a file into a comment (plan §6, "Review comments").
 *
 * The body is a separate composable so a screenshot can photograph the content without the dialog's
 * window: `AlertDialog` never reports idle under Robolectric — its window animation is what does not
 * settle — and a screenshot of a window that never settles is a test that fails on a timeout rather
 * than on a pixel. The chrome is Material's and the content is the part this app owns.
 */
@Composable
fun ReviewCommentDialog(
    draft: CommentDraft,
    onTextChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.review_comment_add, draft.selectionText())) },
        text = { CommentDraftContent(draft = draft, onTextChange = onTextChange) },
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

/** The body of the comment dialog: the anchor, the field and the empty-state sentence. */
@Composable
fun CommentDraftContent(
    draft: CommentDraft,
    onTextChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = draft.path,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.review_comment_add, draft.selectionText()),
            style = MaterialTheme.typography.titleSmall,
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
}

/** The `#12-18` spelling of a draft's range, which the dialog's title and the chip both use. */
internal fun CommentDraft.selectionText(): String =
    dev.opencode.android.core.data.composer.LineRange(startLine, endLine).toSuffix().removePrefix("#")

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
        BaseBranchContent(branches = branches, selected = selected, onSelect = onSelect)
    }
}

/** The base picker's rows, without the sheet. See [FileBrowserContent] for why it is separate. */
@Composable
fun BaseBranchContent(
    branches: List<String>,
    selected: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().padding(bottom = 24.dp)) {
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
