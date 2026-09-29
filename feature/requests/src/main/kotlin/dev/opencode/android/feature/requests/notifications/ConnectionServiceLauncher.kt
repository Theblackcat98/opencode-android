package dev.opencode.android.feature.requests.notifications

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.opencode.android.core.data.presence.PresenceDecision
import dev.opencode.android.core.data.presence.PresencePolicy
import dev.opencode.android.core.data.presence.PresenceSignals
import dev.opencode.android.core.data.presence.PresenceSignalsSource
import dev.opencode.android.core.data.presence.StartExemption
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Asks the platform for the connection service when there is a reason to (plan §6, Phase 4).
 *
 * **The start is the part the platform restricts, and the restriction is why this is separate from
 * the service.** Android 12+ refuses a `startForegroundService` from the background outside a
 * documented exemption list, and "a permission arrived over a socket" is not on it. So the request
 * comes from a place that is legitimately foregrounded — the app being open — or from a notification
 * action, which is the other exemption the app can reach. The *decision* is still [PresencePolicy]'s,
 * with `serviceRunning = false`, so "start only while a session is busy, a request is pending, or
 * always connected" is one rule rather than two.
 *
 * **It asks once.** The platform gives no answer saying the service is up, so the launcher keeps a
 * flag and releases it when the service reports that it has gone; without that, every emission of the
 * signals would call `startForegroundService` again.
 */
@Singleton
class ConnectionServiceLauncher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val presence: PresenceSignalsSource,
) : DefaultLifecycleObserver {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val appForeground = MutableStateFlow(false)

    /** An exemption a caller offered that the app's own state would not justify. */
    private val callerExemption = MutableStateFlow(StartExemption.NONE)

    private val _requested = MutableStateFlow(false)

    /** Whether a start has been asked for and not yet released. */
    val requested: StateFlow<Boolean> = _requested.asStateFlow()

    private val _starts = MutableStateFlow(0)

    /** How many times a start has been asked for. A test asserts on this, not on a service. */
    val starts: StateFlow<Int> = _starts.asStateFlow()

    private var watcher: Job? = null

    /** Starts watching. Idempotent; the application calls it once. */
    @Synchronized
    fun start() {
        if (watcher != null) return
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        watcher = scope.launch {
            combine(
                presence.signals,
                appForeground,
                callerExemption,
                _requested,
            ) { signals, foreground, exemption, requested ->
                Evaluation(signals, foreground, exemption, requested)
            }.collect(::evaluate)
        }
    }

    private data class Evaluation(
        val signals: PresenceSignals,
        val appForeground: Boolean,
        val offered: StartExemption,
        val alreadyRequested: Boolean,
    )

    private fun evaluate(evaluation: Evaluation) {
        if (evaluation.alreadyRequested) return
        // Being foregrounded is the exemption that needs nothing else; an offered one only counts
        // while the app is in the background, which is the case it exists for.
        val exemption = if (evaluation.appForeground) {
            StartExemption.APP_IN_FOREGROUND
        } else {
            evaluation.offered
        }
        val decision = PresencePolicy.decide(
            evaluation.signals.toInputs(
                serviceRunning = false,
                idleSinceMillis = null,
                nowMillis = System.currentTimeMillis(),
                startExemption = exemption,
            ),
        )
        if (decision is PresenceDecision.Start) ask()
    }

    override fun onStart(owner: LifecycleOwner) {
        appForeground.value = true
    }

    override fun onStop(owner: LifecycleOwner) {
        appForeground.value = false
        // An offered exemption is spent on the start it was given for; keeping it would let one
        // notification tap hold the service up for as long as there was work.
        callerExemption.value = StartExemption.NONE
    }

    /**
     * Offers a start that the app's own state would not justify.
     *
     * Used by the notification-action path, where the user's tap is itself a documented exemption.
     */
    fun offer(exemption: StartExemption) {
        callerExemption.value = exemption
        evaluate(Evaluation(presence.signals.value, appForeground.value, exemption, _requested.value))
    }

    /** Called by the service when it stops, so the next piece of work can start it again. */
    fun serviceStopped() {
        _requested.value = false
    }

    private fun ask() {
        _requested.value = true
        _starts.value += 1
        runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ConnectionService::class.java).setAction(ConnectionService.ACTION_START),
            )
        }.onFailure {
            // The platform refused it after all, which is a possibility rather than an impossibility:
            // a background start with no exemption throws. The flag is released so the next
            // legitimate opportunity can try again.
            _requested.value = false
        }
    }
}
