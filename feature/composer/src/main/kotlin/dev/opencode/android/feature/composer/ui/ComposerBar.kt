package dev.opencode.android.feature.composer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.opencode.android.core.data.composer.Completion
import dev.opencode.android.core.data.composer.PromptIntent
import dev.opencode.android.core.designsystem.component.OpenCodeChip
import dev.opencode.android.core.designsystem.text.TextSync
import dev.opencode.android.core.designsystem.theme.OpenCodeThemeExtras
import dev.opencode.android.core.model.Delivery
import dev.opencode.android.feature.composer.R

/**
 * The composer: text, its context, delivery, and the buttons that control a running turn.
 *
 * **Steering is the default and queueing is a deliberate act.** A phone prompt is almost always a
 * correction or a follow-up the agent should see at the next step, which is what `steer` does; `queue`
 * waits for the turn to finish, which is right for "and then also…". The toggle is therefore on by
 * default and a long press on send sends one message as queued without changing it (features doc §6,
 * and the TUI's Alt+Enter).
 *
 * **Stop and Background are separate.** Stopping ends the turn; resuming steering input afterwards is
 * a choice the user makes, not a default, because resuming re-enters the agent loop. Background moves
 * blocking tools out of the way so the turn can finish, which is the TUI's Ctrl+B and is not the same
 * thing as stopping.
 *
 * **The context row sits between the box and the send button on purpose** (Phase 5). Everything the
 * send will carry — what the text means, how many attachments and skills, whether it is steer or
 * queue — is stated there, so a prompt with a picture in it is never a prompt whose contents have to
 * be remembered.
 *
 * The composer's own state is a value, and every action is a parameter with a default, so a
 * screenshot of a new composer state is one call and a caller that does not care about attachments
 * keeps compiling.
 */
@Composable
fun ComposerBar(
    state: ComposerUiState,
    /**
     * The text and the caret.
     *
     * Both, because the caret is what decides which trigger the completion list is completing: a
     * mention in the middle of a sentence is the one under the finger, and `@` at the start of a line
     * is not the same offer. Material's field does not hand the caret to a `String`-valued
     * `onValueChange`, so this composable keeps a [TextFieldValue] locally and reports both.
     */
    onTextChange: (String, Int) -> Unit,
    onSend: () -> Unit,
    onSendQueued: () -> Unit,
    onDeliveryChange: (Delivery) -> Unit,
    onResumeChange: (Boolean) -> Unit,
    onInterrupt: (resumeSteering: Boolean) -> Unit,
    onBackground: () -> Unit,
    onOpenInbox: () -> Unit,
    onOpenAgentPicker: () -> Unit,
    onOpenModelPicker: () -> Unit,
    onCycleAgent: () -> Unit,
    onCycleVariant: () -> Unit,
    // ------------------------------------------------------------------ Phase 5: rich composer
    onSelectCompletion: (Completion) -> Unit = {},
    onRemoveAttachment: (String) -> Unit = {},
    onAttach: () -> Unit = {},
    onOpenSkills: () -> Unit = {},
    onOlderHistory: () -> Unit = {},
    onNewerHistory: () -> Unit = {},
    onStash: () -> Unit = {},
    onOpenStash: () -> Unit = {},
    onOpenEditor: () -> Unit = {},
    onSendConfirmed: () -> Unit = {},
    // ------------------------------------------------------------------ undo and redo
    /** The banner's Redo, or `/redo`: asks; nothing is changed until [onConfirmRedo]. */
    onAskRedo: () -> Unit = {},
    onConfirmRedo: () -> Unit = {},
    onDismissRedo: () -> Unit = {},
    /**
     * The last failed action, already worded.
     *
     * Resolved by the caller rather than here: a feature may not import another feature, the failure
     * wording belongs to one place so it cannot drift, and the composition root is where the two
     * meet. `null` when the last action succeeded.
     */
    errorMessage: String? = null,
    onDismissError: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // The field's own value, which carries the caret. It is the source of truth while the user types
    // and is overwritten whenever the text arrives from somewhere else — a completion, a history
    // step, a restored draft, a cleared box — which is the only way the caret survives a recomposition
    // without being reset on every keystroke.
    var field by remember { mutableStateOf(TextFieldValue(state.text, TextRange(state.text.length))) }
    // `state.text` echoes each keystroke late, so "differs from the field" cannot mean "changed from
    // outside": that reading rewinds the field to a stale echo and eats fast typing.
    val sync = remember { TextSync() }
    LaunchedEffect(state.text) {
        sync.onState(state.text, field.text)?.let { adopted ->
            field = TextFieldValue(adopted, TextRange(adopted.length))
        }
    }
    var actionsOpen by remember { mutableStateOf(false) }
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 0.dp,
        tonalElevation = 0.dp,
    ) {
        Column(Modifier.padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 8.dp)) {
            // A server with no usable model cannot start a turn, and a composer that silently does
            // nothing is the worst way to learn that. The plan's empty state says how to fix it;
            // logging in from the phone is Phase 8.
            if (!state.hasAnyModel) {
                NoModelAvailable()
            }
            errorMessage?.let { ErrorRow(message = it, onDismiss = onDismissError) }
            ComposerProblemRow(state = state, onSendAnyway = onSendConfirmed)
            // An undo that is staged is a state of the session, not of one message: it is why the box holds a
            // prompt that was already sent, and why the next send commits first. Above the field, so it is read
            // before the words it explains.
            StagedRevertBanner(state = state, onRedo = onAskRedo, modifier = Modifier.padding(bottom = 8.dp))
            SearchProgress(visible = state.searchingFiles)
            CompletionList(completions = state.completions, onSelect = onSelectCompletion)
            AttachmentChips(attachments = state.attachments, onRemove = onRemoveAttachment)
            // What the send will carry, said before it is sent (Phase 5). Only when there is something beyond plain
            // text to say: "Sends: nothing yet" under an empty box was a line of chrome on every screen.
            if (state.attachments.isNotEmpty() || state.intent.let { it != null && it !is PromptIntent.Prompt }) {
                ComposerContextRow(state = state)
            }
            ContextChips(
                state = state,
                onOpenAgentPicker = onOpenAgentPicker,
                onOpenModelPicker = onOpenModelPicker,
                onDeliveryChange = onDeliveryChange,
                onResumeChange = onResumeChange,
                onOpenInbox = onOpenInbox,
            )
            // Stop and Background have a row of their own, above the input and only while a turn runs, so the
            // input row (and Send under the thumb) never moves when a turn starts or ends.
            if (state.busy) {
                RunningTurnActions(onInterrupt = { onInterrupt(false) }, onBackground = onBackground)
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                // At the bottom so + and Send stay level with the last line of a growing box.
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ComposerAction(
                    icon = Icons.Filled.Add,
                    description = stringResource(R.string.composer_more_actions),
                    onClick = { actionsOpen = true },
                )
                OutlinedTextField(
                    value = field,
                    onValueChange = { edited: TextFieldValue ->
                        if (edited.text != field.text) sync.onEdited(edited.text)
                        field = edited
                        onTextChange(edited.text, edited.selection.end)
                    },
                    placeholder = {
                        Text(
                            stringResource(
                                if (state.shellMode) R.string.composer_shell_hint else R.string.composer_hint,
                            ),
                        )
                    },
                    modifier = Modifier.weight(1f),
                    textStyle = if (state.shellMode) OpenCodeThemeExtras.code.body else LocalTextStyle.current,
                    // Shell mode is a command line: no autocorrect and no capitalisation rewriting what was typed.
                    keyboardOptions = if (state.shellMode) {
                        KeyboardOptions(autoCorrectEnabled = false, capitalization = KeyboardCapitalization.None)
                    } else {
                        KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)
                    },
                    shape = RoundedCornerShape(24.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    ),
                    maxLines = 6,
                    enabled = !state.sending,
                )
                SendButton(
                    enabled = state.canSend,
                    sending = state.sending,
                    queueOnLongPress = true,
                    onClick = onSend,
                    onLongClick = onSendQueued,
                )
            }
        }
    }
    if (actionsOpen) {
        ComposerActionsSheet(
            state = state,
            onDismiss = { actionsOpen = false },
            onAttach = onAttach,
            onOpenSkills = onOpenSkills,
            onOlderHistory = onOlderHistory,
            onNewerHistory = onNewerHistory,
            onStash = onStash,
            onOpenStash = onOpenStash,
            onOpenEditor = onOpenEditor,
            onCycleAgent = onCycleAgent,
            onCycleVariant = onCycleVariant,
        )
    }
    // A dialog window of its own, so where it is composed does not matter to the layout above.
    if (state.confirmingRedo) {
        RedoConfirmationDialog(
            onConfirm = onConfirmRedo,
            onDismiss = onDismissRedo,
            confirmEnabled = !state.reverting,
        )
    }
}

/**
 * Who answers and how the prompt is delivered, as one scrolling row of chips above the box: the agent and the model
 * open their pickers, and while a turn runs, Steer/Queue and Resume say what a send does to it. The inbox chip is
 * there only when something is queued.
 */
@Composable
private fun ContextChips(
    state: ComposerUiState,
    onOpenAgentPicker: () -> Unit,
    onOpenModelPicker: () -> Unit,
    onDeliveryChange: (Delivery) -> Unit,
    onResumeChange: (Boolean) -> Unit,
    onOpenInbox: () -> Unit,
) {
    val spacing = OpenCodeThemeExtras.spacing
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = spacing.xs, vertical = spacing.xxs),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        state.agent?.let { agent ->
            OpenCodeChip(label = agent, icon = Icons.Outlined.SmartToy, onClick = onOpenAgentPicker)
        }
        state.model?.let { model ->
            OpenCodeChip(
                label = model.variant?.let { "${model.id} · $it" } ?: model.id,
                icon = Icons.Outlined.Memory,
                onClick = onOpenModelPicker,
            )
        }
        if (state.busy) {
            val queued = state.delivery == Delivery.Queue
            OpenCodeChip(
                label = stringResource(if (queued) R.string.composer_delivery_queue else R.string.composer_delivery_steer),
                selected = true,
                onClick = { onDeliveryChange(if (queued) Delivery.Steer else Delivery.Queue) },
            )
            OpenCodeChip(
                label = stringResource(R.string.composer_resume),
                selected = state.resume,
                onClick = { onResumeChange(!state.resume) },
            )
        }
        if (state.pending.isNotEmpty()) {
            OpenCodeChip(
                label = stringResource(R.string.composer_inbox, state.pending.size),
                icon = Icons.Outlined.Inbox,
                selected = true,
                onClick = onOpenInbox,
            )
        }
    }
}

/**
 * Everything the TUI has on keys, behind the composer's + button: attach, skills, history, stash, the full editor,
 * and the agent and variant cycles. They were a row of seven unlabelled icons under the box; here each one has its
 * name, and the box gets the room back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ComposerActionsSheet(
    state: ComposerUiState,
    onDismiss: () -> Unit,
    onAttach: () -> Unit,
    onOpenSkills: () -> Unit,
    onOlderHistory: () -> Unit,
    onNewerHistory: () -> Unit,
    onStash: () -> Unit,
    onOpenStash: () -> Unit,
    onOpenEditor: () -> Unit,
    onCycleAgent: () -> Unit,
    onCycleVariant: () -> Unit,
) {
    val spacing = OpenCodeThemeExtras.spacing
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = spacing.xl)) {
            val run: (() -> Unit) -> () -> Unit = { action ->
                {
                    onDismiss()
                    action()
                }
            }
            SheetAction(Icons.Filled.AttachFile, stringResource(R.string.composer_attach), run(onAttach))
            SheetAction(Icons.Filled.AutoAwesome, stringResource(R.string.composer_add_skill), run(onOpenSkills))
            SheetAction(Icons.Filled.OpenInFull, stringResource(R.string.composer_editor), run(onOpenEditor))
            HorizontalDivider(Modifier.padding(vertical = spacing.xs), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            SheetAction(Icons.Filled.KeyboardArrowUp, stringResource(R.string.composer_history_older), run(onOlderHistory))
            SheetAction(Icons.Filled.KeyboardArrowDown, stringResource(R.string.composer_history_newer), run(onNewerHistory))
            SheetAction(
                Icons.Filled.Inventory2,
                stringResource(R.string.composer_stash),
                run(onStash),
                enabled = state.text.isNotBlank(),
            )
            SheetAction(
                Icons.Filled.Unarchive,
                stringResource(R.string.composer_stash_pop),
                run(onOpenStash),
                enabled = state.stash.isNotEmpty(),
            )
            if (state.nextAgent != null || state.nextVariant?.let { it != state.variant } == true) {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = spacing.xs),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                )
            }
            // The cycle buttons the plan asks for: one tap to the next agent or the next reasoning effort.
            if (state.nextAgent != null) {
                SheetAction(Icons.Outlined.SmartToy, stringResource(R.string.composer_next_agent), run(onCycleAgent))
            }
            state.nextVariant?.takeIf { it != state.variant }?.let { next ->
                SheetAction(Icons.Outlined.Tune, stringResource(R.string.model_variant, next), run(onCycleVariant))
            }
        }
    }
}

@Composable
private fun SheetAction(icon: ImageVector, label: String, onClick: () -> Unit, enabled: Boolean = true) {
    val alpha = if (enabled) 1f else DISABLED_ALPHA
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = OpenCodeThemeExtras.spacing.xl, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha))
        Spacer(Modifier.width(20.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha))
    }
}

private const val DISABLED_ALPHA = 0.38f

/**
 * Stop and Background, for as long as a turn runs.
 *
 * Text buttons, because the two words say what they do and a pair of icons would be one more thing to
 * learn, and a [FlowRow] so that a long translation or a large font wraps the second button under the
 * first instead of clipping it. They sit at the start of the row, away from Send at the bottom end:
 * the tap that was aiming for Send must not be able to land on Background.
 */
@Composable
private fun RunningTurnActions(onInterrupt: () -> Unit, onBackground: () -> Unit, modifier: Modifier = Modifier) {
    FlowRow(
        modifier = modifier.fillMaxWidth().padding(top = OpenCodeThemeExtras.spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(OpenCodeThemeExtras.spacing.xs),
    ) {
        TextButton(onClick = onInterrupt) { Text(stringResource(R.string.composer_interrupt)) }
        TextButton(onClick = onBackground) { Text(stringResource(R.string.composer_background)) }
    }
}

/**
 * One icon-only action on the composer's button row.
 *
 * Icon buttons, and each one carries a content description: a row of five-word labels would not fit
 * beside the send button on a 360 dp phone, and an icon with no name is unusable with TalkBack.
 * Every button is 48 dp, which is the smallest touch target Android considers reliable.
 */
@Composable
private fun ComposerAction(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.size(48.dp).semantics { contentDescription = description },
    ) {
        Icon(icon, contentDescription = null)
    }
}

/**
 * The send button.
 *
 * The long press is the queue affordance, and it says so: a gesture that changes what happens has to
 * be discoverable, which is why the long-click label names it and why the delivery chip above shows
 * the mode a plain send uses.
 */
@Composable
fun SendButton(
    enabled: Boolean,
    sending: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    queueOnLongPress: Boolean = false,
    onLongClick: () -> Unit = {},
) {
    val longPressLabel = stringResource(R.string.composer_queue_on_long_press)
    val send = stringResource(R.string.composer_send)
    val sendingState = stringResource(R.string.composer_sending)
    val container = if (enabled) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val content = if (enabled) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    // A clickable Box rather than an IconButton: the Material icon buttons have no long-click
    // overload, and the queue gesture is the point of this button.
    Box(
        modifier = modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(container)
            .combinedClickable(
                enabled = enabled,
                onClick = onClick,
                onLongClick = onLongClick.takeIf { queueOnLongPress },
                onLongClickLabel = longPressLabel.takeIf { queueOnLongPress },
            )
            .semantics {
                contentDescription = if (queueOnLongPress) "$send. $longPressLabel" else send
                // The spinner has no words, so the state is said here, with a string that already existed
                // and was never used.
                if (sending) stateDescription = sendingState
            },
        contentAlignment = Alignment.Center,
    ) {
        if (sending) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = content)
        } else {
            Icon(Icons.Filled.ArrowUpward, contentDescription = null, tint = content)
        }
    }
}

/** A failed action, with the wording the composition root resolved. */
@Composable
private fun ErrorRow(message: String, onDismiss: () -> Unit) {
    val spacing = OpenCodeThemeExtras.spacing
    Surface(
        modifier = Modifier.fillMaxWidth().padding(bottom = spacing.sm),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Row(
            modifier = Modifier.padding(start = spacing.md, end = spacing.xs, top = spacing.xs, bottom = spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.composer_dismiss)) }
        }
    }
}

/** The "no model available" state, which is the plan's one empty state a user can act on. */
@Composable
fun NoModelAvailable(modifier: Modifier = Modifier) {
    val spacing = OpenCodeThemeExtras.spacing
    Surface(
        modifier = modifier.fillMaxWidth().padding(spacing.md),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Column(Modifier.padding(spacing.md), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            Text(stringResource(R.string.model_none_available), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.model_none_available_body), style = MaterialTheme.typography.bodySmall)
        }
    }
}
