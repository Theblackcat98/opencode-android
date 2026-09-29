package dev.opencode.android.core.data.attention

import dev.opencode.android.core.data.server.PendingRequest
import dev.opencode.android.core.data.server.SessionActivity
import dev.opencode.android.core.model.FormKind
import dev.opencode.android.core.model.Outcome

/**
 * The identity of one notification, and the diff key the reconciler works on.
 *
 * **The slot is the state, not a label.** Two turns of the same session have different slots
 * because the slot carries the `time.idle` they went idle at, so a second turn posts a second
 * notification and the first one is cancelled. Without that, a session that goes idle twice would
 * look unchanged and the user would be told about the first turn only.
 */
sealed interface NotificationSlot {

    val serverId: String

    /** The session it belongs to, or `null` for a notification about the server itself. */
    val sessionId: String?

    /** Grouped per session (plan §6, "grouped per server and session"). */
    val group: String get() = groupOf(serverId, sessionId)

    data class Permission(
        override val serverId: String,
        val requestId: String,
        override val sessionId: String,
    ) : NotificationSlot

    data class Form(
        override val serverId: String,
        val formId: String,
        override val sessionId: String,
    ) : NotificationSlot

    data class TurnFinished(
        override val serverId: String,
        override val sessionId: String,
        /** The `time.idle` of the turn that finished, which is what makes a new turn a new slot. */
        val idleAtMillis: Long,
    ) : NotificationSlot

    data class SubagentFinished(
        override val serverId: String,
        override val sessionId: String,
        val idleAtMillis: Long,
    ) : NotificationSlot

    /**
     * A shell command finished.
     *
     * **No session, and that is the point.** A command started from the shell panel, or by the agent
     * in a session the user is not looking at, has no session to open — the thing to open is the
     * shell panel for its location. [directory] is therefore part of the slot, and the action is the
     * one that takes the user there.
     */
    data class ShellFinished(
        override val serverId: String,
        val shellId: String,
        val directory: String,
        /** The instant the command completed, which is what makes a second run a second slot. */
        val completedAtMillis: Long,
    ) : NotificationSlot {
        override val sessionId: String? = null
    }

    data class Retry(
        override val serverId: String,
        override val sessionId: String,
        val attempt: Int,
        val nextAtMillis: Long,
    ) : NotificationSlot

    data class ServerUpdate(override val serverId: String, val version: String) : NotificationSlot {
        override val sessionId: String? = null
    }

    /** One entry per server, holding the group's members together in the shade. */
    data class ServerSummary(override val serverId: String) : NotificationSlot {
        override val sessionId: String? = null
    }

    /**
     * A one-off message with no durable state behind it, such as a failed notification action.
     *
     * [about] distinguishes two of the same kind in one session, which is what a permission that
     * could not be answered twice in a row is: the second one has to be its own notification rather
     * than a re-post of the first.
     */
    data class Failure(val session: String, val about: String) : NotificationSlot {
        override val serverId: String get() = ""
        override val sessionId: String? get() = session
    }

    /**
     * The "allow always" confirmation, which is the second step of an action the plan requires an
     * explicit confirmation for (§5.2).
     *
     * It is a slot rather than a notice because it is a real notification the user can come back to,
     * and because the permission it confirms stays pending until the user acts on it.
     */
    data class Confirmation(
        override val serverId: String,
        val requestId: String,
        override val sessionId: String,
    ) : NotificationSlot

    companion object {
        fun groupOf(serverId: String, sessionId: String?): String = when (sessionId) {
            null -> summaryGroupOf(serverId)
            else -> "session|$serverId|$sessionId"
        }

        fun summaryGroupOf(serverId: String): String = "server|$serverId"
    }
}

/**
 * What a notification says, as data.
 *
 * **No user-facing text crosses this boundary.** A draft carries the facts — the tool name, the
 * resources, the outcome, the retry attempt — and the renderer turns them into strings, because
 * plan §5.4 externalises every string and a message assembled in a data layer cannot be translated
 * or read by a screen reader with the right emphasis.
 */
sealed interface NotificationContent {

    val sessionId: String

    data class PermissionRequest(
        override val sessionId: String,
        val sessionTitle: String,
        /** The tool or action being asked for, which is the request's own `action`. */
        val action: String,
        val resources: List<String>,
        /** The patterns an "always" reply would store, which the confirmation has to show. */
        val savedPatterns: List<String>,
    ) : NotificationContent

    data class Form(
        override val sessionId: String,
        val sessionTitle: String,
        val title: String,
        val kind: FormKind,
        /** The one question answerable from the notification, when there is exactly one. */
        val question: String?,
    ) : NotificationContent

    data class TurnFinished(
        override val sessionId: String,
        val sessionTitle: String,
        val outcome: Outcome,
    ) : NotificationContent

    data class SubagentFinished(
        override val sessionId: String,
        val sessionTitle: String,
        val parentTitle: String?,
        val outcome: Outcome,
    ) : NotificationContent

    /** A command finished: what it was, how it ended, and which location's panel shows it. */
    data class ShellFinished(
        override val sessionId: String,
        val command: String,
        /** `exited`, `killed` or `timeout`, the server's own word. */
        val status: String,
        val exitCode: Int?,
        val directory: String,
    ) : NotificationContent

    data class RetryScheduled(
        override val sessionId: String,
        val sessionTitle: String,
        val attempt: Int,
        val nextAtMillis: Long,
        val message: String?,
        /** The provider's own action link, for a usage-exceeded style retry. */
        val actionUrl: String?,
    ) : NotificationContent

    data class ServerUpdate(val version: String) : NotificationContent {
        override val sessionId: String get() = ""
    }

    /** A group summary: how many things are waiting, and how many of them are blocking. */
    data class ServerSummary(
        val serverName: String,
        val total: Int,
        val blocking: Int,
    ) : NotificationContent {
        override val sessionId: String get() = ""
    }
}

/** A notification to post, before the platform has given it an id or the renderer has worded it. */
data class AttentionDraft(
    val slot: NotificationSlot,
    val channel: AttentionChannel,
    val content: NotificationContent,
    val actions: List<AttentionAction> = emptyList(),
    /** Set only for a form that is one free-text question; see [RemoteInputSpec]. */
    val remoteInput: RemoteInputSpec? = null,
    /** Milliseconds; the shade orders by it, so the newest is on top. */
    val timestampMillis: Long = 0L,
    /** A summary's count changes as members come and go, and a count that alerts is noise. */
    val onlyAlertOnce: Boolean = false,
)

/**
 * One session as the attention layer needs it.
 *
 * A projection of what three stores already hold, and nothing more, so a test can build a state
 * without a server, a socket or a database.
 */
data class AttentionSession(
    val id: String,
    val title: String,
    val parentID: String? = null,
    val parentTitle: String? = null,
    val activity: SessionActivity = SessionActivity.Idle,
    /** The last run's result, which is what a finished turn reports. */
    val outcome: Outcome? = null,
    /** `time.idle`, the instant the last turn went idle. */
    val idleAtMillis: Long? = null,
    /** `time.viewed`; a session with `idle > viewed` is what the plan calls unread. */
    val viewedAtMillis: Long? = null,
) {
    /** The plan's unread rule, verbatim: `time.idle > time.viewed`. */
    val isUnread: Boolean
        get() = idleAtMillis != null && (viewedAtMillis == null || idleAtMillis > viewedAtMillis)

    val isSubagent: Boolean get() = parentID != null
}

/**
 * One finished shell command as the attention layer needs it.
 *
 * **A projection of a transition, and the transition is the client's.** A shell command has no
 * `viewed` field and no unread rule, so "the user has been told" is not something the server can
 * answer; the only honest record is the client's own, which is why the reconciler is fed a *list of
 * completions the client observed* rather than the running list. [directory] is what the notification
 * opens, and it is also what "the user was already looking at it" is measured against.
 */
data class AttentionShell(
    val id: String,
    val command: String,
    val status: String,
    val exitCode: Int?,
    val directory: String,
    val completedAtMillis: Long,
)

/**
 * Everything the reconciler reads.
 *
 * Held as one value so the diff is a single comparison and so "the same state twice produces no
 * commands" is a property of a function rather than of a collector's timing.
 */
data class AttentionState(
    val serverId: String,
    val serverName: String = "",
    val sessions: Map<String, AttentionSession> = emptyMap(),
    val pending: List<PendingRequest> = emptyList(),
    /**
     * The commands this client watched finish, newest last.
     *
     * Bounded and dropped as the user reads them, because the alternative — deriving completions from
     * the *running* list — would need a per-shell "notified" flag the server does not keep and the
     * client would have to invent.
     */
    val finishedShells: List<AttentionShell> = emptyList(),
    /**
     * The location whose shell panel is on screen, which produces no shell notification.
     *
     * The same idea as [openSessionId] and for the same reason: "the user is looking at it" is a fact
     * about which screen is in front, and it is published once rather than re-derived by each caller.
     */
    val openDirectory: String? = null,
    /** The version `installation.update-available` announced, or `null` once it is dismissed. */
    val updateVersion: String? = null,
    /** The session the user is looking at, which produces no notification. */
    val openSessionId: String? = null,
    /** Sessions the user silenced; one of them produces nothing at all. */
    val mutedSessions: Set<String> = emptySet(),
    val quietHours: QuietHours = QuietHours(),
    /** Sessions whose `ask` requests are being answered automatically, and so need no notification. */
    val autoApprovedSessions: Set<String> = emptySet(),
    val nowMillis: Long = 0L,
    val utcOffsetMillis: Long = 0L,
) {
    val quiet: Boolean get() = quietHours.isQuietAt(nowMillis, utcOffsetMillis)

    fun isMuted(sessionID: String?): Boolean = sessionID != null && sessionID in mutedSessions
}
