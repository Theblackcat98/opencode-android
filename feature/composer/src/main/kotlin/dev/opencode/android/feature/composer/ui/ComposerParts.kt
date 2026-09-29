package dev.opencode.android.feature.composer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.opencode.android.core.data.composer.AttachmentDraft
import dev.opencode.android.core.data.composer.AttachmentKind
import dev.opencode.android.core.data.composer.Completion
import dev.opencode.android.core.data.composer.CompletionKind
import dev.opencode.android.core.designsystem.format.Formatters
import dev.opencode.android.feature.composer.R

/**
 * The completion list under the text field: `@` mentions and `/` commands in one row list.
 *
 * **The row says three things, because a completion has three useful facts**: what it is, what it is
 * called, and where it points. A path relative to the project is what a person recognises; the
 * absolute path is what the request will carry; the kind is what decides whether the result is a
 * file, an agent or a command, and therefore what a screen reader should announce before the user
 * commits to it.
 *
 * The list is a [LazyColumn] with stable keys, because it is rebuilt on every keystroke and a row
 * that moves under a finger is a row that gets tapped by mistake.
 */
@Composable
fun CompletionList(
    completions: List<Completion>,
    onSelect: (Completion) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (completions.isEmpty()) return
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 2.dp,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = MAX_LIST_HEIGHT),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            items(completions, key = { it.key }) { completion ->
                CompletionRow(completion = completion, onSelect = { onSelect(completion) })
            }
        }
    }
}

@Composable
private fun CompletionRow(completion: Completion, onSelect: () -> Unit) {
    val kind = stringResource(completion.kind.labelRes())
    val detail = completion.detail
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .clearAndSetSemantics {
                // One announcement per row: kind, label and target, in that order, because that is
                // the order a person deciding between them needs them.
                contentDescription = "$kind. ${completion.label}" + (detail?.let { ". $it" } ?: "")
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(completion.accent(), androidx.compose.foundation.shape.CircleShape),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = completion.label,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (detail != null && detail != completion.label) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = kind,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The colour that tells the kinds apart at a glance. Colour alone is not the only cue: the label is. */
@Composable
private fun Completion.accent(): Color = when (kind) {
    CompletionKind.FILE -> MaterialTheme.colorScheme.tertiary
    CompletionKind.DIRECTORY, CompletionKind.REFERENCE -> MaterialTheme.colorScheme.primary
    CompletionKind.AGENT -> MaterialTheme.colorScheme.secondary
    CompletionKind.COMMAND, CompletionKind.MCP_COMMAND -> MaterialTheme.colorScheme.error
    CompletionKind.CLIENT_COMMAND -> MaterialTheme.colorScheme.primary
}

private fun CompletionKind.labelRes(): Int = when (this) {
    CompletionKind.FILE -> R.string.completion_file
    CompletionKind.DIRECTORY -> R.string.completion_directory
    CompletionKind.REFERENCE -> R.string.completion_reference
    CompletionKind.AGENT -> R.string.completion_agent
    CompletionKind.COMMAND -> R.string.completion_command
    CompletionKind.MCP_COMMAND -> R.string.completion_mcp_command
    CompletionKind.CLIENT_COMMAND -> R.string.completion_client_command
}

/**
 * The chips for what this prompt will carry.
 *
 * A chip is removable **before** the send, which is the point of showing them here rather than only in
 * the transcript: a picture the model cannot see is cheapest to remove now. The size and the type are
 * on the chip because "it is too big" and "the model will not see it" are both things a person wants
 * to know before they press send, and because a photo with no name is not identifiable.
 */
@Composable
fun AttachmentChips(
    attachments: List<AttachmentDraft>,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (attachments.isEmpty()) return
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            attachments.forEach { attachment ->
                AttachmentChip(attachment = attachment, onRemove = { onRemove(attachment.id) })
            }
        }
    }
}

@Composable
private fun AttachmentChip(attachment: AttachmentDraft, onRemove: () -> Unit) {
    val kind = stringResource(attachment.kind.labelRes())
    val size = attachment.sizeBytes
        .takeIf { it > 0 }
        ?.let { stringResource(R.string.composer_attachment_size, Formatters.bytes(it)) }
    val range = attachment.range?.toSuffix()
    val remove = stringResource(R.string.composer_attachment_remove, attachment.label)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = listOfNotNull(attachment.label, kind, range, size).joinToString(". ")
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = attachment.kind.glyph(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = listOfNotNull(attachment.label, range).joinToString(" "),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOfNotNull(kind, size).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onRemove, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Filled.Close, contentDescription = remove)
        }
    }
}

private fun AttachmentKind.labelRes(): Int = when (this) {
    AttachmentKind.IMAGE -> R.string.composer_attachment_image
    AttachmentKind.TEXT -> R.string.composer_attachment_text
    AttachmentKind.BINARY -> R.string.composer_attachment_binary
    AttachmentKind.DIRECTORY -> R.string.composer_attachment_directory
}

/**
 * The glyph is a second cue, not the only one: a screen reader reads the kind, and the row says what
 * it is in words. Two of the four are the same shape because a file and a directory are told apart by
 * their names anyway.
 */
private fun AttachmentKind.glyph(): String = when (this) {
    AttachmentKind.IMAGE -> "▣"
    AttachmentKind.TEXT -> "≡"
    AttachmentKind.BINARY -> "▤"
    AttachmentKind.DIRECTORY -> "▸"
}

/**
 * The "searching" line under a mention, so an empty list is not the same as a slow one.
 *
 * No content description and no `semantics` block: a determinate-free progress indicator already
 * announces itself as indeterminate, and an empty description would make it a node that says
 * nothing, which is worse than a node that says the right thing.
 */
@Composable
fun SearchProgress(visible: Boolean, modifier: Modifier = Modifier) {
    if (!visible) return
    Box(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}

/** Tall enough for about five rows, which is what a thumb reaches without scrolling. */
private val MAX_LIST_HEIGHT = 220.dp
