package dev.opencode.android.core.model

import dev.opencode.android.core.model.json.OpenCodeJson
import dev.opencode.android.core.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerInfoDecodingTest {

    @Test
    fun decodesServerInfoFixture() {
        val raw = Fixtures.raw("info.json")
        val info = OpenCodeJson.decodeFromString<ServerInfo>(raw)

        assertEquals("2.0.18", info.version)
        assertEquals(2, info.majorVersion)
        assertTrue(info.pid > 0)
        // The port and host depend on the machine the fixtures were recorded on; the contract is
        // that the server names at least one URL it is reachable at, and that it is a loopback URL
        // for a server started by scripts/dev-server.sh.
        assertTrue("the server must report how it is reachable", info.urls.isNotEmpty())
        assertTrue(
            "expected a loopback URL, got ${info.urls}",
            info.urls.all { it.startsWith("http://127.0.0.1:") || it.startsWith("http://localhost:") },
        )
        assertEquals("/tmp/opencode", info.paths.tmp)
    }

    @Test
    fun roundTripsServerInfo() {
        val raw = Fixtures.raw("info.json")
        val info = OpenCodeJson.decodeFromString<ServerInfo>(raw)
        val encoded = OpenCodeJson.encodeToString(ServerInfo.serializer(), info)
        val decodedAgain = OpenCodeJson.decodeFromString<ServerInfo>(encoded)

        assertEquals(info, decodedAgain)
    }

    @Test
    fun decodesPairingModels() {
        val codeJson = """{"code":"pair_12345","expires_in":300}"""
        val pairingCode = OpenCodeJson.decodeFromString<PairingCode>(codeJson)
        assertEquals("pair_12345", pairingCode.code)
        assertEquals(300L, pairingCode.expiresIn)

        val sessionJson = """{"token":"tok_secret_abc"}"""
        val session = OpenCodeJson.decodeFromString<PairingSession>(sessionJson)
        assertEquals("tok_secret_abc", session.token)
    }
}
