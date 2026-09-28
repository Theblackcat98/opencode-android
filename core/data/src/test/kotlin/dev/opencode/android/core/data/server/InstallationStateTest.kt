package dev.opencode.android.core.data.server

import dev.opencode.android.core.model.event.Event
import dev.opencode.android.core.model.event.EventPayload
import dev.opencode.android.core.model.event.InstallationUpdateAvailable
import dev.opencode.android.core.model.event.InstallationUpdated
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The server's own version, and the `installation.*` events that keep it current (plan §6, Phase 4).
 *
 * The stream is live-only with no replay, so a client that connects after a release never sees
 * `installation.updated`; the seed read is what stops the "a newer version is available" notification
 * from being a statement about an unknown version.
 */
class InstallationStateTest {

    @Test
    fun `a running version is read once at start`() = runTest {
        val state = InstallationState(seed = { "2.0.18" })
        assertNull(state.state.value.version)
        state.start()
        assertEquals("2.0.18", state.state.value.version)
        assertFalse(state.state.value.hasUpdate)
    }

    @Test
    fun `a failing seed leaves the version unknown rather than guessing`() = runTest {
        val state = InstallationState(seed = { throw IllegalStateException("offline") })
        state.start()
        assertNull(state.state.value.version)
    }

    @Test
    fun `an announced update is what the notification is built from`() = runTest {
        val state = InstallationState(seed = { "2.0.18" })
        state.start()
        // The server restarted on a newer version, and then announced one further ahead.
        assertTrue(state.apply(event("installation.updated", "2.0.19")))
        assertTrue(state.apply(event("installation.update-available", "2.0.20")))
        assertEquals("2.0.19", state.state.value.version)
        assertEquals("2.0.20", state.state.value.updateAvailable)
        assertTrue(state.state.value.hasUpdate)
    }

    @Test
    fun `an announcement for the version already running is not an update`() = runTest {
        val state = InstallationState(seed = { "2.0.19" })
        state.start()
        state.apply(event("installation.update-available", "2.0.19"))
        assertFalse(state.state.value.hasUpdate)
    }

    @Test
    fun `a repeated event changes nothing, so a resync does not re-announce`() = runTest {
        val state = InstallationState(seed = { "2.0.18" })
        state.start()
        state.apply(event("installation.update-available", "2.0.20"))
        assertFalse(state.apply(event("installation.update-available", "2.0.20")))
    }

    @Test
    fun `another event is not this one's business`() {
        val state = InstallationState()
        assertFalse(state.apply(event("server.connected", "2.0.20")))
        assertEquals(InstallationState.Installation(), state.state.value)
    }

    @Test
    fun `forgetting a server forgets its version`() = runTest {
        val state = InstallationState(seed = { "2.0.18" })
        state.start()
        state.apply(event("installation.update-available", "2.0.20"))
        state.clear()
        assertEquals(InstallationState.Installation(), state.state.value)
    }

    private fun event(type: String, version: String) = Event(
        id = "evt_1",
        type = type,
        payload = when (type) {
            "installation.updated" -> InstallationUpdated(version)
            "installation.update-available" -> InstallationUpdateAvailable(version)
            else -> EventPayload.Unknown(type = type, raw = JsonObject(emptyMap()))
        },
    )
}
