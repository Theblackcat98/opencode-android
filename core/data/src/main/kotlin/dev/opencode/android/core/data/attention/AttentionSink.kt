package dev.opencode.android.core.data.attention

import dev.opencode.android.core.data.action.ActionError
import dev.opencode.android.core.model.Outcome

/** A message with no durable state behind it: a failed notification action, or a confirmation. */
sealed interface AttentionNotice {

    val sessionId: String

    /**
     * A notification action failed.
     *
     * Reported rather than swallowed because the agent is still blocked on whatever the user tried
     * to answer. The request stays pending — [dev.opencode.android.core.data.server.RequestCenter]
     * only drops it on the server's event — so without this the user taps "Allow once" on a locked
     * phone, sees nothing happen, and has no way to know the answer never reached the server.
     */
    data class ActionFailed(
        override val sessionId: String,
        /** What the user was trying to do, so the message can name it. */
        val about: String,
        val error: ActionError,
    ) : AttentionNotice

    /** A turn the user asked about, whose outcome the server already knows. */
    data class TurnOutcome(
        override val sessionId: String,
        val outcome: Outcome,
    ) : AttentionNotice
}

/**
 * Where the attention layer's output goes (plan §6, Phase 4).
 *
 * **An interface, so the coordinator is testable.** Everything above this line is pure and can be
 * asserted on; the implementation is the one part that needs a `NotificationManager`, and it is the
 * part that cannot be exercised without a device. Keeping the boundary here means the decisions are
 * decided in tests and the platform calls are only the delivery mechanism.
 */
interface AttentionSink {
    /** Brings the shade in line with [reconcile]: posts what is new, un-posts what is gone. */
    fun publish(reconcile: AttentionReconcile)

    /** Posts a message that has no slot of its own. */
    fun announce(notice: AttentionNotice)

    /**
     * The number of unread sessions to put on the app's launcher shortcut.
     *
     * A badge on a shortcut is the one piece of "unread" the user sees without opening the app, so it
     * is driven from the same state the notifications are rather than from a second derivation of
     * the rule. Zero clears it.
     */
    fun setLauncherBadge(unreadSessions: Int)
}
