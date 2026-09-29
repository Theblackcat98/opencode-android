package dev.opencode.android.core.model

import dev.opencode.android.core.model.event.Event
import dev.opencode.android.core.model.event.EventPayload
import dev.opencode.android.core.model.json.OpenCodeJson
import dev.opencode.android.core.testing.Fixtures
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every one of the 93 named event types decodes to its own payload class and survives a round trip.
 *
 * The recorded stream in [EventDecodingTest] is real server output, and it only covers the types a
 * normal session produces. This covers the rest — the failure, retry, revert, adoption and TUI
 * frames — from a corpus generated out of the vendored TypeScript declarations, so a binding that
 * does not match the published type fails here rather than on a user's phone.
 */
class EventPayloadCoverageTest {

    private val eventsJson: File by lazy {
        listOf(
            File("api/opencode-2.0.x/events.json"),
            File("../../api/opencode-2.0.x/events.json"),
            File("../api/opencode-2.0.x/events.json"),
        ).firstOrNull { it.exists() } ?: error("events.json not found from ${File(".").absolutePath}")
    }

    @Test
    fun everyNamedTypeDecodesToItsOwnPayload() {
        val declared = declaredTypes()
        val corpus = Fixtures.eventPayloads()
        assertEquals("the corpus must cover every declared type", declared.size, corpus.size)

        val seen = mutableSetOf<String>()
        for (line in corpus) {
            val event = Event.decode(line)
            assertFalse(
                "${event.type} decoded to Unknown, so its binding does not match the published type",
                event.payload is EventPayload.Unknown,
            )
            assertTrue(
                "${event.type} is not one of the declared types",
                event.type in declared,
            )
            assertTrue("${event.type} appears twice in the corpus", seen.add(event.type))
        }
        assertEquals("the corpus must name each declared type exactly once", declared, seen)
    }

    @Test
    fun everyNamedTypeRoundTrips() {
        for (line in Fixtures.eventPayloads()) {
            val event = Event.decode(line)
            val encoded = OpenCodeJson.encodeToString(Event.serializer(), event)
            assertEquals("${event.type} did not survive a round trip", event, Event.decode(encoded))
        }
    }

    /**
     * Decoding is not enough on its own. `ignoreUnknownKeys` means a model that drops a field the
     * server sends still decodes cleanly and still round-trips, so a missing property is invisible
     * to every other test here — the app just never shows that value.
     *
     * So this compares the keys: every member of the published `data` must still be there after the
     * event has been decoded and re-encoded. Dropping one is what this catches.
     */
    @Test
    fun everyPublishedDataMemberSurvivesDecoding() {
        for (line in Fixtures.eventPayloads()) {
            val event = Event.decode(line)
            val sent = (Json.parseToJsonElement(line) as JsonObject)["data"] as JsonObject
            val kept = (Json.parseToJsonElement(OpenCodeJson.encodeToString(Event.serializer(), event)) as JsonObject)
                .let { (it["data"] as JsonObject) }
            val dropped = sent.keys - kept.keys
            assertTrue(
                "${event.type} dropped ${dropped.sorted()}, which the server sends and the model cannot read",
                dropped.isEmpty(),
            )
        }
    }

    /**
     * The location is what tells a location-scoped event which checkout it belongs to, so a payload
     * that decodes without one would make an invalidation fire against every open directory.
     */
    @Test
    fun everyPayloadCarriesItsLocation() {
        for (line in Fixtures.eventPayloads()) {
            val event = Event.decode(line)
            assertNotNull("${event.type} decoded without a location", event.location?.directory)
        }
    }

    @Test
    fun theRpcFamilyIsNotInTheNamedCorpus() {
        val types = Fixtures.eventPayloads().map { Event.decode(it).type }
        assertFalse(types.any { it.startsWith("rpc.") })
    }

    private fun declaredTypes(): Set<String> {
        val json = Json.parseToJsonElement(eventsJson.readText()).jsonObject
        val events = json["events"] as? JsonArray ?: error("no events array")
        return events.map { it.jsonObject["type"]!!.jsonPrimitive.content }
            .filterNot { it.startsWith("rpc.") }
            .toSet()
    }
}
