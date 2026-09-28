package dev.opencode.android.core.data.presence

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * When the connection service runs (plan §6, Phase 4).
 *
 * The phase's fourth exit criterion is "no service runs while nothing is active", and a `Service`
 * cannot be started here. The rule therefore lives in [PresencePolicy] and this states it outright:
 * a case the policy answers wrongly is a case where a battery meter would show a running service
 * with nothing behind it, and the only place that can be caught without a device.
 */
class PresencePolicyTest {

    private val now = 1_700_000_000_000L
    private val grace = 60_000L

    @Test
    fun `a busy session starts the service while the app is in the foreground`() {
        val decision = decide(runningSessions = 1, startExemption = StartExemption.APP_IN_FOREGROUND)
        assertEquals(PresenceDecision.Start(PresenceReason.APP_IN_FOREGROUND), decision)
    }

    @Test
    fun `a pending request starts the service with its own reason`() {
        val decision = decide(pendingRequests = 1, startExemption = StartExemption.APP_IN_FOREGROUND)
        assertEquals(PresenceDecision.Start(PresenceReason.APP_IN_FOREGROUND), decision)
    }

    @Test
    fun `a running service is kept while a session is busy`() {
        val decision = decide(runningSessions = 2, serviceRunning = true)
        assertEquals(PresenceDecision.Keep(PresenceReason.SESSIONS_RUNNING), decision)
    }

    @Test
    fun `a running service is kept while a request is pending`() {
        val decision = decide(pendingRequests = 3, serviceRunning = true)
        assertEquals(PresenceDecision.Keep(PresenceReason.REQUEST_PENDING), decision)
    }

    @Test
    fun `always connected keeps the service up with nothing active`() {
        val decision = decide(alwaysConnected = true, serviceRunning = true, idleSinceMillis = now)
        assertEquals(PresenceDecision.Keep(PresenceReason.ALWAYS_CONNECTED), decision)
    }

    @Test
    fun `always connected starts the service with nothing active`() {
        val decision = decide(alwaysConnected = true, startExemption = StartExemption.APP_IN_FOREGROUND)
        assertEquals(PresenceDecision.Start(PresenceReason.APP_IN_FOREGROUND), decision)
    }

    @Test
    fun `the idle grace period holds the service before it stops`() {
        val decision = decide(
            serviceRunning = true,
            idleSinceMillis = now,
            nowMillis = now + grace - 1,
        )
        assertEquals(PresenceDecision.Keep(PresenceReason.IDLE_GRACE), decision)
    }

    @Test
    fun `the service stops once the idle grace period has elapsed`() {
        val decision = decide(
            serviceRunning = true,
            idleSinceMillis = now,
            nowMillis = now + grace,
        )
        assertEquals(PresenceDecision.Stop(PresenceReason.IDLE_GRACE_ELAPSED), decision)
    }

    @Test
    fun `the grace period is configurable`() {
        val short = decide(serviceRunning = true, idleSinceMillis = now, nowMillis = now + 20_000, idleGraceMillis = 15_000)
        assertEquals(PresenceDecision.Stop(PresenceReason.IDLE_GRACE_ELAPSED), short)
        val long = decide(serviceRunning = true, idleSinceMillis = now, nowMillis = now + 20_000, idleGraceMillis = 120_000)
        assertEquals(PresenceDecision.Keep(PresenceReason.IDLE_GRACE), long)
    }

    @Test
    fun `nothing active and nothing running stops the service`() {
        assertEquals(PresenceDecision.Stop(PresenceReason.NO_SERVER), decide())
    }

    @Test
    fun `no server stops the service even while work is reported`() {
        val decision = PresencePolicy.decide(
            PresenceInputs(
                hasServer = false,
                runningSessions = 1,
                serviceRunning = true,
                idleGraceMillis = grace,
                idleSinceMillis = null,
                nowMillis = now,
                startExemption = StartExemption.APP_IN_FOREGROUND,
            ),
        )
        assertEquals(PresenceDecision.Stop(PresenceReason.NO_SERVER), decision)
    }

    @Test
    fun `a start the system will refuse becomes a keep, not a crash`() {
        // The case the phase's own spike turned up: a permission arrives over a socket while the
        // phone is locked, and Android 12+ refuses a background start. The honest answer is to report
        // the work as waiting and let the next permitted start pick it up.
        val decision = decide(runningSessions = 1, startExemption = StartExemption.NONE)
        assertEquals(PresenceDecision.Keep(PresenceReason.BACKGROUND_START_REFUSED), decision)
    }

    @Test
    fun `battery optimisation being off is an exemption the policy names`() {
        val decision = decide(
            runningSessions = 1,
            startExemption = StartExemption.BATTERY_OPTIMISATION_DISABLED,
        )
        assertEquals(PresenceDecision.Start(PresenceReason.BATTERY_EXEMPT), decision)
    }

    @Test
    fun `a notification action is the other exemption the policy names`() {
        val decision = decide(
            pendingRequests = 1,
            startExemption = StartExemption.NOTIFICATION_ACTION,
        )
        assertEquals(PresenceDecision.Start(PresenceReason.NOTIFICATION_ACTION), decision)
    }

    @Test
    fun `work appearing while the service is down and the app is closed does not ask for a start`() {
        val decision = decide(runningSessions = 1, startExemption = StartExemption.NONE)
        assertEquals(
            PresenceDecision.Keep(PresenceReason.BACKGROUND_START_REFUSED),
            decision,
        )
    }

    @Test
    fun `the signals projection carries everything the policy reads`() {
        val signals = PresenceSignals(
            serverId = "srv",
            running = listOf(RunningSession("ses_1", "Fix the tests", isSubagent = false)),
            pendingPermissions = 1,
            pendingForms = 2,
            alwaysConnected = true,
            idleGraceMillis = 30_000L,
        )
        val inputs = signals.toInputs(
            serviceRunning = true,
            idleSinceMillis = null,
            nowMillis = now,
            startExemption = StartExemption.NONE,
        )
        assertEquals(true, inputs.hasServer)
        assertEquals(1, inputs.runningSessions)
        assertEquals(3, inputs.pendingRequests)
        assertEquals(true, inputs.alwaysConnected)
        assertEquals(30_000L, inputs.idleGraceMillis)
        assertEquals(true, inputs.hasWork)
    }

    private fun decide(
        hasServer: Boolean = true,
        runningSessions: Int = 0,
        pendingRequests: Int = 0,
        alwaysConnected: Boolean = false,
        serviceRunning: Boolean = false,
        idleGraceMillis: Long = grace,
        idleSinceMillis: Long? = null,
        nowMillis: Long = now,
        startExemption: StartExemption = StartExemption.APP_IN_FOREGROUND,
    ): PresenceDecision = PresencePolicy.decide(
        PresenceInputs(
            hasServer = hasServer,
            runningSessions = runningSessions,
            pendingRequests = pendingRequests,
            alwaysConnected = alwaysConnected,
            serviceRunning = serviceRunning,
            idleGraceMillis = idleGraceMillis,
            idleSinceMillis = idleSinceMillis,
            nowMillis = nowMillis,
            startExemption = startExemption,
        ),
    )
}
