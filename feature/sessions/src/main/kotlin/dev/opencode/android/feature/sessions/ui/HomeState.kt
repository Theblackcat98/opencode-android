package dev.opencode.android.feature.sessions.ui

import dev.opencode.android.core.data.server.SessionRow
import dev.opencode.android.core.model.Project

/** Everything the home draws. */
data class HomeUiState(
    val serverId: String? = null,
    /** The added projects, in the order they were added, each with its own sessions. */
    val groups: List<ProjectGroup> = emptyList(),
    /** The server's projects that are not on the home screen, for the "Add project" sheet. */
    val available: List<Project> = emptyList(),
    /** The server has not yet said which projects it has, so an empty home is not yet a fact. */
    val syncing: Boolean = false,
    /** The clock the relative timestamps are computed against. */
    val now: Long = 0L,
)

/**
 * One project on the home screen and the sessions in it.
 *
 * [sessions] is the newest few; [total] and [running] count every root session of the project this client
 * knows about, so "See all" can say what is behind it.
 */
data class ProjectGroup(
    val project: Project,
    val sessions: List<SessionRow>,
    val total: Int,
    val running: Int,
)

/**
 * The home's groups: the [added] projects, in the order they were added, each with its root sessions.
 *
 * A project that was added but that the server no longer lists is dropped rather than drawn empty: the id is
 * all the client has, and a group with no name and no sessions is a dead row. Child sessions (subagents) are
 * left out, as the list's "roots only" default does, because a subagent is reached from its parent.
 *
 * This reads every row the store has, not the list screen's filtered ones, so a project filter chosen on the
 * list screen does not change the home.
 */
fun homeGroups(
    projects: List<Project>,
    added: List<String>,
    rows: List<SessionRow>,
    limit: Int = HOME_SESSIONS_PER_PROJECT,
): List<ProjectGroup> {
    val byId = projects.associateBy { it.id }
    val roots = rows.filter { !it.isChild }.groupBy { it.session.projectID }
    return added.mapNotNull { id ->
        val project = byId[id] ?: return@mapNotNull null
        val sessions = roots[id].orEmpty().sortedByDescending { it.updated }
        ProjectGroup(
            project = project,
            sessions = sessions.take(limit),
            total = sessions.size,
            running = sessions.count { it.isRunning || it.isRetrying },
        )
    }
}

/** Enough to pick up where you left off; "See all" has the rest. */
const val HOME_SESSIONS_PER_PROJECT = 5
