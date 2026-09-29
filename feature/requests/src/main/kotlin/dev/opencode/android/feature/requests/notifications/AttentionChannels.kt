package dev.opencode.android.feature.requests.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.opencode.android.core.data.attention.AttentionChannel
import dev.opencode.android.core.data.attention.AttentionPriority
import dev.opencode.android.feature.requests.R

/**
 * Every channel this app creates, and the one mapping from the data layer's kinds to them.
 *
 * **Names and descriptions are user-visible, so they are strings** (plan §5.4). The channel ids are
 * fixed: the platform keeps a channel's settings until the user deletes it, so an id that changed
 * with a build would silently orphan the choice the user already made.
 */
enum class AttentionChannelSpec(
    val channel: AttentionChannel,
    val nameRes: Int,
    val descriptionRes: Int,
    val importance: Int,
) {
    PERMISSION(
        AttentionChannel.PERMISSION,
        R.string.channel_permission_name,
        R.string.channel_permission_description,
        NotificationManager.IMPORTANCE_HIGH,
    ),
    QUESTION(
        AttentionChannel.QUESTION,
        R.string.channel_question_name,
        R.string.channel_question_description,
        NotificationManager.IMPORTANCE_DEFAULT,
    ),
    TURN_FINISHED(
        AttentionChannel.TURN_FINISHED,
        R.string.channel_turn_name,
        R.string.channel_turn_description,
        NotificationManager.IMPORTANCE_DEFAULT,
    ),
    SUBAGENT_FINISHED(
        AttentionChannel.SUBAGENT_FINISHED,
        R.string.channel_subagent_name,
        R.string.channel_subagent_description,
        NotificationManager.IMPORTANCE_DEFAULT,
    ),
    SHELL_FINISHED(
        AttentionChannel.SHELL_FINISHED,
        R.string.channel_shell_name,
        R.string.channel_shell_description,
        NotificationManager.IMPORTANCE_DEFAULT,
    ),
    RETRY(
        AttentionChannel.RETRY,
        R.string.channel_retry_name,
        R.string.channel_retry_description,
        NotificationManager.IMPORTANCE_DEFAULT,
    ),

    /**
     * A login the user started finished, one way or another (Phase 8).
     *
     * **Its own channel, so it can be silenced on its own.** A user who silences their sessions'
     * turns and their subagents would otherwise also silence "the thing I started in a browser
     * finished", which is the one message they are actually waiting for.
     */
    AUTH_COMPLETED(
        AttentionChannel.AUTH_COMPLETED,
        R.string.channel_auth_name,
        R.string.channel_auth_description,
        NotificationManager.IMPORTANCE_DEFAULT,
    ),
    SERVER_UPDATE(
        AttentionChannel.SERVER_UPDATE,
        R.string.channel_update_name,
        R.string.channel_update_description,
        NotificationManager.IMPORTANCE_LOW,
    ),
    CONNECTION(
        AttentionChannel.CONNECTION,
        R.string.channel_connection_name,
        R.string.channel_connection_description,
        NotificationManager.IMPORTANCE_LOW,
    ),
    ;

    val id: String get() = channel.id

    /**
     * Whether this channel may buzz.
     *
     * Only a blocking request may: it is the one thing that has stopped the agent, so it is the one
     * thing worth a jolt. A turn finishing is worth a glance, and the user is often looking at the
     * screen it happened on.
     */
    val vibrates: Boolean get() = channel.priority == AttentionPriority.HIGH

    companion object {
        private val byKind = entries.associateBy(AttentionChannelSpec::channel)

        /** The spec of [channel]; a kind this build does not know falls back to the question one. */
        fun of(channel: AttentionChannel): AttentionChannelSpec =
            byKind[channel] ?: QUESTION
    }
}

/**
 * Whether this app may post a notification at all.
 *
 * **Android 13 made it a runtime permission**, and `NotificationManager.notify` is annotated to require
 * it. Posting without the check is a `SecurityException` at exactly the moment the app is trying to tell
 * the user something, so the check lives next to the post rather than in the screen that asked for the
 * permission.
 */
fun canPostNotifications(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    } else {
        true
    }

/**
 * Creates the channels. Idempotent, and called from the application *and* from the receiver.
 *
 * The second call is not belt and braces: a `BroadcastReceiver` can start a process that was not
 * running, and the first thing that process does with a notification is post it. A notification on a
 * channel that does not exist is silently dropped, so the receiver has to be able to create them.
 *
 * `createNotificationChannel` is itself idempotent and only ever applies a *lower* importance, never
 * a higher one: the user's own choice in the system settings wins over anything this build says, and
 * that is the behaviour to keep.
 */
fun installAttentionChannels(context: Context) {
    val manager = NotificationManagerCompat.from(context)
    AttentionChannelSpec.entries.forEach { spec ->
        val built = NotificationChannel(spec.id, context.getString(spec.nameRes), spec.importance).apply {
            description = context.getString(spec.descriptionRes)
            enableVibration(spec.vibrates)
            enableLights(false)
            setShowBadge(true)
        }
        manager.createNotificationChannel(built)
    }
}
