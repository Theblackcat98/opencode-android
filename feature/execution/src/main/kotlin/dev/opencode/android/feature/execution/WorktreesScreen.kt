package dev.opencode.android.feature.execution

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.opencode.android.core.model.Project

/**
 * The worktree panel (plan §6, "Worktrees"; features doc §29).
 *
 * **The create form is visible, not hidden behind a dialog.** Creating a worktree is the panel's main
 * verb and it is three short fields; a dialog for three fields on a phone is two screens' worth of
 * chrome, and the fields it holds are the ones a user needs to see while typing the ref.
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
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
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
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = state.draftFrom,
                onValueChange = onFromChange,
                label = { Text(stringResource(R.string.worktrees_from)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.draftBranch,
                onValueChange = onBranchChange,
                label = { Text(stringResource(R.string.worktrees_branch)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.draftName,
                onValueChange = onNameChange,
                label = { Text(stringResource(R.string.worktrees_name)) },
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
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }

        HorizontalDivider(modifier = Modifier.padding(top = 8.dp))

        if (state.directories.isEmpty()) {
            Text(
                text = stringResource(R.string.worktrees_empty),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(24.dp),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().testTag(WorktreeTags.LIST),
                contentPadding = PaddingValues(bottom = 24.dp),
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
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                TextButton(
                    onClick = { if (state.forceArmed) onForceRemove() else onRemove() },
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

/**
 * The project settings sheet (plan §6, "Project settings").
 *
 * **The icon is one control, not three.** `Project.Icon` is replaced rather than merged, so showing a
 * colour field, an emoji field and a URL field side by side would suggest three independent settings
 * where the server has one object. What is shown instead is the icon as it will be — the emoji, or the
 * URL, on the colour — and a single field for whichever override the user wants.
 */
@Composable
fun ProjectSettingsSheet(
    state: ProjectSettingsUiState,
    onNameChange: (String) -> Unit,
    onColorChange: (String) -> Unit,
    onEmojiChange: (String) -> Unit,
    onStartCommandChange: (String) -> Unit,
    onCanonicalChange: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp)
            .testTag(ProjectSettingsTags.SHEET),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProjectIconBadge(state)
            Text(
                text = stringResource(R.string.project_settings_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
        OutlinedTextField(
            value = state.name,
            onValueChange = onNameChange,
            label = { Text(stringResource(R.string.project_settings_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.emoji,
            onValueChange = onEmojiChange,
            label = { Text(stringResource(R.string.project_settings_emoji)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.color,
            onValueChange = onColorChange,
            label = { Text(stringResource(R.string.project_settings_color)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.startCommand,
            onValueChange = onStartCommandChange,
            label = { Text(stringResource(R.string.project_settings_start)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.canonical,
            onValueChange = onCanonicalChange,
            label = { Text(stringResource(R.string.project_settings_canonical)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = stringResource(R.string.project_settings_start_hint),
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
            Button(
                onClick = onSave,
                enabled = state.dirty && !state.saving,
                modifier = Modifier.testTag(ProjectSettingsTags.SAVE),
            ) {
                Text(
                    if (state.saving) {
                        stringResource(R.string.project_settings_saving)
                    } else {
                        stringResource(R.string.project_settings_save)
                    },
                )
            }
        }
        state.error?.let { error ->
            Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        if (state.saved) {
            Text(
                text = stringResource(R.string.project_settings_saved),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag(ProjectSettingsTags.SAVED),
            )
        }
    }
}

/**
 * The icon as it will be drawn in the project list.
 *
 * **A URL is not fetched here.** The project list's own icon rendering is P8's business — that is where
 * Coil earns its place (P6's decision about the file viewer's images applies here too). This badge
 * shows the emoji over the colour, and says so when only a URL is set, rather than pretending a remote
 * image is available offline.
 */
@Composable
private fun ProjectIconBadge(state: ProjectSettingsUiState) {
    val background = state.color.toComposeColorOrDefault(MaterialTheme.colorScheme.secondaryContainer)
    Column(
        modifier = Modifier
            .testTag(ProjectSettingsTags.BADGE)
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = state.emoji.ifBlank { state.project?.icon?.override.orEmpty().ifBlank { "·" } },
            style = MaterialTheme.typography.titleLarge,
            color = background,
        )
        if (state.project?.icon?.url != null) {
            Text(
                text = stringResource(R.string.project_settings_url_set),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

/** A `#rgb` or `#rrggbb` colour, or the theme's own when it is not one. */
private fun String.toComposeColorOrDefault(fallback: androidx.compose.ui.graphics.Color) =
    runCatching {
        androidx.compose.ui.graphics.Color(
            android.graphics.Color.parseColor(if (startsWith("#")) this else "#$this"),
        )
    }.getOrDefault(fallback)

/** The tags the tests and the baselines address. */
object WorktreeTags {
    const val LIST: String = "worktrees-list"
    const val ROW: String = "worktrees-row-"
    const val CREATE: String = "worktrees-create"
    const val FORCE: String = "worktrees-force"
}

object ProjectSettingsTags {
    const val SHEET: String = "project-settings"
    const val SAVE: String = "project-settings-save"
    const val SAVED: String = "project-settings-saved"
    const val BADGE: String = "project-settings-badge"
}
