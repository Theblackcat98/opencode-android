package dev.opencode.android.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import dev.opencode.android.core.database.entity.ServerEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ServerDao {

    @Query("SELECT * FROM servers ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<ServerEntity>>

    @Query("SELECT * FROM servers ORDER BY createdAt DESC")
    suspend fun getAll(): List<ServerEntity>

    @Query("SELECT * FROM servers WHERE id = :id")
    suspend fun getById(id: String): ServerEntity?

    @Query("SELECT * FROM servers WHERE id = :id")
    fun observeById(id: String): Flow<ServerEntity?>

    @Query("SELECT * FROM servers WHERE isDefault = 1 LIMIT 1")
    suspend fun getDefaultServer(): ServerEntity?

    @Query("SELECT * FROM servers WHERE isDefault = 1 LIMIT 1")
    fun observeDefaultServer(): Flow<ServerEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(server: ServerEntity)

    @Update
    suspend fun update(server: ServerEntity)

    @Query("DELETE FROM servers WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM servers")
    suspend fun deleteAll()

    @Query("UPDATE servers SET isDefault = 0")
    suspend fun clearDefaultFlags()

    @Query("UPDATE servers SET isDefault = 1 WHERE id = :id")
    suspend fun markAsDefault(id: String)

    @Transaction
    suspend fun setDefaultServer(id: String) {
        clearDefaultFlags()
        markAsDefault(id)
    }

    @Query("UPDATE servers SET lastSeenAt = :timestamp WHERE id = :id")
    suspend fun updateLastSeen(id: String, timestamp: Long)

    @Query("SELECT COUNT(*) FROM servers")
    suspend fun count(): Int
}
