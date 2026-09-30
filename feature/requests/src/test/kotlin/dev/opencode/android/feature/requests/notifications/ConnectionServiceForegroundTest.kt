package dev.opencode.android.feature.requests.notifications

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import dagger.hilt.android.testing.UninstallModules
import dev.opencode.android.core.data.presence.PresenceSignals
import dev.opencode.android.core.data.presence.PresenceSignalsSource
import dev.opencode.android.core.data.presence.RunningSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowService
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * The platform's contract with a foreground service, checked against the real [ConnectionService]
 * with real Hilt injection.
 *
 * **The contract.** After `startForegroundService()` the service has a few seconds to call
 * `startForeground()`, and if it does not — because it returned, stopped itself, or was still waiting on
 * something — the platform kills the *whole app* with `ForegroundServiceDidNotStartInTimeException`.
 * That is what a pending permission did on every launch: the launcher asked for the service, and the
 * service went on to decide for itself whether to run. So the rule here is not "the service reaches the
 * foreground when the policy says so" but "it is in the foreground before `onStartCommand` returns,
 * whatever the policy says, and stops afterwards if it must".
 *
 * **The watcher is held back on purpose.** The service collects the presence signals on
 * `Dispatchers.Default`, so a test that read the notification straight after `onStartCommand` would be
 * racing that collector, and a promotion the *collector* made would pass for one the service made in
 * time. [GatedSignals] lets the main thread read the signals and parks every other thread until the test
 * has looked, which makes "before `onStartCommand` returns" something the test can actually assert.
 */
@HiltAndroidTest
@UninstallModules(ConnectionServiceModule::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, sdk = [34])
class ConnectionServiceForegroundTest {

    @get:Rule
    val hilt = HiltAndroidRule(this)

    private val gate = GatedSignals()

    @BindValue
    @JvmField
    val presence: PresenceSignalsSource = gate

    /** What the service asked of the event stream, in order: `true` to hold it up, `false` to let it go. */
    private val holds = CopyOnWriteArrayList<Boolean>()

    @BindValue
    @JvmField
    val stream: StreamHold = StreamHold { holds.add(it) }

    @Inject
    lateinit var launcher: ConnectionServiceLauncher

    private val started = mutableListOf<ServiceController<ConnectionService>>()

    @Before
    fun setUp() {
        hilt.inject()
        appIs(Lifecycle.State.CREATED)
    }

    @After
    fun tearDown() {
        // A parked watcher must not outlive the test, and an observer left on the process lifecycle
        // would be handed the next test's events.
        gate.open()
        started.forEach { runCatching { it.destroy() } }
        appIs(Lifecycle.State.CREATED)
    }

    @Test
    fun `a start with nothing to do is in the foreground before it returns, and then stops`() {
        appIs(Lifecycle.State.RESUMED)
        val service = start()

        assertPromoted(service)

        gate.open()
        awaitTrue("the service never stopped itself after having nothing to do") { shadow(service).isStoppedBySelf }
        // The order is the point: the foreground notification went up first, and only then came down.
        assertTrue(shadow(service).isForegroundStopped)
        assertEquals(ConnectionService.ONGOING_NOTIFICATION_ID, shadow(service).lastForegroundNotificationId)
    }

    @Test
    fun `a start with a request pending and the app open is in the foreground before it returns and stays there`() {
        appIs(Lifecycle.State.RESUMED)
        gate.state.value = pendingPermission()
        val service = start()

        assertPromoted(service)

        gate.open()
        stays(service)
    }

    @Test
    fun `a start the service would not have chosen itself is still promoted, because the platform granted it`() {
        // The launcher can be started by an exemption the service cannot see: the user tapped a
        // notification action while the app was in the background. The service, asking the process
        // lifecycle, finds "no exemption" and used to return without ever calling startForeground().
        appIs(Lifecycle.State.CREATED)
        gate.state.value = pendingPermission()
        val service = start()

        assertPromoted(service)

        gate.open()
        stays(service)
    }

    @Test
    fun `a start whose work vanished between the launcher's read and the service's is promoted before it stops`() {
        appIs(Lifecycle.State.RESUMED)
        gate.state.value = pendingPermission()
        val service = start()
        // The launcher decided on the first snapshot; the request is answered before the service looks.
        gate.state.value = PresenceSignals(serverId = SERVER)

        assertPromoted(service)

        gate.open()
        awaitTrue("the service kept running for work that was gone") { shadow(service).isStoppedBySelf }
    }

    @Test
    fun `a start with no server at all is still promoted before it stops`() {
        appIs(Lifecycle.State.RESUMED)
        gate.state.value = PresenceSignals(serverId = null)
        val service = start()

        assertPromoted(service)

        gate.open()
        awaitTrue("the service kept running with no server") { shadow(service).isStoppedBySelf }
    }

    @Test
    fun `every start command promotes again, so a second start on a running service cannot time out`() {
        appIs(Lifecycle.State.RESUMED)
        gate.state.value = running()
        val service = start()
        val first = shadow(service).lastForegroundNotification
        assertNotNull(first)

        service.startCommand(0, 2)

        assertNotSame(
            "the second start command did not call startForeground()",
            first,
            shadow(service).lastForegroundNotification,
        )
    }

    @Test
    fun `a platform refusal of startForeground does not escape, and the service stops itself and says so`() {
        appIs(Lifecycle.State.RESUMED)
        gate.state.value = pendingPermission()
        val service = Robolectric.buildService(ConnectionService::class.java, startIntent()).create().also(started::add)
        shadowOf(service.get()).setThrowInStartForeground(ForegroundServiceStartNotAllowedException("refused"))

        service.startCommand(0, 1)

        assertTrue("a refused service kept running", shadow(service).isStoppedBySelf)
        assertNotNull("the launcher was not told the start was refused", launcher.refusedUnder)
    }

    @Test
    fun `a missing type permission does not escape either`() {
        appIs(Lifecycle.State.RESUMED)
        gate.state.value = pendingPermission()
        val service = Robolectric.buildService(ConnectionService::class.java, startIntent()).create().also(started::add)
        shadowOf(service.get()).setThrowInStartForeground(SecurityException("no FOREGROUND_SERVICE_DATA_SYNC"))

        service.startCommand(0, 1)

        assertTrue(shadow(service).isStoppedBySelf)
    }

    @Test
    fun `a service started in the background holds the stream, and lets it go when it stops`() {
        appIs(Lifecycle.State.CREATED)
        val service = start()

        assertPromoted(service)
        assertEquals("the stream was not held for a service running in the background", listOf(true), holds.toList())

        gate.open()
        awaitTrue("the service never stopped itself") { shadow(service).isStoppedBySelf }
        assertEquals(listOf(true, false), holds.toList())
    }

    @Test
    fun `a service started while the app is open leaves the stream to the app, and holds it when the app leaves`() {
        appIs(Lifecycle.State.RESUMED)
        gate.state.value = pendingPermission()
        val service = start()
        gate.open()
        stays(service)
        assertEquals("the stream was held while the app was open", emptyList<Boolean>(), holds.toList())

        // The connection manager cuts the stream on this same event, and runs after the service does,
        // so the hold has to be put back once the dispatch is over.
        appIs(Lifecycle.State.CREATED)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf(true), holds.toList())
    }

    @Test
    fun `a service that stops while the app is open does not cut the stream the app is showing`() {
        appIs(Lifecycle.State.RESUMED)
        val service = start()
        gate.open()
        awaitTrue("the service never stopped itself") { shadow(service).isStoppedBySelf }

        assertEquals(emptyList<Boolean>(), holds.toList())
    }

    @Test
    fun `the type the service passes is the one its manifest declares`() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val declared = context.packageManager
            .getServiceInfo(android.content.ComponentName(context, ConnectionService::class.java), 0)
            .foregroundServiceType
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, declared)

        appIs(Lifecycle.State.RESUMED)
        val service = start()
        assertEquals(declared, service.get().foregroundServiceType)
    }

    // ------------------------------------------------------------------ helpers

    private fun start(): ServiceController<ConnectionService> {
        val service = Robolectric.buildService(ConnectionService::class.java, startIntent()).create()
        started.add(service)
        service.startCommand(0, 1)
        return service
    }

    private fun startIntent(): Intent {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        return Intent(context, ConnectionService::class.java).setAction(ConnectionService.ACTION_START)
    }

    private fun shadow(service: ServiceController<ConnectionService>): ShadowService = shadowOf(service.get())

    private fun assertPromoted(service: ServiceController<ConnectionService>) {
        val notification: Notification? = shadow(service).lastForegroundNotification
        assertNotNull("startForeground() had not been called by the time onStartCommand returned", notification)
        assertEquals(ConnectionService.ONGOING_NOTIFICATION_ID, shadow(service).lastForegroundNotificationId)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, service.get().foregroundServiceType)
    }

    /** Waits long enough for a wrong `stopSelf()` to have happened, then asserts it did not. */
    private fun stays(service: ServiceController<ConnectionService>) {
        Thread.sleep(QUIET_MILLIS)
        assertFalse("the service stopped itself while there was work", shadow(service).isStoppedBySelf)
        assertNotNull(shadow(service).lastForegroundNotification)
    }

    private fun awaitTrue(message: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(POLL_MILLIS)
        }
        assertTrue(message, condition())
    }

    /** Puts the process in [state], which is what the service reads as "the app is in the foreground". */
    private fun appIs(state: Lifecycle.State) {
        (ProcessLifecycleOwner.get().lifecycle as LifecycleRegistry).currentState = state
    }

    private fun pendingPermission() = PresenceSignals(serverId = SERVER, pendingPermissions = 1)

    private fun running() = PresenceSignals(
        serverId = SERVER,
        running = listOf(RunningSession("ses_1", "Session", isSubagent = false)),
    )

    /**
     * Signals the main thread reads at once and every other thread waits for.
     *
     * The service reads the current value on the main thread inside `onStartCommand`, and its watcher
     * collects on a worker thread; parking the workers is what separates the two.
     */
    private class GatedSignals : PresenceSignalsSource {
        val state = MutableStateFlow(PresenceSignals(serverId = SERVER))
        private val gate = CountDownLatch(1)

        fun open() = gate.countDown()

        override val signals: StateFlow<PresenceSignals>
            get() {
                if (Looper.myLooper() != Looper.getMainLooper()) gate.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                return state
            }
    }

    private companion object {
        const val SERVER = "srv_1"
        const val TIMEOUT_MILLIS = 5_000L
        const val POLL_MILLIS = 20L
        const val QUIET_MILLIS = 400L
    }
}
