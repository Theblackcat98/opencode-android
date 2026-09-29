package dev.opencode.android.core.data.attention

/**
 * The notification channels, one per kind of attention event (plan §6, Phase 4).
 *
 * **Mirrors the TUI's attention events** (features doc §33.4, §38): a permission is the loudest
 * because the agent is stopped, a finished turn is a default notification, and the connection's own
 * ongoing notification is low so it never heads up over a real one.
 *
 * The ids are stable strings because the platform keeps a channel's settings until the user deletes
 * it or the app is uninstalled: changing one silently orphans the user's own choice for the old id.
 * The *names* and the importance are presentation, so they live with the renderer
 * (`feature:requests`) and not here — this module has no strings and no Android types.
 */
enum class AttentionChannel(
    val id: String,
    val priority: AttentionPriority,
    /** Whether a notification on this channel carries a group. */
    val groupable: Boolean = true,
) {
    /** A permission request. High importance: the agent cannot continue until this is answered. */
    PERMISSION("attention.permission", AttentionPriority.HIGH),

    /** A question, a consent or an elicitation. Default, because the same is true of it. */
    QUESTION("attention.question", AttentionPriority.DEFAULT),

    /** A turn finished, one way or another. */
    TURN_FINISHED("attention.turn", AttentionPriority.DEFAULT),

    /** A subagent finished. Its own channel so it can be silenced separately. */
    SUBAGENT_FINISHED("attention.subagent", AttentionPriority.DEFAULT),

    /**
     * A shell command finished.
     *
     * **Its own channel, and not the turn channel.** A build the user started from the phone finishing
     * is the thing they are waiting for, and they may well have muted that session's turns because
     * they do not want a notification every time the agent finishes a sentence. A long build can also
     * be a terminal the user is watching, which is why [NotificationSlot.ShellFinished] is about the
     * command and not about a session.
     */
    SHELL_FINISHED("attention.shell", AttentionPriority.DEFAULT),

    /** A provider retry is scheduled, or a usage limit was hit. */
    RETRY("attention.retry", AttentionPriority.DEFAULT),

    /** The server says a newer version is available (`installation.update-available`). */
    SERVER_UPDATE("attention.update", AttentionPriority.LOW),

    /** The connection service's own ongoing notification. */
    CONNECTION("attention.connection", AttentionPriority.LOW, groupable = false),
    ;

    /**
     * Whether a notification on this channel is a *blocking* request.
     *
     * Quiet hours suppress information, never a request the agent is waiting on: the request will
     * still be there in the morning, and the work will still not be done, which is the worse outcome
     * of the two.
     */
    val blocking: Boolean get() = this == PERMISSION || this == QUESTION
}

/** How insistent a channel is, without naming an Android constant. */
enum class AttentionPriority {
    LOW,
    DEFAULT,
    HIGH,
}

/**
 * The channels in the order the settings screen lists them.
 *
 * The connection channel is not listed: it is a by-product of the service running, not something a
 * user chooses.
 */
val userFacingChannels: List<AttentionChannel> =
    AttentionChannel.entries.filterNot { it == AttentionChannel.CONNECTION }
