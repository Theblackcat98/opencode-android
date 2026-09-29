package dev.opencode.android.feature.requests.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.opencode.android.core.data.attention.AttentionPreferences
import dev.opencode.android.core.data.attention.AttentionSettings
import dev.opencode.android.core.data.attention.QuietHours
import dev.opencode.android.core.data.server.ServerDataRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The one place the user configures what the app does in the background (plan §6, Phase 4). */
data class AttentionSettingsUiState(
    val serverId: String? = null,
    val settings: AttentionSettings = AttentionSettings(),
    val alwaysConnected: Boolean = false,
    val quiet: QuietHours = QuietHours(),
    val autoApproveGlobalMinutes: Long? = null,
    val sessionId: String? = null,
    val sessionMuted: Boolean = false,
    val autoApproveSessionMinutes: Long? = null,
    /** The change plan §5.2 says has to be confirmed before it is stored. */
    val awaitingConfirmation: PendingAutoApprove? = null,
) {
    val isPerSession: Boolean get() = sessionId != null
}

/** An auto-approve change waiting for the user to confirm it. */
data class PendingAutoApprove(
    /** Null for the global scope, a session id for the per-session one. */
    val sessionId: String?,
    val minutes: Long,
)

/**
 * The settings behind Phase 4, as a projection plus the writes.
 *
 * **Every control here is the client's, and none of it lives on the server.** A mute that travelled to
 * the server would silence the desktop too, and the server has no way to know a user wants one
 * session left alone. So this writes [AttentionPreferences] and nothing else, and the value it shows
 * is the file read back rather than a local echo — a toggle that appeared not to stick would be a
 * worse bug than a slow one.
 *
 * **Auto-approve goes through a confirmation.** Plan §5.2 lists it beside "Allow always" as a
 * dangerous action, and it is one: it answers the agent's permission requests without asking. The
 * change is staged in [AttentionSettingsUiState.awaitingConfirmation] and only written by
 * [confirmAutoApprove]. Turning it *off* is not staged, because that is the safe direction.
 */
@HiltViewModel
class AttentionSettingsViewModel @Inject constructor(
    private val preferences: AttentionPreferences,
    dataSets: ServerDataRegistry,
) : ViewModel() {

    /** The server the connection and quiet-hour settings apply to: the one being followed. */
    private val activeServer: StateFlow<String?> = dataSets.active
        .map { it?.serverId }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val sessionId = MutableStateFlow<String?>(null)
    private val clock = MutableStateFlow(System.currentTimeMillis())
    private val pending = MutableStateFlow<PendingAutoApprove?>(null)

    val state: StateFlow<AttentionSettingsUiState> = combine(
        preferences.settings,
        activeServer,
        sessionId,
        clock,
        pending,
    ) { settings, server, session, now, awaiting ->
        AttentionSettingsUiState(
            serverId = server,
            settings = settings,
            alwaysConnected = settings.alwaysConnectedFor(server),
            quiet = settings.quietHoursFor(server),
            // Minutes rather than the stored instant, because what the user chose is "30 minutes"
            // and not a timestamp that quietly drifts.
            autoApproveGlobalMinutes = settings.autoApproveGlobalUntil?.minutesLeft(now),
            sessionId = session,
            sessionMuted = session?.let(settings::isMuted) ?: false,
            autoApproveSessionMinutes = session
                ?.let { settings.autoApproveUntilFor(it)?.minutesLeft(now) },
            awaitingConfirmation = awaiting,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), AttentionSettingsUiState())

    /** The session the per-session controls apply to, or `null` for the app-wide screen. */
    fun forSession(sessionId: String?) {
        this.sessionId.value = sessionId
    }

    fun setAlwaysConnected(enabled: Boolean) {
        val server = state.value.serverId ?: return
        viewModelScope.launch { preferences.setAlwaysConnected(server, enabled) }
    }

    fun setIdleGraceMinutes(minutes: Long) {
        viewModelScope.launch { preferences.setIdleGraceMillis(minutes * 60_000L) }
    }

    fun setQuietHours(hours: QuietHours) {
        val server = state.value.serverId ?: return
        viewModelScope.launch { preferences.setQuietHours(server, hours) }
    }

    fun setSessionMuted(muted: Boolean) {
        val session = state.value.sessionId ?: return
        viewModelScope.launch { preferences.setSessionMuted(session, muted) }
    }

    /** Stages an auto-approve change and asks for confirmation. */
    fun requestAutoApprove(sessionId: String?, minutes: Long?) {
        if (minutes == null) {
            applyAutoApprove(sessionId, null)
            return
        }
        pending.value = PendingAutoApprove(sessionId, minutes)
    }

    /** Writes the staged change. Only this path stores one. */
    fun confirmAutoApprove() {
        val awaiting = pending.value ?: return
        pending.value = null
        applyAutoApprove(awaiting.sessionId, awaiting.minutes)
    }

    fun dismissConfirmation() {
        pending.value = null
    }

    /** The clock is re-read whenever the screen is drawn, so a countdown needs no timer of its own. */
    fun refreshClock() {
        clock.value = System.currentTimeMillis()
    }

    private fun applyAutoApprove(sessionId: String?, minutes: Long?) {
        viewModelScope.launch {
            val now = clock.value
            if (sessionId == null) {
                preferences.setAutoApproveGlobal(minutes, now)
            } else {
                preferences.setAutoApproveSession(sessionId, minutes, now)
            }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

/** How many whole minutes of an auto-approve window are left, or `null` when it has run out. */
private fun Long.minutesLeft(now: Long): Long? = ((this - now) / 60_000L).coerceAtLeast(0L).takeIf { it > 0 }
