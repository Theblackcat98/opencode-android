package dev.opencode.android.feature.admin

import dev.opencode.android.core.data.config.ConfigSchema
import dev.opencode.android.core.data.preferences.ExperimentalPreferences
import dev.opencode.android.core.data.preferences.ExperimentalSettings
import dev.opencode.android.core.data.server.ServerDataSet
import dev.opencode.android.core.database.cache.ReadCacheStore
import dev.opencode.android.core.model.SessionInfo
import dev.opencode.android.core.model.SessionMessage
import dev.opencode.android.core.testing.VendoredSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * The read model over an [AdminServer], for the view models that follow a set rather than call an api.
 *
 * **The real one, with the vendored schema.** A view model test is about what a screen would be told after
 * a real answer, so the surface and the store are the production classes over the same MockWebServer the
 * operation tests use; only the on-device cache, which is not what these tests are about, is stubbed.
 */
internal fun AdminServer.dataSet(scope: CoroutineScope): ServerDataSet = ServerDataSet(
    serverId = "srv",
    api = api,
    scope = scope,
    cache = NoCache,
    schema = ConfigSchema.parse(VendoredSpec.configSchemaText()),
)

/** The experimental switches held in memory, so a test sets them the way the sheet does. */
internal class FakeExperimental(initial: ExperimentalSettings = ExperimentalSettings()) : ExperimentalPreferences {
    val current = MutableStateFlow(initial)

    override val settings: Flow<ExperimentalSettings> = current

    override suspend fun setFileWrites(enabled: Boolean) = current.update { it.copy(fileWrites = enabled) }

    override suspend fun setSessionTransfer(enabled: Boolean) = current.update { it.copy(sessionTransfer = enabled) }

    override suspend fun setPersistentPty(enabled: Boolean) = current.update { it.copy(persistentPty = enabled) }

    override suspend fun setMcpRuntime(enabled: Boolean) = current.update { it.copy(mcpRuntime = enabled) }

    override suspend fun setWellknownIntegrations(enabled: Boolean) =
        current.update { it.copy(wellknownIntegrations = enabled) }

    override suspend fun setConfigUpdate(enabled: Boolean) = current.update { it.copy(configUpdate = enabled) }

    override suspend fun setSessionInstructions(enabled: Boolean) =
        current.update { it.copy(sessionInstructions = enabled) }
}

/** A cache that remembers nothing: these tests are about the network path and not the disk one. */
internal object NoCache : ReadCacheStore {
    override suspend fun readSessions(serverId: String, directory: String?, limit: Int): List<SessionInfo> =
        emptyList()

    override suspend fun writeSessions(serverId: String, directory: String?, sessions: List<SessionInfo>) = Unit

    override suspend fun readSession(serverId: String, sessionId: String): SessionInfo? = null

    override suspend fun writeSession(serverId: String, sessionId: String, session: SessionInfo) = Unit

    override suspend fun deleteSession(serverId: String, sessionId: String) = Unit

    override suspend fun readMessages(serverId: String, sessionId: String, limit: Int): List<SessionMessage> =
        emptyList()

    override suspend fun writeMessages(
        serverId: String,
        sessionId: String,
        messages: List<SessionMessage>,
        keep: Int,
    ) = Unit

    override suspend fun deleteMessages(serverId: String, sessionId: String) = Unit

    override suspend fun dropLocation(serverId: String, directory: String?) = Unit

    override suspend fun dropServer(serverId: String) = Unit
}
