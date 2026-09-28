package dev.opencode.android.core.data.repository

import dev.opencode.android.core.data.security.SecureCredentialStore
import dev.opencode.android.core.database.dao.ServerDao
import dev.opencode.android.core.database.entity.ServerEntity
import dev.opencode.android.core.model.PairingSession
import dev.opencode.android.core.model.json.OpenCodeJson
import dev.opencode.android.core.network.PairingLink
import dev.opencode.android.core.network.ServerValidationResult
import dev.opencode.android.core.network.ServerValidator
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.UUID

interface ServerRepository {
    fun observeServers(): Flow<List<ServerProfile>>
    fun observeDefaultServer(): Flow<ServerProfile?>
    suspend fun getAllServers(): List<ServerProfile>
    suspend fun getServer(id: String): ServerProfile?
    suspend fun addServer(name: String, baseUrl: String, credential: String?, isDefault: Boolean = false): String
    suspend fun updateServer(id: String, name: String, baseUrl: String, credential: String?, isDefault: Boolean)
    suspend fun removeServer(id: String)
    suspend fun setDefaultServer(id: String)
    suspend fun getCredential(serverId: String): String?
    suspend fun updateHealth(serverId: String, health: ServerHealth)
    suspend fun updateLastSeen(serverId: String, timestamp: Long = System.currentTimeMillis())
    suspend fun redeemAndAddPairingLink(
        pairingLink: PairingLink,
        serverName: String? = null,
        isDefault: Boolean = false,
    ): Result<ServerProfile>
}

class DefaultServerRepository(
    private val serverDao: ServerDao,
    private val credentialStore: SecureCredentialStore,
    private val serverValidator: ServerValidator,
    private val okHttpClient: OkHttpClient,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ServerRepository {

    private val healthStatusMap = MutableStateFlow<Map<String, ServerHealth>>(emptyMap())

    override fun observeServers(): Flow<List<ServerProfile>> {
        return combine(serverDao.observeAll(), healthStatusMap) { entities, healthMap ->
            entities.map { entity ->
                toProfile(entity, healthMap[entity.id] ?: ServerHealth.DISCONNECTED)
            }
        }
    }

    override fun observeDefaultServer(): Flow<ServerProfile?> {
        return combine(serverDao.observeDefaultServer(), healthStatusMap) { entity, healthMap ->
            entity?.let { toProfile(it, healthMap[it.id] ?: ServerHealth.DISCONNECTED) }
        }
    }

    override suspend fun getAllServers(): List<ServerProfile> = withContext(ioDispatcher) {
        val healthMap = healthStatusMap.value
        serverDao.getAll().map { entity ->
            toProfile(entity, healthMap[entity.id] ?: ServerHealth.DISCONNECTED)
        }
    }

    override suspend fun getServer(id: String): ServerProfile? = withContext(ioDispatcher) {
        val entity = serverDao.getById(id) ?: return@withContext null
        val health = healthStatusMap.value[id] ?: ServerHealth.DISCONNECTED
        toProfile(entity, health)
    }

    override suspend fun addServer(
        name: String,
        baseUrl: String,
        credential: String?,
        isDefault: Boolean,
    ): String = withContext(ioDispatcher) {
        val serverId = UUID.randomUUID().toString()
        val cleanBaseUrl = baseUrl.trimEnd('/')

        val shouldBeDefault = isDefault || serverDao.count() == 0

        val entity = ServerEntity(
            id = serverId,
            name = name.trim().ifBlank { cleanBaseUrl },
            baseUrl = cleanBaseUrl,
            isDefault = shouldBeDefault,
            createdAt = System.currentTimeMillis(),
        )

        if (shouldBeDefault) {
            serverDao.clearDefaultFlags()
        }
        serverDao.insert(entity)

        if (!credential.isNullOrBlank()) {
            credentialStore.saveCredential(serverId, credential)
        }

        serverId
    }

    override suspend fun updateServer(
        id: String,
        name: String,
        baseUrl: String,
        credential: String?,
        isDefault: Boolean,
    ) = withContext(ioDispatcher) {
        val existing = serverDao.getById(id) ?: return@withContext
        val cleanBaseUrl = baseUrl.trimEnd('/')

        if (isDefault && !existing.isDefault) {
            serverDao.clearDefaultFlags()
        }

        val updated = existing.copy(
            name = name.trim().ifBlank { cleanBaseUrl },
            baseUrl = cleanBaseUrl,
            isDefault = if (isDefault) true else existing.isDefault,
        )
        serverDao.update(updated)

        if (credential != null) {
            if (credential.isNotBlank()) {
                credentialStore.saveCredential(id, credential)
            } else {
                credentialStore.removeCredential(id)
            }
        }
    }

    override suspend fun removeServer(id: String) = withContext(ioDispatcher) {
        serverDao.deleteById(id)
        credentialStore.removeCredential(id)
        val currentHealth = healthStatusMap.value.toMutableMap()
        currentHealth.remove(id)
        healthStatusMap.value = currentHealth
    }

    override suspend fun setDefaultServer(id: String) = withContext(ioDispatcher) {
        serverDao.setDefaultServer(id)
    }

    override suspend fun getCredential(serverId: String): String? = withContext(ioDispatcher) {
        credentialStore.getCredential(serverId)
    }

    override suspend fun updateHealth(serverId: String, health: ServerHealth) {
        val current = healthStatusMap.value.toMutableMap()
        current[serverId] = health
        healthStatusMap.value = current
    }

    override suspend fun updateLastSeen(serverId: String, timestamp: Long) = withContext(ioDispatcher) {
        serverDao.updateLastSeen(serverId, timestamp)
    }

    override suspend fun redeemAndAddPairingLink(
        pairingLink: PairingLink,
        serverName: String?,
        isDefault: Boolean,
    ): Result<ServerProfile> = withContext(ioDispatcher) {
        try {
            // 1. Redeem pairing code: GET /auth/connect/{code} with Accept: application/json
            val redeemUrl = "${pairingLink.baseUrl}/auth/connect/${pairingLink.code}"
            val request = Request.Builder()
                .url(redeemUrl)
                .header("Accept", "application/json")
                .build()

            val response = okHttpClient.newCall(request).execute()
            val token = response.use { resp ->
                if (!resp.isSuccessful) {
                    val code = resp.code
                    val errorBody = resp.body.string()
                    throw IOException("Pairing link redemption failed (HTTP $code): $errorBody")
                }
                val bodyStr = resp.body.string()
                val session = OpenCodeJson.decodeFromString<PairingSession>(bodyStr)
                session.token
            }

            // 2. Validate connection with the redeemed token
            when (val validation = serverValidator.validate(pairingLink.baseUrl, token)) {
                is ServerValidationResult.Failure -> {
                    throw IOException(validation.userMessage)
                }
                is ServerValidationResult.Success -> {
                    val info = validation.serverInfo
                    val resolvedName = serverName?.trim()?.ifBlank { null }
                        ?: info.urls.firstOrNull()
                        ?: pairingLink.baseUrl

                    val serverId = addServer(
                        name = resolvedName,
                        baseUrl = pairingLink.baseUrl,
                        credential = token,
                        isDefault = isDefault,
                    )
                    updateHealth(serverId, ServerHealth.CONNECTED)
                    val profile = getServer(serverId) ?: throw IOException("Failed to load saved server profile")
                    Result.success(profile)
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun toProfile(entity: ServerEntity, health: ServerHealth): ServerProfile {
        val hasCredential = !credentialStore.getCredential(entity.id).isNullOrBlank()
        return ServerProfile(
            id = entity.id,
            name = entity.name,
            baseUrl = entity.baseUrl,
            isDefault = entity.isDefault,
            createdAt = entity.createdAt,
            lastSeenAt = entity.lastSeenAt,
            health = health,
            hasCredential = hasCredential,
        )
    }
}
