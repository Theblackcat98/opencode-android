package dev.opencode.android.core.data.attention

/**
 * The `NotificationManager` id of one notification.
 *
 * **The same uniqueness argument as the request codes, for the same reason.** Two notifications that
 * share an id are the same notification: the second replaces the first, so a permission and a form on
 * the same session would silently cancel one another. Each distinct slot is therefore given a fresh
 * counter value and the assignment is remembered, which makes a collision impossible rather than
 * unlikely.
 *
 * A counter rather than a digest is safe for the same reason [AttentionActionCodes] gives: an id only
 * has to outlive the process, because a notification is re-posted with a fresh id on the next pass
 * and the shade is re-read from the coordinator rather than restored across a reboot.
 */
class NotificationIds {
    private val ids = HashMap<NotificationSlot, Int>()
    private var next = 1

    /** The notification id of [slot], stable across calls. */
    @Synchronized
    fun idFor(slot: NotificationSlot): Int = ids.getOrPut(slot) { next++ }

    /** The number of distinct slots seen, which is what a test uses to prove uniqueness. */
    @get:Synchronized
    val size: Int get() = ids.size
}
