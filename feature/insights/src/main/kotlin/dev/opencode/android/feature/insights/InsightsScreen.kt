package dev.opencode.android.feature.insights

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.opencode.android.core.model.SessionStats
import dev.opencode.android.core.model.SessionStatsTools
import dev.opencode.android.core.model.TokenUsage
import dev.opencode.android.core.model.ToolTotals
import dev.opencode.android.core.model.ToolUsage
import java.util.Locale

/**
 * The usage dashboard (plan §6, "Usage dashboard").
 *
 * **The day buckets are the server's, drawn in order, never re-bucketed.** `stats.activity` is cut by
 * the time zone the query named, and a heatmap that re-derived its own days would disagree with
 * `opencode stats` about where a day ends. So the cells are the server's rows, in the order it sent
 * them, and a day with no steps is drawn as a gap rather than smoothed away.
 *
 * **The grid is a picture, so it is described rather than read cell by cell.** A screen reader
 * announcing 365 numbers would be unusable; one sentence naming the active days, the streak and the
 * busiest day carries the same information. The cells are cleared from the semantics tree and the card
 * carries that sentence instead.
 */
@Composable
fun InsightsScreen(
    state: InsightsUiState,
    onRangeChange: (StatsRange) -> Unit,
    onToggleToolDetail: (Boolean) -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val stats = state.stats
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { RangePicker(state.range, onRangeChange) }

        if (state.loading && stats == null) {
            item {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        }

        state.error?.let { error ->
            item { StatsError(error.message ?: stringResource(R.string.insights_error), onDismissError) }
        }

        if (stats != null) {
            item { TotalsCard(stats) }
            item { HeatmapCard(stats) }
            item { TokenCard(stats.tokens) }
            if (stats.models.isNotEmpty()) item { ModelsCard(stats) }
            item {
                ToolReliabilityCard(
                    tools = stats.tools,
                    expanded = state.isDetail,
                    onToggleDetail = { onToggleToolDetail(!state.isDetail) },
                )
            }
        }
    }
}

/** One chip per range, in a row that a screen reader reads as one group with one selection. */
@Composable
private fun RangePicker(current: StatsRange, onChange: (StatsRange) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.semantics(mergeDescendants = true) {},
    ) {
        for (range in StatsRange.offered) {
            FilterChip(
                selected = range == current,
                onClick = { onChange(range) },
                label = { Text(stringResource(range.labelRes)) },
            )
        }
    }
}

@Composable
private fun TotalsCard(stats: SessionStats) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.insights_totals),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            StatRow(stringResource(R.string.insights_sessions), stats.sessions.toString())
            StatRow(stringResource(R.string.insights_subagents), stats.subagents.toString())
            StatRow(stringResource(R.string.insights_prompts), stats.prompts.toString())
            StatRow(stringResource(R.string.insights_steps), stats.steps.toString())
            StatRow(stringResource(R.string.insights_cost), formatUsd(stats.cost))
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun HeatmapCard(stats: SessionStats) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.insights_activity),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            if (stats.activity.isEmpty()) {
                Text(
                    stringResource(R.string.insights_no_activity),
                    style = MaterialTheme.typography.bodyMedium,
                )
                return@Column
            }
            val busiest = stats.activity.maxOf { it.steps }
            val description = stringResource(
                R.string.insights_activity_description,
                stats.activeDays,
                stats.streak,
                busiest,
            )
            Text(
                stringResource(R.string.insights_streak, stats.streak),
                style = MaterialTheme.typography.bodyMedium,
            )
            // The cells carry no semantics of their own; the sentence above is what is announced.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clearAndSetSemantics { contentDescription = description },
            ) {
                for (week in stats.activity.chunked(CELLS_PER_ROW)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (day in week) HeatCell(day.steps, busiest)
                        repeat(CELLS_PER_ROW - week.size) { Box(Modifier.size(CELL.dp)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun HeatCell(steps: Long, busiest: Long) {
    val fraction = if (busiest == 0L) 0f else (steps.toFloat() / busiest).coerceIn(0f, 1f)
    Box(
        Modifier
            .size(CELL.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(
                if (steps == 0L) {
                    MaterialTheme.colorScheme.surfaceVariant
                } else {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.25f + 0.75f * fraction)
                },
            ),
    )
}

@Composable
private fun TokenCard(tokens: TokenUsage) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(R.string.insights_tokens),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            StatRow(stringResource(R.string.insights_tokens_input), tokens.input.toString())
            StatRow(stringResource(R.string.insights_tokens_output), tokens.output.toString())
            if (tokens.reasoning > 0) {
                StatRow(stringResource(R.string.insights_tokens_reasoning), tokens.reasoning.toString())
            }
            if (tokens.cache.read > 0 || tokens.cache.write > 0) {
                StatRow(
                    stringResource(R.string.insights_tokens_cache),
                    stringResource(
                        R.string.insights_tokens_cache_value,
                        tokens.cache.read,
                        tokens.cache.write,
                    ),
                )
            }
        }
    }
}

@Composable
private fun ModelsCard(stats: SessionStats) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.insights_models),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            for (usage in stats.models.sortedByDescending { it.steps }) {
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    StatRow(usage.model.toString(), formatUsd(usage.cost))
                    Text(
                        stringResource(R.string.insights_model_steps, usage.steps),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

/**
 * Tool reliability.
 *
 * **A rate of 0 over 0 is not a figure, and is not drawn as one.** [ToolTotals.successRate] is `null`
 * for a period with no calls, and the card says so rather than showing "0% reliable", which would
 * blame the user for a question nobody asked.
 *
 * The summary and the detail are one card because the summary is the detail's header, and the switch
 * is the same value as the request's `tools` parameter, so what is on screen and what was asked for
 * cannot disagree.
 */
@Composable
private fun ToolReliabilityCard(
    tools: SessionStatsTools,
    expanded: Boolean,
    onToggleDetail: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.insights_tools),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            when (tools) {
                is SessionStatsTools.None -> Text(
                    stringResource(R.string.insights_tools_off),
                    style = MaterialTheme.typography.bodyMedium,
                )

                is SessionStatsTools.Summary -> ToolTotalsBlock(tools.totals, null, expanded, onToggleDetail)

                is SessionStatsTools.Detail ->
                    ToolTotalsBlock(tools.totals, tools.usage, expanded, onToggleDetail)

                is SessionStatsTools.Unknown -> Text(
                    // A fourth mode the server invented: the counts are not available, and saying so
                    // beats drawing zeros that look like a fact.
                    stringResource(R.string.insights_tools_unknown),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun ToolTotalsBlock(
    totals: ToolTotals,
    usage: List<ToolUsage>?,
    expanded: Boolean,
    onToggleDetail: () -> Unit,
) {
    val rate = totals.successRate
    Text(
        text = if (rate == null) {
            stringResource(R.string.insights_no_calls)
        } else {
            stringResource(R.string.insights_success_rate, (rate * 100).toInt())
        },
        style = MaterialTheme.typography.bodyMedium,
    )
    LinearProgressIndicator(
        progress = { (rate ?: 0.0).toFloat() },
        modifier = Modifier.fillMaxWidth(),
    )
    if (usage != null) {
        HorizontalDivider()
        for (row in usage.sortedByDescending { it.calls }) ToolRow(row)
    }
    FilterChip(
        selected = expanded,
        onClick = onToggleDetail,
        label = { Text(stringResource(R.string.insights_tools_detail)) },
    )
}

@Composable
private fun ToolRow(usage: ToolUsage) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(usage.name, style = MaterialTheme.typography.bodyMedium)
            Text(usage.calls.toString(), style = MaterialTheme.typography.bodyMedium)
        }
        val rate = usage.successRate
        Text(
            text = if (rate == null) {
                stringResource(R.string.insights_no_calls)
            } else {
                stringResource(
                    R.string.insights_tool_detail,
                    (rate * 100).toInt(),
                    usage.durationP50?.toInt() ?: 0,
                )
            },
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun StatsError(message: String, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.insights_dismiss)) }
        }
    }
}

/** Two places, because a cost is shown in a total and beside each model. */
private fun formatUsd(value: Double): String = "$" + String.format(Locale.US, "%.2f", value)

private const val CELLS_PER_ROW = 7
private const val CELL = 14
