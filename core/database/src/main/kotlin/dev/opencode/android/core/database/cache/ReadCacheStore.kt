package dev.opencode.android.core.database.cache

import dev.opencode.android.core.model.SessionInfo
import dev.opencode.android.core.model.SessionMessage

/**
 * The on-device cache of what the server last projected (plan §6, Phase 2).
 *
 * It exists for two reasons the network cannot serve: a session must open instantly without a
 * round trip, and a session must stay readable with no network at all. The contract is therefore
 * deliberately write-through and lossy-safe:
 *
 *  - **The server is authoritative.** A cached value is only ever shown while it is the freshest
 *    thing the app has; any live event or REST read replaces it. Nothing is merged or reconciled
 *    locally, because a merge is a guess (plan §4.2).
 *  - **Bounded.** [writeMessages] keeps the newest [keep] messages of a session, so a 10,000
 *    message transcript cannot fill the device. Sessions themselves are bounded by
 *    [writeSessions], which keeps the most recently updated.
 *  - **Scoped.** Everything is keyed by `(server, directory)` and [dropLocation] is what
 *    `location.shutdown` calls.
 *
 * The contract sits next to its Room implementation in `core:database`, and `core:data` depends on
 * this module, so the stores never see Room and a test fake is a few lines.
 */
interface ReadCacheStore {

    /** The sessions cached for a scope, most recently updated first. */
    suspend fun readSessions(serverId: String, directory: String?, limit: Int = SESSION_LIMIT): List<SessionInfo>

    suspend fun writeSessions(serverId: String, directory: String?, sessions: List<SessionInfo>)

    suspend fun readSession(serverId: String, sessionId: String): SessionInfo?

    suspend fun writeSession(serverId: String, sessionId: String, session: SessionInfo)

    suspend fun deleteSession(serverId: String, sessionId: String)

    /** The newest [limit] messages of a session, returned oldest first. */
    suspend fun readMessages(serverId: String, sessionId: String, limit: Int = MESSAGE_LIMIT): List<SessionMessage>

    /**
     * Replaces a session's cached timeline with the newest [keep] of [messages].
     *
     * [messages] is the window the store holds, not the whole transcript.
     */
    suspend fun writeMessages(serverId: String, sessionId: String, messages: List<SessionMessage>, keep: Int = MESSAGE_LIMIT)

    suspend fun deleteMessages(serverId: String, sessionId: String)

    /** Forgets one directory's caches, which is what `location.shutdown` means. */
    suspend fun dropLocation(serverId: String, directory: String?)

    /** Forgets one server's caches entirely. */
    suspend fun dropServer(serverId: String)

    companion object {
        /** How many sessions one scope keeps. */
        const val SESSION_LIMIT = 200

        /** How many messages of one session are kept, newest first. */
        const val MESSAGE_LIMIT = 300
    }
}
