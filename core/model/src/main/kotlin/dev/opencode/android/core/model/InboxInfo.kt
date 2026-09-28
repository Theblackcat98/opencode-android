package dev.opencode.android.core.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * One pending item of a session's durable inbox, as `GET /api/session/{id}/inbox` returns it
 * (schema `Session.Inbox.*`).
 *
 * The REST form is the event form ([InboxItem]) plus `id`, `sessionID` and `time`, all in one flat
 * object discriminated by `type`. Decoding therefore runs the union first and then reads the three
 * envelope fields, which is what the serializer below does.
 */
@Serializable(with = SessionInboxInfoSerializer::class)
data class SessionInboxInfo(
    val id: String,
    val sessionID: String,
    val time: SessionMessage.CreatedTime,
    val item: InboxItem,
) {
    val created: Long get() = time.created
}

internal object SessionInboxInfoSerializer : KSerializer<SessionInboxInfo> {
    override val descriptor: SerialDescriptor =
        SerialDescriptor("dev.opencode.android.SessionInboxInfo", JsonObject.serializer().descriptor)

    override fun deserialize(decoder: Decoder): SessionInboxInfo {
        val input = decoder as? JsonDecoder
            ?: throw SerializationException("SessionInboxInfo can only be decoded from JSON")
        val obj = input.decodeJsonElement().jsonObject
        val item = input.json.decodeFromJsonElement(InboxItemSerializer, obj)
        val id = (obj["id"] as? kotlinx.serialization.json.JsonPrimitive)?.content
            ?: throw SerializationException("SessionInboxInfo has no id: $obj")
        val sessionID = (obj["sessionID"] as? kotlinx.serialization.json.JsonPrimitive)?.content
            ?: throw SerializationException("SessionInboxInfo has no sessionID: $obj")
        val created = (obj["time"] as? JsonObject)
            ?.get("created") as? kotlinx.serialization.json.JsonPrimitive
        return SessionInboxInfo(
            id = id,
            sessionID = sessionID,
            time = SessionMessage.CreatedTime(created?.content?.toLongOrNull() ?: 0L),
            item = item,
        )
    }

    override fun serialize(encoder: Encoder, value: SessionInboxInfo) {
        val output = encoder as? JsonEncoder
            ?: throw SerializationException("SessionInboxInfo can only be encoded to JSON")
        val body = output.json.encodeToJsonElement(InboxItemSerializer, value.item).jsonObject
        val merged = JsonObject(
            body +
                ("id" to kotlinx.serialization.json.JsonPrimitive(value.id)) +
                ("sessionID" to kotlinx.serialization.json.JsonPrimitive(value.sessionID)) +
                ("time" to output.json.encodeToJsonElement(SessionMessage.CreatedTime.serializer(), value.time))
        )
        output.encodeJsonElement(merged)
    }
}

/**
 * `GET /api/session/active`: the sessions with a live execution, keyed by session id
 * (schema `SessionActive`).
 */
@Serializable(with = ActiveSessionMapSerializer::class)
data class ActiveSessionMap(
    val running: Set<String>,
) {
    companion object {
        val Empty = ActiveSessionMap(emptySet())
    }
}

/**
 * Decodes the `data` object of `session.active`, whose values are `{type}` objects.
 *
 * Only `type = running` means a session is executing, which is the state the running badge and
 * the follow mode read; anything else the server invents is treated as not running, because the
 * alternative (showing a running badge that never clears) is worse.
 */
internal object ActiveSessionMapSerializer : KSerializer<ActiveSessionMap> {
    override val descriptor: SerialDescriptor =
        SerialDescriptor("dev.opencode.android.ActiveSessionMap", JsonObject.serializer().descriptor)

    override fun deserialize(decoder: Decoder): ActiveSessionMap {
        val input = decoder as? JsonDecoder
            ?: throw SerializationException("ActiveSessionMap can only be decoded from JSON")
        val obj = input.decodeJsonElement().jsonObject
        val running = obj.entries
            .filter { (_, value) ->
                val type = (value as? JsonObject)?.get("type")
                    as? kotlinx.serialization.json.JsonPrimitive
                type?.content == SessionActive.RUNNING
            }
            .map { (id, _) -> id }
            .toSet()
        return ActiveSessionMap(running)
    }

    override fun serialize(encoder: Encoder, value: ActiveSessionMap) {
        val output = encoder as? JsonEncoder
            ?: throw SerializationException("ActiveSessionMap can only be encoded to JSON")
        val running = output.json.encodeToJsonElement(
            SessionActive.serializer(),
            SessionActive(SessionActive.RUNNING),
        )
        output.encodeJsonElement(JsonObject(value.running.sorted().associateWith { running }))
    }
}
