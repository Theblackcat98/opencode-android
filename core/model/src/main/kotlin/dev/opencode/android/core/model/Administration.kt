package dev.opencode.android.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import dev.opencode.android.core.model.json.DiscriminatedUnionSerializer
import dev.opencode.android.core.model.json.UnknownVariant
import dev.opencode.android.core.model.json.variant

/** One loaded location (`GET /api/debug/location`, schema `Location.PublicRef`). */
@Serializable
data class LoadedLocation(val directory: String)

/**
 * One saved approval (`GET /api/permission/saved`, schema `PermissionSaved.Info`).
 *
 * **A saved approval is a standing decision, so removing it is a privilege change in the other
 * direction** (plan §5.2): the tool that asked once will ask again. The app confirms by naming the
 * action and the resource for that reason.
 */
@Serializable
data class SavedPermission(
    val id: String,
    val projectID: String,
    val action: String,
    val resource: String,
    val time: Time,
) {
    @Serializable
    data class Time(val created: Long, val updated: Long)
}

/** One durable session instruction entry (schema `InstructionEntry.Info`). */
@Serializable
data class InstructionEntry(val key: String, val value: JsonElement)

/** `PUT …/instructions/entries/{key}` — the entry's value, which may be any JSON. */
@Serializable
data class InstructionEntryRequest(val value: JsonElement)

/**
 * `GET /api/experimental/migration/v1` — the V1-to-V2 session-history migration.
 *
 * **Four states plus an unknown one, and `running` is the only one with progress.** A `null`
 * numerator or denominator is `progress.label` alone, which is what the server sends before it has
 * counted anything, so a screen shows the label rather than a `0/0` it would have invented.
 */
@Serializable(with = MigrationStatusSerializer::class)
sealed interface MigrationStatus {

    /** The migration has not started and will run on demand. */
    @Serializable
    data object Required : MigrationStatus

    /** Everything from V1 has been imported. */
    @Serializable
    data object Completed : MigrationStatus

    /** In progress, with a label and an optional count. */
    @Serializable
    data class Running(
        val label: String,
        val numerator: Long? = null,
        val denominator: Long? = null,
    ) : MigrationStatus

    /** The migration failed. [message] is the server's own text. */
    @Serializable
    data class Error(val message: String) : MigrationStatus

    data class Unknown(override val discriminator: String?, override val raw: JsonObject) :
        MigrationStatus,
        UnknownVariant

    /** Whether the user is waiting on something, which is what decides whether a screen polls. */
    val isRunning: Boolean get() = this is Running
}

internal object MigrationStatusSerializer : DiscriminatedUnionSerializer<MigrationStatus>(
    serialName = "dev.opencode.android.MigrationStatus",
    unknown = { discriminator, raw -> MigrationStatus.Unknown(discriminator, raw) },
    variants = listOf(
        variant("required", MigrationStatus.Required.serializer()),
        variant("completed", MigrationStatus.Completed.serializer()),
        variant("running", MigrationStatus.Running.serializer()),
        variant("error", MigrationStatus.Error.serializer()),
    ),
)
