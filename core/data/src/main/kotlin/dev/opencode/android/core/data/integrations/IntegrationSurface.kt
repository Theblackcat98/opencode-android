package dev.opencode.android.core.data.integrations

import dev.opencode.android.core.data.action.ActionError
import dev.opencode.android.core.data.action.toActionError
import dev.opencode.android.core.data.capability.CapabilityPolicy
import dev.opencode.android.core.data.capability.ExperimentalRoute
import dev.opencode.android.core.data.capability.RouteAvailability
import dev.opencode.android.core.data.sync.SyncedResource
import dev.opencode.android.core.model.ConnectCommandRequest
import dev.opencode.android.core.model.ConnectKeyRequest
import dev.opencode.android.core.model.ConnectOAuthCompleteRequest
import dev.opencode.android.core.model.ConnectOAuthRequest
import dev.opencode.android.core.model.CredentialUpdateRequest
import dev.opencode.android.core.model.IntegrationInfo
import dev.opencode.android.core.model.McpAddRequest
import dev.opencode.android.core.model.McpResourceCatalog
import dev.opencode.android.core.model.McpServer
import dev.opencode.android.core.model.McpServerConfig
import dev.opencode.android.core.model.PluginCheckRequest
import dev.opencode.android.core.model.PluginInfo
import dev.opencode.android.core.model.PluginUpdateRequest
import dev.opencode.android.core.model.ProviderInfo
import dev.opencode.android.core.model.Secret
import dev.opencode.android.core.model.WellknownSourceRequest
import dev.opencode.android.core.model.WebSearchProviderInfo
import dev.opencode.android.core.model.WebSearchQueryRequest
import dev.opencode.android.core.model.WebSearchResponse
import dev.opencode.android.core.model.event.CredentialSwitched
import dev.opencode.android.core.model.event.Event
import dev.opencode.android.core.model.event.EventPayload
import dev.opencode.android.core.model.event.McpResourcesChanged
import dev.opencode.android.core.model.event.McpStatusChanged
import dev.opencode.android.core.network.ServerApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * One server's integrations, providers, MCP servers, plugins and web-search providers.
 *
 * **All of these are per location, and all of them are [SyncedResource]s.** A login made while the
 * phone was pointed at one checkout has to be visible from the next, and the events that say so
 * (`integration.updated`, `credential.updated`, `mcp.status.changed`) carry no payload at all, so
 * the only correct response is a refetch. The stores are keyed by directory for the same reason the
 * agents and models are: a store that was not keyed the way the route is would answer a screen with
 * another checkout's logins, which for a *credential list* would be showing another project whose
 * keys the user has no business reading.
 *
 * **The experimental writes report what the server can do, not what the switch says.** [mcpRuntime]
 * and [wellknownSources] are a two-key answer — the user's switch and the route's availability — and
 * the UI offers a button only when both say yes. That is plan §4.2's "detect capabilities with
 * graceful degradation", and it is why a `404` from `experimental.mcp.connect` records an
 * [RouteAvailability.Absent] instead of an error: a server without the route is a server whose MCP
 * panel shows the connect button greyed out, not one that shows a failure.
 */
class IntegrationSurface(
    private val serverId: String,
    private val api: ServerApi,
    private val scope: CoroutineScope,
) {
    private val integrations = ConcurrentHashMap<String, SyncedResource<List<IntegrationInfo>>>()
    private val providers = ConcurrentHashMap<String, SyncedResource<List<ProviderInfo>>>()
    private val mcpServers = ConcurrentHashMap<String, SyncedResource<List<McpServer>>>()
    private val mcpResources = ConcurrentHashMap<String, SyncedResource<McpResourceCatalog>>()
    private val plugins = ConcurrentHashMap<String, SyncedResource<List<PluginInfo>>>()
    private val webSearchProviders = ConcurrentHashMap<String, SyncedResource<List<WebSearchProviderInfo>>>()

    /** `integration.list` for a directory: the methods and the existing connections. */
    fun integrations(directory: String): SyncedResource<List<IntegrationInfo>> = integrations.getOrPut(directory) {
        SyncedResource(
            key = dev.opencode.android.core.data.sync.ResourceKey(serverId, directory),
            name = "integration.list($directory)",
            scope = scope,
            loader = { api.listIntegrations(directory).data },
        )
    }

    /** `provider.list` for a directory, which is also the model catalog's source of `enabled`. */
    fun providers(directory: String): SyncedResource<List<ProviderInfo>> = providers.getOrPut(directory) {
        SyncedResource(
            key = dev.opencode.android.core.data.sync.ResourceKey(serverId, directory),
            name = "provider.list($directory)",
            scope = scope,
            loader = { api.listProviders(directory).data },
        )
    }

    /** `mcp.list` for a directory. */
    fun mcpServers(directory: String): SyncedResource<List<McpServer>> = mcpServers.getOrPut(directory) {
        SyncedResource(
            key = dev.opencode.android.core.data.sync.ResourceKey(serverId, directory),
            name = "mcp.list($directory)",
            scope = scope,
            loader = { api.listMcpServers(directory).data },
        )
    }

    /**
     * `mcp.resource.catalog` for a directory.
     *
     * **Invalidated on its own event, not the servers'.** `mcp.status.changed {server}` says a
     * server connected, and `mcp.resources.changed {server}` says what it publishes changed; the
     * second is the only one that means the catalog is stale, and refetching on the first would
     * cost a request for every connect a user does.
     */
    fun mcpResources(directory: String): SyncedResource<McpResourceCatalog> = mcpResources.getOrPut(directory) {
        SyncedResource(
            key = dev.opencode.android.core.data.sync.ResourceKey(serverId, directory),
            name = "mcp.resource.catalog($directory)",
            scope = scope,
            loader = { api.getMcpResourceCatalog(directory).data },
        )
    }

    /** `plugin.list` for a directory. */
    fun plugins(directory: String): SyncedResource<List<PluginInfo>> = plugins.getOrPut(directory) {
        SyncedResource(
            key = dev.opencode.android.core.data.sync.ResourceKey(serverId, directory),
            name = "plugin.list($directory)",
            scope = scope,
            loader = { api.listPlugins(directory).data },
        )
    }

    /** `websearch.providers` for a directory, which is `503` when nothing is configured. */
    fun webSearchProviders(directory: String): SyncedResource<List<WebSearchProviderInfo>> =
        webSearchProviders.getOrPut(directory) {
            SyncedResource(
                key = dev.opencode.android.core.data.sync.ResourceKey(serverId, directory),
                name = "websearch.providers($directory)",
                scope = scope,
                loader = { api.listWebSearchProviders(directory).data },
            )
        }

    /** The directories this client has opened, which is what a resync and a shutdown walk. */
    val directories: Set<String>
        get() = (integrations.keys + providers.keys + mcpServers.keys + mcpResources.keys +
            plugins.keys + webSearchProviders.keys).toSet()

    // ------------------------------------------------------------------------------ capability

    private val mcpRuntimeState = MutableStateFlow<RouteAvailability>(RouteAvailability.Unknown)
    private val wellknownState = MutableStateFlow<RouteAvailability>(RouteAvailability.Unknown)

    /** Whether this server has `experimental.mcp.*`. */
    val mcpRuntime: StateFlow<RouteAvailability> = mcpRuntimeState.asStateFlow()

    /** Whether this server has `experimental.integration.wellknown`. */
    val wellknownSources: StateFlow<RouteAvailability> = wellknownState.asStateFlow()

    /**
     * Whether a runtime MCP write may be offered (plan §4.2).
     *
     * **Both conditions, and they are different conditions.** The switch is the user's decision that
     * this app may change the server's runtime; the availability is the only honest record of
     * whether the route exists, and it comes from a call the app made. A switch alone would show a
     * connect button on a server that has never heard of the route; availability alone would ignore
     * a user who has not agreed to the app touching their MCP configuration.
     */
    fun mcpUsable(allowedBySetting: Boolean): Boolean =
        allowedBySetting && CapabilityPolicy.isUsable(mcpRuntimeState.value)

    /** Whether adding a well-known integration source may be offered. */
    fun wellknownUsable(allowedBySetting: Boolean): Boolean =
        allowedBySetting && CapabilityPolicy.isUsable(wellknownState.value)

    /**
     * Records what a failed experimental call said about the route.
     *
     * **Only a `404`/`405` changes the answer**, which is [CapabilityPolicy]'s rule and not this
     * function's: a `500` proves the route is there, and treating it as absence would grey out a
     * working feature because of a transient fault.
     */
    fun recordFailure(route: ExperimentalRoute, error: ActionError) {
        // The whole error, not just its kind: a `405` has no `_tag` to classify by and arrives as
        // `SERVER`, so only the status tells the probe that the route exists but refuses this method.
        val availability = CapabilityPolicy.from(error) ?: return
        when (route) {
            ExperimentalRoute.MCP_RUNTIME -> mcpRuntimeState.value = availability
            ExperimentalRoute.WELLKNOWN_INTEGRATION -> wellknownState.value = availability
            else -> Unit
        }
    }

    private fun recordSuccess(route: ExperimentalRoute) {
        when (route) {
            ExperimentalRoute.MCP_RUNTIME -> mcpRuntimeState.value = RouteAvailability.Present
            ExperimentalRoute.WELLKNOWN_INTEGRATION -> wellknownState.value = RouteAvailability.Present
            else -> Unit
        }
    }

    // ------------------------------------------------------------------------------ writes

    /**
     * `integration.connect.key`.
     *
     * **The key goes in as a [Secret] and comes out as nothing.** The payload is built, handed to
     * the API, and dropped; the value is not stored, not put in a state object the UI can read back,
     * and not echoed into the error — a failure is reported by class, and the server's own message
     * is about the request, not the key.
     */
    suspend fun connectWithKey(
        directory: String,
        integrationID: String,
        key: String,
        answer: Map<String, kotlinx.serialization.json.JsonElement>? = null,
        label: String? = null,
    ): Result<Unit> = write {
        api.connectWithKey(integrationID, ConnectKeyRequest(Secret.of(key), answer, label), directory)
        integrations(directory).invalidate()
    }

    /** `integration.oauth.connect`. The returned attempt carries the URL the app may open. */
    suspend fun startOauth(
        directory: String,
        integrationID: String,
        methodID: String,
        answer: Map<String, kotlinx.serialization.json.JsonElement>? = null,
        label: String? = null,
    ): Result<dev.opencode.android.core.model.OAuthAttempt> = write {
        val attempt = api.connectWithOauth(
            integrationID,
            ConnectOAuthRequest(methodID, answer, label),
            directory,
        ).data
        integrations(directory).invalidate()
        attempt
    }

    /**
     * `integration.oauth.status`.
     *
     * Answers `null` for a `404`, because an attempt the server no longer has is the end of the
     * flow rather than an error the user should see.
     */
    suspend fun oauthStatus(
        directory: String,
        integrationID: String,
        attemptID: String,
    ): dev.opencode.android.core.model.OAuthAttemptStatus? = try {
        api.getOauthAttemptStatus(integrationID, attemptID, directory).data
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        if (error.toActionError().kind == dev.opencode.android.core.data.action.ActionErrorKind.NOT_FOUND) null else throw error
    }

    /**
     * `integration.oauth.complete`, for a `mode=code` attempt.
     *
     * The device code the user typed is not a secret of this app's, and it is passed as a plain
     * string because that is the wire; it is also short-lived and single-use, and nothing here
     * keeps it after the call.
     */
    suspend fun completeOauth(
        directory: String,
        integrationID: String,
        attemptID: String,
        code: String?,
    ): Result<Unit> = write {
        api.completeOauthAttempt(integrationID, attemptID, ConnectOAuthCompleteRequest(code), directory)
        integrations(directory).invalidate()
    }

    /** `integration.oauth.cancel`. Best effort: a cancel that fails has already been given up on. */
    suspend fun cancelOauth(directory: String, integrationID: String, attemptID: String) {
        runCatching { api.cancelOauthAttempt(integrationID, attemptID, directory) }
        integrations(directory).invalidate()
    }

    /** `integration.command.connect`. */
    suspend fun startCommand(
        directory: String,
        integrationID: String,
        methodID: String,
        label: String? = null,
    ): Result<dev.opencode.android.core.model.CommandAttempt> = write {
        val attempt = api.connectWithCommand(integrationID, ConnectCommandRequest(methodID, label), directory).data
        integrations(directory).invalidate()
        attempt
    }

    /** `integration.command.status`, or `null` when the server no longer has the attempt. */
    suspend fun commandStatus(
        directory: String,
        integrationID: String,
        attemptID: String,
    ): dev.opencode.android.core.model.CommandAttemptStatus? = try {
        api.getCommandAttemptStatus(integrationID, attemptID, directory).data
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        if (error.toActionError().kind == dev.opencode.android.core.data.action.ActionErrorKind.NOT_FOUND) null else throw error
    }

    /** `integration.command.cancel`. */
    suspend fun cancelCommand(directory: String, integrationID: String, attemptID: String) {
        runCatching { api.cancelCommandAttempt(integrationID, attemptID, directory) }
        integrations(directory).invalidate()
    }

    /** `credential.update`: renames a credential. Not location-scoped. */
    suspend fun renameCredential(credentialID: String, label: String): Result<Unit> = write {
        api.updateCredential(credentialID, CredentialUpdateRequest(label))
        // The credential is named on the integration, and every open location has a copy of that
        // list, so all of them are stale rather than the one the screen happens to show.
        integrations.values.forEach { it.invalidate() }
    }

    /** `credential.activate`: makes this the account in use. Not location-scoped. */
    suspend fun activateCredential(credentialID: String): Result<Unit> = write {
        api.activateCredential(credentialID)
        integrations.values.forEach { it.invalidate() }
    }

    /** `credential.remove`: logs out. Not location-scoped, and confirmed by the caller. */
    suspend fun removeCredential(credentialID: String): Result<Unit> = write {
        api.removeCredential(credentialID)
        integrations.values.forEach { it.invalidate() }
    }

    /**
     * `experimental.integration.wellknown`, with the URL checked first.
     *
     * **The check is here rather than in the UI because the UI is not the only caller.** The route
     * makes the *server* fetch a URL, so an `http://` or `https://` URL with a host is the only
     * thing that may be sent; a `file://` or `intent://` would make the server read a local file or
     * hand a URL to another app, and the same rule the OAuth Custom Tab uses applies.
     */
    suspend fun addWellknownSource(directory: String, url: String): Result<Unit> {
        val safe = dev.opencode.android.core.model.WellknownSourceUrl.parse(url)
            ?: return failure(
                ActionError(
                    kind = dev.opencode.android.core.data.action.ActionErrorKind.INVALID_REQUEST,
                    message = "Only an http or https address with a host can be added",
                ),
            )
        return experimental(ExperimentalRoute.WELLKNOWN_INTEGRATION) {
            api.addWellknownIntegration(WellknownSourceRequest(safe), directory)
            integrations(directory).invalidate()
        }
    }

    // ------------------------------------------------------------------------------ MCP

    /**
     * `experimental.mcp.add`.
     *
     * The config is a union built by [McpConfigForm], so the form's fields and the wire's shape
     * cannot drift: a form that collects a command produces a `local` config, and one that collects
     * a URL produces a `remote` one, with no path that could produce a config missing a required
     * field.
     */
    suspend fun addMcpServer(directory: String, server: String, config: McpServerConfig): Result<Unit> =
        experimental(ExperimentalRoute.MCP_RUNTIME) {
            api.putMcpServer(server, McpAddRequest(config), directory)
            mcpServers(directory).invalidate()
            mcpResources(directory).invalidate()
        }

    /** `experimental.mcp.remove`. The removal is confirmed by the caller (plan §5.2). */
    suspend fun removeMcpServer(directory: String, server: String): Result<Unit> =
        experimental(ExperimentalRoute.MCP_RUNTIME) {
            api.removeMcpServer(server, directory)
            mcpServers(directory).invalidate()
            mcpResources(directory).invalidate()
        }

    /**
     * `experimental.mcp.connect`.
     *
     * **The status list is invalidated, not the resource catalog.** Connecting a server makes it
     * publish resources, but it does not change what the *other* servers publish, and
     * `mcp.resources.changed` is the event that says so. Refetching both would double the cost of
     * the one action users do most.
     */
    suspend fun connectMcpServer(directory: String, server: String): Result<Unit> =
        experimental(ExperimentalRoute.MCP_RUNTIME) {
            api.connectMcpServer(server, directory)
            mcpServers(directory).invalidate()
        }

    /** `experimental.mcp.disconnect`. */
    suspend fun disconnectMcpServer(directory: String, server: String): Result<Unit> =
        experimental(ExperimentalRoute.MCP_RUNTIME) {
            api.disconnectMcpServer(server, directory)
            mcpServers(directory).invalidate()
        }

    // ------------------------------------------------------------------------------ plugins

    /**
     * `plugin.check`.
     *
     * **The answer replaces the list, it is not merged into it.** The route answers with every
     * plugin and refreshes the `outdated` flags, and a plugin that stopped being outdated has no
     * event to say so — so a merge would leave a stale "update available" on a row forever. A
     * `503` is returned as a failure rather than as an empty list, because "the registry is
     * unreachable" and "everything is up to date" are opposites and the UI must not show the second
     * for the first.
     */
    suspend fun checkPlugins(directory: String, target: String? = null): Result<List<PluginInfo>> {
        val answer = try {
            api.checkPlugins(PluginCheckRequest(target), directory).data
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            return failure(error.toActionError())
        }
        plugins(directory).complete(answer)
        return Result.success(answer)
    }

    /**
     * `plugin.update`.
     *
     * **Only package targets, and only ones that are outdated.** `plugin.update` takes package
     * targets; sending a builtin plugin's name or an already-current one is a request the server
     * cannot act on, and the UI's checkbox set is derived from [PluginInfo.isUpdatable] so the two
     * cannot disagree.
     */
    suspend fun updatePlugins(directory: String, plugins: List<PluginInfo>): Result<Unit> {
        val targets = plugins.filter { it.isUpdatable }.map { it.source.target!! }.distinct()
        if (targets.isEmpty()) return Result.success(Unit)
        return write {
            api.updatePlugins(PluginUpdateRequest(targets), directory)
            plugins(directory).invalidate()
        }
    }

    // ------------------------------------------------------------------------------ web search

    /**
     * `websearch.query`, for the "test this provider" screen.
     *
     * The answer names the provider that actually ran, which is not always the one that was asked
     * for: a `null` [providerID] means "the server's default", and showing the default's name is
     * the only way the user learns which one answered.
     */
    suspend fun queryWebSearch(directory: String, query: String, providerID: String?): Result<WebSearchResponse> = try {
        Result.success(api.queryWebSearch(WebSearchQueryRequest(query, providerID), directory).data)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        failure(error.toActionError())
    }

    // ------------------------------------------------------------------------------ events

    /**
     * Applies the Phase 8 events (features doc §10, §17, §18; plan §6).
     *
     * **The empty-payload ones invalidate; the two with a payload narrow.** `mcp.status.changed
     * {server}` and `mcp.resources.changed {server}` name a server, and only that server's status
     * row and catalog entries are affected — so a directory-scoped invalidation of the whole list
     * would be correct but would cost a request for every server on the box. The client cannot
     * patch a single row out of a list it does not hold, so the narrow case still refetches, but
     * only the one resource it names.
     *
     * `credential.switched {integrationID, credentialID}` carries enough to know *which* integration
     * changed, and the client holds the list per directory rather than per integration, so the
     * honest response is the same refetch — but the integration id is what the OAuth-completion
     * notification names, so it is read here rather than discarded.
     */
    fun apply(event: Event) {
        when (val payload = event.payload) {
            is EventPayload.IntegrationUpdated,
            is EventPayload.CredentialUpdated,
            -> integrations.values.forEach { it.invalidate() }

            is EventPayload.ProviderUpdated -> providers.values.forEach { it.invalidate() }
            is EventPayload.WebsearchUpdated -> webSearchProviders.values.forEach { it.invalidate() }
            is EventPayload.PluginUpdated -> plugins.values.forEach { it.invalidate() }
            is McpStatusChanged -> mcpServers.values.forEach { it.invalidate() }
            is McpResourcesChanged -> mcpResources.values.forEach { it.invalidate() }
            is CredentialSwitched -> integrations.values.forEach { it.invalidate() }
            else -> Unit
        }
    }

    /**
     * Invalidates every catalog, for an event that names no particular one.
     *
     * **`config.updated` is the case that needs it.** A configuration document can name a provider, an
     * MCP server or a plugin, and the event carries no payload, so the only correct response is the
     * blunt one: stop answering from catalogs the app now knows are stale. The events that *do* name
     * something are the narrow ones and are handled in [apply].
     */
    fun invalidate() {
        integrations.values.forEach { it.invalidate() }
        providers.values.forEach { it.invalidate() }
        mcpServers.values.forEach { it.invalidate() }
        mcpResources.values.forEach { it.invalidate() }
        plugins.values.forEach { it.invalidate() }
        webSearchProviders.values.forEach { it.invalidate() }
    }

    /** A `server.connected` resync: the client has no idea what it missed, so everything is re-read. */
    fun resync() {
        integrations.values.forEach { it.invalidate() }
        providers.values.forEach { it.invalidate() }
        mcpServers.values.forEach { it.invalidate() }
        mcpResources.values.forEach { it.invalidate() }
        plugins.values.forEach { it.invalidate() }
        webSearchProviders.values.forEach { it.invalidate() }
        // The capability answer is a record of calls this process made, and a reconnect does not
        // make a route appear. It is kept on purpose: re-probing would be one request per route per
        // reconnect, for an answer that is a property of the server's build.
    }

    /** `location.shutdown` drops that location's catalogs and nothing else. */
    fun dropLocation(directory: String) {
        integrations.remove(directory)?.clear()
        providers.remove(directory)?.clear()
        mcpServers.remove(directory)?.clear()
        mcpResources.remove(directory)?.clear()
        plugins.remove(directory)?.clear()
        webSearchProviders.remove(directory)?.clear()
    }

    fun clear() {
        integrations.values.forEach { it.clear() }
        providers.values.forEach { it.clear() }
        mcpServers.values.forEach { it.clear() }
        mcpResources.values.forEach { it.clear() }
        plugins.values.forEach { it.clear() }
        webSearchProviders.values.forEach { it.clear() }
        integrations.clear()
        providers.clear()
        mcpServers.clear()
        mcpResources.clear()
        plugins.clear()
        webSearchProviders.clear()
    }

    // ------------------------------------------------------------------------------ internals

    /**
     * Runs a write, turning a failure into an [ActionError] and keeping cancellation a cancellation.
     *
     * **`ActionFailure` rather than the raw throwable**, because every screen in this phase reports
     * the *class* of a failure rather than its text, and because a raw `HttpException` carries the
     * request URL — which for `connect.key` does not contain the key, but which is still a string
     * this app has no reason to print.
     */
    private suspend inline fun <T> write(crossinline block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        failure(error.toActionError())
    }

    /** A write that also records what the call said about its experimental route. */
    private suspend inline fun experimental(
        route: ExperimentalRoute,
        crossinline block: suspend () -> Unit,
    ): Result<Unit> {
        val result = write { block() }
        result.exceptionOrNull()?.let { error ->
            recordFailure(route, (error as? ActionFailure)?.error ?: error.toActionError())
        } ?: recordSuccess(route)
        return result
    }

    private fun <T> failure(error: ActionError): Result<T> = Result.failure(ActionFailure(error))
}

/** A write that failed, carrying the classification every screen reports. */
/** The one [ActionFailure], in the action package; see its note for why. */
typealias ActionFailure = dev.opencode.android.core.data.action.ActionFailure
