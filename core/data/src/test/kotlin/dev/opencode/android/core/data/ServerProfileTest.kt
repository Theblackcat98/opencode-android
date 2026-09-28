package dev.opencode.android.core.data

import dev.opencode.android.core.data.repository.ServerHealth
import dev.opencode.android.core.data.repository.ServerProfile
import dev.opencode.android.core.network.toServerBaseUrl
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerProfileTest {

    @Test
    fun identifiesCleartextLanConnections() {
        val lanServer = profile("http://192.168.1.100:4096")
        assertTrue(lanServer.isCleartext)

        val tailscaleServer = profile("http://100.64.0.1:4096")
        assertTrue("A Tailscale address is still cleartext", tailscaleServer.isCleartext)

        val namedHost = profile("http://box.local:4096")
        assertTrue(namedHost.isCleartext)
    }

    @Test
    fun identifiesLoopbackConnectionsAsNotCleartext() {
        assertFalse(profile("http://localhost:4096").isCleartext)
        assertFalse(profile("http://127.0.0.1:4096").isCleartext)
        assertFalse(profile("http://[::1]:4096").isCleartext)
        assertFalse(profile("http://10.0.2.2:4096").isCleartext)
    }

    @Test
    fun identifiesHttpsConnectionsAsNotCleartext() {
        assertFalse(profile("https://opencode.example.com").isCleartext)
    }

    @Test
    fun anUnparsableAddressCountsAsCleartextSoItIsNeverShownAsSafe() {
        assertTrue(profile("not a url").isCleartext)
        assertFalse(profile("not a url").hasValidAddress)
    }

    @Test
    fun reportsTheHostAndWhetherTheAddressCanBeUsed() {
        assertTrue(profile("http://192.168.1.100:4096/prefix").host == "192.168.1.100")
        assertTrue(profile("http://192.168.1.100:4096/prefix").hasValidAddress)
        assertFalse(profile("").hasValidAddress)
    }

    @Test
    fun aServerWithoutACredentialIsStillAValidProfile() {
        val server = ServerProfile(
            id = "1",
            name = "No auth",
            baseUrl = "http://192.168.1.100:4096",
            health = ServerHealth.REAUTH_REQUIRED,
            hasCredential = false,
        )
        assertFalse(server.hasCredential)
        assertTrue(server.baseUrl.toServerBaseUrl().startsWith("http://192.168.1.100:4096"))
    }

    private fun profile(baseUrl: String) = ServerProfile(
        id = "1",
        name = "Test",
        baseUrl = baseUrl,
        health = ServerHealth.CONNECTED,
    )
}
