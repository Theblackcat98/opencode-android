package dev.opencode.android.feature.composer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.opencode.android.core.data.composer.Completion
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
    var field by remember(state.text) { mutableStateOf(TextFieldValue(state.text)) }
    LaunchedEffect(state.text) {
        if (field.text != state.text) {
            field = TextFieldValue(state.text, TextRange(state.text.length))
        }
    }
    Surface(modifier = modifier.fillMaxWidth(), tonalElevation = 3.dp) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            // A server with no usable model cannot start a turn, and a composer that silently does
            // nothing is the worst way to learn that. The plan's empty state says how to fix it;
            // logging in from the phone is Phase 8.
            if (!state.hasAnyModel) {
                NoModelAvailable()
            }
            errorMessage?.let { ErrorRow(message = it, onDismiss = onDismissError) }
            ComposerProblemRow(state = state, onSendAnyway = onSendConfirmed)
            OutlinedTextField(
                value = field,
                onValueChange = { edited: TextFieldValue ->
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
                modifier = Modifier.fillMaxWidth(),
                maxLines = 6,
                enabled = !state.sending,
            )
            SearchProgress(visible = state.searchingFiles)
            CompletionList(completions = state.completions, onSelect = onSelectCompletion)
            AttachmentChips(attachments = state.attachments, onRemove = onRemoveAttachment)
            ComposerContextRow(state = state)
            // The chips get their own line. Sharing one with Stop, Background and send meant the
            // delivery toggle — the control the plan puts first — sat behind a horizontal scroll and
            // was clipped by the buttons beside it.
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The cycle buttons the plan asks for: one tap to the next agent or the next
                // reasoning effort, without opening a picker.
                if (state.nextAgent != null) {
                    AssistChip(
                        onClick = onCycleAgent,
                        label = { Text(stringResource(R.string.composer_next_agent)) },
                    )
                }
                state.nextVariant?.takeIf { it != state.variant }?.let { next ->
                    AssistChip(
                        onClick = onCycleVariant,
                        label = { Text(stringResource(R.string.model_variant, next)) },
                    )
                }
                FilterChip(
                    selected = state.delivery == Delivery.Queue,
                    onClick = {
                        onDeliveryChange(
                            if (state.delivery == Delivery.Queue) Delivery.Steer else Delivery.Queue,
                        )
                    },
                    label = {
                        Text(
                            stringResource(
                                if (state.delivery == Delivery.Queue) {
                                    R.string.composer_delivery_queue
                                } else {
                                    R.string.composer_delivery_steer
                                },
                            ),
                        )
                    },
                )
                FilterChip(
                    selected = state.resume,
                    onClick = { onResumeChange(!state.resume) },
                    label = { Text(stringResource(R.string.composer_resume)) },
                )
                state.agent?.let { agent ->
                    AssistChip(
                        onClick = onOpenAgentPicker,
                        label = { Text(agent, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                }
                state.model?.let { model ->
                    AssistChip(
                        onClick = onOpenModelPicker,
                        label = {
                            Text(
                                model.variant?.let { "${model.id}#$it" } ?: model.id,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                    )
                }
                if (state.pending.isNotEmpty()) {
                    AssistChip(
                        onClick = onOpenInbox,
                        label = { Text(stringResource(R.string.composer_inbox, state.pending.size)) },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        ),
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                // The ergonomics the TUI has on keys are buttons here, because a phone has no up-arrow
                // to bind and a chip row that scrolls is a row whose last items nobody finds. They sit
                // on the send row, which is otherwise empty, so they cost no vertical space.
                ComposerAction(
                    icon = Icons.Filled.AttachFile,
                    description = stringResource(R.string.composer_attach),
                    onClick = onAttach,
                )
                ComposerAction(
                    icon = Icons.Filled.AutoAwesome,
                    description = stringResource(R.string.composer_add_skill),
                    onClick = onOpenSkills,
                )
                ComposerAction(
                    icon = Icons.Filled.KeyboardArrowUp,
                    description = stringResource(R.string.composer_history_older),
                    onClick = onOlderHistory,
                )
                ComposerAction(
                    icon = Icons.Filled.KeyboardArrowDown,
                    description = stringResource(R.string.composer_history_newer),
                    onClick = onNewerHistory,
                )
                ComposerAction(
                    icon = Icons.Filled.Inventory2,
                    description = stringResource(R.string.composer_stash),
                    onClick = onStash,
                    enabled = state.text.isNotBlank(),
                )
                ComposerAction(
                    icon = Icons.Filled.Unarchive,
                    description = stringResource(R.string.composer_stash_pop),
                    onClick = onOpenStash,
                    enabled = state.stash.isNotEmpty(),
                )
                ComposerAction(
                    icon = Icons.Filled.OpenInFull,
                    description = stringResource(R.string.composer_editor),
                    onClick = onOpenEditor,
                )
                Spacer(Modifier.weight(1f))
                if (state.busy) {
                    TextButton(onClick = { onInterrupt(false) }) {
                        Text(stringResource(R.string.composer_interrupt))
                    }
                    TextButton(onClick = onBackground) {
                        Text(stringResource(R.string.composer_background))
                    }
                }
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
    icon: androidx.compose.ui.graphics.vector.ImageVector,
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
    val container = if (enabled) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
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
            .semantics { contentDescription = if (queueOnLongPress) "$send. $longPressLabel" else send },
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
    Surface(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
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
    Surface(
        modifier = modifier.fillMaxWidth().padding(12.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.model_none_available), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.model_none_available_body), style = MaterialTheme.typography.bodySmall)
        }
    }
}
