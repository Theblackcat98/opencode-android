package dev.opencode.android.core.model

import dev.opencode.android.core.model.json.DiscriminatedUnionSerializer
import dev.opencode.android.core.model.json.UnknownVariant
import dev.opencode.android.core.model.json.variant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** A session's live status, from `session.status` events (features doc §4.3). */
@Serializable(with = SessionStatusSerializer::class)
sealed interface SessionStatus {
    @Serializable
    data object Idle : SessionStatus

    @Serializable
    data object Busy : SessionStatus

    /** A provider call failed and will be retried at [next] (epoch milliseconds). */
    @Serializable
    data class Retry(
        val attempt: Int,
        val message: String,
        val next: Long,
        val action: Action? = null,
    ) : SessionStatus {
        /** A provider-supplied call to action, such as "usage exceeded, upgrade". */
        @Serializable
        data class Action(
            val reason: String,
            val provider: String,
            val title: String,
            val message: String,
            val label: String,
            val link: String? = null,
        )
    }

    data class Unknown(override val discriminator: String?, override val raw: JsonObject) :
        SessionStatus,
        UnknownVariant
}

internal object SessionStatusSerializer : DiscriminatedUnionSerializer<SessionStatus>(
    serialName = "dev.opencode.android.SessionStatus",
    unknown = SessionStatus::Unknown,
    variants = listOf(
        variant("idle", SessionStatus.Idle.serializer()),
        variant("busy", SessionStatus.Busy.serializer()),
        variant("retry", SessionStatus.Retry.serializer()),
    ),
)
