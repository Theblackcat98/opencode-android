package dev.opencode.android.core.testing.integration

import dev.opencode.android.core.model.ServerInfo
import dev.opencode.android.core.model.json.OpenCodeJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class DevServerIntegrationTest {

    @Test
    fun verifiesServerInfoFromLiveDevServer() {
        assumeTrue(
            "Dev server is not configured (run 'scripts/dev-server.sh start')",
            DevServerHarness.isAvailable,
        )

        val response = DevServerHarness.execute("/api/info")
        assertTrue("Expected 200 OK from /api/info, got ${response.code}", response.isSuccessful)

        val body = response.body?.string()
        assertNotNull("Response body must not be null", body)

        val info = OpenCodeJson.decodeFromString<ServerInfo>(body!!)
        assertEquals(2, info.majorVersion)
        assertTrue(info.pid > 0)
        assertFalse("Server URLs list must not be empty", info.urls.isEmpty())
        assertTrue("Paths tmp directory must not be blank", info.paths.tmp.isNotBlank())
    }
}
