package dev.opencode.android.feature.sessions.ui.timeline

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.opencode.android.core.designsystem.format.Formatters
import dev.opencode.android.core.designsystem.markdown.MarkdownText
import dev.opencode.android.core.designsystem.theme.OpenCodeThemeExtras
import dev.opencode.android.core.model.AssistantContent
import dev.opencode.android.core.model.Outcome
import dev.opencode.android.core.model.SessionMessage
import dev.opencode.android.feature.sessions.R

/**
 * The transcript.
 *
 * **Newest at the bottom, keyed rows, bounded pages.** A chat transcript reads bottom-up, and
 * `reverseLayout` puts the newest message at the bottom without reversing the model: the list is
 * then a plain `LazyColumn` whose keys are message ids, so a streaming `text.delta` that grows the
 * last message does not re-key anything and a 1,000-message session recycles rows the normal way
 * (plan §5.4).
 *
 * Every block in features doc §5 has a renderer here, and an unrecognised type falls through to
 * [UnknownMessage] rather than disappearing.
 */
@Composable
fun TimelineList(
    messages: List<SessionMessage>,
    loadingOlder: Boolean,
    hasMore: Boolean,
    following: Boolean,
    onLoadOlder: () -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(12.dp),
    onOpenChangedFile: ((String) -> Unit)? = null,
    messageActions: (@Composable (String) -> Unit)? = null,
) {
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "older") {
            OlderMessagesRow(loadingOlder = loadingOlder, hasMore = hasMore, onLoadOlder = onLoadOlder)
        }
        items(
            items = messages,
            key = { it.stableKey() },
        ) { message ->
            Column {
                TimelineMessageItem(message, onOpenChangedFile = onOpenChangedFile)
                // The per-message actions row belongs to the message and not to a card, so a
                // message with no action simply renders nothing extra.
                messageActions?.invoke(message.id)
            }
        }
        if (following) {
            item(key = "following") {
                Text(
                    text = stringResource(R.string.session_following),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun OlderMessagesRow(
    loadingOlder: Boolean,
    hasMore: Boolean,
    onLoadOlder: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when {
            loadingOlder -> {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(
                    text = stringResource(R.string.session_loading_older),
                    style = MaterialTheme.typography.labelSmall,
                )
            }

            hasMore -> Text(
                text = stringResource(R.string.session_load_older),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable(onClick = onLoadOlder)
                    .padding(8.dp),
            )

            else -> Text(
                text = stringResource(R.string.session_start_of_history),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(8.dp),
            )
        }
    }
}

/** One message, whatever its type. */
@Composable
fun TimelineMessageItem(
    message: SessionMessage,
    modifier: Modifier = Modifier,
    /**
     * Opens a file this message changed, which is Phase 6's "changed files per message".
     *
     * A callback rather than a screen because a feature may not import another one, and the review
     * viewer is another feature. `null` hides the links, which is what a caller with no review
     * destination to offer passes.
     */
    onOpenChangedFile: ((String) -> Unit)? = null,
) {
    when (message) {
        is SessionMessage.User -> UserMessageCard(message, modifier)
        is SessionMessage.Assistant -> AssistantMessageCard(message, modifier, onOpenChangedFile)
        is SessionMessage.Synthetic -> NoticeCard(
            title = stringResource(R.string.timeline_synthetic),
            body = message.text,
            description = message.description,
            modifier = modifier,
        )

        is SessionMessage.System -> NoticeCard(
            title = stringResource(R.string.timeline_system),
            body = message.text,
            description = message.description,
            modifier = modifier,
        )

        is SessionMessage.Skill -> NoticeCard(
            title = stringResource(R.string.timeline_skill, message.name),
            body = message.text,
            description = message.skill,
            modifier = modifier,
        )

        is SessionMessage.Shell -> ShellMessageCard(message, modifier)
        is SessionMessage.Compaction -> CompactionCard(message, modifier)
        is SessionMessage.Idle -> IdleDivider(message, modifier)
        is SessionMessage.AgentSwitched -> AgentSwitchMarker(message, modifier)
        is SessionMessage.ModelSwitched -> ModelSwitchMarker(message, modifier)
        is SessionMessage.LocationSwitched -> LocationSwitchMarker(message, modifier)
        is SessionMessage.Unknown -> UnknownMessage(message, modifier)
    }
}

@Composable
private fun UserMessageCard(message: SessionMessage.User, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = stringResource(R.string.timeline_user),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(text = message.text, style = MaterialTheme.typography.bodyLarge)
            message.files?.takeIf { it.isNotEmpty() }?.let { files ->
                Spacer(Modifier.height(8.dp))
                Text(
                    text = if (files.size == 1) {
                        stringResource(R.string.timeline_attachment_one)
                    } else {
                        stringResource(R.string.timeline_attachment_count, files.size)
                    },
                    style = MaterialTheme.typography.labelSmall,
                )
                files.forEach { file ->
                    AttachmentChip(
                        label = file.name ?: file.mime,
                        isImage = file.mime.startsWith("image/"),
                    )
                }
            }
            message.agents?.takeIf { it.isNotEmpty() }?.let { agents ->
                Text(
                    text = stringResource(R.string.timeline_agents_mentioned, agents.joinToString(", ") { it.name }),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            message.skills?.takeIf { it.isNotEmpty() }?.let { skills ->
                Text(
                    text = stringResource(R.string.timeline_skills_attached, skills.joinToString(", ") { it.name }),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

/**
 * An attachment chip.
 *
 * The image itself is a `data:` URL of base64 the server stored; decoding it is Phase 3's image
 * pipeline, so the chip names the file and the renderer is where a thumbnail goes. Saying so here
 * keeps the placeholder honest rather than pretending to be a thumbnail.
 */
@Composable
private fun AttachmentChip(label: String, isImage: Boolean) {
    val description = if (isImage) {
        stringResource(R.string.timeline_image_attachment, label)
    } else {
        stringResource(R.string.timeline_file_attachment, label)
    }
    AssistChip(
        onClick = {},
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        colors = AssistChipDefaults.assistChipColors(),
        modifier = Modifier
            .padding(top = 4.dp)
            .semantics { contentDescription = description },
    )
}

@Composable
private fun AssistantMessageCard(
    message: SessionMessage.Assistant,
    modifier: Modifier = Modifier,
    onOpenChangedFile: ((String) -> Unit)? = null,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = message.agent,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = message.model.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            (message.time.completed ?: 0L).takeIf { it > 0L }?.let { completed ->
                val ran = completed - message.time.created
                if (ran > 0) {
                    Text(
                        text = Formatters.duration(ran),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        // The step's own snapshot is the first thing a reviewer wants: it names every file the turn
        // changed, whether or not a tool card said so (features doc §5, "assistant.snapshot").
        if (onOpenChangedFile != null) {
            ChangedFilesRow(
                files = dev.opencode.android.core.data.review.ChangedFiles.fromSnapshot(message.snapshot?.files),
                onOpenFile = onOpenChangedFile,
            )
        }
        message.content.forEach { part ->
            when (part) {
                is AssistantContent.Text -> if (part.text.isNotBlank()) {
                    MarkdownText(
                        markdown = part.text,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }

                is AssistantContent.Reasoning -> ReasoningBlock(part, Modifier.padding(top = 4.dp))
                is AssistantContent.Tool -> {
                    val card = part.toCard()
                    ToolCardView(card, Modifier.padding(top = 4.dp))
                    if (onOpenChangedFile != null) {
                        ChangedFilesRow(files = card.changedFiles, onOpenFile = onOpenChangedFile)
                    }
                }

                is AssistantContent.Unknown -> UnknownTool(part, Modifier.padding(top = 4.dp))
            }
        }
        message.error?.let { error ->
            StepErrorCard(error, Modifier.padding(top = 4.dp))
        }
        message.retry?.let { retry ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
            ) {
                Text(
                    text = stringResource(
                        R.string.step_retry_scheduled,
                        Formatters.duration((retry.at - retry.at).coerceAtLeast(0)),
                        retry.attempt,
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(8.dp),
                )
            }
        }
    }
}

/**
 * Reasoning, collapsed by default.
 *
 * A reasoning block is often the longest thing in a turn and is rarely the point, so it starts
 * folded behind a one-line summary with its duration; the expand control carries a description
 * because the chevron alone says nothing to TalkBack.
 */
@Composable
fun ReasoningBlock(part: AssistantContent.Reasoning, modifier: Modifier = Modifier) {
    var expanded by remember(part.id(), part.text.length) { mutableStateOf(false) }
    val duration = (part.time?.completed ?: 0L).takeIf { it > 0L }?.let { it - (part.time?.created ?: it) }
    val toggleLabel = stringResource(if (expanded) R.string.timeline_collapse else R.string.timeline_expand)
    val reasonLabel = stringResource(R.string.timeline_reasoning)
    val durationLabel = duration?.let { stringResource(R.string.timeline_reasoning_duration, Formatters.duration(it)) }
    Card(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(8.dp)
                .semantics { contentDescription = "$reasonLabel, ${durationLabel.orEmpty()}, $toggleLabel" },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.timeline_reasoning),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
            duration?.let {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.timeline_reasoning_duration, Formatters.duration(it)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.weight(1f))
            Icon(
                imageVector = Icons.Filled.ExpandMore,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
        }
        if (expanded) {
            Text(
                text = part.text,
                style = OpenCodeThemeExtras.code.small,
                modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
            )
        }
    }
}

@Composable
private fun AssistantContent.Reasoning.id(): String = "reasoning-${text.hashCode()}-${time?.created ?: 0}"

@Composable
private fun NoticeCard(
    title: String,
    body: String,
    description: String?,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(text = title, style = MaterialTheme.typography.labelMedium)
            description?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(text = body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ShellMessageCard(message: SessionMessage.Shell, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = stringResource(R.string.timeline_shell_command),
                style = MaterialTheme.typography.labelMedium,
            )
            Text(
                text = stringResource(R.string.tool_command, message.command),
                style = OpenCodeThemeExtras.code.small,
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = message.status.value,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                message.exit?.let {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "exit $it",
                        style = OpenCodeThemeExtras.code.small,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            message.output?.let { output ->
                Text(
                    text = stringResource(R.string.tool_output),
                    style = MaterialTheme.typography.labelSmall,
                )
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(6.dp),
                ) {
                    Text(
                        text = output.output,
                        style = OpenCodeThemeExtras.code.small,
                        modifier = Modifier
                            .padding(8.dp)
                            .horizontalScroll(rememberScrollState()),
                    )
                }
            }
        }
    }
}

@Composable
private fun CompactionCard(message: SessionMessage.Compaction, modifier: Modifier = Modifier) {
    val title = when (message.status) {
        SessionMessage.Compaction.RUNNING -> stringResource(R.string.compaction_running)
        SessionMessage.Compaction.COMPLETED -> stringResource(R.string.compaction_completed)
        else -> stringResource(R.string.compaction_failed)
    }
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(text = title, style = MaterialTheme.typography.labelMedium)
            Text(
                text = stringResource(
                    if (message.reason.value == "manual") {
                        R.string.compaction_reason_manual
                    } else {
                        R.string.compaction_reason_auto
                    },
                ),
                style = MaterialTheme.typography.labelSmall,
            )
            if (message.status == SessionMessage.Compaction.RUNNING) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            message.summary?.takeIf { it.isNotBlank() }?.let { summary ->
                Text(text = stringResource(R.string.compaction_summary), style = MaterialTheme.typography.labelSmall)
                Text(text = summary, style = MaterialTheme.typography.bodySmall)
            }
            message.error?.let { error ->
                StepErrorCard(error, Modifier.padding(top = 4.dp))
            }
        }
    }
}

@Composable
private fun IdleDivider(message: SessionMessage.Idle, modifier: Modifier = Modifier) {
    val outcomeDescription = stringResource(message.outcome.labelRes())
    Column(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = outcomeDescription },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
        Text(
            text = stringResource(message.outcome.labelRes()),
            style = MaterialTheme.typography.labelSmall,
            color = when (message.outcome) {
                Outcome.Failed -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        HorizontalDivider()
    }
}

@Composable
private fun AgentSwitchMarker(message: SessionMessage.AgentSwitched, modifier: Modifier = Modifier) {
    val previous = message.previous
    val text = if (previous != null) {
        stringResource(R.string.marker_agent_switched_from, previous, message.agent)
    } else {
        stringResource(R.string.marker_agent_switched, message.agent)
    }
    SwitchMarker(text = text, modifier = modifier)
}

@Composable
private fun ModelSwitchMarker(message: SessionMessage.ModelSwitched, modifier: Modifier = Modifier) {
    val previous = message.previous
    val text = if (previous != null) {
        stringResource(R.string.marker_model_switched_from, previous.toString(), message.model.toString())
    } else {
        stringResource(R.string.marker_model_switched, message.model.toString())
    }
    SwitchMarker(text = text, modifier = modifier)
}

@Composable
private fun LocationSwitchMarker(message: SessionMessage.LocationSwitched, modifier: Modifier = Modifier) {
    val to = Formatters.directoryName(message.location.directory)
    val previous = message.previous
    val text = if (previous != null) {
        stringResource(
            R.string.marker_location_switched_from,
            Formatters.directoryName(previous.location.directory),
            to,
        )
    } else {
        stringResource(R.string.marker_location_switched, to)
    }
    SwitchMarker(text = text, modifier = modifier)
}

@Composable
private fun SwitchMarker(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = text },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HorizontalDivider(modifier = Modifier.weight(1f))
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        HorizontalDivider(modifier = Modifier.weight(1f))
    }
}

/** A step error, and the retry that follows it. */
@Composable
fun StepErrorCard(error: dev.opencode.android.core.model.StructuredError, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text(
                text = stringResource(R.string.step_error),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                text = stringResource(R.string.step_error_type, error.type, error.message),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

/**
 * A message type this client does not know.
 *
 * Rendered as a labelled card with its id rather than skipped: an unknown type is a server that has
 * moved on, and seeing that it happened is the only way a user can report it usefully.
 */
@Composable
fun UnknownMessage(message: SessionMessage.Unknown, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = stringResource(R.string.unknown_message_type, message.discriminator ?: "?"),
                style = MaterialTheme.typography.labelMedium,
            )
            Text(
                text = stringResource(R.string.unknown_event_logged),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(text = message.id, style = OpenCodeThemeExtras.code.small)
        }
    }
}

@Composable
private fun UnknownTool(part: AssistantContent.Unknown, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Text(
            text = stringResource(R.string.unknown_tool_type, part.discriminator ?: "?"),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(12.dp),
        )
    }
}
