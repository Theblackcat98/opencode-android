package dev.opencode.android.feature.requests.notifications

import android.annotation.SuppressLint
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.opencode.android.core.data.attention.AttentionActionCodes
import dev.opencode.android.core.data.attention.AttentionNotice
import dev.opencode.android.core.data.attention.AttentionReconcile
import dev.opencode.android.core.data.attention.AttentionSink
import dev.opencode.android.core.data.attention.NotificationIds
import dev.opencode.android.core.data.attention.NotificationSlot
import dev.opencode.android.feature.requests.R
import dev.opencode.android.feature.requests.ui.messageRes
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one place that talks to `NotificationManager` (plan §6, Phase 4).
 *
 * **Everything above it is a pure function and a test; this is only the delivery.** The reconciler
 * decides what should be on screen, [AttentionNotificationBuilder] decides how each one reads, and
 * this class performs the diff: cancel what left the set, post what joined or changed, and keep one
 * summary per server.
 *
 * **The ids come from [NotificationIds], not from the draft.** Two notifications that shared an id
 * would replace each other, and a permission and a form on one session is the case that shows it.
 */
@Singleton
class AndroidAttentionSink @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ids: NotificationIds,
    private val builder: AttentionNotificationBuilder,
    private val badges: LauncherBadges,
) : AttentionSink {

    override fun setLauncherBadge(unreadSessions: Int) = badges.publish(unreadSessions)

    /**
     * Android 13 and later refuse every notification without `POST_NOTIFICATIONS`, so the post is
     * guarded by [canPostNotifications] first.
     *
     * The check is a real one, in the same file and one line above each post; lint's `MissingPermission`
     * analysis only recognises an inline `checkSelfPermission` or a `@RequiresPermission` annotation, and
     * propagating the requirement to every caller instead would push the problem up to the coordinator.
     */
    @SuppressLint("MissingPermission")
    override fun publish(reconcile: AttentionReconcile) {
        if (!canPostNotifications(context)) return
        val manager = NotificationManagerCompat.from(context)
        reconcile.removed.forEach { slot -> manager.cancel(ids.idFor(slot)) }
        (reconcile.added + reconcile.changed).forEach { draft ->
            manager.notify(ids.idFor(draft.slot), builder.build(draft))
        }
        reconcile.summaries.forEach { summary ->
            manager.notify(ids.idFor(summary.slot), builder.build(summary))
        }
    }

    /**
     * A message with no durable state behind it.
     *
     * **Posted on the channel of the thing that failed.** A user who silenced "retries and limits"
     * should not still be told about a failed approval, and a user who did not should not miss it.
     */
    @SuppressLint("MissingPermission")
    override fun announce(notice: AttentionNotice) {
        if (!canPostNotifications(context)) return
        when (notice) {
            is AttentionNotice.ActionFailed -> post(
                slot = NotificationSlot.Failure(notice.sessionId, notice.about),
                channel = AttentionChannelSpec.PERMISSION.id,
                priority = NotificationCompat.PRIORITY_HIGH,
                title = context.getString(R.string.notify_failed_title),
                body = context.getString(
                    R.string.notify_failed_body,
                    notice.about,
                    context.getString(notice.error.kind.messageRes(), notice.error.message.orEmpty()),
                ),
            )

            is AttentionNotice.TurnOutcome -> post(
                slot = NotificationSlot.Failure(notice.sessionId, "outcome"),
                channel = AttentionChannelSpec.TURN_FINISHED.id,
                priority = NotificationCompat.PRIORITY_DEFAULT,
                title = context.getString(R.string.notify_outcome_title),
                body = context.getString(R.string.notify_outcome_body),
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun post(slot: NotificationSlot, channel: String, priority: Int, title: String, body: String) {
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setPriority(priority)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .build()
        NotificationManagerCompat.from(context).notify(ids.idFor(slot), notification)
    }
}

/**
 * Where the platform bits the attention layer needs come from.
 *
 * Bound here rather than in the app so the whole notification surface is one module: the channels, the
 * builders, the sink and the components that use them.
 */
@Module
@InstallIn(SingletonComponent::class)
object AttentionModule {

    /** The launcher needs the presence signals and nothing else; see `PresenceSignalsSource`. */
    @Provides
    @Singleton
    fun providePresenceSignals(
        presence: dev.opencode.android.core.data.presence.PresenceController,
    ): dev.opencode.android.core.data.presence.PresenceSignalsSource = presence

    @Provides
    @Singleton
    fun provideActionCodes(): AttentionActionCodes = AttentionActionCodes()

    @Provides
    @Singleton
    fun provideNotificationIds(): NotificationIds = NotificationIds()

    @Provides
    @Singleton
    fun provideConnectionNotification(
        @ApplicationContext context: Context,
        codes: AttentionActionCodes,
    ): ConnectionNotificationBuilder = ConnectionNotificationBuilder(
        context = context,
        codes = codes,
        openSession = { serverId, sessionId ->
            NotificationIntents.openSession(context, serverId, sessionId, codes)
        },
    )

    @Provides
    @Singleton
    fun provideAttentionSink(
        @ApplicationContext context: Context,
        ids: NotificationIds,
        builder: AttentionNotificationBuilder,
        badges: LauncherBadges,
    ): AttentionSink = AndroidAttentionSink(context, ids, builder, badges)

    @Provides
    @Singleton
    fun provideLauncherBadges(
        @ApplicationContext context: Context,
    ): LauncherBadges = LauncherBadges(context)

    @Provides
    @Singleton
    fun provideNotificationBuilder(
        @ApplicationContext context: Context,
        codes: AttentionActionCodes,
        ids: NotificationIds,
    ): AttentionNotificationBuilder = AttentionNotificationBuilder(
        context = context,
        codes = codes,
        ids = ids,
        openSession = { serverId, sessionId ->
            NotificationIntents.openSession(context, serverId, sessionId, codes)
        },
    )
}
