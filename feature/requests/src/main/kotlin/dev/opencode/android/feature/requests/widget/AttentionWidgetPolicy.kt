package dev.opencode.android.feature.requests.widget

import dev.opencode.android.core.data.presence.PresenceSignals

/**
 * The two numbers the home-screen widget shows: sessions running and requests waiting for the user (matrix J8).
 *
 * **They are the numbers the connection notification shows,** read from the same presence signals, so the
 * widget and the shade cannot disagree about what is running or what is waiting.
 */
data class WidgetCounts(
    val running: Int,
    val waiting: Int,
)

/** Which label a counted widget line carries. The renderer maps each one to its string resource. */
enum class WidgetLabel {
    WAITING_FOR_YOU,
    RUNNING,
}

/** One counted line of the widget: [count] shown in front of [label]. */
data class WidgetLine(
    val label: WidgetLabel,
    val count: Int,
)

/**
 * What the widget shows, decided before any platform call is made.
 *
 * - [OpenApp] has no counts behind it: there is no active server, or its stream is not live. The widget asks
 *   the user to open the app rather than showing a number it cannot stand behind.
 * - [NothingActive] is the zero state: the counts are known and both are zero.
 * - [Counts] lists its lines in display order, waiting first, because that is the line the user acts on.
 *   A zero count beside a non-zero one still shows its number, so the two lines are always both present.
 */
sealed interface WidgetRendering {
    data object OpenApp : WidgetRendering

    data object NothingActive : WidgetRendering

    data class Counts(
        val lines: List<WidgetLine>,
    ) : WidgetRendering
}

/**
 * The widget's face as a pure function of what the app knows.
 *
 * **Pure, so it is tested without a device or a launcher.** The provider only maps the result onto
 * `RemoteViews`, so the rules that matter are stated once here and asserted by `AttentionWidgetPolicyTest`:
 * no count is shown without a stream that is live, the zero state is both counts being zero, and waiting
 * comes first.
 */
object AttentionWidgetPolicy {

    /**
     * The counts for the widget, or `null` when they cannot be trusted.
     *
     * A count from a server whose stream is down describes what was in memory when it dropped, not what is
     * running now, so it is withheld rather than shown as current.
     */
    fun countsOf(signals: PresenceSignals, streamLive: Boolean): WidgetCounts? {
        if (!signals.hasServer || !streamLive) return null
        return WidgetCounts(running = signals.running.size, waiting = signals.pendingRequests)
    }

    fun render(counts: WidgetCounts?): WidgetRendering = when {
        counts == null -> WidgetRendering.OpenApp

        counts.running == 0 && counts.waiting == 0 -> WidgetRendering.NothingActive

        else -> WidgetRendering.Counts(
            lines = listOf(
                WidgetLine(WidgetLabel.WAITING_FOR_YOU, counts.waiting),
                WidgetLine(WidgetLabel.RUNNING, counts.running),
            ),
        )
    }
}
