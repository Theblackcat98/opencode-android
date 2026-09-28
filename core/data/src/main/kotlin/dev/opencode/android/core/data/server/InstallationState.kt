package dev.opencode.android.core.data.server

import dev.opencode.android.core.model.event.Event
import dev.opencode.android.core.model.event.InstallationUpdateAvailable
import dev.opencode.android.core.model.event.InstallationUpdated
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the server says about its own version (plan §6, Phase 4; features doc §33).
 *
 * **The two P4 events are the whole of it.** `installation.updated` carries the running version and
 * `installation.update-available` the one the user could move to; the second is what produces the
 * "server update available" notification, and the first is what makes it say which version.
 *
 * **Events only, with the version seeded from `GET /api/info`.** The stream is live-only with no
 * replay, so a client that connects after a release would never see `installation.updated` and would
 * announce an update against an unknown version. The one REST read at start fills that gap, and the
 * events keep it current afterwards.
 *
 * The state is not cleared when the announced version is the running one: the user has been told
 * about it once and dismissing that is a separate decision, which belongs to a resync that reports no
 * update any more. Until then the notification stays, which is also what makes the reconciler's diff
 * idempotent across a resync.
 */
class InstallationState(
    private val seed: suspend () -> String? = { null },
) {
    /**
     * The version this server is running, when the client knows it.
     */
    data class Installation(
        val version: String? = null,
        val updateAvailable: String? = null,
    ) {
        /** True when a newer version was announced and the running one is not already it. */
        val hasUpdate: Boolean
            get() = updateAvailable != null && updateAvailable != version
    }

    private val _state = MutableStateFlow(Installation())

    val state: StateFlow<Installation> = _state.asStateFlow()

    /** Reads the running version once, before the first frame. */
    suspend fun start() {
        val version = runCatching { seed() }.getOrNull() ?: return
        if (version == _state.value.version) return
        _state.value = _state.value.copy(version = version)
    }

    /** Applies one event. Returns true when the visible state changed. */
    fun apply(event: Event): Boolean {
        val payload = event.payload
        return when (payload) {
            is InstallationUpdated -> update { copy(version = payload.version) }
            is InstallationUpdateAvailable -> update { copy(updateAvailable = payload.version) }
            else -> false
        }
    }

    fun clear() {
        _state.value = Installation()
    }

    private inline fun update(transform: Installation.() -> Installation): Boolean {
        val next = _state.value.transform()
        if (next == _state.value) return false
        _state.value = next
        return true
    }
}
