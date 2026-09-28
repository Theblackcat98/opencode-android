package dev.opencode.android.core.model.event

import dev.opencode.android.core.model.LocationRef
import dev.opencode.android.core.model.json.OpenCodeJson
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * One frame of the global event stream `GET /api/event` (features doc §36):
 * `{id, created, type, location?, data, metadata?, durable?}`.
 *
 * [payload] is the typed `data`, chosen by [type]. Types this client does not know decode to
 * [EventPayload.Unknown], and `rpc.<rpcID>.<event>` types decode to [EventPayload.Rpc].
 */
@Serializable(with = EventSerializer::class)
data class Event(
    val id: String,
    val type: String,
    /** Epoch milliseconds. Absent on `server.connected`. */
    val created: Long? = null,
    val location: LocationRef? = null,
    val metadata: JsonObject? = null,
    /** Present on durable events, which change the projected history. */
    val durable: Durable? = null,
    val payload: EventPayload,
) {
    /** The position of a durable event in its aggregate's (usually a session's) log. */
    @Serializable
    data class Durable(
        val aggregateID: String,
        val seq: Long,
        val version: Int,
    )

    companion object {
        /** Decodes one SSE `data:` line. */
        fun decode(json: String): Event = OpenCodeJson.decodeFromString(serializer(), json)
    }
}

@Serializable
private data class EnvelopeFields(
    val id: String,
    val type: String,
    val created: Long? = null,
    val location: LocationRef? = null,
    val metadata: JsonObject? = null,
    val durable: Event.Durable? = null,
)

internal object EventSerializer : KSerializer<Event> {
    override val descriptor: SerialDescriptor =
        SerialDescriptor("dev.opencode.android.Event", JsonObject.serializer().descriptor)

    override fun deserialize(decoder: Decoder): Event {
        val input = decoder as? JsonDecoder ?: throw SerializationException("Event can only be decoded from JSON")
        val obj = input.decodeJsonElement().jsonObject
        val data: JsonElement = obj["data"] ?: JsonObject(emptyMap())
        val envelope = input.json.decodeFromJsonElement(EnvelopeFields.serializer(), JsonObject(obj - "data"))
        val payload = EventTypes.decodePayload(input.json, envelope.type, data)
        return Event(
            id = envelope.id,
            type = envelope.type,
            created = envelope.created,
            location = envelope.location,
            metadata = envelope.metadata,
            durable = envelope.durable,
            payload = payload,
        )
    }

    override fun serialize(encoder: Encoder, value: Event) {
        val output = encoder as? JsonEncoder ?: throw SerializationException("Event can only be encoded to JSON")
        val envelope = output.json.encodeToJsonElement(
            EnvelopeFields.serializer(),
            EnvelopeFields(value.id, value.type, value.created, value.location, value.metadata, value.durable),
        ).jsonObject
        val data = EventTypes.encodePayload(output.json, value.payload)
        output.encodeJsonElement(JsonObject(envelope + ("data" to data)))
    }
}

/** The `type` of a raw event object, without decoding the rest. */
fun eventTypeOf(raw: JsonObject): String? = (raw["type"] as? JsonPrimitive)?.contentOrNull
