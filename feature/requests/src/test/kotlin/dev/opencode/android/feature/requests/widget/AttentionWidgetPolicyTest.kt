package dev.opencode.android.feature.requests.widget

import dev.opencode.android.core.data.presence.PresenceSignals
import dev.opencode.android.core.data.presence.RunningSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What the home-screen widget shows (matrix J8).
 *
 * **A plain function, so it runs without a launcher.** The provider only maps the result onto `RemoteViews`, so these
 * assertions are the whole of what a user reads: the counts are the connection notification's counts, no count is shown
 * without a live stream, the zero state is both counts being zero, and waiting is always the first line.
 */
class AttentionWidgetPolicyTest {

    private fun running(id: String) = RunningSession(id = id, title = "Session $id", isSubagent = false)

    private fun signals(
        serverId: String? = "server",
        running: List<RunningSession> = emptyList(),
        pendingPermissions: Int = 0,
        pendingForms: Int = 0,
    ) = PresenceSignals(
        serverId = serverId,
        running = running,
        pendingPermissions = pendingPermissions,
        pendingForms = pendingForms,
    )

    @Test
    fun `the counts are the sessions running and the requests waiting, counted as the notification counts them`() {
        val counts = AttentionWidgetPolicy.countsOf(
            signals(running = listOf(running("a"), running("b")), pendingPermissions = 2, pendingForms = 1),
            streamLive = true,
        )
        assertEquals(WidgetCounts(running = 2, waiting = 3), counts)
    }

    @Test
    fun `no active server means no counts`() {
        assertNull(AttentionWidgetPolicy.countsOf(signals(serverId = null), streamLive = true))
    }

    @Test
    fun `a stream that is not live means no counts, even with work in the signals`() {
        // The stores hold what was in memory when the stream dropped. Showing it would show a count nothing can
        // refresh, so it is withheld rather than presented as current.
        val stale = signals(running = listOf(running("a")), pendingPermissions = 1)
        assertNull(AttentionWidgetPolicy.countsOf(stale, streamLive = false))
    }

    @Test
    fun `no counts render as open the app, and not as a zero`() {
        assertEquals(WidgetRendering.OpenApp, AttentionWidgetPolicy.render(null))
    }

    @Test
    fun `both counts at zero is the zero state, with no count lines`() {
        assertEquals(WidgetRendering.NothingActive, AttentionWidgetPolicy.render(WidgetCounts(running = 0, waiting = 0)))
    }

    @Test
    fun `waiting is listed first, then running`() {
        val rendering = AttentionWidgetPolicy.render(WidgetCounts(running = 4, waiting = 1))
        assertEquals(
            WidgetRendering.Counts(
                listOf(WidgetLine(WidgetLabel.WAITING_FOR_YOU, 1), WidgetLine(WidgetLabel.RUNNING, 4)),
            ),
            rendering,
        )
    }

    @Test
    fun `a zero beside a non-zero count is still shown as a number`() {
        // A widget that dropped the zero line would make "nothing is waiting" look like a missing value.
        assertEquals(
            WidgetRendering.Counts(
                listOf(WidgetLine(WidgetLabel.WAITING_FOR_YOU, 0), WidgetLine(WidgetLabel.RUNNING, 2)),
            ),
            AttentionWidgetPolicy.render(WidgetCounts(running = 2, waiting = 0)),
        )
        assertEquals(
            WidgetRendering.Counts(
                listOf(WidgetLine(WidgetLabel.WAITING_FOR_YOU, 3), WidgetLine(WidgetLabel.RUNNING, 0)),
            ),
            AttentionWidgetPolicy.render(WidgetCounts(running = 0, waiting = 3)),
        )
    }
}
