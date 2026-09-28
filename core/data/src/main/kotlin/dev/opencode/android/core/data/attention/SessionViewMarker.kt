package dev.opencode.android.core.data.attention

import dev.opencode.android.core.model.SessionInfo

/**
 * One `POST /api/session/{id}/view` call: the idle transition the user has now actually seen.
 *
 * The body is the `time.idle` the client observed, not a "now": the server records which transition
 * was viewed, so telling it a later instant would mark a turn the user never read as seen.
 */
data class SessionViewRequest(
    val sessionId: String,
    val idleAtMillis: Long,
)

/**
 * When to tell the server that the user has seen a session (plan §6, "Unread model").
 *
 * **Only when the user actually sees the idle transition.** Marking a session viewed on `session.idle`
 * would be a guess — the event says the agent stopped, not that anyone was looking — and it would
 * clear the badge for a session the user has not opened. The signal the app has is the screen being
 * resumed on that session, and this turns it into the call.
 *
 * **A pure predicate, so the rule is testable without a screen.** The caller reads the session's
 * projection, asks this whether there is anything to mark, and sends the result. Nothing is marked
 * optimistically: the server's `session.viewed` event is what clears the badge, which is the plan's
 * "the server echoes" rule applied to the client's own write.
 */
object SessionViewMarker {

    /** The call to make for [session], or `null` when there is nothing new to mark. */
    fun requestFor(session: SessionInfo?): SessionViewRequest? {
        val idle = session?.time?.idle ?: return null
        val viewed = session.time.viewed
        // The plan's rule verbatim: an idle transition later than the last viewed one is unread.
        if (viewed != null && idle <= viewed) return null
        return SessionViewRequest(sessionId = session.id, idleAtMillis = idle)
    }

    /** Whether marking [session] would do anything, which is what a resumed screen asks first. */
    fun hasUnseenIdle(session: SessionInfo?): Boolean = requestFor(session) != null
}
