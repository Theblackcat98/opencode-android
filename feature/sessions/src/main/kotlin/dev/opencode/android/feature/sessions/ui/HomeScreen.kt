package dev.opencode.android.feature.sessions.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.opencode.android.core.data.server.SessionActivity
import dev.opencode.android.core.data.server.SessionRow
import dev.opencode.android.core.designsystem.format.Formatters
import dev.opencode.android.core.model.Project
import dev.opencode.android.feature.sessions.R

/**
 * The per-server home: projects, what is running now, and the most recent sessions (plan §4.3).
 *
 * "All sessions" is a link rather than a tab, because the list screen is the same list with a
 * filter the home has not set; duplicating it would be two implementations of one thing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    projects: List<Project>,
    rows: List<SessionRow>,
    serverName: String?,
    loading: Boolean,
    onProjectClick: (String) -> Unit,
    onSessionClick: (String) -> Unit,
    onAllSessionsClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** How many permissions and questions are waiting across every session (plan §4.3). */
    pendingRequests: Int = 0,
    onNewSessionClick: () -> Unit = {},
    onPendingRequestsClick: () -> Unit = {},
) {
    val running = rows.filter { it.activity is SessionActivity.Running || it.isRetrying }
    val recent = rows.take(RECENT_LIMIT)

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.home_title))
                        serverName?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                actions = {
                    // The inbox is only there when something is waiting: an always-visible empty
                    // inbox is a dead end, and a badge is the whole signal the user needs.
                    if (pendingRequests > 0) {
                        val label = stringResource(R.string.home_requests_waiting_short, pendingRequests)
                        TextButton(
                            onClick = onPendingRequestsClick,
                            modifier = Modifier.semantics { contentDescription = label },
                        ) {
                            Text(label, color = MaterialTheme.colorScheme.error)
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNewSessionClick,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.home_new_session)) },
            )
        },
    ) { padding ->
        if (loading && rows.isEmpty()) {
            Box(modifier = Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        if (rows.isEmpty() && projects.isEmpty() && pendingRequests == 0) {
            EmptyHome(Modifier.padding(padding), onNewSessionClick)
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (pendingRequests > 0) {
                item(key = "pending-requests") {
                    PendingRequestsCard(pendingRequests, onPendingRequestsClick)
                }
            }
            if (projects.isNotEmpty()) {
                item(key = "projects-header") { SectionHeader(stringResource(R.string.home_projects)) }
                items(items = projects, key = { "project-${it.id}" }) { project ->
                    ProjectCard(project, onClick = { onProjectClick(project.id) })
                }
            }
            if (running.isNotEmpty()) {
                item(key = "running-header") { SectionHeader(stringResource(R.string.home_running)) }
                items(items = running, key = { "running-${it.id}" }) { row ->
                    SessionPreview(row, onClick = { onSessionClick(row.id) })
                }
            }
            item(key = "recent-header") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.home_recent),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onAllSessionsClick) {
                        Text(stringResource(R.string.home_all_sessions))
                    }
                }
            }
            items(items = recent, key = { "recent-${it.id}" }) { row ->
                SessionPreview(row, onClick = { onSessionClick(row.id) })
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 8.dp),
    )
}

/**
 * A project: its name, its VCS, and the directory it is checked out at.
 *
 * The icon is a URL the server serves; Phase 2 shows the name and colour, and the icon becomes a
 * loaded image when the image pipeline lands.
 */
@Composable
private fun ProjectCard(project: Project, onClick: () -> Unit) {
    val label = project.name ?: Formatters.directoryName(project.canonical)
    val description = buildString {
        append(label)
        project.vcs?.let {
            append(", ")
            append(stringResource(R.string.home_vcs, it))
        }
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .semantics { contentDescription = description },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(28.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label.take(1).uppercase(),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(text = label, style = MaterialTheme.typography.bodyLarge)
                project.vcs?.let {
                    Text(
                        text = stringResource(R.string.home_vcs, it),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = stringResource(
                        R.string.home_canonical_directory,
                        Formatters.directoryTail(project.canonical),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SessionPreview(row: SessionRow, onClick: () -> Unit) {
    val badges = buildList {
        if (row.isRunning) add(stringResource(R.string.sessions_running_description))
        if (row.isRetrying) add(stringResource(R.string.sessions_retry_badge))
        if (row.isUnread) add(stringResource(R.string.sessions_unread_description))
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .semantics { contentDescription = "${row.title}. ${badges.joinToString(", ")}" },
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
                    CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    text = row.title,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
            }
            badges.forEach { badge ->
                Text(
                    text = badge,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
 * The empty home.
 *
 * A server with no sessions is a server the user has not started work on, so the empty state offers
 * the one action that starts it rather than only explaining that there is nothing here.
 */
@Composable
private fun EmptyHome(modifier: Modifier = Modifier, onNewSessionClick: () -> Unit = {}) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.home_empty_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.home_empty_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onNewSessionClick) { Text(stringResource(R.string.home_new_session)) }
    }
}

/**
 * The pending-requests card.
 *
 * The agent is blocked until this is answered, so it is the first thing on the home and not a
 * notification-only feature: a user who is not looking at the session still has to be able to find
 * it (plan §4.3).
 */
@Composable
private fun PendingRequestsCard(count: Int, onClick: () -> Unit) {
    val label = stringResource(R.string.home_requests_waiting, count)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .semantics { contentDescription = label },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                text = stringResource(R.string.home_requests_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

private const val RECENT_LIMIT = 10
