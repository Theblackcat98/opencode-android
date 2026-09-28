package dev.opencode.android.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import dev.opencode.android.core.database.dao.ServerDao
import dev.opencode.android.core.database.entity.ServerEntity

@Database(
    entities = [
        ServerEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class OpenCodeDatabase : RoomDatabase() {
    abstract fun serverDao(): ServerDao
}
