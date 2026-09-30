package dev.opencode.android.feature.sessions.ui.timeline

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.opencode.android.core.designsystem.diff.DiffTable
import dev.opencode.android.core.designsystem.format.Formatters
import dev.opencode.android.core.designsystem.theme.OpenCodeThemeExtras
import dev.opencode.android.feature.sessions.R

/**
 * A tool call, compact.
 *
 * One card shape for every tool, with the per-tool details the plan lists: a path for the file
 * tools, a pattern for `glob` and `grep`, the command for `shell` and `execute`, the URL for
 * `webfetch`, the query for `websearch`, the answers for `question`, the child session for
 * `subagent`, the changed files for `edit`, `write` and `patch`, and the raw input for anything
 * this client has no dedicated card for.
 */
@Composable
fun ToolCardView(card: ToolCard, modifier: Modifier = Modifier) {
    val statusLabel = stringResource(card.status.labelRes())
    val title = if (card.kind == ToolCardKind.GENERIC) {
        stringResource(R.string.tool_unknown, card.name)
    } else {
        stringResource(card.kind.labelRes)
    }
    val duration = card.durationMillis?.let { Formatters.duration(it) }
    val description = if (duration == null) {
        stringResource(R.string.tool_status_description, title, statusLabel)
    } else {
        stringResource(
            R.string.tool_status_description,
            title,
            stringResource(R.string.tool_status_description, statusLabel, duration),
        )
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = description },
        colors = CardDefaults.cardColors(
            containerColor = when (card.status) {
                is ToolStatus.Failed -> MaterialTheme.colorScheme.errorContainer
                ToolStatus.Running, ToolStatus.Streaming -> MaterialTheme.colorScheme.surfaceVariant
                ToolStatus.Completed -> MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.weight(1f))
                when (card.status) {
                    ToolStatus.Streaming, ToolStatus.Running -> CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                    )

                    is ToolStatus.Failed -> Text(
                        text = statusLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )

                    ToolStatus.Completed -> Text(
                        text = duration ?: statusLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (card.status is ToolStatus.Running || card.status is ToolStatus.Streaming) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            card.subject?.let { subject ->
                Text(
                    text = if (card.kind == ToolCardKind.SHELL || card.kind == ToolCardKind.EXECUTE) {
                        stringResource(R.string.tool_command, subject)
                    } else {
                        subject
                    },
                    style = OpenCodeThemeExtras.code.small,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .horizontalScroll(rememberScrollState()),
                )
            }
            card.diff?.let { diff ->
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.padding(top = 6.dp).fillMaxWidth(),
                ) {
                    DiffTable(
                        rows = diff.rows,
                        // Wrapped, because a row's text is weighted and an unwrapped table sits in a horizontal
                        // scroll with unbounded width, where a weighted child is given none.
                        wrap = true,
                        showGutter = false,
                        language = diff.language,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
                if (diff.hiddenRows > 0) {
                    Text(
                        text = stringResource(R.string.tool_diff_more, diff.hiddenRows),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            // The diff replaces the raw `files` text an edit used to show: the same information, drawn.
            card.detail?.takeIf { it.isNotBlank() && card.diff == null }?.let { detail ->
                Text(
                    text = stringResource(R.string.tool_output),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(6.dp),
                ) {
                    Text(
                        text = detail,
                        style = OpenCodeThemeExtras.code.small,
                        maxLines = 20,
                        modifier = Modifier
                            .padding(6.dp)
                            .horizontalScroll(rememberScrollState()),
                    )
                }
            }
        }
    }
}

@androidx.annotation.StringRes
private fun ToolStatus.labelRes(): Int = when (this) {
    ToolStatus.Streaming -> R.string.tool_streaming
    ToolStatus.Running -> R.string.tool_running
    ToolStatus.Completed -> R.string.tool_completed
    is ToolStatus.Failed -> R.string.tool_failed
}

/** The subagent chip, shown under its parent's tool card. */
@Composable
fun SubagentChip(sessionID: String, modifier: Modifier = Modifier) {
    AssistChip(
        onClick = {},
        label = {
            Text(
                text = stringResource(R.string.tool_child_session, sessionID),
                style = MaterialTheme.typography.labelSmall,
            )
        },
        modifier = modifier.padding(top = 4.dp),
    )
}

/** The answers a `question` tool recorded, shown as chips. */
@Composable
fun AnswerChips(answers: List<String>, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        answers.forEach { answer ->
            AssistChip(
                onClick = {},
                label = {
                    Text(
                        text = stringResource(R.string.tool_answers, answer),
                        style = MaterialTheme.typography.labelSmall,
                    )
                },
                modifier = Modifier.width(0.dp),
            )
        }
    }
}
