package dev.opencode.android.core.data

import dev.opencode.android.core.data.repository.ServerHealth
import dev.opencode.android.core.data.repository.ServerProfile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerProfileTest {

    @Test
    fun identifiesCleartextLanConnections() {
        val lanServer = ServerProfile(
            id = "1",
            name = "LAN Box",
            baseUrl = "http://192.168.1.100:4096",
            health = ServerHealth.CONNECTED,
        )
        assertTrue(lanServer.isCleartext)

        val tailscaleServer = ServerProfile(
            id = "2",
            name = "Tailscale Box",
            baseUrl = "http://100.64.0.1:4096",
            health = ServerHealth.CONNECTED,
        )
        assertTrue(tailscaleServer.isCleartext)
    }

    @Test
    fun identifiesLoopbackConnectionsAsNotCleartextBadge() {
        val localhost = ServerProfile(
            id = "1",
            name = "Local",
            baseUrl = "http://localhost:4096",
            health = ServerHealth.CONNECTED,
        )
        assertFalse(localhost.isCleartext)

        val loopbackIp = ServerProfile(
            id = "2",
            name = "127",
            baseUrl = "http://127.0.0.1:4096",
            health = ServerHealth.CONNECTED,
        )
        assertFalse(loopbackIp.isCleartext)

        val emulatorAlias = ServerProfile(
            id = "3",
            name = "Emulator Loopback",
            baseUrl = "http://10.0.2.2:4096",
            health = ServerHealth.CONNECTED,
        )
        assertFalse(emulatorAlias.isCleartext)
    }

    @Test
    fun identifiesHttpsConnectionsAsNotCleartext() {
        val httpsServer = ServerProfile(
            id = "1",
            name = "Secure",
            baseUrl = "https://opencode.example.com",
            health = ServerHealth.CONNECTED,
        )
        assertFalse(httpsServer.isCleartext)
    }
}
