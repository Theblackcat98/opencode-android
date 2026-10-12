package dev.opencode.android.feature.execution

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.opencode.android.core.data.execution.SessionNode
import dev.opencode.android.core.data.server.SessionActivity
import dev.opencode.android.core.designsystem.component.OpenCodeAvatar
import dev.opencode.android.core.designsystem.component.OpenCodeStatusPill
import dev.opencode.android.core.designsystem.component.StatusKind
import dev.opencode.android.core.designsystem.format.Formatters
import dev.opencode.android.core.designsystem.theme.OpenCodeThemeExtras

/**
 * The session family tree (plan §6, "Subagents").
 *
 * **One indented list, not nested boxes.** A family of subagents can be four deep and each level has
 * a row; nested `Column`s would give every level its own scroll and its own state, and a
 * `LazyColumn` cannot host them. Indentation and a stable key per session are enough to read the
 * shape, and the keys are what let a `session.created` land on the right row instead of redrawing the
 * list.
 *
 * **The selection is the session the timeline is showing.** That is why the arrows are here rather
 * than in a toolbar: they move the thing the user is looking at, and a screen that drew the tree
 * without moving anything would be a diagram.
 */
@Composable
fun SubagentsScreen(
    state: SubagentsUiState,
    onSelect: (String) -> Unit,
    onParent: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = OpenCodeThemeExtras.spacing
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.sm, vertical = spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onParent, enabled = state.canGoToParent) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    stringResource(R.string.subagents_go_to_parent),
                )
            }
            Text(
                text = state.parentTitle?.let { stringResource(R.string.subagents_child_of, it) }
                    ?: stringResource(R.string.subagents_root),
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onPrevious, enabled = state.canGoToPrevious) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    stringResource(R.string.subagents_previous),
                )
            }
            IconButton(onClick = onNext, enabled = state.canGoToNext) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    stringResource(R.string.subagents_next),
                )
            }
        }
        HorizontalDivider()

        if (state.nodes.isEmpty()) {
            Text(
                text = stringResource(R.string.subagents_empty),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(spacing.xl),
            )
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag(SubagentTags.TREE),
            contentPadding = PaddingValues(bottom = spacing.xl),
        ) {
            items(state.nodes, key = { it.id }) { node ->
                SubagentNodeRow(node = node, selected = node.id == state.selected, onClick = { onSelect(node.id) })
            }
        }

        if (state.missingChildren > 0) {
            Text(
                text = stringResource(R.string.subagents_incomplete, state.missingChildren),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(spacing.md),
            )
        }
    }
}

/**
 * One session in the tree: a guide line per level of depth, the agent's avatar, the title, who ran it and what it
 * cost, and its state. The guides are what make four levels of nesting readable on a phone, where an indent alone
 * is a few millimetres.
 */
@Composable
private fun SubagentNodeRow(node: SessionNode, selected: Boolean, onClick: () -> Unit) {
    val row = node.row
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .background(if (selected) scheme.secondaryContainer.copy(alpha = 0.35f) else scheme.surface)
            .clickable(onClick = onClick)
            .testTag(SubagentTags.NODE + node.id)
            .padding(start = OpenCodeThemeExtras.spacing.md, end = OpenCodeThemeExtras.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(node.depth) {
            Box(
                Modifier
                    .padding(start = 10.dp, end = OpenCodeThemeExtras.spacing.md)
                    .width(1.dp)
                    .fillMaxHeight()
                    .background(scheme.outlineVariant),
            )
        }
        OpenCodeAvatar(key = row.agent ?: node.id, label = row.agent ?: node.title, size = 28.dp)
        Column(modifier = Modifier.weight(1f).padding(horizontal = OpenCodeThemeExtras.spacing.md, vertical = 10.dp)) {
            Text(
                text = node.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.SemiBold else null,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val meta = listOfNotNull(
                stringResource(
                    R.string.subagents_supporting,
                    row.agent ?: stringResource(R.string.subagents_no_agent),
                    row.model?.label ?: stringResource(R.string.subagents_no_model),
                ),
                row.tokens.takeIf { it > 0 }?.let { Formatters.tokens(it) },
                row.cost.takeIf { it > 0.0 }?.let { Formatters.cost(it) },
            ).joinToString(" · ")
            Text(
                text = meta,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (row.isRunning) {
            OpenCodeStatusPill(
                kind = StatusKind.Running,
                label = stringResource(R.string.subagents_running),
                modifier = Modifier.testTag(SubagentTags.RUNNING),
            )
        } else {
            Box(Modifier.size(1.dp).testTag(SubagentTags.IDLE))
        }
    }
}

/**
 * The strip of running subagents, drawn above the composer (plan §6, "A strip of running subagents in
 * the composer, with an interrupt action per child").
 *
 * **A `LazyRow`, because a wide subagent tree can have more children than fit.** A `Row` would clip
 * them with no way to reach the last one, and a strip whose point is "something is still working" that
 * cannot show the last something is a strip that lies.
 *
 * **Each chip interrupts its own child.** The interrupt is per child rather than "stop the turn"
 * because a parent waiting on three subagents usually wants to stop the one that is stuck; the parent's
 * own interrupt is the composer's existing control.
 */
@Composable
fun SubagentStrip(
    state: SubagentStripState,
    onOpen: (String) -> Unit,
    onInterrupt: (String) -> Unit,
    onDismissError: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    if (state.isEmpty) return
    Column(modifier = modifier.fillMaxWidth().testTag(SubagentTags.STRIP)) {
        Text(
            text = stringResource(R.string.subagents_strip_title, state.children.size),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(start = 12.dp, top = 4.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.children, key = { it.sessionID }) { child ->
                val busy = state.interrupting == child.sessionID
                // The interrupt sits beside the chip, not inside it: a 24dp button nested in a
                // clickable chip's trailing icon is a TalkBack hazard (chip opens, button
                // interrupts — adjacent targets, one tiny). Its own 48dp target ends the nesting.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AssistChip(
                        onClick = { onOpen(child.sessionID) },
                        label = {
                            Text(
                                text = child.title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        leadingIcon = {
                            // One glyph, and the name carries the state. A stop glyph on a *retrying* child
                            // and a play glyph on a *running* one read as the opposite of what they mean;
                            // both chips are interrupt targets, and "running" versus "retrying" is a
                            // distinction TalkBack makes from the content description and a sighted user
                            // makes from the spinner the row shows.
                            Icon(
                                imageVector = Icons.Filled.Stop,
                                contentDescription = if (child.isRetrying) {
                                    stringResource(R.string.subagents_retrying)
                                } else {
                                    stringResource(R.string.subagents_running)
                                },
                            )
                        },
                        colors = AssistChipDefaults.assistChipColors(),
                    )
                    IconButton(
                        onClick = { onInterrupt(child.sessionID) },
                        enabled = !busy,
                        modifier = Modifier.size(48.dp).testTag(SubagentTags.INTERRUPT + child.sessionID),
                    ) {
                        if (busy) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(
                                Icons.Filled.Stop,
                                stringResource(R.string.subagents_interrupt),
                            )
                        }
                    }
                }
            }
        }
        // The interrupt's own failure. A strip that swallowed it would show a chip that stopped
        // spinning and a subagent that is still running, and the user would have no way to tell those
        // two apart.
        state.error?.let { error ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = error,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).testTag(SubagentTags.ERROR),
                )
                IconButton(onClick = onDismissError) {
                    Icon(Icons.Filled.Close, stringResource(R.string.action_close))
                }
            }
        }
    }
}

/** The tags the tests and the baselines address. */
object SubagentTags {
    const val TREE: String = "subagents-tree"
    const val NODE: String = "subagents-node-"
    const val STRIP: String = "subagents-strip"
    const val INTERRUPT: String = "subagents-interrupt-"
    const val RUNNING: String = "subagents-running"
    const val IDLE: String = "subagents-idle"
    const val ERROR: String = "subagents-error"
}

private const val INDENT_DP = 16
