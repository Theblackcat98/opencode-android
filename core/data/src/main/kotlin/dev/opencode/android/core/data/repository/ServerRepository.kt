package dev.opencode.android.core.data.repository

import dev.opencode.android.core.data.security.SecureCredentialStore
import dev.opencode.android.core.database.dao.ServerDao
import dev.opencode.android.core.database.entity.ServerEntity
import dev.opencode.android.core.network.PairingClient
import dev.opencode.android.core.network.PairingErrorType
import dev.opencode.android.core.network.PairingLink
import dev.opencode.android.core.network.PairingRedemptionResult
import dev.opencode.android.core.network.ServerCredentialCache
import dev.opencode.android.core.network.ServerValidationResult
import dev.opencode.android.core.network.ServerValidator
import dev.opencode.android.core.network.ValidationErrorType
import dev.opencode.android.core.network.toServerBaseUrl
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.util.UUID

/** How a server attempt ended, in the classes the onboarding help maps to. */
sealed interface AddServerOutcome {
    data class Success(val profile: ServerProfile) : AddServerOutcome

    /**
     * A failure already reduced to the class that selects the help text: the UI reads a string
     * resource for [errorType] and keeps [technicalDetail] for the event inspector and the log.
     */
    data class Failure(
        val errorType: AddServerErrorType,
        val technicalDetail: String? = null,
    ) : AddServerOutcome
}

enum class AddServerErrorType {
    /** The pairing code was already used, expired, or never existed. */
    PAIRING_CODE_REJECTED,

    /** The address is not usable, or nothing answered on it. */
    UNREACHABLE,

    /** The password or token was rejected: pair again. */
    UNAUTHORIZED,

    /** A certificate could not be validated. */
    TLS_ERROR,

    /** The connection or the response timed out. */
    TIMEOUT,

    /** The host name did not resolve. */
    UNKNOWN_HOST,

    /** Not an OpenCode V2 server. */
    UNSUPPORTED_VERSION,

    /** The server answered with an error status, or with something unexpected. */
    SERVER_ERROR,
}

interface ServerRepository {
    fun observeServers(): Flow<List<ServerProfile>>
    fun observeDefaultServer(): Flow<ServerProfile?>
    suspend fun getAllServers(): List<ServerProfile>
    suspend fun getServer(id: String): ServerProfile?
    suspend fun addServer(
        name: String,
        baseUrl: String,
        credential: String?,
        isDefault: Boolean = false,
        trustUserCertificates: Boolean = false,
    ): String

    /** Edits a profile. A blank [credential] leaves the stored one alone; a null one clears it. */
    suspend fun updateServer(
        id: String,
        name: String,
        baseUrl: String,
        credential: String?,
        isDefault: Boolean,
        trustUserCertificates: Boolean,
    )

    suspend fun removeServer(id: String)
    suspend fun setDefaultServer(id: String)
    suspend fun getCredential(serverId: String): String?
    suspend fun updateHealth(serverId: String, health: ServerHealth)
    suspend fun updateLastSeen(serverId: String, timestamp: Long = System.currentTimeMillis())

    /** Redeems a pairing link, stores the token encrypted, and saves the server. */
    suspend fun addPairedServer(
        pairingLink: PairingLink,
        serverName: String? = null,
        isDefault: Boolean = false,
        trustUserCertificates: Boolean = false,
    ): AddServerOutcome

    /**
     * Replaces the credential of an existing server with the token from [pairingLink].
     *
     * This is the recovery path for a rotated password or a revoked token: the profile, its name
     * and its address are kept, and only the credential changes. The link must point at the same
     * address, because a link for a different server is a different pairing, not a repair.
     */
    suspend fun rePairServer(
        id: String,
        pairingLink: PairingLink,
    ): AddServerOutcome

    /** Validates a manually entered address and password, then saves the server. */
    suspend fun addManualServer(
        name: String,
        baseUrl: String,
        password: String?,
        isDefault: Boolean = false,
        trustUserCertificates: Boolean = false,
    ): AddServerOutcome

    /** Edits a profile after checking the new address and password, keeping the old values on failure. */
    suspend fun editAndValidateServer(
        id: String,
        name: String,
        baseUrl: String,
        password: String?,
        isDefault: Boolean,
        trustUserCertificates: Boolean,
    ): AddServerOutcome
}

/** Live per-server state that is not in the database, so reading the registry never decrypts. */
private data class RuntimeState(val health: ServerHealth, val hasCredential: Boolean)

class DefaultServerRepository(
    private val serverDao: ServerDao,
    private val credentialStore: SecureCredentialStore,
    private val serverValidator: ServerValidator,
    private val pairingClient: PairingClient,
    private val credentialCache: ServerCredentialCache,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = System::currentTimeMillis,
) : ServerRepository {

    /**
     * The health dot and credential presence per server. Held in memory so that reading the
     * registry never decrypts the Keystore, and updated with [MutableStateFlow.update] so two
     * concurrent updates cannot lose one another.
     */
    private val runtime = MutableStateFlow<Map<String, RuntimeState>>(emptyMap())

    override fun observeServers(): Flow<List<ServerProfile>> =
        combine(serverDao.observeAll(), runtime) { entities, states ->
            entities.map { toProfile(it, states) }
        }

    override fun observeDefaultServer(): Flow<ServerProfile?> =
        combine(serverDao.observeDefaultServer(), runtime) { entity, states ->
            entity?.let { toProfile(it, states) }
        }

    override suspend fun getAllServers(): List<ServerProfile> = withContext(ioDispatcher) {
        val states = runtime.value
        serverDao.getAll().map { toProfile(it, states) }
    }

    override suspend fun getServer(id: String): ServerProfile? = withContext(ioDispatcher) {
        serverDao.getById(id)?.let { toProfile(it, runtime.value) }
    }

    override suspend fun addServer(
        name: String,
        baseUrl: String,
        credential: String?,
        isDefault: Boolean,
        trustUserCertificates: Boolean,
    ): String = withContext(ioDispatcher) {
        val serverId = UUID.randomUUID().toString()
        val cleanBaseUrl = baseUrl.toServerBaseUrl().trimEnd('/')

        // The first server added is the default, so a fresh install has one to open.
        val shouldBeDefault = isDefault || serverDao.count() == 0

        if (shouldBeDefault) serverDao.clearDefaultFlags()
        serverDao.insert(
            ServerEntity(
                id = serverId,
                name = name.trim().ifBlank { cleanBaseUrl },
                baseUrl = cleanBaseUrl,
                isDefault = shouldBeDefault,
                trustUserCertificates = trustUserCertificates,
                createdAt = now(),
            ),
        )

        if (!credential.isNullOrBlank()) {
            saveCredential(serverId, cleanBaseUrl, credential)
        } else {
            setRuntime(serverId, health = null, hasCredential = false)
        }
        serverId
    }

    override suspend fun updateServer(
        id: String,
        name: String,
        baseUrl: String,
        credential: String?,
        isDefault: Boolean,
        trustUserCertificates: Boolean,
    ) = withContext(ioDispatcher) {
        val existing = serverDao.getById(id) ?: return@withContext
        val cleanBaseUrl = baseUrl.toServerBaseUrl().trimEnd('/')
        val addressChanged = cleanBaseUrl != existing.baseUrl

        if (isDefault && !existing.isDefault) serverDao.clearDefaultFlags()
        if (addressChanged) {
            // A token belongs to the server it was issued by, and to the address it was issued for.
            credentialCache.forget(existing.baseUrl)
        }
        serverDao.update(
            existing.copy(
                name = name.trim().ifBlank { cleanBaseUrl },
                baseUrl = cleanBaseUrl,
                isDefault = isDefault,
                trustUserCertificates = trustUserCertificates,
            ),
        )

        when {
            credential == null -> Unit // Unchanged: the user did not touch the field.
            credential.isBlank() -> {
                credentialStore.removeCredential(id)
                credentialCache.forget(cleanBaseUrl)
                setRuntime(id, health = null, hasCredential = false)
            }

            else -> saveCredential(id, cleanBaseUrl, credential)
        }
    }

    override suspend fun removeServer(id: String) = withContext(ioDispatcher) {
        val existing = serverDao.getById(id)
        serverDao.deleteById(id)
        credentialStore.removeCredential(id)
        existing?.baseUrl?.let(credentialCache::forget)
        runtime.update { it - id }
        // A registry with no default has no screen to open, so the newest survivor takes over.
        if (existing?.isDefault == true) {
            serverDao.getAll().firstOrNull()?.let { survivor -> serverDao.setDefaultServer(survivor.id) }
        }
    }

    override suspend fun setDefaultServer(id: String) = withContext(ioDispatcher) {
        serverDao.setDefaultServer(id)
    }

    override suspend fun getCredential(serverId: String): String? = withContext(ioDispatcher) {
        credentialStore.getCredential(serverId)
    }

    override suspend fun updateHealth(serverId: String, health: ServerHealth) {
        runtime.update { states ->
            states + (
                serverId to RuntimeState(
                    health = health,
                    hasCredential = states[serverId]?.hasCredential ?: false,
                )
                )
        }
    }

    override suspend fun updateLastSeen(serverId: String, timestamp: Long) = withContext(ioDispatcher) {
        serverDao.updateLastSeen(serverId, timestamp)
    }

    override suspend fun addPairedServer(
        pairingLink: PairingLink,
        serverName: String?,
        isDefault: Boolean,
        trustUserCertificates: Boolean,
    ): AddServerOutcome = withContext(ioDispatcher) {
        val token = when (val redemption = pairingClient.redeem(pairingLink)) {
            is PairingRedemptionResult.Success -> redemption.token
            is PairingRedemptionResult.Failure -> return@withContext AddServerOutcome.Failure(
                errorType = redemption.errorType.toAddServerErrorType(),
                technicalDetail = redemption.technicalDetail,
            )
        }

        val baseUrl = pairingLink.baseUrl.trimEnd('/')
        // The token is useless without a working connection, so the address is checked before the
        // server is saved: a failed pairing must not leave a half-configured profile behind.
        when (
            val validation = serverValidator.validate(
                baseUrl = baseUrl,
                credential = token,
                trustUserCertificates = trustUserCertificates,
            )
        ) {
            is ServerValidationResult.Failure -> AddServerOutcome.Failure(
                errorType = validation.errorType.toAddServerErrorType(),
                technicalDetail = validation.technicalDetail,
            )

            is ServerValidationResult.Success -> {
                val resolvedName = serverName?.trim()?.ifBlank { null }
                    ?: validation.serverInfo.urls.firstOrNull()
                    ?: baseUrl
                val id = runCatching {
                    addServer(
                        name = resolvedName,
                        baseUrl = baseUrl,
                        credential = token,
                        isDefault = isDefault,
                        trustUserCertificates = trustUserCertificates,
                    )
                }.getOrElse { error -> return@withContext storageFailure(error) }
                updateHealth(id, ServerHealth.CONNECTED)
                val profile = getServer(id)
                    ?: return@withContext AddServerOutcome.Failure(
                        errorType = AddServerErrorType.SERVER_ERROR,
                        technicalDetail = "The saved server could not be read back",
                    )
                AddServerOutcome.Success(profile)
            }
        }
    }

    override suspend fun rePairServer(
        id: String,
        pairingLink: PairingLink,
    ): AddServerOutcome = withContext(ioDispatcher) {
        val existing = getServer(id) ?: return@withContext AddServerOutcome.Failure(
            errorType = AddServerErrorType.UNREACHABLE,
            technicalDetail = "The server to re-pair no longer exists",
        )
        val linkBaseUrl = pairingLink.baseUrl.trimEnd('/')
        if (!linkBaseUrl.equals(existing.baseUrl.trimEnd('/'), ignoreCase = true)) {
            return@withContext AddServerOutcome.Failure(
                errorType = AddServerErrorType.UNREACHABLE,
                technicalDetail = "The pairing link points at $linkBaseUrl, not at ${existing.baseUrl}",
            )
        }

        val token = when (val redemption = pairingClient.redeem(pairingLink)) {
            is PairingRedemptionResult.Success -> redemption.token
            is PairingRedemptionResult.Failure -> return@withContext AddServerOutcome.Failure(
                errorType = redemption.errorType.toAddServerErrorType(),
                technicalDetail = redemption.technicalDetail,
            )
        }

        when (
            val validation = serverValidator.validate(
                baseUrl = existing.baseUrl,
                credential = token,
                trustUserCertificates = existing.trustUserCertificates,
            )
        ) {
            is ServerValidationResult.Failure -> AddServerOutcome.Failure(
                errorType = validation.errorType.toAddServerErrorType(),
                technicalDetail = validation.technicalDetail,
            )

            is ServerValidationResult.Success -> {
                runCatching { saveCredential(id, existing.baseUrl, token) }
                    .getOrElse { error -> return@withContext storageFailure(error) }
                updateHealth(id, ServerHealth.CONNECTED)
                val profile = getServer(id)
                    ?: return@withContext AddServerOutcome.Failure(
                        errorType = AddServerErrorType.SERVER_ERROR,
                        technicalDetail = "The re-paired server could not be read back",
                    )
                AddServerOutcome.Success(profile)
            }
        }
    }

    override suspend fun addManualServer(
        name: String,
        baseUrl: String,
        password: String?,
        isDefault: Boolean,
        trustUserCertificates: Boolean,
    ): AddServerOutcome = withContext(ioDispatcher) {
        val normalized = runCatching { baseUrl.toServerBaseUrl() }.getOrElse { error ->
            return@withContext AddServerOutcome.Failure(
                errorType = AddServerErrorType.UNREACHABLE,
                technicalDetail = error.message,
            )
        }
        when (
            val validation = serverValidator.validate(
                baseUrl = normalized,
                credential = password?.takeIf { it.isNotBlank() },
                trustUserCertificates = trustUserCertificates,
            )
        ) {
            is ServerValidationResult.Failure -> AddServerOutcome.Failure(
                errorType = validation.errorType.toAddServerErrorType(),
                technicalDetail = validation.technicalDetail,
            )

            is ServerValidationResult.Success -> {
                val resolvedName = name.trim().ifBlank { validation.serverInfo.urls.firstOrNull() ?: normalized }
                val id = runCatching {
                    addServer(
                        name = resolvedName,
                        baseUrl = normalized,
                        credential = password?.takeIf { it.isNotBlank() },
                        isDefault = isDefault,
                        trustUserCertificates = trustUserCertificates,
                    )
                }.getOrElse { error -> return@withContext storageFailure(error) }
                updateHealth(id, ServerHealth.CONNECTED)
                val profile = getServer(id)
                    ?: return@withContext AddServerOutcome.Failure(
                        errorType = AddServerErrorType.SERVER_ERROR,
                        technicalDetail = "The saved server could not be read back",
                    )
                AddServerOutcome.Success(profile)
            }
        }
    }

    override suspend fun editAndValidateServer(
        id: String,
        name: String,
        baseUrl: String,
        password: String?,
        isDefault: Boolean,
        trustUserCertificates: Boolean,
    ): AddServerOutcome = withContext(ioDispatcher) {
        val existing = getServer(id) ?: return@withContext AddServerOutcome.Failure(
            errorType = AddServerErrorType.UNREACHABLE,
            technicalDetail = "The server to edit no longer exists",
        )
        val normalized = runCatching { baseUrl.toServerBaseUrl() }.getOrElse { error ->
            return@withContext AddServerOutcome.Failure(
                errorType = AddServerErrorType.UNREACHABLE,
                technicalDetail = error.message,
            )
        }
        // A blank field means "keep the stored credential", so the check uses whatever will remain.
        val effectiveCredential = password?.takeIf { it.isNotBlank() } ?: getCredential(id)
        when (
            val validation = serverValidator.validate(
                baseUrl = normalized,
                credential = effectiveCredential,
                trustUserCertificates = trustUserCertificates,
            )
        ) {
            is ServerValidationResult.Failure -> AddServerOutcome.Failure(
                errorType = validation.errorType.toAddServerErrorType(),
                technicalDetail = validation.technicalDetail,
            )

            is ServerValidationResult.Success -> {
                runCatching {
                    updateServer(
                        id = id,
                        name = name.trim().ifBlank { normalized },
                        baseUrl = normalized,
                        credential = password?.takeIf { it.isNotBlank() },
                        isDefault = isDefault,
                        trustUserCertificates = trustUserCertificates,
                    )
                }.getOrElse { error -> return@withContext storageFailure(error) }
                updateHealth(id, ServerHealth.CONNECTED)
                val profile = getServer(id)
                    ?: return@withContext AddServerOutcome.Failure(
                        errorType = AddServerErrorType.SERVER_ERROR,
                        technicalDetail = "The edited server could not be read back",
                    )
                AddServerOutcome.Success(profile)
            }
        }
    }

    private suspend fun saveCredential(serverId: String, baseUrl: String, credential: String) {
        credentialStore.saveCredential(serverId, credential)
        credentialCache.put(baseUrl, credential)
        setRuntime(serverId, health = null, hasCredential = true)
    }

    /**
     * A credential that cannot be written to the Keystore must not leave a server saved without
     * one, so the failure is reported instead of being swallowed.
     */
    private fun storageFailure(error: Throwable) = AddServerOutcome.Failure(
        errorType = AddServerErrorType.SERVER_ERROR,
        technicalDetail = "The credential could not be stored: ${error.message}",
    )

    private fun setRuntime(serverId: String, health: ServerHealth?, hasCredential: Boolean?) {
        runtime.update { states ->
            val current = states[serverId]
            states + (
                serverId to RuntimeState(
                    health = health ?: current?.health ?: ServerHealth.DISCONNECTED,
                    hasCredential = hasCredential ?: current?.hasCredential ?: false,
                )
                )
        }
    }

    private fun toProfile(entity: ServerEntity, states: Map<String, RuntimeState>): ServerProfile {
        val state = states[entity.id]
        return ServerProfile(
            id = entity.id,
            name = entity.name,
            baseUrl = entity.baseUrl,
            isDefault = entity.isDefault,
            createdAt = entity.createdAt,
            lastSeenAt = entity.lastSeenAt,
            health = state?.health ?: ServerHealth.DISCONNECTED,
            hasCredential = state?.hasCredential ?: false,
            trustUserCertificates = entity.trustUserCertificates,
        )
    }
}

fun PairingErrorType.toAddServerErrorType(): AddServerErrorType = when (this) {
    PairingErrorType.CODE_REJECTED -> AddServerErrorType.PAIRING_CODE_REJECTED
    PairingErrorType.UNREACHABLE -> AddServerErrorType.UNREACHABLE
    PairingErrorType.SERVER_ERROR -> AddServerErrorType.SERVER_ERROR
    PairingErrorType.MALFORMED_RESPONSE -> AddServerErrorType.SERVER_ERROR
}

fun ValidationErrorType.toAddServerErrorType(): AddServerErrorType = when (this) {
    ValidationErrorType.CONNECTION_REFUSED -> AddServerErrorType.UNREACHABLE
    ValidationErrorType.UNAUTHORIZED -> AddServerErrorType.UNAUTHORIZED
    ValidationErrorType.TLS_ERROR -> AddServerErrorType.TLS_ERROR
    ValidationErrorType.TIMEOUT -> AddServerErrorType.TIMEOUT
    ValidationErrorType.UNSUPPORTED_VERSION -> AddServerErrorType.UNSUPPORTED_VERSION
    ValidationErrorType.UNKNOWN_HOST -> AddServerErrorType.UNKNOWN_HOST
    ValidationErrorType.SERVER_ERROR -> AddServerErrorType.SERVER_ERROR
    ValidationErrorType.MALFORMED_RESPONSE -> AddServerErrorType.SERVER_ERROR
    ValidationErrorType.UNKNOWN -> AddServerErrorType.UNREACHABLE
}
