package dev.opencode.android.feature.requests.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.opencode.android.core.data.connection.ServerConnectionManager
import dev.opencode.android.core.data.presence.PresenceSignalsSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Redraws the home-screen widget whenever its counts change (matrix J8).
 *
 * **The hook is the presence signals, not the attention ledger.** The ledger knows only the unread count, and it runs
 * only while the coordinator does. The presence signals carry the running sessions and the waiting requests the
 * connection notification already shows, from the same store, so the widget reads the number the shade reads.
 *
 * **Counts are pushed only while the stream is live.** [AttentionWidgetPolicy.countsOf] withholds them otherwise, so a
 * process that starts with no stream draws "open the app" rather than a zero it cannot vouch for.
 *
 * The app starts this once, from [dev.opencode.android.OpenCodeApplication]. A widget that is not placed costs nothing:
 * the redraw asks the manager for placed ids and stops when there are none.
 */
@Singleton
class AttentionWidgetUpdater @Inject constructor(
    @ApplicationContext private val context: Context,
    private val presence: PresenceSignalsSource,
    private val connections: ServerConnectionManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _counts = MutableStateFlow<WidgetCounts?>(null)

    /** The counts last drawn, or `null` while they are not known. The provider draws from this on a system update. */
    val counts: StateFlow<WidgetCounts?> = _counts.asStateFlow()

    private var job: Job? = null

    /** Starts pushing. Idempotent, because the application calls it once and a second call would double every redraw. */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Synchronized
    fun start() {
        if (job != null) return
        val streamLive = connections.activeConnection.flatMapLatest { connection ->
            connection?.isLive ?: flowOf(false)
        }
        job = scope.launch {
            combine(presence.signals, streamLive) { signals, live ->
                AttentionWidgetPolicy.countsOf(signals, live)
            }
                .distinctUntilChanged()
                .collect { counts ->
                    _counts.value = counts
                    redraw(counts)
                }
        }
    }

    private fun redraw(counts: WidgetCounts?) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, AttentionWidgetProvider::class.java))
        if (ids.isNotEmpty()) manager.updateAppWidget(ids, attentionWidgetViews(context, counts))
    }
}
