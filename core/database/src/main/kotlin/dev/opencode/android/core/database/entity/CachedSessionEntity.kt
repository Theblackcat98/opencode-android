package dev.opencode.android.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A cached session, keyed by `(serverId, sessionId)` and stored as the server's own JSON.
 *
 * The projection is kept verbatim rather than spread over columns: it is a union of a dozen shapes
 * that 2.0.x adds to freely, so a column-per-field cache would either drop fields (and the contract
 * tests would catch it) or need a migration per release. The cost is a JSON parse per read, which
 * happens off the main thread and only for the sessions actually being shown.
 */
@Entity(
    tableName = "cached_sessions",
    primaryKeys = ["serverId", "sessionId"],
    indices = [Index(value = ["serverId", "scope"]), Index(value = ["serverId", "updatedAt"])],
)
data class CachedSessionEntity(
    val serverId: String,
    val sessionId: String,
    /** `ResourceKey.directory`, or an empty string for a scope that has no location. */
    val scope: String,
    @ColumnInfo(name = "updatedAt")
    val updatedAt: Long,
    @ColumnInfo(name = "projectId")
    val projectId: String,
    val json: String,
)

/**
 * A cached timeline row, one per message, oldest first through [ordinal].
 *
 * Messages are stored one row each rather than one blob so that "load older" is a range query and
 * a bounded write never rewrites the whole transcript.
 */
@Entity(
    tableName = "cached_messages",
    primaryKeys = ["serverId", "sessionId", "ordinal"],
    indices = [Index(value = ["serverId", "sessionId"])],
)
data class CachedMessageEntity(
    val serverId: String,
    val sessionId: String,
    val ordinal: Int,
    @ColumnInfo(name = "createdAt")
    val createdAt: Long,
    val json: String,
)
