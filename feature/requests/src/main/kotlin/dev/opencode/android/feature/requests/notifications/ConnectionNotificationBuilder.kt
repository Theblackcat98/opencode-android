package dev.opencode.android.feature.requests.notifications

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import dev.opencode.android.core.data.attention.AttentionAction
import dev.opencode.android.core.data.attention.AttentionActionCodes
import dev.opencode.android.core.data.presence.RunningSession
import dev.opencode.android.feature.requests.R

/**
 * What the ongoing notification says (plan §6, Phase 4).
 *
 * A value rather than a `Notification`, so the wording is testable and translatable (plan §5.4) and
 * so the decision about *whether* to interrupt is not hidden inside string formatting.
 */
data class ConnectionWords(
    val title: String,
    val body: String,
    val subText: String? = null,
    val running: List<String> = emptyList(),
    val canInterrupt: Boolean = false,
    val autoApproveUntilMillis: Long? = null,
)

/**
 * The connection service's own notification.
 *
 * **It is the user-visible proof that the service is running, so it names the work.** A foreground
 * service with an empty notification is the thing Android's policy and users both object to, and the
 * phase's own exit criteria say the user should be able to see what is running and stop it. It lists
 * the running sessions and offers an Interrupt action, which is the plan's requirement for this
 * notification.
 *
 * **`setOngoing` and no `setAutoCancel`.** It is not a message, and the only way to remove it is for
 * the service to stop — which is what makes "no service runs while nothing is active" visible rather
 * than asserted.
 *
 * **`setSilent`, because the connection notification is not news.** Every change to the running set
 * re-posts it, and a re-post that buzzes would make a background conversation noisy.
 */
class ConnectionNotificationBuilder(
    private val context: Context,
    private val codes: AttentionActionCodes,
    private val openSession: (serverId: String, sessionId: String) -> PendingIntent,
) {
    fun wordsFor(
        running: List<RunningSession>,
        pending: Int,
        alwaysConnected: Boolean,
        autoApproveUntilMillis: Long?,
    ): ConnectionWords {
        val names = running.map { it.title }
        val title = when {
            running.isNotEmpty() -> context.resources.getQuantityString(
                R.plurals.connection_running_title,
                running.size,
                running.size,
            )

            pending > 0 -> context.getString(R.string.connection_waiting_title, pending)

            alwaysConnected -> context.getString(R.string.connection_always_title)

            else -> context.getString(R.string.connection_idle_title)
        }
        val body = when {
            running.isNotEmpty() -> names.take(MAX_LISTED).joinToString("\n")
            pending > 0 -> context.getString(R.string.connection_waiting_body)
            else -> context.getString(R.string.connection_idle_body)
        }
        return ConnectionWords(
            title = title,
            body = body,
            // The persistent indicator auto-approve needs (plan §6): an approval mode with no
            // visible sign is one the user cannot tell is on.
            subText = autoApproveUntilMillis?.let {
                context.getString(R.string.connection_auto_approve_subtext)
            },
            running = names,
            canInterrupt = running.isNotEmpty(),
            autoApproveUntilMillis = autoApproveUntilMillis,
        )
    }

    fun build(
        words: ConnectionWords,
        serverId: String,
        interruptSessionId: String?,
        contentIntent: PendingIntent,
    ): Notification {
        val builder = NotificationCompat.Builder(context, AttentionChannelSpec.CONNECTION.id)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(words.title)
            .setContentText(words.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(words.body))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        words.subText?.let { builder.setSubText(it) }
        if (words.canInterrupt && interruptSessionId != null) {
            builder.addAction(
                NotificationCompat.Action.Builder(
                    R.drawable.ic_notification_interrupt,
                    context.getString(R.string.action_interrupt),
                    PendingIntent.getBroadcast(
                        context,
                        codes.codeFor(AttentionAction.Interrupt(serverId, interruptSessionId)),
                        NotificationIntents.actionIntent(
                            context,
                            AttentionAction.Interrupt(serverId, interruptSessionId),
                        ),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    ),
                ).build(),
            )
        }
        return builder.build()
    }

    private companion object {
        /** The ongoing notification is a summary, not a list. */
        const val MAX_LISTED = 4
    }
}
