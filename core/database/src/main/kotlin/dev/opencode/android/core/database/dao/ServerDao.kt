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

    // `createdAt` alone leaves the order undefined when two servers are added in the same
    // millisecond, which makes the registry jump around; the name is a stable tie-breaker.
    @Query("SELECT * FROM servers ORDER BY createdAt DESC, name ASC")
    fun observeAll(): Flow<List<ServerEntity>>

    @Query("SELECT * FROM servers ORDER BY createdAt DESC, name ASC")
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

    /** Inserts a server and applies first-server/default policy atomically. */
    @Transaction
    suspend fun insertWithDefault(server: ServerEntity, makeDefault: Boolean) {
        val shouldBeDefault = makeDefault || count() == 0
        if (shouldBeDefault) clearDefaultFlags()
        insert(server.copy(isDefault = shouldBeDefault))
    }

    /** Updates a profile and its default flag in a single Room transaction. */
    @Transaction
    suspend fun updateWithDefault(server: ServerEntity, isDefault: Boolean) {
        if (isDefault) clearDefaultFlags()
        update(server.copy(isDefault = isDefault))
    }

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
