package dev.opencode.android.core.data.attention

import dev.opencode.android.core.model.PermissionReply
import dev.opencode.android.core.model.PermissionRequest

/** What auto-approve decided about one pending request. */
sealed interface AutoApproveDecision {

    /** The request and the decision to send. */
    data class Approve(val request: PermissionRequest) : AutoApproveDecision

    /** The request stays pending and the user is asked. */
    data class Ask(val request: PermissionRequest, val reason: AutoApproveSkip) : AutoApproveDecision
}

/** Why auto-approve did not apply to a request. */
enum class AutoApproveSkip {
    /** Auto-approve is on for this session, or globally, but the window has passed. */
    NOT_RUNNING,

    /** This request is already being answered by an earlier decision. */
    ALREADY_DECIDED,
}

/**
 * Auto-approve mode, the way the TUI's `autoaccept` works (features doc §14; plan §6).
 *
 * **Client-side, and only ever `once`.** The alternative the plan rejected — a server-side session
 * rule — would be evaluated last and would therefore override an agent's `deny` rules. Here the client
 * answers only the requests the server actually raised, and a `deny` rule never produces one, so
 * there is nothing for the client to override. That is not a check this code performs: it is a
 * property of the server's rule evaluation, and the honest way to keep it is for this function to
 * have no rule input at all.
 *
 * **Never `always`.** An `always` reply stores patterns on the server as a standing change, which is
 * exactly the thing plan §5.2 requires an explicit confirmation for. Auto-approve is a mode the user
 * turns on deliberately and a mode they turn off; it does not quietly widen the server's rules.
 *
 * **Per session, or globally, and always time-limited.** The plan says "optionally time-limited" and
 * this makes it always time-limited, because an approval mode with no end is a permission that
 * cannot be taken back without noticing.
 */
object AutoApprovePolicy {

    /**
     * Whether a request is auto-answered at [now].
     *
     * The decision is [AutoApproveDecision.Approve] with [PermissionReply.Once] implied: the caller
     * answers through the same [dev.opencode.android.core.data.server.RequestCenter] the screens use,
     * so a retried approve is the same request and cannot approve twice.
     */
    fun decide(
        request: PermissionRequest,
        settings: AttentionSettings,
        now: Long,
        alreadyDecided: Set<String> = emptySet(),
    ): AutoApproveDecision {
        if (request.id in alreadyDecided) {
            return AutoApproveDecision.Ask(request, AutoApproveSkip.ALREADY_DECIDED)
        }
        if (!settings.autoApproveActive(request.sessionID, now)) {
            return AutoApproveDecision.Ask(request, AutoApproveSkip.NOT_RUNNING)
        }
        return AutoApproveDecision.Approve(request)
    }

    /** The decision an approval sends. Named here so no caller can pass anything else. */
    val decision: PermissionReply = PermissionReply.Once

    /**
     * The sessions whose requests are being answered automatically right now.
     *
     * This is what [AttentionState.autoApprovedSessions] carries, so a request in one of these
     * sessions produces no notification: it is about to be answered, and a shade button the user
     * cannot outrace the client on is worse than no button.
     */
    fun autoApprovedSessions(settings: AttentionSettings, sessionIds: Collection<String>, now: Long): Set<String> =
        sessionIds.filterTo(mutableSetOf()) { settings.autoApproveActive(it, now) }
}
