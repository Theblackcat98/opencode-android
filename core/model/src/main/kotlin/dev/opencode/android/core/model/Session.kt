package dev.opencode.android.core.model

import dev.opencode.android.core.model.json.DiscriminatedUnionSerializer
import dev.opencode.android.core.model.json.UnknownVariant
import dev.opencode.android.core.model.json.variant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Free-form session metadata (schema `Session.Metadata`). */
typealias SessionMetadata = Map<String, JsonElement>

/** A durable conversation (schema `Session.Info`, features doc §4.1). */
@Serializable
data class SessionInfo(
    val id: String,
    /** Set for child sessions: subagents and background commands. */
    val parentID: String? = null,
    val fork: Fork? = null,
    val projectID: String,
    val agent: String? = null,
    val model: ModelRef? = null,
    /** Accumulated cost in USD. */
    val cost: Double,
    val tokens: TokenUsage,
    val outcome: Outcome? = null,
    val time: Time,
    val title: String? = null,
    val subpath: String? = null,
    val metadata: SessionMetadata? = null,
    val permissions: List<PermissionRule>? = null,
    val revert: SessionRevert? = null,
    val location: LocationPublicRef,
) {
    @Serializable
    data class Time(
        val created: Long,
        val updated: Long,
        val idle: Long? = null,
        val viewed: Long? = null,
        val archived: Long? = null,
    )

    @Serializable
    data class Fork(
        val sessionID: String,
        val boundary: ForkBoundary,
    )

    /** A session is unread when it went idle after the user last viewed it. */
    val isUnread: Boolean
        get() = time.idle != null && (time.viewed == null || time.idle > time.viewed)
}

/** Where a fork was cut (schema `Session.ForkBoundary`). */
@Serializable(with = ForkBoundarySerializer::class)
sealed interface ForkBoundary {
    @Serializable
    data class Before(val messageID: String) : ForkBoundary

    @Serializable
    data class Through(val messageID: String) : ForkBoundary

    data class Unknown(override val discriminator: String?, override val raw: JsonObject) :
        ForkBoundary,
        UnknownVariant
}

internal object ForkBoundarySerializer : DiscriminatedUnionSerializer<ForkBoundary>(
    serialName = "dev.opencode.android.ForkBoundary",
    unknown = ForkBoundary::Unknown,
    variants = listOf(
        variant("before", ForkBoundary.Before.serializer()),
        variant("through", ForkBoundary.Through.serializer()),
    ),
)

/** One permission rule; the last matching rule wins (schema `Permission.Rule`). */
@Serializable
data class PermissionRule(
    val action: String,
    val resource: String,
    val effect: PermissionEffect,
)

/** A staged revert (schema `Session.Revert`). */
@Serializable
data class SessionRevert(
    val messageID: String,
    val partID: String? = null,
    val snapshot: String? = null,
    val files: List<FileDiff>? = null,
)

/** A per-file diff (schema `FileDiff.Info`). */
@Serializable
data class FileDiff(
    val file: String,
    val patch: String,
    val additions: Int,
    val deletions: Int,
    val status: FileDiffStatus,
)

/** Entry of `GET /api/session/active`. */
@Serializable
data class SessionActive(val type: String) {
    companion object {
        const val RUNNING = "running"
    }
}
