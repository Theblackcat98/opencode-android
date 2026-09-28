package dev.opencode.android.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A saved server profile. The credential is deliberately not a column: it lives in the
 * Keystore-backed store, which this table only points at through the server id.
 */
@Entity(tableName = "servers")
data class ServerEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val baseUrl: String,
    val isDefault: Boolean = false,
    val createdAt: Long = 0L,
    val lastSeenAt: Long? = null,
    /**
     * True when this server's connections validate against CA certificates the user installed on
     * the device as well as the platform ones (plan §2.4). Off by default, because trusting a user
     * CA weakens the guarantee for that one connection only.
     */
    @ColumnInfo(name = "trustUserCertificates", defaultValue = "0")
    val trustUserCertificates: Boolean = false,
)
