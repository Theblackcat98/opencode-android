package dev.opencode.android.feature.requests.notifications

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import dagger.hilt.android.AndroidEntryPoint
import dev.opencode.android.core.data.action.ActionError
import dev.opencode.android.core.data.action.ActionErrorKind
import dev.opencode.android.core.data.attention.AttentionAction
import dev.opencode.android.core.data.attention.AttentionActionCodes
import dev.opencode.android.core.data.attention.AttentionNotice
import dev.opencode.android.core.data.attention.AttentionSink
import dev.opencode.android.core.data.attention.NotificationIds
import dev.opencode.android.core.data.attention.NotificationSlot
import dev.opencode.android.core.data.attention.encodeField
import dev.opencode.android.core.data.attention.remoteInputAnswer
import dev.opencode.android.core.data.attention.toFormFields
import dev.opencode.android.core.data.forms.FormEngine
import dev.opencode.android.core.data.presence.StartExemption
import dev.opencode.android.core.data.server.ServerDataRegistry
import dev.opencode.android.core.data.server.ServerDataSet
import dev.opencode.android.core.data.server.actionErrorOrNull
import dev.opencode.android.core.model.FormInfo
import dev.opencode.android.core.model.PermissionReply
import dev.opencode.android.core.model.PermissionRequest
import dev.opencode.android.feature.requests.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * Performs a notification action (plan §6, Phase 4).
 *
 * **A receiver, because that is the only thing a notification action can target.** Tapping "Allow
 * once" has to work while the app is not running, and a `BroadcastReceiver` is what the platform
 * offers: it starts the process, runs this, and finishes. Every decision it makes is elsewhere — which
 * channel an answer goes to, how a form is validated, what the words of a failure are — so the part
 * that cannot be tested without a device is this file's `when` and nothing more.
 *
 * **It answers through the same [dev.opencode.android.core.data.server.RequestCenter] the screens
 * use.** A retried approve is therefore the same request, which is what the confirmation below needs
 * behind it: a second tap cannot approve twice.
 *
 * **"Allow always" is answered in two steps.** Plan §5.2 requires an explicit confirmation for a
 * standing change to the server's rules, and a notification action has no dialog to confirm in, so
 * the first tap posts a confirmation that names the patterns and only the second sends the reply. The
 * permission stays pending throughout, so nothing is lost if the user never confirms.
 *
 * **Every wait is bounded.** A receiver is killed by the platform after a few seconds of foreground
 * time, and an unbounded wait here would be a hang the user sees as a button that does nothing, so
 * each call has a deadline and reports when it is hit.
 */
@AndroidEntryPoint
class NotificationActionReceiver : BroadcastReceiver() {

    @Inject
    lateinit var dataSets: ServerDataRegistry

    @Inject
    lateinit var sink: AttentionSink

    @Inject
    lateinit var ids: NotificationIds

    @Inject
    lateinit var codes: AttentionActionCodes

    @Inject
    lateinit var builder: AttentionNotificationBuilder

    @Inject
    lateinit var launcher: ConnectionServiceLauncher

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        // A receiver can start a process that has never created its channels, and a notification on a
        // channel that does not exist is dropped without a word.
        installAttentionChannels(context)
        val action = NotificationIntents.readAction(intent) ?: return
        val pending = goAsync()
        scope.launch {
            try {
                perform(context, action, intent)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun perform(context: Context, action: AttentionAction, intent: Intent) {
        val set = withTimeoutOrNull(ACTION_TIMEOUT_MILLIS) { dataSets.ensureDataSet(action.serverId) }
        if (set == null) {
            report(context, action, ActionError(ActionErrorKind.OFFLINE, "No server profile for this notification"))
            return
        }
        when (action) {
            is AttentionAction.ReplyPermission -> replyPermission(context, set, action)
            is AttentionAction.AnswerForm -> answerForm(context, set, action, intent)
            is AttentionAction.CancelForm -> cancelForm(context, set, action)
            is AttentionAction.Interrupt -> interrupt(context, set, action)
            // Opening a session or a location is the activity's job; the receiver has nothing to do
            // but finish.
            is AttentionAction.OpenSession, is AttentionAction.OpenLocation -> Unit
        }
    }

    private suspend fun replyPermission(
        context: Context,
        set: ServerDataSet,
        action: AttentionAction.ReplyPermission,
    ) {
        val request = findPermission(set, action)
        if (request == null) {
            // Another client answered it, or the session is gone. Either way there is nothing to send
            // and the notification has nothing left to say.
            cancel(context, NotificationSlot.Permission(action.serverId, action.requestId, action.sessionId))
            return
        }
        if (action.decision == PermissionReply.Always) {
            confirmAlways(context, action, request)
            return
        }
        val error = withTimeoutOrNull(ACTION_TIMEOUT_MILLIS) { set.requests.replyPermission(request, action.decision) }
        if (error == null) {
            cancel(context, NotificationSlot.Permission(action.serverId, action.requestId, action.sessionId))
            // Answering from the shade is the user's tap on this app's own notification, which is one
            // of the two documented exemptions from Android's background-start restriction. The agent
            // is about to keep working, so the connection should come back with it.
            launcher.offer(StartExemption.NOTIFICATION_ACTION)
        } else {
            report(context, action, error)
        }
    }

    private suspend fun answerForm(
        context: Context,
        set: ServerDataSet,
        action: AttentionAction.AnswerForm,
        intent: Intent,
    ) {
        val form = findForm(set, action.sessionId, action.formId)
        if (form == null) {
            cancel(context, NotificationSlot.Form(action.serverId, action.formId, action.sessionId))
            return
        }
        // A form that turned out not to be one free-text question between posting and tapping has
        // nothing to validate the text against, so there is no answer to send.
        val key = NotificationIntents.answeredKey(action) ?: return report(
            context,
            action,
            ActionError(ActionErrorKind.INVALID_REQUEST, context.getString(R.string.notify_form_invalid)),
        )
        val text = withTimeoutOrNull(ACTION_TIMEOUT_MILLIS) {
            RemoteInput.getResultsFromIntent(intent)?.getCharSequence(key)?.toString()
        }
        val answer = remoteInputAnswer(key, text)
        if (answer == null) {
            report(context, action, ActionError(ActionErrorKind.INVALID_REQUEST, context.getString(R.string.notify_form_blank)))
            return
        }
        // The same validation the on-screen renderer applies. A shade answer that skipped it would be
        // the one answer the server could reject, and the user would have no way to see why.
        val fields = action.fields.ifEmpty { form.fields.map(::encodeField) }
        val problems = FormEngine.validate(fields.toFormFields(), mapOf(key to answer))
        if (problems.isNotEmpty()) {
            report(context, action, ActionError(ActionErrorKind.INVALID_REQUEST, context.getString(R.string.notify_form_invalid)))
            return
        }
        val error = withTimeoutOrNull(ACTION_TIMEOUT_MILLIS) {
            set.requests.replyForm(form, mapOf(key to answer))
        }
        if (error == null) {
            cancel(context, NotificationSlot.Form(action.serverId, action.formId, action.sessionId))
        } else {
            report(context, action, error)
        }
    }

    private suspend fun cancelForm(context: Context, set: ServerDataSet, action: AttentionAction.CancelForm) {
        val form = findForm(set, action.sessionId, action.formId)
        if (form != null) withTimeoutOrNull(ACTION_TIMEOUT_MILLIS) { set.requests.cancelForm(form) }
        cancel(context, NotificationSlot.Form(action.serverId, action.formId, action.sessionId))
    }

    private suspend fun interrupt(context: Context, set: ServerDataSet, action: AttentionAction.Interrupt) {
        // `resume` is left false: the user asked to stop, not to stop and carry on from what they
        // had already typed.
        val result = withTimeoutOrNull(ACTION_TIMEOUT_MILLIS) { set.commands.interrupt(action.sessionId) }
        result?.actionErrorOrNull?.let { report(context, action, it) }
    }

    /**
     * The "Allow always" confirmation, as a notification.
     *
     * The patterns are named on it, because the point of the confirmation is that the user can see
     * what the server will match from now on. It carries the *same* reply action, so tapping it is
     * the confirmation and the second tap is the write.
     */
    @SuppressLint("MissingPermission")
    private fun confirmAlways(
        context: Context,
        action: AttentionAction.ReplyPermission,
        request: PermissionRequest,
    ) {
        // A receiver only runs because a notification was tapped, so the permission was granted a
        // moment ago; the check is a formality, and the one that matters is the original post.
        if (!canPostNotifications(context)) return
        val confirmation = action.copy(decision = PermissionReply.Always)
        val words = builder.alwaysWords(request)
        val notification = NotificationCompat.Builder(context, AttentionChannelSpec.PERMISSION.id)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(words.title)
            .setContentText(words.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(words.body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(true)
            .addAction(
                NotificationCompat.Action.Builder(
                    R.drawable.ic_notification_allow,
                    context.getString(R.string.confirm_always_action),
                    PendingIntent.getBroadcast(
                        context,
                        codes.codeFor(confirmation),
                        NotificationIntents.actionIntent(context, confirmation),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    ),
                ).setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ).build(),
            )
            .build()
        NotificationManagerCompat.from(context)
            .notify(ids.idFor(NotificationSlot.Confirmation(action.serverId, request.id, request.sessionID)), notification)
    }

    private fun findPermission(set: ServerDataSet, action: AttentionAction.ReplyPermission): PermissionRequest? =
        set.requests.currentPermissions().firstOrNull { it.id == action.requestId && it.sessionID == action.sessionId }

    private fun findForm(set: ServerDataSet, sessionId: String, formId: String): FormInfo? =
        set.requests.currentForms().firstOrNull { it.id == formId && it.sessionID == sessionId }

    private fun cancel(context: Context, slot: NotificationSlot) {
        NotificationManagerCompat.from(context).cancel(ids.idFor(slot))
    }

    private fun report(context: Context, action: AttentionAction, error: ActionError) {
        sink.announce(AttentionNotice.ActionFailed(action.sessionId, action.describe(context), error))
    }

    /** A named thing to say in a failure, so the message is not "the action". */
    private fun AttentionAction.describe(context: Context): String = context.getString(
        when (this) {
            is AttentionAction.ReplyPermission -> when (decision.value) {
                "reject" -> R.string.action_reject
                "always" -> R.string.action_allow_always
                else -> R.string.action_allow_once
            }

            is AttentionAction.AnswerForm -> R.string.action_answer
            is AttentionAction.CancelForm -> R.string.action_cancel
            is AttentionAction.Interrupt -> R.string.action_interrupt
            is AttentionAction.OpenSession -> R.string.action_open
            is AttentionAction.OpenLocation -> R.string.action_open
        },
    )

    private companion object {
        /**
         * A receiver is given a few seconds of foreground time and then killed. Longer than this and
         * the platform kills the process mid-call, which the user sees as a button that does nothing;
         * shorter and a slow LAN server fails a write it would have accepted.
         */
        const val ACTION_TIMEOUT_MILLIS = 8_000L
    }
}
