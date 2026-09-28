package dev.opencode.android.feature.requests.notifications

import android.content.Context
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import dev.opencode.android.feature.requests.R

/**
 * The one dynamic shortcut the app publishes, carrying the unread count (plan §6, "Unread model").
 *
 * **A shortcut is needed at all before a badge can mean anything.** "Unread badges on launcher
 * shortcuts" is a claim about a shortcut this app publishes, so this publishes one and keeps its
 * label current.
 *
 * **The count is in the label, not on the icon.** `ShortcutManager.setBadges` exists but is
 * `@SystemApi`, and there is no `ShortcutManagerCompat` equivalent, so a third-party app cannot put a
 * dot with a number on its own icon through any public API. The label is the part every launcher
 * shows, so `"3 unread"` there is the achievable half of the plan's requirement; the other half needs
 * either a system launcher or a platform that publishes the API. See the plan's Phase 4 deviations.
 *
 * **The count is unread sessions, not pending requests.** A request is not unread: it has its own
 * notification, its own badge in the session list and its own inbox. The launcher answers "did I miss
 * a turn", which is the one question an icon can usefully answer.
 */
class LauncherBadges(private val context: Context) {

    /**
     * Republishes the shortcut with [unread] sessions on it, or removes it when there are none.
     *
     * **Every platform call is guarded, because launchers vary.** Not every home screen supports
     * dynamic shortcuts, and a launcher that does not is a supported device; the failure mode has to
     * be "no shortcut", not a crash on the way to a notification.
     */
    fun publish(unread: Int) {
        runCatching {
            if (unread <= 0) {
                ShortcutManagerCompat.removeDynamicShortcuts(context, listOf(SHORTCUT_ID))
                return@runCatching
            }
            ShortcutManagerCompat.removeDynamicShortcuts(context, listOf(SHORTCUT_ID))
            val shortcut = ShortcutInfoCompat.Builder(context, SHORTCUT_ID)
                .setShortLabel(
                    context.resources.getQuantityString(R.plurals.launcher_unread_label, unread, unread),
                )
                .setLongLabel(context.getString(R.string.launcher_unread_long_label))
                .setIcon(IconCompat.createWithResource(context, R.drawable.ic_notification))
                .setIntent(NotificationIntents.launchIntent(context))
                .build()
            ShortcutManagerCompat.addDynamicShortcuts(context, listOf(shortcut))
        }
    }

    private companion object {
        /**
         * A fixed id, because this is one shortcut whose label is updated and a new id every pass
         * would leave a trail of them in the launcher's own list.
         */
        const val SHORTCUT_ID = "dev.opencode.android.unread"
    }
}
