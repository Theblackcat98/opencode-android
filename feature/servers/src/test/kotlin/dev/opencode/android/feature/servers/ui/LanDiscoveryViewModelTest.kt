package dev.opencode.android.feature.servers.ui

import dev.opencode.android.core.network.LanFound
import dev.opencode.android.core.network.LanScanner
import dev.opencode.android.core.network.LocalIpv4Source
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The LAN probe on the add-server form: off on every launch, one scan at a time, and a stop that cancels
 * the probes in flight rather than letting them report later.
 *
 * The scanner and the phone's address are fakes, so no socket is opened. What is checked is what the
 * view model asks the scanner to do and what it shows.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LanDiscoveryViewModelTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * Probes every host on every port in order, like the real prober would, emitting the [answers] that
     * are among them. After [stallAfter] probes it waits to be cancelled, which is a scan still running.
     * [cancelled] records whether that wait ended in cancellation.
     */
    private class FakeScanner(
        private val answers: List<LanFound> = emptyList(),
        private val stallAfter: Int = Int.MAX_VALUE,
    ) : LanScanner {
        var calls = 0
        var probedHosts: List<String> = emptyList()
        var probedPorts: List<Int> = emptyList()
        var cancelled = false

        override fun scan(
            hosts: List<String>,
            ports: List<Int>,
            concurrency: Int,
            timeoutMs: Long,
            onProbed: () -> Unit,
        ): Flow<LanFound> = flow {
            calls++
            probedHosts = hosts
            probedPorts = ports
            try {
                var probed = 0
                for (host in hosts) {
                    for (port in ports) {
                        if (++probed > stallAfter) awaitCancellation()
                        onProbed()
                        val address = LanFound(host, port)
                        if (address in answers) emit(address)
                    }
                }
            } catch (e: CancellationException) {
                cancelled = true
                throw e
            }
        }
    }

    private class FakeLocalIpv4(private val ip: String?) : LocalIpv4Source {
        override fun activeIpv4(): String? = ip
    }

    @Test
    fun theProbeIsOffOnANewScreenAndSwitchingItOnStartsNothing() = runTest {
        val scanner = FakeScanner()
        val viewModel = LanDiscoveryViewModel(scanner, FakeLocalIpv4("192.168.1.42"))

        assertFalse(viewModel.uiState.value.enabled)
        viewModel.startScan()
        assertEquals("a scan cannot start while the probe is off", 0, scanner.calls)

        viewModel.setEnabled(true)
        assertTrue(viewModel.uiState.value.enabled)
        assertFalse(viewModel.uiState.value.scanning)
        assertEquals("switching on does not scan by itself", 0, scanner.calls)
    }

    @Test
    fun aScanProbesTheSlash24AroundThePhoneOnTheDefaultPortsAndReportsWhatItFinds() = runTest {
        val answer = LanFound("192.168.1.20", 4096)
        val scanner = FakeScanner(answers = listOf(answer))
        val viewModel = LanDiscoveryViewModel(scanner, FakeLocalIpv4("192.168.1.42"))
        viewModel.setEnabled(true)

        viewModel.startScan()

        assertEquals(253, scanner.probedHosts.size)
        assertFalse("the phone does not probe itself", "192.168.1.42" in scanner.probedHosts)
        assertEquals(listOf(49374, 4096), scanner.probedPorts)
        val state = viewModel.uiState.value
        assertEquals(listOf(answer), state.found)
        assertEquals(LanNotice.FINISHED, state.notice)
        assertFalse(state.scanning)
        assertEquals(253 * 2, state.total)
        assertEquals("every address tried is counted", 253 * 2, state.checked)
    }

    @Test
    fun customPortsAreAddedToTheDefaultsAndAnInvalidOneStopsTheScan() = runTest {
        val scanner = FakeScanner()
        val viewModel = LanDiscoveryViewModel(scanner, FakeLocalIpv4("192.168.1.42"))
        viewModel.setEnabled(true)

        viewModel.updatePortsInput("4097, 8080 4096")
        assertEquals(emptyList<String>(), viewModel.uiState.value.invalidPorts)
        viewModel.startScan()
        assertEquals(listOf(49374, 4096, 4097, 8080), scanner.probedPorts)

        viewModel.updatePortsInput("4097, banana, 70000")
        assertEquals(listOf("banana", "70000"), viewModel.uiState.value.invalidPorts)
        assertFalse(viewModel.uiState.value.canScan)
        viewModel.startScan()
        assertEquals("an invalid port starts no scan", 1, scanner.calls)
    }

    @Test
    fun aPhoneWithNoPrivateAddressIsToldSoAndNothingIsScanned() = runTest {
        for (address in listOf<String?>(null, "8.8.8.8", "fe80::1")) {
            val scanner = FakeScanner()
            val viewModel = LanDiscoveryViewModel(scanner, FakeLocalIpv4(address))
            viewModel.setEnabled(true)

            viewModel.startScan()

            assertEquals("no scan for $address", 0, scanner.calls)
            assertEquals(LanNotice.NO_PRIVATE_ADDRESS, viewModel.uiState.value.notice)
        }
    }

    @Test
    fun aSecondScanCannotStartWhileOneIsRunning() = runTest {
        val scanner = FakeScanner(stallAfter = 5)
        val viewModel = LanDiscoveryViewModel(scanner, FakeLocalIpv4("192.168.1.42"))
        viewModel.setEnabled(true)

        viewModel.startScan()
        viewModel.startScan()

        assertEquals(1, scanner.calls)
        assertTrue(viewModel.uiState.value.scanning)
        viewModel.stopScan()
    }

    @Test
    fun stoppingAScanCancelsItAndKeepsOnlyWhatWasAlreadyFound() = runTest {
        val answer = LanFound("192.168.1.20", 4096)
        // Sixty probes in, the scan is still running; the answer is the fortieth of them.
        val scanner = FakeScanner(answers = listOf(answer), stallAfter = 60)
        val viewModel = LanDiscoveryViewModel(scanner, FakeLocalIpv4("192.168.1.42"))
        viewModel.setEnabled(true)

        viewModel.startScan()
        val running = viewModel.uiState.value
        assertTrue(running.scanning)
        assertEquals(60, running.checked)
        assertEquals(listOf(answer), running.found)

        viewModel.stopScan()

        val stopped = viewModel.uiState.value
        assertFalse(stopped.scanning)
        assertEquals(LanNotice.STOPPED, stopped.notice)
        assertEquals(listOf(answer), stopped.found)
        assertTrue("the probes still in flight were cancelled", scanner.cancelled)
    }

    @Test
    fun switchingTheProbeOffStopsAScanAndClearsWhatItFound() = runTest {
        val answer = LanFound("192.168.1.20", 4096)
        val scanner = FakeScanner(answers = listOf(answer), stallAfter = 60)
        val viewModel = LanDiscoveryViewModel(scanner, FakeLocalIpv4("192.168.1.42"))
        viewModel.setEnabled(true)
        viewModel.startScan()

        viewModel.setEnabled(false)

        val off = viewModel.uiState.value
        assertFalse(off.enabled)
        assertFalse(off.scanning)
        assertEquals(emptyList<LanFound>(), off.found)
        assertEquals(null, off.notice)
        assertTrue("the scan was cancelled with the switch", scanner.cancelled)
    }

    @Test
    fun stoppingWhenNothingIsRunningChangesNothing() = runTest {
        val scanner = FakeScanner()
        val viewModel = LanDiscoveryViewModel(scanner, FakeLocalIpv4("192.168.1.42"))
        viewModel.setEnabled(true)

        viewModel.stopScan()

        assertEquals(null, viewModel.uiState.value.notice)
        assertEquals(0, scanner.calls)
    }

    @Test
    fun parseLanPortsKeepsTheDefaultsFirstDropsDuplicatesAndNamesTheInvalid() {
        assertEquals(listOf(49374, 4096), parseLanPorts("").ports)
        assertEquals(listOf(49374, 4096, 4097), parseLanPorts("4096,4097").ports)
        assertEquals(listOf(49374, 4096, 1, 65535), parseLanPorts("1; 65535 , 4096").ports)
        assertEquals(listOf("0", "65536", "x"), parseLanPorts("0 65536 x").invalid)
        assertEquals(emptyList<String>(), parseLanPorts(" , ;").invalid)
    }
}
