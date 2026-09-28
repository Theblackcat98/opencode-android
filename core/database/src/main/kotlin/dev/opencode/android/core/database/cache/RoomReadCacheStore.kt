package dev.opencode.android.core.database.cache

import androidx.room.withTransaction
import dev.opencode.android.core.database.OpenCodeDatabase
import dev.opencode.android.core.database.entity.CachedMessageEntity
import dev.opencode.android.core.database.entity.CachedSessionEntity
import dev.opencode.android.core.model.SessionInfo
import dev.opencode.android.core.model.SessionMessage
import dev.opencode.android.core.model.json.OpenCodeJson
import kotlinx.serialization.KSerializer

/**
 * The Room-backed [ReadCacheStore].
 *
 * Serialization is the one that decoded the payload, so a cache round trip is lossless: a message
 * written here decodes to the value it was written from, which is what lets a cached transcript be
 * compared with a live one.
 */
class RoomReadCacheStore(
    private val database: OpenCodeDatabase,
) : ReadCacheStore {

    private val sessionDao = database.cachedSessionDao()
    private val messageDao = database.cachedMessageDao()


    override suspend fun readSessions(
        serverId: String,
        directory: String?,
        limit: Int,
    ): List<SessionInfo> = sessionDao.sessionsInScope(serverId, scope(directory), limit)
        .mapNotNull { it.json.decodeOrNull(SessionInfo.serializer()) }

    override suspend fun writeSessions(
        serverId: String,
        directory: String?,
        sessions: List<SessionInfo>,
    ) {
        val scope = scope(directory)
        val kept = sessions.sortedByDescending { it.time.updated }.take(ReadCacheStore.SESSION_LIMIT)
        database.withTransaction {
            sessionDao.upsert(
                kept.map { session ->
                    CachedSessionEntity(
                        serverId = serverId,
                        sessionId = session.id,
                        scope = scope,
                        updatedAt = session.time.updated,
                        projectId = session.projectID,
                        json = OpenCodeJson.encodeToString(SessionInfo.serializer(), session),
                    )
                },
            )
            sessionDao.trimScope(serverId, scope, ReadCacheStore.SESSION_LIMIT)
        }
    }

    override suspend fun readSession(serverId: String, sessionId: String): SessionInfo? =
        sessionDao.session(serverId, sessionId)?.json?.decodeOrNull(SessionInfo.serializer())

    override suspend fun writeSession(serverId: String, sessionId: String, session: SessionInfo) {
        val scope = session.location.directory
        sessionDao.upsert(
            listOf(
                CachedSessionEntity(
                    serverId = serverId,
                    sessionId = sessionId,
                    scope = scope,
                    updatedAt = session.time.updated,
                    projectId = session.projectID,
                    json = OpenCodeJson.encodeToString(SessionInfo.serializer(), session),
                ),
            ),
        )
    }

    override suspend fun deleteSession(serverId: String, sessionId: String) {
        database.withTransaction {
            sessionDao.delete(serverId, sessionId)
            messageDao.deleteMessages(serverId, sessionId)
        }
    }

    override suspend fun readMessages(
        serverId: String,
        sessionId: String,
        limit: Int,
    ): List<SessionMessage> = messageDao.messages(serverId, sessionId, limit)
        .mapNotNull { it.json.decodeOrNull(SessionMessage.serializer()) }

    override suspend fun writeMessages(
        serverId: String,
        sessionId: String,
        messages: List<SessionMessage>,
        keep: Int,
    ) {
        val kept = messages.takeLast(keep)
        database.withTransaction {
            messageDao.replace(
                serverId = serverId,
                sessionId = sessionId,
                page = kept.mapIndexed { position, message ->
                    CachedMessageEntity(
                        serverId = serverId,
                        sessionId = sessionId,
                        ordinal = position,
                        createdAt = message.created,
                        json = OpenCodeJson.encodeToString(SessionMessage.serializer(), message),
                    )
                },
            )
        }
    }

    override suspend fun deleteMessages(serverId: String, sessionId: String) {
        messageDao.deleteMessages(serverId, sessionId)
    }

    override suspend fun dropLocation(serverId: String, directory: String?) {
        val scope = scope(directory)
        database.withTransaction {
            val sessions = sessionDao.sessionsInScope(serverId, scope, Int.MAX_VALUE)
            sessionDao.deleteScope(serverId, scope)
            sessions.forEach { messageDao.deleteMessages(serverId, it.sessionId) }
        }
    }

    override suspend fun dropServer(serverId: String) {
        database.withTransaction {
            sessionDao.deleteServer(serverId)
            messageDao.deleteServer(serverId)
        }
    }

    /** `ResourceKey.directory` as a column value; a scope with no location is the empty string. */
    private fun scope(directory: String?): String = directory.orEmpty()

    /** A cache row written by another build must never be able to crash a read. */
    private fun <T> String.decodeOrNull(serializer: KSerializer<T>): T? =
        runCatching { OpenCodeJson.decodeFromString(serializer, this) }.getOrNull()
}
