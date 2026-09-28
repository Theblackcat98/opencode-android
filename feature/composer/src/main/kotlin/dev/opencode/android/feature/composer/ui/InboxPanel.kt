package dev.opencode.android.feature.composer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.opencode.android.core.data.timeline.PendingInboxItem
import dev.opencode.android.core.model.Delivery
import dev.opencode.android.core.model.InboxItem
import dev.opencode.android.feature.composer.R

/**
 * The inbox panel: what is still waiting to be sent, and what can be done about it
 * (plan §6, Composer, "An inbox panel lists items, cancels them, and switches their delivery").
 *
 * **The list is the server's, not a draft buffer.** The items come from `TimelineState.pending`,
 * which the `session.inbox.*` events keep, so an item that was delivered or cancelled by another
 * client disappears on its own. Cancelling and switching delivery are REST calls confirmed by
 * `session.inbox.cancelled` and `session.inbox.delivery.changed`, so the chip flips when the server
 * agrees rather than when the button is pressed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxPanel(
    items: List<PendingInboxItem>,
    onCancel: (String) -> Unit,
    onDeliveryChange: (String, Delivery) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, modifier = modifier) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.inbox_title), style = MaterialTheme.typography.titleMedium)
            if (items.isEmpty()) {
                Text(
                    text = stringResource(R.string.inbox_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }
            items.forEach { pending ->
                HorizontalDivider()
                InboxRow(pending, onCancel, onDeliveryChange)
            }
        }
    }
}

/** One pending item: its text, how it will be delivered, and the two things that can change it. */
@Composable
private fun InboxRow(
    pending: PendingInboxItem,
    onCancel: (String) -> Unit,
    onDeliveryChange: (String, Delivery) -> Unit,
) {
    val delivery = pending.item.delivery
    val isQueue = delivery == Delivery.Queue
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(
            text = stringResource(R.string.inbox_pending),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = summaryOf(pending.item),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 3,
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val other = if (isQueue) Delivery.Steer else Delivery.Queue
            AssistChip(
                onClick = { onDeliveryChange(pending.id, other) },
                label = {
                    Text(
                        stringResource(
                            if (isQueue) R.string.inbox_make_steer else R.string.inbox_make_queue,
                        ),
                    )
                },
                leadingIcon = {
                    Text(
                        text = stringResource(
                            if (isQueue) R.string.inbox_delivery_steer else R.string.inbox_delivery_queue,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                    )
                },
            )
            val cancel = stringResource(R.string.inbox_cancel)
            IconButton(
                onClick = { onCancel(pending.id) },
                modifier = Modifier.semantics { contentDescription = cancel },
            ) {
                Icon(Icons.Filled.Close, contentDescription = null)
            }
        }
    }
}

/** The text of a pending item, which is the only part a user needs to recognise it by. */
internal fun summaryOf(item: InboxItem): String = when (item) {
    is InboxItem.User -> item.payload.text
    is InboxItem.Synthetic -> item.payload.description ?: item.payload.text
    is InboxItem.Compaction -> NO_SUMMARY
    is InboxItem.Move -> item.payload.location.directory
    is InboxItem.Unknown -> item.raw.toString()
}

/**
 * The summary for the item kinds a composer cannot create.
 *
 * Phase 5 adds `/compact` and the move action, which are the only sources of those two; until then
 * there is nothing to name, and a dash is better than a label for a feature that does not exist.
 */
private const val NO_SUMMARY = "—"
