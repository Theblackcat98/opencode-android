package dev.opencode.android.feature.sessions.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.NoteAlt
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.opencode.android.core.designsystem.theme.OpenCodeThemeExtras
import dev.opencode.android.feature.sessions.R

/**
 * Session management: rename, delete and copy (plan §6, Session management).
 *
 * **Deleting asks first and says what goes with it.** `session.remove` deletes the session *and its
 * children* with no undo, and a list row shows a child count as a badge, not as a warning; the
 * confirmation therefore names the subagents it found, or says there are none, so the user is never
 * surprised by a subtree disappearing.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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
     * The workspace tiles: review, files, shells, terminal, subagents, worktrees, session terminals ([SessionToolTile]).
     *
     * A slot for the same reason [onOpenHistory] is a callback: those panels belong to the review and execution
     * features, which the session screen may not import. The app module supplies the tiles; the default draws none.
     */
    workspaceSlot: @Composable () -> Unit = {},
    /** The project rows (project settings, permissions, instructions), supplied by the app module like [workspaceSlot]. */
    projectSlot: @Composable () -> Unit = {},
    /** The context gauge's words, shown under the title because the top bar draws the gauge without them. */
    contextLabel: String? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var renaming by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, modifier = modifier) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Column(Modifier.padding(horizontal = 24.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                contextLabel?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            SheetSection(R.string.session_section_workspace)
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                maxItemsInEachRow = TILES_PER_ROW,
            ) { workspaceSlot() }

            SheetSection(R.string.session_section_session)
            SessionMenuRow(stringResource(R.string.session_rename), icon = Icons.Outlined.DriveFileRenameOutline, onClick = {
                renaming = true
            })
            onOpenHistory?.let { open ->
                SessionMenuRow(stringResource(R.string.session_history), icon = Icons.Outlined.History, onClick = open)
            }
            onWaitUntilIdle?.let { wait ->
                SessionMenuRow(stringResource(R.string.session_wait_idle), icon = Icons.Outlined.HourglassEmpty, onClick = wait)
            }
            onToggleFollowDesktop?.let { toggle -> FollowDesktopRow(on = followDesktopOn, onClick = toggle) }
            if (onOpenAttention != null) {
                SessionMenuRow(
                    stringResource(R.string.session_attention),
                    icon = Icons.Outlined.NotificationsActive,
                    onClick = onOpenAttention,
                )
            }
            onAddSyntheticNote?.let { add ->
                SessionMenuRow(stringResource(R.string.session_synthetic_note), icon = Icons.Outlined.NoteAlt, onClick = add)
            }
            SessionMenuRow(stringResource(R.string.session_copy_message), icon = Icons.Outlined.ContentCopy, onClick = {
                onCopyMessage()
                onDismiss()
            })
            SessionMenuRow(stringResource(R.string.session_copy_transcript), icon = Icons.AutoMirrored.Outlined.Article, onClick = {
                onCopyTranscript()
                onDismiss()
            })

            SheetSection(R.string.session_section_project)
            projectSlot()

            if (onOpenEventHistory != null || onOpenExperimental != null) {
                SheetSection(R.string.session_section_advanced)
                onOpenEventHistory?.let { open ->
                    SessionMenuRow(stringResource(R.string.session_event_history), icon = Icons.Outlined.Timeline, onClick = open)
                }
                onOpenExperimental?.let { open ->
                    SessionMenuRow(stringResource(R.string.session_experimental), icon = Icons.Outlined.Science, onClick = open)
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            SessionMenuRow(
                label = stringResource(R.string.session_delete),
                icon = Icons.Outlined.Delete,
                destructive = true,
                onClick = { confirmingDelete = true },
            )
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
            Column(verticalArrangement = Arrangement.spacedBy(OpenCodeThemeExtras.spacing.md)) {
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
            Column(verticalArrangement = Arrangement.spacedBy(OpenCodeThemeExtras.spacing.md)) {
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
 * The desktop-follow row. Its switch is the state the app is in now, so a screen reader hears "on" or "off" with the
 * label, and the explanation under it says what on does. It stays on the sheet when toggled, so the change is seen.
 */
@Composable
private fun FollowDesktopRow(on: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = on, role = Role.Switch, onValueChange = { onClick() })
            .padding(horizontal = OpenCodeThemeExtras.spacing.xl, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Devices, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(20.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.session_follow_desktop), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(R.string.session_follow_desktop_explain),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = on, onCheckedChange = null)
    }
}

@Composable
private fun SheetSection(titleRes: Int) {
    val spacing = OpenCodeThemeExtras.spacing
    Text(
        text = stringResource(titleRes),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = spacing.xl, end = spacing.xl, top = 20.dp, bottom = 6.dp).semantics { heading() },
    )
}

/**
 * One row of a session's tools sheet: an icon, a label, and an enabled flag.
 *
 * Public because the app module composes the project rows into this sheet, and a row the composition root cannot
 * draw is a row the composition root cannot add. [destructive] colors it as the one row that cannot be undone.
 */
@Composable
fun SessionMenuRow(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    destructive: Boolean = false,
) {
    val alpha = if (enabled) 1f else DISABLED_ALPHA
    val tint = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = OpenCodeThemeExtras.spacing.xl, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = tint.copy(alpha = alpha))
            Spacer(Modifier.width(20.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = (if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface).copy(alpha = alpha),
        )
    }
}

/**
 * One tile of the sheet's workspace grid. A disabled tile says why under its label ([disabledReason]), because a
 * greyed-out Shells with no explanation reads as broken rather than as "this session has not loaded its directory".
 */
@Composable
fun SessionToolTile(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    disabledReason: String? = null,
) {
    val alpha = if (enabled) 1f else DISABLED_ALPHA
    Column(
        modifier = modifier
            .width(TILE_WIDTH)
            .clip(OpenCodeThemeExtras.shapes.card)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {
                if (!enabled && disabledReason != null) stateDescription = disabledReason
            }
            .padding(vertical = OpenCodeThemeExtras.spacing.md, horizontal = OpenCodeThemeExtras.spacing.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha))
        Spacer(Modifier.height(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
    }
}

private const val DISABLED_ALPHA = 0.38f
private const val TILES_PER_ROW = 4
private val TILE_WIDTH = 80.dp

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
