package dev.opencode.android.core.data.presence

/**
 * Why the connection service is in the state the policy put it in.
 *
 * The reasons are values, not strings, because the ongoing notification, the event log and the tests
 * all name them and a test should assert on the class rather than on English.
 */
enum class PresenceReason {
    /** A session has a live execution. */
    SESSIONS_RUNNING,

    /** Something is blocked on the user: a permission or a form. */
    REQUEST_PENDING,

    /** The user asked for the connection to stay up. */
    ALWAYS_CONNECTED,

    /** Something became active while the app was in the background and no exemption applies, so
     * starting the service would throw `ForegroundServiceStartNotAllowedException`.
     *
     * The policy still reports what *should* be running, so the ongoing notification and the
     * event log show the truth; it just does not ask the system for something it will refuse.
     */
    BACKGROUND_START_REFUSED,

    /** The user turned battery optimisation off, which is a documented background-start exemption. */
    BATTERY_EXEMPT,

    /** A notification action brought the app forward, which is the other documented exemption. */
    NOTIFICATION_ACTION,

    /** The user is foregrounded, so no exemption is needed at all. */
    APP_IN_FOREGROUND,

    /** The idle grace period still has time left. */
    IDLE_GRACE,

    /** The idle grace period elapsed with nothing active. */
    IDLE_GRACE_ELAPSED,

    /** There is no server, or nothing to be connected to. */
    NO_SERVER,
}

/**
 * What the service should do about the connection right now.
 *
 * A [Start] is a *request* to the platform, not a promise: [PresenceInputs.startExemption] is what
 * says whether the system will grant it, and a start the platform would refuse is reported as a
 * [Keep] carrying [PresenceReason.BACKGROUND_START_REFUSED] rather than as a crash.
 */
sealed interface PresenceDecision {

    val reason: PresenceReason

    /** Promote the service to the foreground. */
    data class Start(override val reason: PresenceReason) : PresenceDecision

    /** Leave the service exactly as it is. */
    data class Keep(override val reason: PresenceReason) : PresenceDecision

    /**
     * Stop the service.
     *
     * The connection itself is stopped too, which is the point of the phase's fourth exit
     * criterion: nothing is active, so nothing is running.
     */
    data class Stop(override val reason: PresenceReason) : PresenceDecision

    companion object {
        val Idle: PresenceDecision = Keep(PresenceReason.NO_SERVER)
    }
}

/**
 * Whether the platform would grant a `startForegroundService` call right now.
 *
 * Android 12+ forbids it while the app is in the background, outside a documented list of
 * exemptions. Two of them are reachable from here and both are real: the user acting on one of the
 * app's own notifications, and the user having turned battery optimisation off. "An event arrived
 * over a socket" is **not** on the list, which is the whole reason this is a value rather than a
 * boolean — the caller has to say *which* exemption it is relying on, so the event log can name it.
 */
enum class StartExemption {
    /** The app is foregrounded, so no exemption is needed. */
    APP_IN_FOREGROUND,

    /** The user tapped a notification, a widget or a bubble belonging to this app. */
    NOTIFICATION_ACTION,

    /** The user turned battery optimisation off for this app. */
    BATTERY_OPTIMISATION_DISABLED,

    /** Nothing permits it. A start now would throw `ForegroundServiceStartNotAllowedException`. */
    NONE,
}

/**
 * Everything the decision depends on, read once per change.
 *
 * A record rather than a bag of parameters because every field of it is a question the service has
 * to answer anyway, and because a test builds one in a single expression.
 */
data class PresenceInputs(
    /** A server is being followed. Without one there is nothing to stay connected to. */
    val hasServer: Boolean = false,
    /** The sessions with a live execution. Only the count is needed to decide. */
    val runningSessions: Int = 0,
    /** Permissions and forms waiting for an answer. */
    val pendingRequests: Int = 0,
    /** The user turned "always connected" on for this server. */
    val alwaysConnected: Boolean = false,
    /** Whether the service is up right now. */
    val serviceRunning: Boolean = false,
    /** How long the service waits after the last active thing before it stops. */
    val idleGraceMillis: Long = PresencePolicy.DEFAULT_IDLE_GRACE_MILLIS,
    /** When nothing was active, or `null` while something is. */
    val idleSinceMillis: Long? = null,
    /** The clock the grace period is measured against. */
    val nowMillis: Long = 0L,
    /** Whether, and under which exemption, the platform would allow the start. */
    val startExemption: StartExemption = StartExemption.NONE,
) {
    val hasWork: Boolean get() = runningSessions > 0 || pendingRequests > 0

    val startAllowed: Boolean get() = startExemption != StartExemption.NONE
}

/**
 * Whether the connection belongs in a foreground service (plan §6, Phase 4).
 *
 * **Pure, and therefore testable without a device.** A `Service` cannot be exercised without one, so
 * everything that decides *whether it runs* is here instead: the inputs are read from the stores, and
 * the service does nothing but carry out the answer. The exit criterion "no service runs while
 * nothing is active" is then a property of this function that a test can state outright.
 *
 * **Three rules, in order.**
 *
 *  1. Nothing to connect to and nothing to do → stop. A server that was removed, or one the user
 *     never chose, must not hold a socket open.
 *  2. Work, or "always connected", or a grace period that has not elapsed → keep (or start).
 *  3. Idle, grace elapsed → stop. This is the only rule that ends the service, and it is the one
 *     that makes "runs while any session is busy, any request is pending, or always connected"
 *     and "stops after a configurable idle grace period" the same statement.
 *
 * **A start the system will refuse becomes a keep.** Android 12+ forbids starting a foreground
 * service from the background outside a documented exemption list, and "an event arrived over a
 * socket" is not on it. A permission raised by the desktop while the phone is locked therefore
 * cannot, on its own, start the service — the honest answer is to report that the work is waiting and
 * let the next legitimate start (the user opening the app, tapping the notification, or having turned
 * battery optimisation off) pick it up. Pretending otherwise would be a `ForegroundServiceStartNotAllowedException`
 * on first run, which is how this is usually discovered.
 */
object PresencePolicy {

    fun decide(inputs: PresenceInputs): PresenceDecision {
        if (!inputs.hasServer) return PresenceDecision.Stop(PresenceReason.NO_SERVER)

        // Nothing to connect to, nothing waiting, and the service is not up. The grace period only
        // protects a service that is already running, so there is nothing to do here at all.
        if (!inputs.serviceRunning && !inputs.alwaysConnected && !inputs.hasWork) {
            return PresenceDecision.Stop(PresenceReason.NO_SERVER)
        }

        if (inputs.serviceRunning) {
            return when {
                inputs.alwaysConnected -> PresenceDecision.Keep(PresenceReason.ALWAYS_CONNECTED)
                inputs.runningSessions > 0 -> PresenceDecision.Keep(PresenceReason.SESSIONS_RUNNING)
                inputs.pendingRequests > 0 -> PresenceDecision.Keep(PresenceReason.REQUEST_PENDING)
                else -> idleVerdict(inputs)
            }
        }

        val wanted = when {
            inputs.alwaysConnected -> PresenceReason.ALWAYS_CONNECTED
            inputs.runningSessions > 0 -> PresenceReason.SESSIONS_RUNNING
            else -> PresenceReason.REQUEST_PENDING
        }
        if (!inputs.startAllowed) return PresenceDecision.Keep(PresenceReason.BACKGROUND_START_REFUSED)
        return PresenceDecision.Start(inputs.startExemption.toStartReason())
    }

    private fun idleVerdict(inputs: PresenceInputs): PresenceDecision {
        val since = inputs.idleSinceMillis ?: return PresenceDecision.Stop(PresenceReason.IDLE_GRACE_ELAPSED)
        val elapsed = inputs.nowMillis - since
        return if (elapsed < inputs.idleGraceMillis) {
            PresenceDecision.Keep(PresenceReason.IDLE_GRACE)
        } else {
            PresenceDecision.Stop(PresenceReason.IDLE_GRACE_ELAPSED)
        }
    }

    /**
     * The reason a start carries.
     *
     * It names the exemption, not the work: the work is already in the ongoing notification, and
     * what a reader of the event log needs to explain is *why* a start was permitted at all.
     */
    private fun StartExemption.toStartReason(): PresenceReason = when (this) {
        StartExemption.APP_IN_FOREGROUND -> PresenceReason.APP_IN_FOREGROUND
        StartExemption.NOTIFICATION_ACTION -> PresenceReason.NOTIFICATION_ACTION
        StartExemption.BATTERY_OPTIMISATION_DISABLED -> PresenceReason.BATTERY_EXEMPT
        StartExemption.NONE -> PresenceReason.BACKGROUND_START_REFUSED
    }

    /** The default grace period: long enough to survive a gap, short enough to be noticed. */
    const val DEFAULT_IDLE_GRACE_MILLIS: Long = 2 * 60 * 1000L
}
