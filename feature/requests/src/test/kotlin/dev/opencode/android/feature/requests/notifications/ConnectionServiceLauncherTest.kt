package dev.opencode.android.feature.requests.notifications

import android.app.ForegroundServiceStartNotAllowedException
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.core.app.ApplicationProvider
import dev.opencode.android.core.data.presence.PresenceSignals
import dev.opencode.android.core.data.presence.PresenceSignalsSource
import dev.opencode.android.core.data.presence.RunningSession
import dev.opencode.android.core.data.presence.StartExemption
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * When the app asks the platform for the connection service (plan §6, Phase 4).
 *
 * **The decision is [dev.opencode.android.core.data.presence.PresencePolicy]'s and is tested there.**
 * What this covers is the part around it, which can be wrong without the policy being wrong: a start has
 * to actually reach `startForegroundService`, it must not be asked for twice while the service is up, and
 * the two exemptions the app can reach must be the only ones that unlock a background start. Without a
 * device the platform will not *grant* a start, which is why the assertions are about the request.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConnectionServiceLauncherTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `a start is requested when the app is in the foreground and something is running`() {
        val (launcher, signals) = launcher()
        launcher.start()
        launcher.onStart(owner())
        signals.emit(running = 1)
        assertEquals("no start was requested for a running session", 1, awaitStarts(launcher, atLeast = 1))
        assertTrue(launcher.requested.value)
    }

    @Test
    fun `no start is requested when nothing is active`() {
        val (launcher, signals) = launcher()
        launcher.start()
        launcher.onStart(owner())
        signals.emit(running = 0)
        settle()
        assertEquals(0, launcher.starts.value)
        assertFalse(launcher.requested.value)
    }

    @Test
    fun `no start is requested from the background, because the platform would refuse it`() {
        // The spike's second finding: "a permission arrived over a socket" is not on the exemption list,
        // so a background start is not something the app may attempt.
        val (launcher, signals) = launcher()
        launcher.start()
        signals.emit(running = 1)
        settle()
        assertEquals(0, launcher.starts.value)
    }

    @Test
    fun `a notification action is an exemption the launcher accepts`() {
        val (launcher, signals) = launcher()
        launcher.start()
        signals.emit(running = 1)
        settle()
        assertEquals(0, launcher.starts.value)
        launcher.offer(StartExemption.NOTIFICATION_ACTION)
        assertEquals("the notification exemption unlocked nothing", 1, awaitStarts(launcher, atLeast = 1))
    }

    @Test
    fun `the service is asked for once, and again after it reports that it stopped`() {
        val (launcher, signals) = launcher()
        launcher.start()
        launcher.onStart(owner())
        signals.emit(running = 1)
        assertEquals(1, awaitStarts(launcher, atLeast = 1))
        signals.emit(running = 2)
        settle()
        assertEquals("the service was asked for twice while it was already up", 1, launcher.starts.value)
        launcher.serviceStopped()
        signals.emit(running = 3)
        assertEquals("a stopped service was not asked for again", 2, awaitStarts(launcher, atLeast = 2))
    }

    @Test
    fun `a start reaches the service rather than staying a wish`() {
        val (launcher, signals) = launcher()
        launcher.start()
        launcher.onStart(owner())
        signals.emit(running = 1)
        awaitStarts(launcher, atLeast = 1)
        val started = startedService()
        assertEquals(ConnectionService::class.java.name, started?.component?.className)
        assertEquals(ConnectionService.ACTION_START, started?.action)
    }

    // ------------------------------------------------------------------ refusals

    @Test
    fun `a start the platform refuses is swallowed, and not asked for again under the same exemption`() {
        refusal(ForegroundServiceStartNotAllowedException("app is in the background"))
    }

    @Test
    fun `a start refused with IllegalStateException, as before Android 12, is swallowed too`() {
        refusal(IllegalStateException("Not allowed to start service Intent: app is in background"))
    }

    @Test
    fun `a start refused with SecurityException is swallowed too`() {
        refusal(SecurityException("Starting FGS with type dataSync requires FOREGROUND_SERVICE_DATA_SYNC"))
    }

    private fun refusal(error: Throwable) {
        val presence = FakePresenceController()
        val launcher = ConnectionServiceLauncher(RefusingContext(context, error), presence)
        launcher.start()
        launcher.onStart(owner())
        presence.emit(running = 1)
        assertEquals("the launcher never tried", 1, awaitStarts(launcher, atLeast = 1))
        settle()

        assertFalse("a refused start still counts as requested, so nothing could ask again", launcher.requested.value)
        assertEquals(StartExemption.APP_IN_FOREGROUND, launcher.refusedUnder)

        // More work under the same conditions is not a reason to ask again: that is a retry loop.
        presence.emit(running = 2)
        presence.emit(running = 3)
        settle()
        assertEquals("a refused start was retried under the same exemption", 1, launcher.starts.value)

        // The app leaving and coming back is a different condition, and the one legitimate second try.
        launcher.onStop(owner())
        settle()
        assertNull("the refusal outlived the conditions it was made under", launcher.refusedUnder)
        launcher.onStart(owner())
        assertEquals("a changed exemption did not try again", 2, awaitStarts(launcher, atLeast = 2))
    }

    @Test
    fun `a refusal reported by the service is not undone by the service stopping`() {
        val (launcher, signals) = launcher()
        launcher.start()
        launcher.onStart(owner())
        signals.emit(running = 1)
        assertEquals(1, awaitStarts(launcher, atLeast = 1))

        // What the service does when its startForeground() throws: says so, stops itself, and reports
        // the stop — which on its own reads as "free to ask again".
        launcher.serviceRefused()
        launcher.serviceStopped()
        signals.emit(running = 2)
        settle()

        assertEquals("the launcher asked again straight after a refusal", 1, launcher.starts.value)
        assertEquals(StartExemption.APP_IN_FOREGROUND, launcher.refusedUnder)
    }

    /** A context whose platform says no, whatever the reason. */
    private class RefusingContext(base: Context, private val error: Throwable) : ContextWrapper(base) {
        override fun startForegroundService(service: Intent?): ComponentName? = throw error

        override fun startService(service: Intent?): ComponentName? = throw error
    }

    // ------------------------------------------------------------------ waiting

    /**
     * Waits for the launcher to have asked for [atLeast] starts.
     *
     * The launcher collects on `Dispatchers.Default`, so reading its counter straight after moving the
     * signals would race the collector rather than test anything. The wait is bounded, and a timeout
     * names what it was waiting for, so a failure says which case regressed instead of being a bare
     * `expected 1 but was 0`.
     */
    private fun awaitStarts(launcher: ConnectionServiceLauncher, atLeast: Int): Int {
        val deadline = System.currentTimeMillis() + TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            if (launcher.starts.value >= atLeast) return launcher.starts.value
            Thread.sleep(POLL_MILLIS)
        }
        return launcher.starts.value
    }

    /** Gives the collector time to do something it should not have done. */
    private fun settle() = Thread.sleep(QUIET_MILLIS)

    private fun startedService() = shadowOf(context as android.app.Application).nextStartedService

    private companion object {
        const val TIMEOUT_MILLIS = 3_000L
        const val POLL_MILLIS = 20L
        const val QUIET_MILLIS = 300L
    }

    // ------------------------------------------------------------------ fixtures

    private fun launcher(): Pair<ConnectionServiceLauncher, FakePresenceController> {
        val presence = FakePresenceController()
        return ConnectionServiceLauncher(context, presence) to presence
    }

    /**
     * A lifecycle owner with no activity behind it.
     *
     * The launcher observes the process lifecycle to learn whether the app is in the foreground; the
     * observer is driven directly here so the test does not have to install a splash screen and a real
     * activity to find out.
     */
    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private fun owner(): LifecycleOwner = Owner()

    /** The one thing the launcher reads, and nothing else. */
    private class FakePresenceController : PresenceSignalsSource {
        private val _signals = MutableStateFlow(PresenceSignals())
        override val signals: StateFlow<PresenceSignals> = _signals

        fun emit(running: Int) {
            _signals.value = PresenceSignals(
                serverId = "srv_1",
                running = List(running) { RunningSession("ses_$it", "Session $it", isSubagent = false) },
            )
        }
    }
}
