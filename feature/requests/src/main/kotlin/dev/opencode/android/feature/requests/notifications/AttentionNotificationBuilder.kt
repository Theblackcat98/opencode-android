package dev.opencode.android.feature.requests.notifications

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import dev.opencode.android.core.data.attention.AttentionAction
import dev.opencode.android.core.data.attention.AttentionChannel
import dev.opencode.android.core.data.attention.AttentionDraft
import dev.opencode.android.core.data.attention.AttentionPriority
import dev.opencode.android.core.data.attention.NotificationContent
import dev.opencode.android.core.data.attention.NotificationIds
import dev.opencode.android.core.data.attention.NotificationSlot
import dev.opencode.android.core.data.attention.RemoteInputSpec
import dev.opencode.android.core.data.attention.AttentionActionCodes
import dev.opencode.android.core.model.Outcome
import dev.opencode.android.core.model.PermissionRequest
import dev.opencode.android.feature.requests.R
import java.text.DateFormat
import java.util.Date

/**
 * The words on one notification.
 *
 * **Resolved from resources, never assembled in the data layer** (plan §5.4). The reconciler decided
 * *what* to say and which channel it belongs to; this decides the wording, which is the part that
 * needs a `Context` and that a locale change has to re-run. Keeping it as a value also means a test
 * can assert what a user is told without a `Notification` in sight.
 */
data class NotificationWords(
    val title: String,
    val body: String,
    /** The line under the title when there is one: a saved pattern, or a provider's own message. */
    val subText: String? = null,
)

/**
 * Turns an [AttentionDraft] into a [Notification] (plan §6, Phase 4).
 *
 * **The content is data, so the strings are here.** [AttentionReconciler][dev.opencode.android.core.data.attention.AttentionReconciler]
 * decided what to say and which channel it belongs to; this assembles it.
 *
 * **Grouped per session, summarised per server.** Android groups one level deep, so the session is
 * the native group and the server summary is the level above it, which is what the plan's "grouped
 * per server and session" means in the platform's terms.
 *
 * **Every pending intent is immutable.** `targetSdk` is 36, so an implicit `PendingIntent` without
 * `FLAG_IMMUTABLE` is refused outright, and an immutable one is also what stops another app from
 * filling in a reply on the user's behalf.
 */
class AttentionNotificationBuilder(
    private val context: Context,
    private val codes: AttentionActionCodes,
    private val ids: NotificationIds,
    /** The pending intent that opens a session's screen, for the body of a notification. */
    private val openSession: (serverId: String, sessionId: String) -> PendingIntent,
) {
    fun build(draft: AttentionDraft): Notification {
        val words = wordsFor(draft)
        val isSummary = draft.slot is NotificationSlot.ServerSummary
        val builder = NotificationCompat.Builder(context, draft.channel.id)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(words.title)
            .setContentText(words.body)
            .setWhen(draft.timestampMillis)
            .setShowWhen(draft.timestampMillis > 0)
            .setPriority(priorityOf(draft.channel.priority))
            .setCategory(categoryOf(draft.channel))
            .setOnlyAlertOnce(draft.onlyAlertOnce)
            .setGroup(draft.slot.group)
            .setGroupSummary(isSummary)
            // A summary has nothing to open, and the user dismisses it by tapping through to a member.
            .setAutoCancel(!isSummary)
        words.subText?.let { builder.setSubText(it) }
        if (!isSummary) {
            builder.setContentIntent(openSession(draft.slot.serverId, draft.content.sessionId))
        }
        draft.remoteInput?.let { builder.addAction(replyAction(draft, it)) }
        draft.actions.forEach { builder.addAction(actionFor(it)) }
        return builder.build()
    }

    /**
     * The words, without a `Notification`.
     *
     * Exposed so a test can assert the wording, which is most of what a user reads and none of what
     * a JVM can do to a real notification.
     */
    fun wordsFor(draft: AttentionDraft): NotificationWords = when (val content = draft.content) {
        is NotificationContent.PermissionRequest -> NotificationWords(
            title = context.getString(R.string.notify_permission_title, content.action),
            body = if (content.resources.isEmpty()) {
                context.getString(R.string.notify_permission_body, content.sessionTitle)
            } else {
                context.getString(
                    R.string.notify_permission_body_resources,
                    content.sessionTitle,
                    content.resources.take(MAX_RESOURCES).joinToString(", "),
                )
            },
            // The patterns "always" would store are named up front: a standing change to the
            // server's rules is not something a notification may hide.
            subText = content.savedPatterns.takeIf { it.isNotEmpty() }
                ?.let { context.getString(R.string.notify_permission_saves, it.joinToString(", ")) },
        )

        is NotificationContent.Form -> NotificationWords(
            title = context.getString(R.string.notify_form_title, content.title),
            body = content.question?.takeIf(String::isNotBlank)
                ?: context.getString(R.string.notify_form_body, content.sessionTitle),
        )

        is NotificationContent.TurnFinished -> NotificationWords(
            title = outcomeTitle(content.sessionTitle, content.outcome),
            body = context.getString(R.string.notify_turn_body, content.sessionTitle),
        )

        is NotificationContent.SubagentFinished -> NotificationWords(
            title = outcomeTitle(
                context.getString(R.string.notify_subagent_subject, content.sessionTitle),
                content.outcome,
            ),
            body = content.parentTitle?.let { context.getString(R.string.notify_subagent_body, it) }
                ?: context.getString(R.string.notify_subagent_body_unknown),
        )

        is NotificationContent.RetryScheduled -> NotificationWords(
            title = context.getString(R.string.notify_retry_title, content.sessionTitle),
            body = context.getString(
                R.string.notify_retry_body,
                content.attempt,
                DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(content.nextAtMillis)),
            ),
            subText = content.message?.takeIf(String::isNotBlank),
        )

        is NotificationContent.ServerUpdate -> NotificationWords(
            title = context.getString(R.string.notify_update_title, content.version),
            body = context.getString(R.string.notify_update_body),
        )

        is NotificationContent.ServerSummary -> NotificationWords(
            title = context.getString(
                R.string.notify_summary_title,
                content.serverName.ifBlank { context.getString(R.string.notify_summary_server_fallback) },
            ),
            body = if (content.blocking == content.total) {
                context.getString(R.string.notify_summary_all_blocking, content.total)
            } else {
                context.getString(R.string.notify_summary_body, content.blocking, content.total)
            },
        )
    }

    /**
     * The words of the "allow always" confirmation.
     *
     * **The patterns are the body of the message.** A standing change to the server's rules is not
     * something a notification may summarise: plan §5.2 requires the user to see what will be stored
     * before it is stored, and a shade notification with no dialog is the only place that can happen.
     */
    fun alwaysWords(request: PermissionRequest): NotificationWords = NotificationWords(
        title = context.getString(R.string.confirm_always_title, request.action),
        body = request.savedPatterns
            .takeIf { it.isNotEmpty() }
            ?.joinToString("\n")
            ?: context.getString(R.string.confirm_always_no_patterns),
    )

    private fun outcomeTitle(subject: String, outcome: Outcome?): String = context.getString(
        when (outcome) {
            Outcome.Failed -> R.string.notify_turn_failed
            Outcome.Interrupted -> R.string.notify_turn_interrupted
            Outcome.Succeeded, null -> R.string.notify_turn_succeeded
            else -> R.string.notify_turn_succeeded
        },
        subject,
    )

    private fun actionFor(action: AttentionAction): NotificationCompat.Action =
        NotificationCompat.Action.Builder(
            iconFor(action),
            context.getString(labelFor(action)),
            actionPendingIntent(action),
        ).build()

    /**
     * The inline reply row.
     *
     * Only a form that is one free-text question ever gets here, which [RemoteInputSpec] decides.
     * The system fills the `PendingIntent`'s intent when the user answers, so this side of the code
     * only has to attach the input to the action and name the key to read it back under.
     */
    private fun replyAction(draft: AttentionDraft, spec: RemoteInputSpec): NotificationCompat.Action {
        val action = draft.actions.filterIsInstance<AttentionAction.AnswerForm>().first()
        val remoteInput = RemoteInput.Builder(spec.key)
            .setLabel(spec.label.takeIf(String::isNotBlank))
            .setAllowFreeFormInput(true)
            .build()
        return NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_edit,
            context.getString(R.string.action_answer),
            actionPendingIntent(action),
        ).addRemoteInput(remoteInput)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setAllowGeneratedReplies(false)
            .setShowsUserInterface(false)
            .build()
    }

    private fun actionPendingIntent(action: AttentionAction): PendingIntent = PendingIntent.getBroadcast(
        context,
        codes.codeFor(action),
        NotificationIntents.actionIntent(context, action),
        // Immutable because another app must not be able to fill in an answer; update-current
        // because the same request may be re-posted with a newer title.
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun labelFor(action: AttentionAction): Int = when (action) {
        is AttentionAction.ReplyPermission -> when (action.decision.value) {
            "reject" -> R.string.action_reject
            "always" -> R.string.action_allow_always
            else -> R.string.action_allow_once
        }

        is AttentionAction.AnswerForm -> R.string.action_answer
        is AttentionAction.CancelForm -> R.string.action_cancel
        is AttentionAction.OpenSession -> R.string.action_open
        is AttentionAction.Interrupt -> R.string.action_interrupt
    }

    private fun iconFor(action: AttentionAction): Int = when (action) {
        is AttentionAction.ReplyPermission -> when (action.decision.value) {
            "reject" -> R.drawable.ic_notification_reject
            else -> R.drawable.ic_notification_allow
        }

        is AttentionAction.AnswerForm -> R.drawable.ic_notification_reply
        is AttentionAction.CancelForm -> R.drawable.ic_notification_reject
        is AttentionAction.OpenSession -> R.drawable.ic_notification_open
        is AttentionAction.Interrupt -> R.drawable.ic_notification_interrupt
    }

    private fun priorityOf(priority: AttentionPriority): Int = when (priority) {
        AttentionPriority.HIGH -> NotificationCompat.PRIORITY_HIGH
        AttentionPriority.DEFAULT -> NotificationCompat.PRIORITY_DEFAULT
        AttentionPriority.LOW -> NotificationCompat.PRIORITY_LOW
    }

    private fun categoryOf(channel: AttentionChannel): String = when (channel) {
        // A blocked agent behaves like a call: it is waiting on the user and nothing else matters.
        AttentionChannel.PERMISSION -> NotificationCompat.CATEGORY_CALL
        AttentionChannel.QUESTION -> NotificationCompat.CATEGORY_PROGRESS
        AttentionChannel.RETRY -> NotificationCompat.CATEGORY_ERROR
        else -> NotificationCompat.CATEGORY_STATUS
    }

    private companion object {
        /** A shade notification is not a list; three resources is what fits. */
        const val MAX_RESOURCES = 3
    }
}
