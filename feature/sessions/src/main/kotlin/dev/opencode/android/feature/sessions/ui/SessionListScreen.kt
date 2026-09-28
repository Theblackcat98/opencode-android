package dev.opencode.android.feature.sessions.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.opencode.android.core.data.server.PagingState
import dev.opencode.android.core.data.server.SessionActivity
import dev.opencode.android.core.data.server.SessionFilter
import dev.opencode.android.core.data.server.SessionRow
import dev.opencode.android.core.designsystem.format.Formatters
import dev.opencode.android.core.designsystem.format.relativeTimeDescription
import dev.opencode.android.core.model.Project
import dev.opencode.android.feature.sessions.R

/**
 * The session list: search, the roots-only filter, project and directory filters, the badges, and
 * "load more" (plan §6, Phase 2).
 *
 * The list is driven entirely by [SessionListUiState], which the ViewModel derives from the store's
 * flows; nothing here polls or refetches. Rows are keyed by session id, so a `session.created` on
 * the desktop inserts a row without disturbing the scroll position, and "load more" is requested
 * when the last row comes into view rather than by a button the user has to find.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionListScreen(
    state: SessionListUiState,
    onSearchChange: (String) -> Unit,
    onToggleRootsOnly: () -> Unit,
    onProjectSelected: (String?) -> Unit,
    onDirectorySelected: (String?) -> Unit,
    onLoadMore: () -> Unit,
    onSessionClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val searchDescription = stringResource(R.string.sessions_search_description)
    val lastIndex = state.rows.lastIndex
    LaunchedEffect(lastIndex, state.paging.hasMore) {
        if (lastIndex >= 0 && lastIndex >= state.rows.size - PREFETCH_DISTANCE && state.paging.hasMore) {
            onLoadMore()
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text(stringResource(R.string.sessions_title)) }) },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = state.search,
                onValueChange = onSearchChange,
                label = { Text(stringResource(R.string.sessions_search)) },
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (state.search.isNotEmpty()) {
                        IconButton(onClick = { onSearchChange("") }) {
                            Icon(
                                Icons.Filled.Clear,
                                contentDescription = stringResource(R.string.sessions_filter_clear),
                            )
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .semantics { contentDescription = searchDescription },
            )
            FilterRow(
                rootsOnly = state.filter.rootsOnly,
                onToggleRootsOnly = onToggleRootsOnly,
                projectId = state.filter.projectId,
                onProjectSelected = onProjectSelected,
                directory = state.filter.directory,
                onDirectorySelected = onDirectorySelected,
                projects = state.projects,
            )
            when {
                state.rows.isEmpty() && state.paging.loading -> Loading()
                state.rows.isEmpty() -> EmptyState()
                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(items = state.rows, key = { it.id }) { row ->
                        SessionRowCard(row, now = state.now, onClick = { onSessionClick(row.id) })
                    }
                    item(key = "paging") {
                        PagingFooter(state.paging, onLoadMore)
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterRow(
    rootsOnly: Boolean,
    onToggleRootsOnly: () -> Unit,
    projectId: String?,
    onProjectSelected: (String?) -> Unit,
    directory: String?,
    onDirectorySelected: (String?) -> Unit,
    projects: List<Project>,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val rootsDescription = stringResource(R.string.sessions_filter_roots_description)
        FilterChip(
            selected = rootsOnly,
            onClick = onToggleRootsOnly,
            label = { Text(stringResource(R.string.sessions_filter_roots)) },
            modifier = Modifier.semantics { contentDescription = rootsDescription },
        )
        AssistChip(
            onClick = { onProjectSelected(projectId) },
            label = {
                Text(
                    text = projectId
                        ?.let { id -> projects.firstOrNull { it.id == id }?.name ?: id.take(8) }
                        ?: stringResource(R.string.sessions_filter_all),
                    style = MaterialTheme.typography.labelSmall,
                )
            },
        )
        AssistChip(
            onClick = { onDirectorySelected(directory) },
            label = {
                Text(
                    text = directory?.let(Formatters::directoryName)
                        ?: stringResource(R.string.sessions_filter_directory),
                    style = MaterialTheme.typography.labelSmall,
                )
            },
        )
    }
}

@Composable
private fun SessionRowCard(row: SessionRow, now: Long, onClick: () -> Unit) {
    val relative = Formatters.relativeTime(row.updated, now)
    val relativeDescription = relativeTimeDescription(row.updated, now)
    val badges = row.badgeLabels(now)
    val summaryTemplate = stringResource(R.string.sessions_summary, relativeDescription, badges.joinToString(", "))
    val description = buildString {
        append(row.title)
        append(". ")
        append(summaryTemplate)
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .semantics { contentDescription = description },
        colors = CardDefaults.cardColors(
            containerColor = if (row.isUnread) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (row.isRunning) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    text = row.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (row.isUnread) FontWeight.Bold else FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                if (relative.isNotEmpty()) {
                    Text(
                        text = relative,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            badges.forEach { badge ->
                Text(
                    text = badge,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            row.agent?.let { agent ->
                Text(
                    text = stringResource(R.string.sessions_agent_chip, agent),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            row.model?.let { model ->
                Text(
                    text = stringResource(R.string.sessions_model_chip, model.label),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                model.variantLabel?.let { variant ->
                    Text(
                        text = stringResource(R.string.sessions_variant_chip, variant),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (row.cost > 0.0 || row.tokens > 0) {
                Text(
                    text = stringResource(
                        R.string.sessions_cost_and_tokens,
                        Formatters.cost(row.cost),
                        Formatters.tokens(row.tokens),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The badges a row shows, in reading order: retry, running, unread, then the outcome.
 *
 * Formatted here rather than returned as resource ids because two of them take an argument (the
 * countdown, the child count), and a list of ids would have to be special-cased at the call site.
 */
@Composable
private fun SessionRow.badgeLabels(now: Long): List<String> = buildList {
    when (val current = activity) {
        is SessionActivity.Retrying -> add(
            stringResource(
                R.string.sessions_retry_in,
                Formatters.duration((current.next - now).coerceAtLeast(0)),
            ),
        )

        SessionActivity.Running -> add(stringResource(R.string.sessions_running_description))
        SessionActivity.Idle, SessionActivity.Unknown -> Unit
    }
    if (isUnread) add(stringResource(R.string.sessions_unread_description))
    session.outcome?.let { outcome ->
        add(
            stringResource(
                when (outcome.value) {
                    "failed" -> R.string.sessions_outcome_failed
                    "interrupted" -> R.string.sessions_outcome_interrupted
                    else -> R.string.sessions_outcome_succeeded
                },
            ),
        )
    }
    if (childCount > 0) {
        add(
            if (childCount == 1) {
                stringResource(R.string.home_subagent)
            } else {
                stringResource(R.string.home_subagents, childCount)
            },
        )
    }
}

@Composable
private fun PagingFooter(paging: PagingState, onLoadMore: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(8.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            paging.loading -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            paging.hasMore -> Text(
                text = stringResource(R.string.sessions_load_more),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable { onLoadMore() }
                    .padding(8.dp),
            )

            else -> Text(
                text = stringResource(R.string.sessions_end),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Loading() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.sessions_empty_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.sessions_empty_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private const val PREFETCH_DISTANCE = 3
