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
        assertEquals(listOf("http://127.0.0.1:4196"), info.urls)
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
