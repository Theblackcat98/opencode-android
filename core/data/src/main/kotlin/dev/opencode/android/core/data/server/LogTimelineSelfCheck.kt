package dev.opencode.android.core.data.server

import android.util.Log
import dev.opencode.android.core.data.timeline.TimelineDivergence

/**
 * Sends the self-check's findings to logcat.
 *
 * Only active in a debug build, which [DebugFlags.selfCheck] decides. A release build still runs
 * the comparison, because the cost is one REST read per finished turn and the value is a bug report
 * a developer can act on; it simply does not log.
 */
class LogTimelineSelfCheck(
    private val enabled: Boolean,
) : TimelineSelfCheck {
    override fun report(sessionID: String, divergences: List<TimelineDivergence>) {
        if (!enabled) return
        Log.w(
            TAG,
            "timeline divergence in $sessionID (${divergences.size}):\n" +
                divergences.joinToString("\n") { "  $it" },
        )
    }

    private companion object {
        const val TAG = "OpenCodeTimeline"
    }
}

/**
 * Debug-only behaviour switches.
 *
 * The app sets these from `BuildConfig.DEBUG`, so a release build compiles the self-check's logging
 * out of reach without a second code path through the stores.
 */
object DebugFlags {
    /** Whether the timeline self-check logs its findings. Off in release. */
    @Volatile
    var selfCheckLogs: Boolean = false
}
