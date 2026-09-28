package dev.opencode.android.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import dev.opencode.android.core.database.entity.CachedMessageEntity
import dev.opencode.android.core.database.entity.CachedSessionEntity

/** Reads and writes of the cached session projections, keyed by `(serverId, scope)`. */
@Dao
interface CachedSessionDao {

    @Query(
        """
        SELECT * FROM cached_sessions
        WHERE serverId = :serverId AND scope = :scope
        ORDER BY updatedAt DESC, sessionId ASC
        LIMIT :limit
        """,
    )
    suspend fun sessionsInScope(serverId: String, scope: String, limit: Int): List<CachedSessionEntity>

    @Query("SELECT * FROM cached_sessions WHERE serverId = :serverId AND sessionId = :sessionId LIMIT 1")
    suspend fun session(serverId: String, sessionId: String): CachedSessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(sessions: List<CachedSessionEntity>)

    @Query("DELETE FROM cached_sessions WHERE serverId = :serverId AND sessionId = :sessionId")
    suspend fun delete(serverId: String, sessionId: String)

    @Query("DELETE FROM cached_sessions WHERE serverId = :serverId AND scope = :scope")
    suspend fun deleteScope(serverId: String, scope: String)

    @Query("DELETE FROM cached_sessions WHERE serverId = :serverId")
    suspend fun deleteServer(serverId: String)

    @Query(
        """
        DELETE FROM cached_sessions
        WHERE serverId = :serverId AND scope = :scope AND sessionId NOT IN (
            SELECT sessionId FROM cached_sessions
            WHERE serverId = :serverId AND scope = :scope
            ORDER BY updatedAt DESC, sessionId ASC
            LIMIT :keep
        )
        """,
    )
    suspend fun trimScope(serverId: String, scope: String, keep: Int)
}

/** Reads and writes of the cached timeline rows, keyed by `(serverId, sessionId)`. */
@Dao
interface CachedMessageDao {

    @Query(
        """
        SELECT * FROM cached_messages
        WHERE serverId = :serverId AND sessionId = :sessionId
        ORDER BY ordinal ASC
        LIMIT :limit
        """,
    )
    suspend fun messages(serverId: String, sessionId: String, limit: Int): List<CachedMessageEntity>

    /**
     * Writes the window a store holds and forgets everything else, in one transaction.
     *
     * Replacing rather than appending is what keeps the cache bounded: a store only ever writes
     * the messages it actually has, so "load older" grows the window and a resync shrinks it back
     * to the server's own first page.
     */
    @Transaction
    suspend fun replace(
        serverId: String,
        sessionId: String,
        page: List<CachedMessageEntity>,
    ) {
        deleteMessages(serverId, sessionId)
        if (page.isNotEmpty()) insertMessages(page)
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessages(messages: List<CachedMessageEntity>)

    @Query("DELETE FROM cached_messages WHERE serverId = :serverId AND sessionId = :sessionId")
    suspend fun deleteMessages(serverId: String, sessionId: String)

    @Query("DELETE FROM cached_messages WHERE serverId = :serverId")
    suspend fun deleteServer(serverId: String)
}
