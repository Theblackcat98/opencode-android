package dev.opencode.android.feature.servers.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.opencode.android.core.network.LanFound
import dev.opencode.android.core.network.LanProber
import dev.opencode.android.core.network.LanScanner
import dev.opencode.android.core.network.LocalIpv4Source
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Why the last scan ended, when it did not simply run. */
enum class LanNotice {
    /** The phone has no private IPv4 address, so there was nothing to scan. */
    NO_PRIVATE_ADDRESS,

    /** Every address was tried. */
    FINISHED,

    /** The user stopped it, or switched the probe off, before it finished. */
    STOPPED,
}

/**
 * The opt-in "find servers on this network" section of the add-server form.
 *
 * It is off on every launch: [enabled] lives only in this view model, which is not persisted, so a
 * process restart starts it off again. Nothing is probed until the user asks, and switching it off
 * or leaving the screen cancels a scan in progress.
 */
data class LanDiscoveryUiState(
    val enabled: Boolean = false,
    val portsInput: String = "",
    val scanning: Boolean = false,
    /** Addresses tried so far in this scan. */
    val checked: Int = 0,
    /** Addresses to try in this scan: hosts times ports. */
    val total: Int = 0,
    val found: List<LanFound> = emptyList(),
    val notice: LanNotice? = null,
) {
    /** The entries of [portsInput] that are not a port from 1 to 65535. */
    val invalidPorts: List<String> get() = parseLanPorts(portsInput).invalid

    /** A scan can start when none is running and every port the user typed is valid. */
    val canScan: Boolean get() = !scanning && invalidPorts.isEmpty()
}

/**
 * The ports one scan probes: the two the plan names, then each port the user typed that is valid and
 * not already listed. [invalid] is what the user typed that could not be a port.
 */
internal data class LanPortList(val ports: List<Int>, val invalid: List<String>)

internal fun parseLanPorts(input: String): LanPortList {
    val ports = LinkedHashSet(LanProber.DEFAULT_PORTS)
    val invalid = mutableListOf<String>()
    input.split(PORT_SEPARATORS).filter { it.isNotEmpty() }.forEach { token ->
        val port = token.toIntOrNull()?.takeIf { it in 1..65535 }
        if (port == null) invalid += token else ports += port
    }
    return LanPortList(ports.toList(), invalid)
}

private val PORT_SEPARATORS = Regex("[,;\\s]+")

/** The address as a number, so the results read in network order rather than in the order probes answered. */
private fun LanFound.sortKey(): Long = LanProber.ipv4Value(host)

/**
 * Drives the LAN probe on the add-server form. It knows nothing about the form: a found server is
 * handed to the screen, which puts its [LanFound.url] in the Server URL field.
 *
 * The phone's own address comes from [localIpv4Source] and the scan from [scanner], so the tests can
 * supply both and no network is touched.
 */
@HiltViewModel
class LanDiscoveryViewModel @Inject constructor(
    private val scanner: LanScanner,
    private val localIpv4Source: LocalIpv4Source,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LanDiscoveryUiState())
    val uiState: StateFlow<LanDiscoveryUiState> = _uiState.asStateFlow()

    private var scanJob: Job? = null

    /**
     * Turns the probe on or off. Switching it on starts nothing; switching it off stops a scan in
     * progress and clears what it found. The typed ports are kept, since they are only text.
     */
    fun setEnabled(enabled: Boolean) {
        if (enabled) {
            _uiState.update { it.copy(enabled = true) }
            return
        }
        scanJob?.cancel()
        scanJob = null
        _uiState.update { LanDiscoveryUiState(portsInput = it.portsInput) }
    }

    fun updatePortsInput(input: String) = _uiState.update { it.copy(portsInput = input) }

    /**
     * Probes the /24 around the phone's address on the default ports and any valid ports typed. It
     * does nothing when the probe is off, a scan is already running, or a typed port is invalid.
     */
    fun startScan() {
        val current = _uiState.value
        if (!current.enabled || !current.canScan) return

        val localIp = localIpv4Source.activeIpv4()
        if (localIp == null || !LanProber.isPrivateIpv4(localIp)) {
            _uiState.update { it.copy(found = emptyList(), notice = LanNotice.NO_PRIVATE_ADDRESS) }
            return
        }

        val hosts = LanProber.hostsOf(localIp)
        val ports = parseLanPorts(current.portsInput).ports
        _uiState.update {
            it.copy(
                scanning = true,
                checked = 0,
                total = hosts.size * ports.size,
                found = emptyList(),
                notice = null,
            )
        }
        scanJob = viewModelScope.launch {
            try {
                scanner.scan(hosts, ports, onProbed = ::countProbe).collect { server ->
                    _uiState.update { it.copy(found = (it.found + server).sortedBy { entry -> entry.sortKey() }) }
                }
                scanJob = null
                _uiState.update { it.copy(scanning = false, notice = LanNotice.FINISHED) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                // A probe that blew up must not leave Scan disabled; what was found before it stays.
                scanJob = null
                _uiState.update { it.copy(scanning = false, notice = LanNotice.STOPPED) }
            }
        }
    }

    /**
     * Cancels the scan. The probes in flight are cancelled with it, and nothing they would have
     * answered is reported. What was found before the stop stays on the screen.
     */
    fun stopScan() {
        val job = scanJob ?: return
        scanJob = null
        job.cancel()
        _uiState.update { it.copy(scanning = false, notice = LanNotice.STOPPED) }
    }

    private fun countProbe() = _uiState.update { it.copy(checked = it.checked + 1) }
}
