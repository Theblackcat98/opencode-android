package dev.opencode.android.feature.requests.notifications

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.AndroidEntryPoint
import dev.opencode.android.core.data.attention.AttentionCoordinator
import dev.opencode.android.core.data.attention.AttentionPreferences
import dev.opencode.android.core.data.attention.AttentionSettings
import dev.opencode.android.core.data.connection.ServerConnectionManager
import dev.opencode.android.core.data.presence.PresenceController
import dev.opencode.android.core.data.presence.PresenceDecision
import dev.opencode.android.core.data.presence.PresencePolicy
import dev.opencode.android.core.data.presence.PresenceReason
import dev.opencode.android.core.data.presence.PresenceSignals
import dev.opencode.android.core.data.presence.StartExemption
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The foreground service that keeps the connection alive while the agent needs the user
 * (plan §6, Phase 4).
 *
 * **It decides nothing.** [PresencePolicy] decides whether it should be running and the ongoing
 * notification says why; this class is the platform shell that carries the answer out. That split is
 * the only reason any of the phase's exit criteria can be checked without a device: "no service runs
 * while nothing is active" is a property of the policy, and this is what happens when the policy says
 * stop — the notification goes, the service stops, and the socket goes with it.
 *
 * **The type is `dataSync`.** The plan proposed `connectedDevice` first and left the choice to a
 * spike; the finding is recorded in the plan's Phase 4 status. In short: `connectedDevice` requires,
 * at runtime, one of a list of Bluetooth, USB, NFC, IR or network-state-change permissions, and the
 * only one this app could honestly declare is `CHANGE_NETWORK_STATE` for a capability it does not
 * have — the app observes the network, it never changes it. `dataSync` describes what the service
 * does (fetching the server's state over the network), needs no extra permission, and its one real
 * cost, Android 15's six-hours-per-day cap, is a limit this design stays far below because the
 * service stops within minutes of nothing happening.
 */
@AndroidEntryPoint
class ConnectionService : Service(), DefaultLifecycleObserver {

    @Inject
    lateinit var presence: PresenceController

    @Inject
    lateinit var connections: ServerConnectionManager

    @Inject
    lateinit var preferences: AttentionPreferences

    @Inject
    lateinit var attention: AttentionCoordinator

    @Inject
    lateinit var launcher: ConnectionServiceLauncher

    @Inject
    lateinit var notification: ConnectionNotificationBuilder

    @Inject
    lateinit var codes: dev.opencode.android.core.data.attention.AttentionActionCodes

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var watcher: Job? = null
    private var graceTimer: Job? = null

    /** Whether the service has been promoted to the foreground, which is what "running" means. */
    private var foreground = false

    /** The app's own lifecycle, which decides whether a start is permitted right now. */
    private var appForeground = false

    /** When nothing was active, which is what the grace period is measured from. */
    private var idleSince: Long? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super<Service>.onCreate()
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        installAttentionChannels(this)
        // The attention layer has to run in this process too: a receiver can start the process on its
        // own, and the notifications the service itself does not own have to be there too.
        attention.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startWatching()
        // Not sticky: a service the system restarts with no knowledge of what was running would
        // reconnect to a server the user may have left. The next foreground pass starts it again if
        // there is still work, which is the plan's "runs while any session is busy" rather than "runs
        // until the process dies".
        return START_NOT_STICKY
    }

    override fun onStart(owner: LifecycleOwner) {
        appForeground = true
    }

    override fun onStop(owner: LifecycleOwner) {
        appForeground = false
    }

    override fun onDestroy() {
        watcher?.cancel()
        graceTimer?.cancel()
        watcher = null
        ProcessLifecycleOwner.get().lifecycle.removeObserver(this)
        demote()
        scope.cancel()
        launcher.serviceStopped()
        super<Service>.onDestroy()
    }

    /**
     * Android 15 caps a `dataSync` foreground service at six hours in any 24-hour period and calls
     * [Service.onTimeout] when the cap is reached.
     *
     * The service is meant to stop within minutes of nothing happening, so reaching the cap means
     * something kept it alive that should not have. Stopping is the honest response: the connection
     * comes back when the app comes forward, or on the next start the platform permits.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        demote()
        stopSelf()
    }

    private fun startWatching() {
        if (watcher != null) return
        watcher = scope.launch {
            presence.signals.collect { onSignals(it) }
        }
    }

    private suspend fun onSignals(signals: PresenceSignals) {
        val settings = preferences.settings.first()
        // Anything active means the clock restarts; otherwise the grace period starts now if it has
        // not already. Holding this in the service rather than in the policy is what lets the
        // policy stay a pure function of its inputs.
        idleSince = if (signals.hasWork || settings.alwaysConnectedFor(signals.serverId)) {
            null
        } else {
            idleSince ?: System.currentTimeMillis()
        }
        val autoApprove = settings.autoApproveActive(
            signals.running.firstOrNull()?.id,
            System.currentTimeMillis(),
        )
        when (
            val decision = PresencePolicy.decide(
                signals.toInputs(
                    serviceRunning = foreground,
                    idleSinceMillis = idleSince,
                    nowMillis = System.currentTimeMillis(),
                    startExemption = startExemption(),
                ),
            )
        ) {
            is PresenceDecision.Start -> promote(signals, settings, autoApprove)
            is PresenceDecision.Stop -> stop()
            is PresenceDecision.Keep -> keep(decision.reason, signals, settings, autoApprove)
        }
    }

    private fun promote(signals: PresenceSignals, settings: AttentionSettings, autoApprove: Boolean) {
        val words = notification.wordsFor(
            running = signals.running,
            pending = signals.pendingRequests,
            alwaysConnected = settings.alwaysConnectedFor(signals.serverId),
            autoApproveUntilMillis = if (autoApprove) System.currentTimeMillis() else null,
        )
        ServiceCompat.startForeground(
            this,
            ONGOING_NOTIFICATION_ID,
            notification.build(
                words = words,
                serverId = signals.serverId.orEmpty(),
                // A subagent is interruptible too, but the parent is the one the user is likely to
                // mean, so the button names the session they opened.
                interruptSessionId = signals.running.firstOrNull { !it.isSubagent }?.id
                    ?: signals.running.firstOrNull()?.id,
                contentIntent = contentIntent(signals),
            ),
            foregroundServiceType(),
        )
        foreground = true
        // The service is what keeps the stream up while the app is in the background; the connection
        // manager's own lifecycle observer cannot, because it only knows about the app.
        if (!appForeground) connections.activeConnection.value?.setForeground(true)
        graceTimer?.cancel()
    }

    private fun keep(
        reason: PresenceReason,
        signals: PresenceSignals,
        settings: AttentionSettings,
        autoApprove: Boolean,
    ) {
        if (foreground) updateOngoing(signals, settings, autoApprove)
        when (reason) {
            PresenceReason.IDLE_GRACE -> scheduleGraceCheck()

            // Nothing to do, and nothing to log: the work is waiting and the platform will not let a
            // socket event start a service. The next permitted start picks it up.
            PresenceReason.BACKGROUND_START_REFUSED -> Unit

            PresenceReason.NO_SERVER -> Unit

            else -> graceTimer?.cancel()
        }
    }

    private fun stop() {
        graceTimer?.cancel()
        if (foreground) demote()
        // Both halves matter: the service going away and the socket going with it are what "no service
        // runs while nothing is active" means to a battery meter. The exception is a foregrounded app,
        // which is showing a live timeline and needs the stream whether or not a service is up — the
        // connection manager keeps it running there.
        if (!appForeground) connections.activeConnection.value?.setForeground(false)
        idleSince = null
        stopSelf()
    }

    private fun demote() {
        if (!foreground) return
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        foreground = false
    }

    /**
     * Re-posts the ongoing notification in place.
     *
     * Updating an existing notification needs the same `POST_NOTIFICATIONS` as creating one, so the post
     * is guarded the same way; without the permission the service still runs and the shade simply has
     * nothing to show, which is the platform's documented behaviour rather than a failure.
     */
    @SuppressLint("MissingPermission")
    private fun updateOngoing(signals: PresenceSignals, settings: AttentionSettings, autoApprove: Boolean) {
        if (!canPostNotifications(this)) return
        val manager = androidx.core.app.NotificationManagerCompat.from(this)
        manager.notify(
            ONGOING_NOTIFICATION_ID,
            notification.build(
                words = notification.wordsFor(
                    running = signals.running,
                    pending = signals.pendingRequests,
                    alwaysConnected = settings.alwaysConnectedFor(signals.serverId),
                    autoApproveUntilMillis = if (autoApprove) System.currentTimeMillis() else null,
                ),
                serverId = signals.serverId.orEmpty(),
                interruptSessionId = signals.running.firstOrNull { !it.isSubagent }?.id
                    ?: signals.running.firstOrNull()?.id,
                contentIntent = contentIntent(signals),
            ),
        )
    }

    /** The grace period needs a clock, because the policy is only re-read when something changes. */
    private fun scheduleGraceCheck() {
        if (graceTimer?.isActive == true) return
        graceTimer = scope.launch {
            delay(PresencePolicy.DEFAULT_IDLE_GRACE_MILLIS)
            onSignals(presence.signals.value)
        }
    }

    /**
     * The ongoing notification's body.
     *
     * Built with the *injected* codes, so "open this session" is the same `PendingIntent` here as it is
     * on any notification's Open button. Two allocators would make two intents for one action, and the
     * second would be a stale one.
     */
    private fun contentIntent(signals: PresenceSignals): PendingIntent {
        val serverId = signals.serverId.orEmpty()
        val sessionId = signals.running.firstOrNull()?.id.orEmpty()
        return NotificationIntents.openSession(this, serverId, sessionId, codes)
    }

    /**
     * Whether, and under which exemption, the platform would allow a start right now.
     *
     * Battery optimisation being off is the third documented exemption, and it is the one the plan's
     * battery guidance asks the user for, so it is read here rather than being left to chance.
     */
    private fun startExemption(): StartExemption = when {
        appForeground -> StartExemption.APP_IN_FOREGROUND
        batteryOptimisationDisabled() -> StartExemption.BATTERY_OPTIMISATION_DISABLED
        else -> StartExemption.NONE
    }

    private fun batteryOptimisationDisabled(): Boolean {
        val power = getSystemService(PowerManager::class.java) ?: return false
        return power.isIgnoringBatteryOptimizations(packageName)
    }

    /**
     * `dataSync`, and nothing before API 29.
     *
     * The type did not exist before API 29, and passing one there is an `IllegalArgumentException`.
     */
    private fun foregroundServiceType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }

    companion object {
        const val ACTION_START: String = "dev.opencode.android.action.START_CONNECTION_SERVICE"

        /**
         * The ongoing notification's id.
         *
         * A constant rather than an allocated one, because it is one notification that is updated in
         * place; a fresh id on every change would leave a row of stale ones in the shade.
         */
        const val ONGOING_NOTIFICATION_ID: Int = 1
    }
}
