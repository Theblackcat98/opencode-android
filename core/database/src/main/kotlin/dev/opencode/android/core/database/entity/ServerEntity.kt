package dev.opencode.android.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "servers")
data class ServerEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val baseUrl: String,
    val isDefault: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val lastSeenAt: Long? = null,
)
