package dev.opencode.android.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import dev.opencode.android.core.database.dao.CachedMessageDao
import dev.opencode.android.core.database.dao.CachedSessionDao
import dev.opencode.android.core.database.dao.ServerDao
import dev.opencode.android.core.database.entity.CachedMessageEntity
import dev.opencode.android.core.database.entity.CachedSessionEntity
import dev.opencode.android.core.database.entity.ServerEntity

@Database(
    entities = [
        ServerEntity::class,
        CachedSessionEntity::class,
        CachedMessageEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class OpenCodeDatabase : RoomDatabase() {
    abstract fun serverDao(): ServerDao

    abstract fun cachedSessionDao(): CachedSessionDao

    abstract fun cachedMessageDao(): CachedMessageDao
}
