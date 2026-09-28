package dev.opencode.android.core.model

import dev.opencode.android.core.model.json.OpenCodeJson
import dev.opencode.android.core.testing.Fixtures
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionMessageDecodingTest {

    private val allScenarios = listOf(
        "text",
        "reasoning",
        "shell",
        "edit",
        "question",
        "subagent",
        "error",
        "long",
        "misc",
    )

    @Test
    fun decodesAllMessageScenarios() {
        val seenTypes = mutableSetOf<String>()

        for (scenario in allScenarios) {
            val raw = Fixtures.raw("messages-$scenario.json")
            val paged = OpenCodeJson.decodeFromString<Paged<SessionMessage>>(raw)

            assertFalse("Scenario $scenario had no messages", paged.data.isEmpty())
            for (message in paged.data) {
                assertNotNull(message.id)
                assertTrue(message.created > 0)
                seenTypes.add(message::class.simpleName ?: "")
            }

            // Test round-trip encoding
            val encoded = OpenCodeJson.encodeToString(
                Paged.serializer(SessionMessage.serializer()),
                paged,
            )
            val decodedAgain = OpenCodeJson.decodeFromString<Paged<SessionMessage>>(encoded)
            assertEquals("Round-trip failed for scenario $scenario", paged, decodedAgain)
        }

        // Verify we encountered the key message types in our recorded fixtures
        assertTrue(seenTypes.contains("User"))
        assertTrue(seenTypes.contains("Assistant"))
        assertTrue(seenTypes.contains("Idle"))
        assertTrue(seenTypes.contains("Synthetic"))
        assertTrue(seenTypes.contains("Shell"))
        assertTrue(seenTypes.contains("ModelSwitched"))
        assertTrue(seenTypes.contains("Compaction"))
    }

    @Test
    fun fallsBackToUnknownOnUnrecognizedMessageType() {
        val unknownJson = """
            {
                "id": "msg_future_999",
                "type": "future-quantum-turn",
                "time": {"created": 1790567452813},
                "quantumState": "superposed"
            }
        """.trimIndent()

        val decoded = OpenCodeJson.decodeFromString<SessionMessage>(unknownJson)
        assertTrue(decoded is SessionMessage.Unknown)
        val unknown = decoded as SessionMessage.Unknown
        assertEquals("msg_future_999", unknown.id)
        assertEquals("future-quantum-turn", unknown.discriminator)
        assertEquals(1790567452813L, unknown.created)

        // Round-trip preserves the unknown payload verbatim
        val reEncoded = OpenCodeJson.encodeToString(SessionMessage.serializer(), unknown)
        val decodedAgain = OpenCodeJson.decodeFromString<SessionMessage>(reEncoded)
        assertEquals(unknown, decodedAgain)
    }

    @Test
    fun decodesAssistantPartsAccurately() {
        val raw = Fixtures.raw("messages-reasoning.json")
        val paged = OpenCodeJson.decodeFromString<Paged<SessionMessage>>(raw)
        val assistant = paged.data.filterIsInstance<SessionMessage.Assistant>().firstOrNull()

        assertNotNull(assistant)
        assertEquals("build", assistant?.agent)
        assertEquals("reasoning", assistant?.model?.id)

        // Assistant should contain text or reasoning content
        val hasReasoning = assistant?.content?.any { it is AssistantContent.Reasoning } == true
        assertTrue(hasReasoning)
    }
}
