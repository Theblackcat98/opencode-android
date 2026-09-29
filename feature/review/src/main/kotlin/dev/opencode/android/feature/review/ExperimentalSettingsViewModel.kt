package dev.opencode.android.feature.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.opencode.android.core.data.preferences.ExperimentalPreferences
import dev.opencode.android.core.data.preferences.ExperimentalSettings
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The experimental switches, and nothing else (plan §4.2, §5.2).
 *
 * **The value shown is the file read back.** [ExperimentalPreferences] is a `Flow`, so a switch that
 * was pressed and did not stick would be a switch the app insists is on while the next launch says
 * otherwise; reading the flow back makes the disagreement visible instead of hiding it.
 *
 * **The switches are per installation, so this view model holds no server id.** Which server is
 * active is a connection question and belongs to the stores; the decision "may this app write to a
 * server" is the user's and is the same whichever one they are looking at.
 */
@HiltViewModel
class ExperimentalSettingsViewModel @Inject constructor(
    private val preferences: ExperimentalPreferences,
) : ViewModel() {

    /** The switches as they are stored. */
    val settings: StateFlow<ExperimentalSettings> = preferences.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), ExperimentalSettings())

    fun setFileWrites(enabled: Boolean) {
        viewModelScope.launch { preferences.setFileWrites(enabled) }
    }

    fun setSessionTransfer(enabled: Boolean) {
        viewModelScope.launch { preferences.setSessionTransfer(enabled) }
    }

    fun setPersistentPty(enabled: Boolean) {
        viewModelScope.launch { preferences.setPersistentPty(enabled) }
    }

    fun setMcpRuntime(enabled: Boolean) {
        viewModelScope.launch { preferences.setMcpRuntime(enabled) }
    }

    fun setWellknownIntegrations(enabled: Boolean) {
        viewModelScope.launch { preferences.setWellknownIntegrations(enabled) }
    }

    private companion object {
        const val STOP_TIMEOUT = 5_000L
    }
}
