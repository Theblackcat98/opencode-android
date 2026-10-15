package dev.opencode.android.feature.execution

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.opencode.android.core.designsystem.text.SyncedTextField
import dev.opencode.android.core.designsystem.theme.OpenCodeThemeExtras
import dev.opencode.android.core.model.Project

/**
 * The worktree panel (plan §6, "Worktrees"; features doc §29).
 *
 * **The create form is visible, not hidden behind a dialog.** Creating a worktree is the panel's main
 * verb and it is three short fields; a dialog for three fields on a phone is two screens' worth of
 * chrome.
 *
 * **The two optional fields are named for what the server accepts, not for what git would call them**
 * (matrix F5, issue #7). `from` is the absolute path of a worktree the project already has — the
 * server resolves it and refuses one it does not know, which is why the label says "Source
 * directory" and the help says absolute path. `branch` is an existing ref checked out *detached*:
 * the server runs `git worktree add --detach`, so a name that does not already resolve is refused
 * and no branch is ever created — "Existing ref", never "Branch name".
 *
 * **The remove confirmation says what forcing costs, and only appears when the server said so.** The
 * first attempt is `force = false`; a refusal brings the server's own reason into
 * [WorktreesUiState.removeReason] and only then does the second button appear. A panel that asked
 * "delete uncommitted work?" before trying would ask about a risk that turns out not to exist.
 */
@Composable
fun WorktreesScreen(
    state: WorktreesUiState,
    onFromChange: (String) -> Unit,
    onBranchChange: (String) -> Unit,
    onNameChange: (String) -> Unit,
    onCreate: () -> Unit,
    onRefresh: () -> Unit,
    onRequestRemove: (String) -> Unit,
    onRemove: () -> Unit,
    onForceRemove: () -> Unit,
    onCancelRemove: () -> Unit,
    onMoveSession: ((String) -> Unit)?,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = OpenCodeThemeExtras.spacing
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.lg, vertical = spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = state.projectName ?: stringResource(R.string.worktrees_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onRefresh) {
                Icon(Icons.Filled.Refresh, stringResource(R.string.worktrees_refresh))
            }
        }

        Column(
            modifier = Modifier.padding(horizontal = spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            SyncedTextField(
                value = state.draftFrom,
                onValueChange = onFromChange,
                label = { Text(stringResource(R.string.worktrees_from)) },
                supportingText = { Text(stringResource(R.string.worktrees_from_help)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            SyncedTextField(
                value = state.draftBranch,
                onValueChange = onBranchChange,
                label = { Text(stringResource(R.string.worktrees_branch)) },
                supportingText = { Text(stringResource(R.string.worktrees_branch_help)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            SyncedTextField(
                value = state.draftName,
                onValueChange = onNameChange,
                label = { Text(stringResource(R.string.worktrees_name)) },
                supportingText = { Text(stringResource(R.string.worktrees_name_help)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = stringResource(R.string.worktrees_create_hint),
                style = MaterialTheme.typography.bodySmall,
            )
            Button(
                onClick = onCreate,
                enabled = state.canCreate,
                modifier = Modifier.testTag(WorktreeTags.CREATE),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Text(
                    text = stringResource(R.string.worktrees_create),
                    modifier = Modifier.padding(start = spacing.sm),
                )
            }
            // Creating runs the project's setup script on the server, which takes a while; the
            // button alone greying out says nothing about the wait, so the progress gets its own row.
            if (state.creating) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                    Text(
                        text = stringResource(R.string.worktrees_creating),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // Every failure the panel's writes produce lands in state.error, so it is shown here, next to the
            // form the user is correcting, until they dismiss it. The drafts are kept beside it on a refusal.
            state.error?.let { error ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.worktrees_failed, error),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f).testTag(WorktreeTags.ERROR),
                    )
                    IconButton(onClick = onDismissError) {
                        Icon(Icons.Filled.Close, stringResource(R.string.action_close))
                    }
                }
            }
            // A completed write says which directory it landed on, in the server's words: the server
            // chooses the path, and the notice is the receipt. It stays until the next write or
            // refresh, like the error beside it; it is informational, so it carries no dismiss of
            // its own.
            state.notice?.let { notice ->
                Text(
                    text = stringResource(R.string.worktrees_notice, notice),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag(WorktreeTags.NOTICE),
                )
            }
        }

        HorizontalDivider(modifier = Modifier.padding(top = spacing.sm))

        if (state.loading) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                modifier = Modifier.padding(spacing.xl),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                )
                Text(
                    text = stringResource(R.string.worktrees_loading),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else if (state.directories.isEmpty()) {
            Text(
                text = stringResource(R.string.worktrees_empty),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(spacing.xl),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().testTag(WorktreeTags.LIST),
                contentPadding = PaddingValues(bottom = spacing.xl),
            ) {
                items(state.directories, key = { it.directory }) { entry ->
                    ListItem(
                        headlineContent = {
                            Text(
                                text = entry.directory,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        supportingContent = entry.strategy?.let { strategy ->
                            {
                                Text(
                                    text = stringResource(R.string.worktrees_strategy, strategy),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        },
                        trailingContent = {
                            Row {
                                onMoveSession?.let { move ->
                                    IconButton(onClick = { move(entry.directory) }) {
                                        Icon(
                                            Icons.Filled.DriveFileMove,
                                            stringResource(R.string.worktrees_move_session),
                                        )
                                    }
                                }
                                IconButton(onClick = { onRequestRemove(entry.directory) }) {
                                    Icon(
                                        Icons.Filled.Delete,
                                        stringResource(R.string.worktrees_remove),
                                    )
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(WorktreeTags.ROW + entry.directory),
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    state.removeTarget?.let { target ->
        AlertDialog(
            onDismissRequest = onCancelRemove,
            title = { Text(stringResource(R.string.worktrees_remove_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    Text(
                        text = target,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    state.removeReason?.let { reason ->
                        Text(reason, style = MaterialTheme.typography.bodyMedium)
                    }
                    if (state.forceArmed) {
                        Text(
                            text = stringResource(R.string.worktrees_force_warning),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            },
            confirmButton = {
                // Destructive: the error color is the whole signal that this is not a dismissal.
                TextButton(
                    onClick = { if (state.forceArmed) onForceRemove() else onRemove() },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.testTag(WorktreeTags.FORCE),
                ) {
                    Text(
                        if (state.forceArmed) {
                            stringResource(R.string.worktrees_force_remove)
                        } else {
                            stringResource(R.string.worktrees_remove_confirm)
                        },
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = onCancelRemove) {
                    Text(stringResource(R.string.action_close))
                }
            },
        )
    }
}

/** The tags the tests and the baselines address. */
object WorktreeTags {
    const val LIST: String = "worktrees-list"
    const val ROW: String = "worktrees-row-"
    const val CREATE: String = "worktrees-create"
    const val FORCE: String = "worktrees-force"
    const val ERROR: String = "worktrees-error"
    const val NOTICE: String = "worktrees-notice"
}
