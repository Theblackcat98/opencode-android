package dev.opencode.android.core.model

import dev.opencode.android.core.model.json.OpenCodeJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `session.active` answers "what is running right now", which is the running badge and the follow
 * mode. Anything that is not `type = running` must therefore read as not running: a badge that
 * never clears is worse than one that is briefly late.
 */
class ActiveSessionMapDecodingTest {

    @Test
    fun `decodes the running ids and ignores anything else`() {
        val decoded = OpenCodeJson.decodeFromString(
            DataResponse.serializer(ActiveSessionMap.serializer()),
            """{"data":{"ses_a":{"type":"running"},"ses_b":{"type":"idle"},"ses_c":{}}}""",
        )
        assertEquals(setOf("ses_a"), decoded.data.running)
    }

    @Test
    fun `an empty map decodes to no running sessions`() {
        val decoded = OpenCodeJson.decodeFromString(
            DataResponse.serializer(ActiveSessionMap.serializer()),
            """{"data":{}}""",
        )
        assertTrue(decoded.data.running.isEmpty())
    }

    @Test
    fun `round trips`() {
        val encoded = OpenCodeJson.encodeToString(
            DataResponse.serializer(ActiveSessionMap.serializer()),
            DataResponse(ActiveSessionMap(setOf("ses_a"))),
        )
        assertEquals("""{"data":{"ses_a":{"type":"running"}}}""", encoded)
    }
}
