package dev.opencode.android.feature.sessions.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.opencode.android.core.data.server.SessionRow
import dev.opencode.android.core.designsystem.component.OpenCodeAvatar
import dev.opencode.android.core.designsystem.component.OpenCodeEmptyState
import dev.opencode.android.core.designsystem.component.OpenCodeStatusPill
import dev.opencode.android.core.designsystem.component.StatusKind
import dev.opencode.android.core.designsystem.format.Formatters
import dev.opencode.android.core.designsystem.format.relativeTimeDescription
import dev.opencode.android.core.designsystem.theme.OpenCodeThemeExtras
import dev.opencode.android.core.model.Project
import dev.opencode.android.feature.sessions.R

/**
 * The per-server home: the projects the user added, each with its newest sessions (plan §4.3).
 *
 * **Only added projects.** The server registers a project for every directory it is asked about, so its list
 * is a history (scratch directories, a home directory, a path typed once), not the user's work. The home shows
 * the projects the user put here, separated by whitespace rather than boxed in cards, and "Add project" offers
 * the rest of the server's list. A session created in a project adds it, so the first session fills the home.
 *
 * **No Manage menu.** The server's settings moved to the Settings tab of the bottom bar; the home is for work.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: HomeUiState,
    serverName: String?,
    onSessionClick: (String) -> Unit,
    onProjectSessionsClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** How many permissions and questions are waiting across every session (plan §4.3). */
    pendingRequests: Int = 0,
    onNewSessionClick: () -> Unit = {},
    onPendingRequestsClick: () -> Unit = {},
    onAddProject: (String) -> Unit = {},
    onRemoveProject: (String) -> Unit = {},
    /** Back to the server list, from the server name in the top bar. Null makes the name plain text. */
    onSwitchServer: (() -> Unit)? = null,
    /** Opens the command palette. Null hides the action. */
    onOpenCommandPalette: (() -> Unit)? = null,
    /** Opens the add-project sheet as the screen appears, for screenshots of it. */
    initialAddOpen: Boolean = false,
) {
    var addOpen by remember { mutableStateOf(initialAddOpen) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { ServerTitle(serverName, onSwitchServer) },
                actions = {
                    // The inbox is only there when something is waiting: an always-visible empty inbox is a dead
                    // end, and a badge is the whole signal the user needs.
                    if (pendingRequests > 0) {
                        val label = stringResource(R.string.home_requests_waiting, pendingRequests)
                        IconButton(onClick = onPendingRequestsClick) {
                            BadgedBox(badge = { Badge { Text(pendingRequests.toString()) } }) {
                                Icon(Icons.Outlined.Inbox, contentDescription = label)
                            }
                        }
                    }
                    if (onOpenCommandPalette != null) {
                        IconButton(onClick = onOpenCommandPalette) {
                            Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.home_command_palette))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        floatingActionButton = {
            val newSession = stringResource(R.string.home_new_session)
            ExtendedFloatingActionButton(
                onClick = onNewSessionClick,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(newSession) },
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        },
    ) { padding ->
        if (state.syncing && state.groups.isEmpty()) {
            Box(modifier = Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (state.groups.isEmpty() && pendingRequests == 0) {
            OpenCodeEmptyState(
                title = stringResource(R.string.home_empty_projects_title),
                subtitle = stringResource(R.string.home_empty_projects_subtitle),
                icon = Icons.Filled.CreateNewFolder,
                modifier = Modifier.padding(padding).fillMaxSize(),
                action = {
                    Button(onClick = { addOpen = true }, enabled = state.available.isNotEmpty()) {
                        Text(stringResource(R.string.home_add_project))
                    }
                },
            )
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding).fillMaxSize(),
                // The bottom padding keeps the last row clear of the floating button.
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            ) {
                if (pendingRequests > 0) {
                    item(key = "pending-requests") {
                        PendingRequestsCard(pendingRequests, onPendingRequestsClick)
                        Spacer(Modifier.height(GROUP_GAP))
                    }
                }
                state.groups.forEach { group ->
                    item(key = "project-${group.project.id}") {
                        ProjectHeader(
                            group = group,
                            onSeeAll = { onProjectSessionsClick(group.project.id) },
                            onRemove = { onRemoveProject(group.project.id) },
                        )
                    }
                    if (group.sessions.isEmpty()) {
                        item(key = "project-${group.project.id}-empty") {
                            Text(
                                text = stringResource(R.string.home_project_no_sessions),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 44.dp, top = 4.dp, bottom = 4.dp),
                            )
                        }
                    }
                    items(items = group.sessions, key = { "session-${it.id}" }) { row ->
                        HomeSessionRow(row, now = state.now, onClick = { onSessionClick(row.id) })
                    }
                    item(key = "project-${group.project.id}-gap") { Spacer(Modifier.height(GROUP_GAP)) }
                }
                if (state.available.isNotEmpty()) {
                    item(key = "add-project") {
                        TextButton(onClick = { addOpen = true }, modifier = Modifier.testTag(HomeTags.ADD_PROJECT)) {
                            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(8.dp))
                            Text(stringResource(R.string.home_add_project))
                        }
                    }
                }
            }
        }
    }

    if (addOpen) {
        AddProjectSheet(
            projects = state.available,
            onPick = { id ->
                addOpen = false
                onAddProject(id)
            },
            onDismiss = { addOpen = false },
        )
    }
}

/** The tags the home screen's own tests address. */
object HomeTags {
    const val ADD_PROJECT: String = "home:add-project"
    const val PROJECT_MENU: String = "home:project-menu"
}

/** The server's name as the title, and the way back to the server list when [onSwitchServer] is given. */
@Composable
private fun ServerTitle(serverName: String?, onSwitchServer: (() -> Unit)?) {
    val name = serverName ?: stringResource(R.string.home_title)
    val switchLabel = stringResource(R.string.home_switch_server)
    val clickable = if (onSwitchServer != null) {
        Modifier
            .clip(OpenCodeThemeExtras.shapes.button)
            .clickable(onClickLabel = switchLabel, role = Role.Button, onClick = onSwitchServer)
    } else {
        Modifier
    }
    Row(
        modifier = clickable.padding(horizontal = OpenCodeThemeExtras.spacing.xs, vertical = OpenCodeThemeExtras.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (onSwitchServer != null) Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
    }
}

/** A project's heading: avatar, name and path, what is running, "See all", and a menu to take it off the home. */
@Composable
private fun ProjectHeader(group: ProjectGroup, onSeeAll: () -> Unit, onRemove: () -> Unit) {
    val project = group.project
    val label = projectLabel(project)
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = OpenCodeThemeExtras.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OpenCodeAvatar(key = project.id, label = label, size = 32.dp)
        Spacer(Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f).semantics(mergeDescendants = true) { heading() }) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = Formatters.directoryTail(project.canonical),
                style = OpenCodeThemeExtras.code.small,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (group.total > group.sessions.size) {
            TextButton(onClick = onSeeAll) { Text(stringResource(R.string.home_see_all, group.total)) }
        } else if (group.total > 0) {
            TextButton(onClick = onSeeAll) { Text(stringResource(R.string.home_all)) }
        }
        Box {
            IconButton(onClick = { menuOpen = true }, modifier = Modifier.testTag(HomeTags.PROJECT_MENU)) {
                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.home_project_options, label))
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.home_remove_project)) },
                    onClick = {
                        menuOpen = false
                        onRemove()
                    },
                )
            }
        }
    }
}

/** One session under its project: title, when and by which agent, and its state. No card, so a group reads as one. */
@Composable
private fun HomeSessionRow(row: SessionRow, now: Long, onClick: () -> Unit) {
    val relative = if (now > 0L) Formatters.relativeTime(row.updated, now) else ""
    val relativeDescription = if (now > 0L) relativeTimeDescription(row.updated, now) else ""
    val badges = buildList {
        if (row.isRunning) add(stringResource(R.string.sessions_running_description))
        if (row.isRetrying) add(stringResource(R.string.sessions_retry_badge))
        if (row.isUnread) add(stringResource(R.string.sessions_unread_description))
    }
    val description = listOf(row.title, relativeDescription, badges.joinToString(", "))
        .filter { it.isNotBlank() }
        .joinToString(". ")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(OpenCodeThemeExtras.shapes.button)
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(start = 44.dp, top = 10.dp, bottom = 10.dp, end = OpenCodeThemeExtras.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (row.isUnread) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val meta = listOfNotNull(relative.takeIf { it.isNotEmpty() }, row.agent).joinToString(" · ")
            if (meta.isNotEmpty()) {
                Text(
                    text = meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        when {
            row.isRetrying -> OpenCodeStatusPill(StatusKind.Retrying, stringResource(R.string.sessions_retry_badge))

            row.isRunning -> OpenCodeStatusPill(StatusKind.Running, stringResource(R.string.home_running_short))

            row.isUnread -> Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

/**
 * The server's projects that are not on the home yet. Only what the server already knows is offered: the server
 * registers a project for any directory it is asked about, so a typed path would create one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddProjectSheet(projects: List<Project>, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            text = stringResource(R.string.home_add_project),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = OpenCodeThemeExtras.spacing.xl, vertical = OpenCodeThemeExtras.spacing.sm),
        )
        Text(
            text = stringResource(R.string.home_add_project_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = OpenCodeThemeExtras.spacing.xl),
        )
        Spacer(Modifier.height(8.dp))
        LazyColumn(contentPadding = PaddingValues(bottom = OpenCodeThemeExtras.spacing.xl)) {
            items(items = projects.sortedByDescending { it.time.updated }, key = { it.id }) { project ->
                val label = projectLabel(project)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClickLabel = stringResource(R.string.home_add_project)) { onPick(project.id) }
                        .padding(horizontal = OpenCodeThemeExtras.spacing.xl, vertical = OpenCodeThemeExtras.spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OpenCodeAvatar(key = project.id, label = label, size = 28.dp)
                    Spacer(Modifier.size(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            text = Formatters.directoryTail(project.canonical, segments = 3),
                            style = OpenCodeThemeExtras.code.small,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider(
                    modifier = Modifier.padding(start = 64.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                )
            }
        }
    }
}

/**
 * The pending-requests card.
 *
 * The agent is blocked until this is answered, so it is the first thing on the home and not a notification-only
 * feature: a user who is not looking at the session still has to be able to find it (plan §4.3).
 */
@Composable
private fun PendingRequestsCard(count: Int, onClick: () -> Unit) {
    val label = stringResource(R.string.home_requests_waiting, count)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(OpenCodeThemeExtras.shapes.card)
            .clickable(onClick = onClick)
            .semantics { contentDescription = label },
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = OpenCodeThemeExtras.shapes.card,
    ) {
        Row(Modifier.padding(OpenCodeThemeExtras.spacing.lg), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Inbox, contentDescription = null)
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(text = stringResource(R.string.home_requests_title), style = MaterialTheme.typography.titleSmall)
                Text(text = label, style = MaterialTheme.typography.bodyMedium)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        }
    }
}

/** A project's display name: the one the server was given, or its directory's. */
fun projectLabel(project: Project): String = project.name?.takeIf { it.isNotBlank() } ?: Formatters.directoryName(project.canonical)

/** The whitespace between two projects, which is what separates them instead of a card edge. */
private val GROUP_GAP = 28.dp
