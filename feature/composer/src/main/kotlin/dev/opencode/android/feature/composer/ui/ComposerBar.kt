package dev.opencode.android.feature.composer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.unit.dp
import dev.opencode.android.core.model.Delivery
import dev.opencode.android.feature.composer.R

/**
 * The composer: text, delivery, and the buttons that control a running turn.
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
 */
@Composable
fun ComposerBar(
    state: ComposerUiState,
    onTextChange: (String) -> Unit,
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
    Surface(modifier = modifier.fillMaxWidth(), tonalElevation = 3.dp) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            // A server with no usable model cannot start a turn, and a composer that silently does
            // nothing is the worst way to learn that. The plan's empty state says how to fix it;
            // logging in from the phone is Phase 8.
            if (!state.hasAnyModel) {
                NoModelAvailable()
            }
            errorMessage?.let { ErrorRow(message = it, onDismiss = onDismissError) }
            OutlinedTextField(
                value = state.text,
                onValueChange = onTextChange,
                placeholder = { Text(stringResource(R.string.composer_hint)) },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 6,
                enabled = !state.sending,
            )
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
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
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
