package dev.opencode.android.core.data.server

import dev.opencode.android.core.model.SessionInfo
import dev.opencode.android.core.model.SessionStatus
import dev.opencode.android.core.model.event.Event

/**
 * How a session's list row should read: the badges, the chips and the totals.
 *
 * Everything here is derived, never stored twice. [SessionRow] is a projection of a
 * [SessionInfo] plus the live activity and status the event stream reported, and it is recomputed
 * whenever any of those change, so a badge can never disagree with the session it is drawn on.
 */
data class SessionRow(
    val session: SessionInfo,
    val activity: SessionActivity,
    val status: SessionStatus?,
    /** Direct children of this session, known so far. Drives the "N subagents" chip. */
    val childCount: Int,
) {
    val id: String get() = session.id
    val title: String get() = session.title?.takeIf { it.isNotBlank() } ?: DEFAULT_TITLE
    val agent: String? get() = session.agent
    val model: SessionModelChip? get() = session.model?.let { SessionModelChip(it.providerID, it.id, it.variant) }
    val isUnread: Boolean get() = session.isUnread
    val cost: Double get() = session.cost
    val tokens: Long get() = session.tokens.total
    val updated: Long get() = session.time.updated
    val directory: String get() = session.location.directory
    val isChild: Boolean get() = session.parentID != null

    val isRunning: Boolean get() = activity is SessionActivity.Running
    val isRetrying: Boolean get() = activity is SessionActivity.Retrying

    companion object {
        const val DEFAULT_TITLE = "Untitled session"
    }
}

/** The `provider/model#variant` chip on a row and in the session header. */
data class SessionModelChip(
    val providerID: String,
    val modelID: String,
    val variant: String?,
) {
    val label: String get() = "$providerID/$modelID"
    val variantLabel: String? get() = variant?.takeIf { it.isNotBlank() }
}

/**
 * Whether a session has a live execution.
 *
 * Three sources agree on this and the plan says so: `session.active` (the REST snapshot a resync
 * reads), `session.status`, and the `session.execution.*` events. [Unknown] is the state after a
 * disconnect, when the client honestly does not know; showing "running" then would be a guess.
 */
sealed interface SessionActivity {
    /** The server reported an execution in flight. */
    data object Running : SessionActivity

    /** A provider call failed and will be retried. */
    data class Retrying(
        val attempt: Int,
        val next: Long,
        val message: String,
        val action: SessionStatus.Retry.Action? = null,
    ) : SessionActivity

    /** No execution. */
    data object Idle : SessionActivity

    /** Not known: the stream is down and the last snapshot is stale. */
    data object Unknown : SessionActivity
}

/**
 * The session list's own query, and the client-side half of it.
 *
 * [search], [projectId] and [directory] are also sent to the server, which filters while it pages;
 * [rootsOnly] is the one the server cannot answer in a paged list, so it is applied here. The
 * child count that goes with it comes from the sessions the client has already seen.
 */
data class SessionFilter(
    val search: String? = null,
    /** Hide child sessions, which are subagents and background commands. */
    val rootsOnly: Boolean = true,
    val projectId: String? = null,
    /** A session's directory, or a project's canonical checkout. */
    val directory: String? = null,
) {
    /** True when the server can narrow the page for this filter. */
    val narrowsOnServer: Boolean get() = !search.isNullOrBlank() || projectId != null || directory != null

    fun matches(session: SessionInfo): Boolean {
        if (rootsOnly && session.parentID != null) return false
        if (projectId != null && session.projectID != projectId) return false
        if (directory != null && session.location.directory != directory) return false
        val term = search?.trim()
        if (!term.isNullOrEmpty()) {
            val title = session.title
            if (title.isNullOrBlank() || !title.contains(term, ignoreCase = true)) return false
        }
        return true
    }

    companion object {
        val All = SessionFilter()
        val Roots = SessionFilter(rootsOnly = true)
        val Everything = SessionFilter(rootsOnly = false)
    }
}

/** The event's session, or `null` for an envelope that names none. */
internal val Event.sessionIDOrNull: String?
    get() = (payload as? dev.opencode.android.core.model.event.EventPayload.SessionScoped)?.sessionID
