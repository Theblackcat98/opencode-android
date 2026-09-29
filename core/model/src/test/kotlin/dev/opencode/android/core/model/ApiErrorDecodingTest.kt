package dev.opencode.android.core.model

import dev.opencode.android.core.model.json.OpenCodeJson
import dev.opencode.android.core.testing.Fixtures
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiErrorDecodingTest {

    @Test
    fun decodesSessionNotFoundErrorFixture() {
        val raw = Fixtures.raw("errors/session-not-found.json")
        val error = OpenCodeJson.decodeFromString<ApiError>(raw)

        assertTrue(error is ApiError.SessionNotFound)
        val sessionNotFound = error as ApiError.SessionNotFound
        assertEquals("ses_missing0000000000000000", sessionNotFound.sessionID)
        assertEquals("Session not found: ses_missing0000000000000000", sessionNotFound.message)

        val reEncoded = OpenCodeJson.encodeToString(ApiError.serializer(), error)
        val decodedAgain = OpenCodeJson.decodeFromString<ApiError>(reEncoded)
        assertEquals(error, decodedAgain)
    }

    @Test
    fun decodesMessageNotFoundErrorFixture() {
        val raw = Fixtures.raw("errors/message-not-found.json")
        val error = OpenCodeJson.decodeFromString<ApiError>(raw)

        assertTrue(error is ApiError.MessageNotFound)
        val messageNotFound = error as ApiError.MessageNotFound
        assertEquals("ses_fixture_misc", messageNotFound.sessionID)
        assertEquals("msg_missing0000000000000000", messageNotFound.messageID)
        assertEquals("Message not found: msg_missing0000000000000000", messageNotFound.message)

        val reEncoded = OpenCodeJson.encodeToString(ApiError.serializer(), error)
        val decodedAgain = OpenCodeJson.decodeFromString<ApiError>(reEncoded)
        assertEquals(error, decodedAgain)
    }

    @Test
    fun decodesUnauthorizedErrorFixture() {
        val raw = Fixtures.raw("errors/unauthorized.json")
        val jsonElement = Fixtures.json("errors/unauthorized.json")
        val body = jsonElement.jsonObject["body"]?.toString() ?: error("Missing body")
        val error = OpenCodeJson.decodeFromString<ApiError>(body)

        assertTrue(error is ApiError.Unauthorized)
        val unauthorized = error as ApiError.Unauthorized
        assertEquals("Authentication required", unauthorized.message)

        val reEncoded = OpenCodeJson.encodeToString(ApiError.serializer(), error)
        val decodedAgain = OpenCodeJson.decodeFromString<ApiError>(reEncoded)
        assertEquals(error, decodedAgain)
    }

    @Test
    fun decodesVariousKnownErrorTypes() {
        val cases = listOf(
            """{"_tag":"ForbiddenError","message":"Forbidden access"}""" to ApiError.Forbidden("Forbidden access"),
            """{"_tag":"InvalidRequestError","message":"Bad field","field":"title"}""" to
                ApiError.InvalidRequest("Bad field", field = "title"),
            """{"_tag":"ConflictError","message":"ID exists","resource":"session"}""" to
                ApiError.Conflict("ID exists", resource = "session"),
            """{"_tag":"SessionBusyError","message":"Session is busy","sessionID":"ses_1"}""" to
                ApiError.SessionBusy("Session is busy", sessionID = "ses_1"),
            """{"_tag":"FileNotFoundError","message":"Not found","path":"/test/a.txt"}""" to
                ApiError.FileNotFound("Not found", path = "/test/a.txt"),
            """{"_tag":"AgentNotFoundError","message":"Agent unknown","agentID":"custom"}""" to
                ApiError.AgentNotFound("Agent unknown", agentID = "custom"),
            """{"_tag":"CommandExecutionError","message":"Failed","command":"ls"}""" to
                ApiError.CommandExecution("Failed", command = "ls"),
        )

        for ((json, expected) in cases) {
            val decoded = OpenCodeJson.decodeFromString<ApiError>(json)
            assertEquals(expected, decoded)
            val reEncoded = OpenCodeJson.encodeToString(ApiError.serializer(), decoded)
            val decodedAgain = OpenCodeJson.decodeFromString<ApiError>(reEncoded)
            assertEquals(expected, decodedAgain)
        }
    }

    @Test
    fun fallsBackToUnrecognizedOnUnknownTag() {
        val raw = """{"_tag":"CosmicRayInterferenceError","message":"Bit flip detected","cpuCore":3}"""
        val error = OpenCodeJson.decodeFromString<ApiError>(raw)

        assertTrue(error is ApiError.Unrecognized)
        val unrecognized = error as ApiError.Unrecognized
        assertEquals("Bit flip detected", unrecognized.message)
        assertEquals("CosmicRayInterferenceError", unrecognized.discriminator)

        val reEncoded = OpenCodeJson.encodeToString(ApiError.serializer(), error)
        val decodedAgain = OpenCodeJson.decodeFromString<ApiError>(reEncoded)
        assertEquals(error, decodedAgain)
    }
}
