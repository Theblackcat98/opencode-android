package dev.opencode.android.core.model

import dev.opencode.android.core.model.json.DiscriminatedUnionSerializer
import dev.opencode.android.core.model.json.UnknownVariant
import dev.opencode.android.core.model.json.variant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Pending work in a session's durable inbox, as carried by `session.inbox.enqueued`
 * (schema `Session.Inbox.Item`). The REST form (`GET /api/session/{id}/inbox`) adds `id`, `sessionID` and `time`.
 */
@Serializable(with = InboxItemSerializer::class)
sealed interface InboxItem {
    val delivery: Delivery

    @Serializable
    data class User(val payload: UserPromptPayload, override val delivery: Delivery) : InboxItem

    @Serializable
    data class Synthetic(val payload: SyntheticPayload, override val delivery: Delivery) : InboxItem

    @Serializable
    data class Compaction(val payload: JsonObject, override val delivery: Delivery) : InboxItem

    @Serializable
    data class Move(val payload: MovePayload, override val delivery: Delivery) : InboxItem

    data class Unknown(override val discriminator: String?, override val raw: JsonObject) :
        InboxItem,
        UnknownVariant {
        override val delivery: Delivery get() = Delivery.Steer
    }

    @Serializable
    data class SyntheticPayload(
        val text: String,
        val description: String? = null,
        val metadata: Map<String, JsonElement>? = null,
    )

    @Serializable
    data class MovePayload(
        val location: LocationRef,
        val projectID: String,
        val subpath: String? = null,
    )
}

internal object InboxItemSerializer : DiscriminatedUnionSerializer<InboxItem>(
    serialName = "dev.opencode.android.InboxItem",
    unknown = InboxItem::Unknown,
    variants = listOf(
        variant("user", InboxItem.User.serializer()),
        variant("synthetic", InboxItem.Synthetic.serializer()),
        variant("compaction", InboxItem.Compaction.serializer()),
        variant("move", InboxItem.Move.serializer()),
    ),
)
