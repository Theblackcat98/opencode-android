package dev.opencode.android.feature.sessions.ui.timeline

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.Psychology
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.opencode.android.core.designsystem.component.OpenCodeAvatar
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
    /** Opens a subagent's session from its tool row. Null shows the session id instead of a link. */
    onOpenSession: ((String) -> Unit)? = null,
    /**
     * Whether a turn is in flight. Only when none is does the newest turn-end divider announce itself.
     *
     * Every turn's divider is composed on load and again each time its row scrolls back into view, so a
     * live region on each would have a screen reader read out the whole history. The newest divider is
     * the only one that can be the turn that just ended, so it is the only one that carries the region.
     */
    sessionRunning: Boolean = false,
) {
    val announcedTurnEnd = if (sessionRunning) null else messages.lastOrNull { it is SessionMessage.Idle }?.id
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "older") {
            OlderMessagesRow(loadingOlder = loadingOlder, hasMore = hasMore, onLoadOlder = onLoadOlder)
        }
        items(
            items = messages,
            key = { it.stableKey() },
        ) { message ->
            Column {
                TimelineMessageItem(
                    message,
                    onOpenChangedFile = onOpenChangedFile,
                    onOpenSession = onOpenSession,
                    announceTurnEnd = message.id == announcedTurnEnd,
                )
                // The per-message actions row belongs to the message and not to a card, so a
                // message with no action simply renders nothing extra.
                messageActions?.invoke(message.id)
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
    /** Whether this divider is the turn that just ended and should be announced; see [TimelineList]. */
    announceTurnEnd: Boolean = false,
    onOpenSession: ((String) -> Unit)? = null,
) {
    when (message) {
        is SessionMessage.User -> UserMessageCard(message, modifier)

        is SessionMessage.Assistant -> AssistantMessageCard(message, modifier, onOpenChangedFile, onOpenSession)

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

        is SessionMessage.Idle -> IdleDivider(message, modifier, announceTurnEnd)

        is SessionMessage.AgentSwitched -> AgentSwitchMarker(message, modifier)

        is SessionMessage.ModelSwitched -> ModelSwitchMarker(message, modifier)

        is SessionMessage.LocationSwitched -> LocationSwitchMarker(message, modifier)

        is SessionMessage.Unknown -> UnknownMessage(message, modifier)
    }
}

/**
 * What the user sent, as a bubble on the right. The "You" label is gone from sight, since the side says it, but a
 * screen reader still hears it first, because it cannot see which side a bubble is on.
 */
@Composable
private fun UserMessageCard(message: SessionMessage.User, modifier: Modifier = Modifier) {
    val you = stringResource(R.string.timeline_user)
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val bubbleMax = maxWidth * USER_BUBBLE_WIDTH
        Surface(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .widthIn(max = bubbleMax)
                .semantics(mergeDescendants = true) { contentDescription = "$you: ${message.text}" },
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 4.dp),
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(text = message.text, style = MaterialTheme.typography.bodyLarge)
                message.files?.takeIf { it.isNotEmpty() }?.let { files ->
                    Spacer(Modifier.height(6.dp))
                    files.forEach { file ->
                        AttachmentChip(
                            label = file.name ?: file.mime,
                            isImage = file.mime.startsWith("image/"),
                            data = file.data,
                        )
                    }
                }
                message.agents?.takeIf { it.isNotEmpty() }?.let { agents ->
                    Text(
                        text = stringResource(R.string.timeline_agents_mentioned, agents.joinToString(", ") { it.name }),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 4.dp),
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
}

/** How much of the row a user bubble may take, so a short message reads as a reply and not as a banner. */
private const val USER_BUBBLE_WIDTH = 0.85f

/**
 * An attachment chip, with the image above it when the attachment is one this device can decode.
 *
 * The payload is the base64 the server stored. When it does not decode to an image, the chip
 * names the file alone.
 */
@Composable
private fun AttachmentChip(label: String, isImage: Boolean, data: String) {
    val description = if (isImage) {
        stringResource(R.string.timeline_image_attachment, label)
    } else {
        stringResource(R.string.timeline_file_attachment, label)
    }
    val thumbnail = remember(data, isImage) { if (isImage) decodeAttachmentImage(data) else null }
    var fullSize by remember { mutableStateOf(false) }
    // A chip with nothing to show says why under itself, and a second tap takes that line back away.
    var noPreview by remember { mutableStateOf(false) }
    Column {
        thumbnail?.let { bitmap ->
            Image(
                bitmap = bitmap,
                contentDescription = description,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .size(160.dp)
                    .clickable { fullSize = true },
            )
        }
        AssistChip(
            onClick = {
                if (thumbnail != null) {
                    fullSize = true
                } else {
                    noPreview = !noPreview
                }
            },
            label = { Text(label, style = MaterialTheme.typography.labelSmall) },
            colors = AssistChipDefaults.assistChipColors(),
            modifier = Modifier
                .padding(top = 4.dp)
                .semantics { contentDescription = description },
        )
        if (noPreview) {
            Text(
                text = stringResource(R.string.timeline_attachment_no_preview),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
    if (fullSize && thumbnail != null) {
        Dialog(onDismissRequest = { fullSize = false }) {
            Image(
                bitmap = thumbnail,
                contentDescription = description,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { fullSize = false },
            )
        }
    }
}

/**
 * What the agent did, full width and without a container: a small header (the agent, the model, how long the step
 * took), the files the step changed, then its parts in order, with runs of quiet tool calls folded into one row
 * ([assistantBlocks]).
 */
@Composable
private fun AssistantMessageCard(
    message: SessionMessage.Assistant,
    modifier: Modifier = Modifier,
    onOpenChangedFile: ((String) -> Unit)? = null,
    onOpenSession: ((String) -> Unit)? = null,
) {
    val blocks = remember(message.content) { assistantBlocks(message.content) }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OpenCodeAvatar(key = message.agent, label = message.agent, size = 20.dp)
            Spacer(Modifier.width(8.dp))
            Text(
                text = message.agent,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = message.model.id,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            (message.time.completed ?: 0L).takeIf { it > 0L }?.let { completed ->
                val ran = completed - message.time.created
                if (ran > 0) {
                    Text(
                        text = Formatters.duration(ran),
                        style = OpenCodeThemeExtras.code.small,
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
        blocks.forEach { block ->
            when (block) {
                is AssistantBlock.Tools -> ToolGroupView(
                    cards = block.cards,
                    onOpenChangedFile = onOpenChangedFile,
                    onOpenSession = onOpenSession,
                )

                is AssistantBlock.Part -> when (val part = block.part) {
                    is AssistantContent.Text -> if (part.text.isNotBlank()) {
                        MarkdownText(markdown = part.text, style = MaterialTheme.typography.bodyLarge)
                    }

                    is AssistantContent.Reasoning -> ReasoningBlock(part)

                    is AssistantContent.Tool -> ToolCardView(
                        card = block.card ?: part.toCard(),
                        onOpenChangedFile = onOpenChangedFile,
                        onOpenSession = onOpenSession,
                    )

                    is AssistantContent.Unknown -> UnknownTool(part)
                }
            }
        }
        message.error?.let { error -> StepErrorCard(error) }
        message.retry?.let { retry ->
            val now = System.currentTimeMillis()
            Text(
                text = stringResource(
                    R.string.step_retry_scheduled,
                    Formatters.duration((retry.at - now).coerceAtLeast(0)),
                    retry.attempt,
                ),
                style = MaterialTheme.typography.labelMedium,
                color = OpenCodeThemeExtras.status.onWarning,
                modifier = Modifier
                    .clip(OpenCodeThemeExtras.shapes.chip)
                    .background(OpenCodeThemeExtras.status.warning)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
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
    var expanded by remember(part.id()) { mutableStateOf(false) }
    val duration = (part.time?.completed ?: 0L).takeIf { it > 0L }?.let { it - (part.time?.created ?: it) }
    val toggleLabel = stringResource(if (expanded) R.string.timeline_collapse else R.string.timeline_expand)
    val title = duration?.let { stringResource(R.string.timeline_thought_for, Formatters.duration(it)) }
        ?: stringResource(R.string.timeline_reasoning)
    Column(modifier = modifier.fillMaxWidth().animateContentSize()) {
        Row(
            modifier = Modifier
                .clip(OpenCodeThemeExtras.shapes.chip)
                .clickable(onClickLabel = toggleLabel) { expanded = !expanded }
                .padding(vertical = 4.dp, horizontal = 2.dp)
                .semantics(mergeDescendants = true) { contentDescription = "$title, $toggleLabel" },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.Psychology,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandMore else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (expanded) {
            Row(modifier = Modifier.padding(top = 4.dp).height(IntrinsicSize.Min)) {
                Box(
                    Modifier
                        .width(2.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
                Text(
                    text = part.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 10.dp),
                )
            }
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
    Row(modifier = modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Box(
            Modifier
                .width(3.dp)
                .fillMaxHeight()
                .clip(OpenCodeThemeExtras.shapes.pill)
                .background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.6f)),
        )
        Column(modifier = Modifier.padding(start = 12.dp, top = 2.dp, bottom = 2.dp)) {
            Text(text = title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.tertiary)
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
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
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
                        // A code the schema allows to be Infinity or NaN, shown as the server sent it.
                        text = stringResource(R.string.timeline_tool_exit, it),
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
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
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

/**
 * The end of a turn.
 *
 * **A turn that finished normally draws nothing**: the next bubble is the boundary, and a "Turn finished" rule after
 * every answer was most of the transcript's chrome. A failed or interrupted turn still says so, as a small caption,
 * because that is news. Either way the newest one keeps its live region, as an invisible node when there is no text,
 * so a screen reader is still told the turn ended.
 */
@Composable
private fun IdleDivider(message: SessionMessage.Idle, modifier: Modifier = Modifier, announceTurnEnd: Boolean = false) {
    val outcomeDescription = stringResource(message.outcome.labelRes())
    val live = if (announceTurnEnd) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier
    if (message.outcome == Outcome.Failed || message.outcome == Outcome.Interrupted) {
        val failed = message.outcome == Outcome.Failed
        Row(
            modifier = modifier
                .fillMaxWidth()
                .semantics { contentDescription = outcomeDescription },
            horizontalArrangement = Arrangement.Center,
        ) {
            Text(
                text = outcomeDescription,
                modifier = live
                    .clip(OpenCodeThemeExtras.shapes.pill)
                    .background(if (failed) OpenCodeThemeExtras.status.failure else OpenCodeThemeExtras.status.neutral)
                    .padding(horizontal = 10.dp, vertical = 3.dp),
                style = MaterialTheme.typography.labelSmall,
                color = if (failed) OpenCodeThemeExtras.status.onFailure else OpenCodeThemeExtras.status.onNeutral,
            )
        }
    } else if (announceTurnEnd) {
        Box(modifier.fillMaxWidth().height(1.dp).semantics { contentDescription = outcomeDescription }.then(live))
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
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .clip(OpenCodeThemeExtras.shapes.pill)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = 10.dp, vertical = 3.dp),
        )
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
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
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
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Text(
            text = stringResource(R.string.unknown_tool_type, part.discriminator ?: "?"),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(12.dp),
        )
    }
}
