package dev.opencode.android.core.model

import dev.opencode.android.core.model.event.Event
import dev.opencode.android.core.model.event.EventPayload
import dev.opencode.android.core.model.event.EventTypes
import dev.opencode.android.core.model.json.OpenCodeJson
import dev.opencode.android.core.testing.Fixtures
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EventDecodingTest {

    @Test
    fun decodesAllRecordedEventsWithoutLoss() {
        val lines = Fixtures.events()
        assertFalse("Fixture events.jsonl was empty", lines.isEmpty())

        val typeCounts = mutableMapOf<String, Int>()

        for ((index, line) in lines.withIndex()) {
            val event = Event.decode(line)
            assertNotNull("Event at line $index missing id", event.id)
            assertNotNull("Event at line $index missing type", event.type)

            // None of the recorded events from a 2.0.18 server should be Unknown
            assertFalse(
                "Event at line $index with type ${event.type} decoded to Unknown",
                event.payload is EventPayload.Unknown,
            )

            typeCounts[event.type] = (typeCounts[event.type] ?: 0) + 1

            // Round trip
            val reEncoded = OpenCodeJson.encodeToString(Event.serializer(), event)
            val decodedAgain = Event.decode(reEncoded)
            assertEquals("Round trip failed for event at line $index (${event.type})", event, decodedAgain)
        }

        // Verify key events appeared
        assertTrue(typeCounts.containsKey("server.connected"))
        assertTrue(typeCounts.containsKey("session.created"))
        assertTrue(typeCounts.containsKey("session.step.started"))
        assertTrue(typeCounts.containsKey("session.text.started"))
        assertTrue(typeCounts.containsKey("session.text.delta"))
        assertTrue(typeCounts.containsKey("session.text.ended"))
        assertTrue(typeCounts.containsKey("session.execution.succeeded"))
    }

    @Test
    fun decodesUnknownEventTypeToUnknownPayload() {
        val raw = """
            {
                "id": "evt_mystery_001",
                "type": "teleportation.initiated",
                "created": 1790567234829,
                "data": {"destination": "Mars", "coords": [12.3, 45.6]}
            }
        """.trimIndent()

        val event = Event.decode(raw)
        assertEquals("evt_mystery_001", event.id)
        assertEquals("teleportation.initiated", event.type)
        assertTrue(event.payload is EventPayload.Unknown)

        val unknown = event.payload as EventPayload.Unknown
        assertEquals("teleportation.initiated", unknown.type)
        assertEquals("Mars", unknown.raw["destination"]?.jsonPrimitive?.content)

        // Round trip preserves raw payload
        val reEncoded = OpenCodeJson.encodeToString(Event.serializer(), event)
        val decodedAgain = Event.decode(reEncoded)
        assertEquals(event, decodedAgain)
    }

    @Test
    fun decodesRpcEvent() {
        val raw = """
            {
                "id": "evt_rpc_1",
                "type": "rpc.plugin_git.statusChanged",
                "created": 1790567234829,
                "data": {"clean": true}
            }
        """.trimIndent()

        val event = Event.decode(raw)
        assertTrue(event.payload is EventPayload.Rpc)
        val rpc = event.payload as EventPayload.Rpc
        assertEquals("plugin_git", rpc.rpcID)
        assertEquals("statusChanged", rpc.event)
        assertEquals("true", rpc.data["clean"]?.jsonPrimitive?.content)
    }

    @Test
    fun verifiesEventTypesRegistryMatchesEventsJson() {
        val candidates = listOf(
            File("api/opencode-2.0.x/events.json"),
            File("../../api/opencode-2.0.x/events.json"),
            File("../api/opencode-2.0.x/events.json"),
        )
        val eventsFile = candidates.firstOrNull { it.exists() }
        assertNotNull("events.json file should exist", eventsFile)

        val json = Json.parseToJsonElement(eventsFile!!.readText()).jsonObject
        val eventsArray = json["events"]?.jsonArray ?: error("events array missing")
        val expectedTypes = eventsArray.map { it.jsonObject["type"]!!.jsonPrimitive.content }.toSet()

        // EventTypes.knownTypes should cover every event type listed in events.json
        val knownTypes = EventTypes.knownTypes
        for (expected in expectedTypes) {
            assertTrue(
                "EventTypes.knownTypes missing '$expected' declared in events.json",
                knownTypes.contains(expected),
            )
        }
        assertEquals(94, knownTypes.size)
    }
}
