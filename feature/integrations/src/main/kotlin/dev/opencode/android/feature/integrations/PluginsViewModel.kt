package dev.opencode.android.feature.integrations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.opencode.android.core.data.action.ActionError
import dev.opencode.android.core.data.action.toActionError
import dev.opencode.android.core.data.server.ServerDataRegistry
import dev.opencode.android.core.data.sync.SyncedState
import dev.opencode.android.core.model.PluginInfo
import dev.opencode.android.core.model.ProviderInfo
import dev.opencode.android.core.model.WebSearchProviderInfo
import dev.opencode.android.core.model.WebSearchResponse
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The plugins screen (plan §6, "Plugins"). */
data class PluginsUiState(
    val directory: String? = null,
    val plugins: SyncedState<List<PluginInfo>> = SyncedState(),
    /** The packages the user has ticked to update, which is a subset of the outdated ones. */
    val selected: Set<String> = emptySet(),
    val checking: Boolean = false,
    val updating: Boolean = false,
    /** The last line the screen reports, which is how a check says what it found. */
    val status: String? = null,
    val error: ActionError? = null,
) {
    val rows: List<PluginInfo> get() = plugins.value.orEmpty()

    /** How many plugins have a newer version, which is the badge on the "check" button. */
    val outdatedCount: Int get() = rows.count { it.isOutdated }

    /** Whether anything is selected to update. */
    val canUpdate: Boolean get() = selected.isNotEmpty() && !updating

    /**
     * Whether "update all" is offered.
     *
     * **Tied to the outdated count, not to the list size.** `plugin.update` re-downloads, so a
     * button that appears when nothing is outdated is a request that cannot help.
     */
    val canUpdateAll: Boolean get() = outdatedCount > 0 && !updating
}

/**
 * The plugins screen: the list, the check, and the update.
 *
 * **`plugin.check` replaces the list rather than merging into it, and this is the reason the store
 * does that.** A plugin that stopped being outdated has no event to say so, so a merge would leave
 * a stale "update available" badge on a plugin the registry has already superseded. A `503` is a
 * failure, not an empty answer: "the registry is unreachable" and "everything is current" are
 * opposites and the status line has to say which one happened.
 */
@HiltViewModel
class PluginsViewModel @Inject constructor(
    private val dataSets: ServerDataRegistry,
) : ViewModel() {

    private val _state = MutableStateFlow(PluginsUiState())
    val state: StateFlow<PluginsUiState> = _state.asStateFlow()

    private var collector: Job? = null

    fun open(directory: String) {
        val set = dataSets.active.value ?: return
        _state.value = _state.value.copy(directory = directory)
        collector?.cancel()
        collector = viewModelScope.launch {
            set.integrations.plugins(directory).state.collect { syncing ->
                val rows = syncing.value.orEmpty()
                val stillThere = _state.value.selected.intersect(rows.filter { it.isUpdatable }.map { it.key }.toSet())
                _state.value = _state.value.copy(plugins = syncing, selected = stillThere)
            }
        }
        viewModelScope.launch { set.integrations.plugins(directory).sync() }
    }

    fun resume() {
        val set = dataSets.active.value ?: return
        val directory = _state.value.directory ?: return
        viewModelScope.launch { set.integrations.plugins(directory).sync() }
    }

    fun toggle(key: String) {
        val selected = _state.value.selected
        _state.value = _state.value.copy(
            selected = if (key in selected) selected - key else selected + key,
            error = null,
        )
    }

    fun selectAll() {
        val targets = _state.value.rows.filter { it.isUpdatable }.map { it.key }.toSet()
        _state.value = _state.value.copy(selected = targets)
    }

    fun clearSelection() {
        _state.value = _state.value.copy(selected = emptySet())
    }

    /** `plugin.check`, for one plugin or for all of them. */
    fun check(target: String? = null) {
        val set = dataSets.active.value ?: return
        val directory = _state.value.directory ?: return
        _state.value = _state.value.copy(checking = true, error = null, status = null)
        viewModelScope.launch {
            val result = set.integrations.checkPlugins(directory, target)
            val error = result.exceptionOrNull()?.toActionError()
            val found = result.getOrNull()?.count { it.isOutdated } ?: 0
            _state.value = _state.value.copy(
                checking = false,
                error = error,
                status = when {
                    error != null -> null
                    found == 0 -> "Every plugin is up to date"
                    else -> "$found plugin${if (found == 1) "" else "s"} can be updated"
                },
            )
        }
    }

    /**
     * `plugin.update`, for the ticked packages.
     *
     * **The selection is intersected with what is actually updatable** inside the store, so a tap on
     * "update" after a `plugin.updated` event invalidated the list cannot send a target that has
     * nothing to fetch. The status line says what was attempted rather than assuming success from a
     * `204`.
     */
    fun update() {
        val set = dataSets.active.value ?: return
        val directory = _state.value.directory ?: return
        val chosen = _state.value.rows.filter { it.key in _state.value.selected }
        if (chosen.isEmpty()) return
        _state.value = _state.value.copy(updating = true, error = null, status = null)
        viewModelScope.launch {
            val result = set.integrations.updatePlugins(directory, chosen)
            val error = result.exceptionOrNull()?.toActionError()
            _state.value = _state.value.copy(
                updating = false,
                error = error,
                selected = if (error == null) emptySet() else _state.value.selected,
                status = if (error == null) {
                    val count = chosen.count { it.isUpdatable }
                    if (count ==
                        0
                    ) {
                        "Nothing needed updating"
                    } else {
                        "Updating $count plugin${if (count == 1) "" else "s"}"
                    }
                } else {
                    null
                },
            )
            if (error == null) {
                viewModelScope.launch { set.integrations.plugins(directory).sync(force = true) }
            }
        }
    }

    fun dismissError() {
        _state.value = _state.value.copy(error = null)
    }

    override fun onCleared() {
        collector?.cancel()
        super.onCleared()
    }
}

/** The providers screen (plan §6, "Providers and models"). */
data class ProvidersUiState(
    val directory: String? = null,
    val providers: SyncedState<List<ProviderInfo>> = SyncedState(),
    val search: String = "",
    val selected: ProviderInfo? = null,
    val error: ActionError? = null,
) {
    val rows: List<ProviderInfo>
        get() {
            val all = providers.value.orEmpty()
            val query = search.trim()
            if (query.isEmpty()) return all
            return all.filter { provider ->
                provider.id.contains(query, ignoreCase = true) || provider.name.contains(query, ignoreCase = true)
            }
        }
}

/**
 * The providers screen, read-only (plan §6).
 *
 * **Read-only on purpose, and the UI says why.** Activation, `settings`, `headers` and `body` are
 * all config (features doc §9), so a switch here would either not take effect or would need the
 * experimental config writer that Phase 9 owns. The screen shows the state the server reports and
 * links to the editor, which is the honest arrangement rather than a control that silently does
 * nothing.
 */
@HiltViewModel
class ProvidersViewModel @Inject constructor(
    private val dataSets: ServerDataRegistry,
) : ViewModel() {

    private val _state = MutableStateFlow(ProvidersUiState())
    val state: StateFlow<ProvidersUiState> = _state.asStateFlow()

    private var collector: Job? = null

    fun open(directory: String) {
        val set = dataSets.active.value ?: return
        _state.value = _state.value.copy(directory = directory)
        collector?.cancel()
        collector = viewModelScope.launch {
            set.integrations.providers(directory).state.collect { syncing ->
                val selected = _state.value.selected?.id?.let { id -> syncing.value?.firstOrNull { it.id == id } }
                _state.value = _state.value.copy(providers = syncing, selected = selected)
            }
        }
        viewModelScope.launch { set.integrations.providers(directory).sync() }
    }

    fun resume() {
        val set = dataSets.active.value ?: return
        val directory = _state.value.directory ?: return
        viewModelScope.launch { set.integrations.providers(directory).sync() }
    }

    fun setSearch(query: String) {
        _state.value = _state.value.copy(search = query)
    }

    fun select(provider: ProviderInfo?) {
        _state.value = _state.value.copy(selected = provider)
    }

    override fun onCleared() {
        collector?.cancel()
        super.onCleared()
    }
}

/** The web-search screen (plan §6, "Web search"). */
data class WebSearchUiState(
    val directory: String? = null,
    val providers: SyncedState<List<WebSearchProviderInfo>> = SyncedState(),
    val selected: String? = null,
    val query: String = "",
    val response: WebSearchResponse? = null,
    val searching: Boolean = false,
    val error: ActionError? = null,
) {
    val rows: List<WebSearchProviderInfo> get() = providers.value.orEmpty()

    /**
     * Whether the search button is enabled.
     *
     * **A non-blank query is the only requirement.** Selecting a provider is optional because the
     * server has a default, and a screen that forced a choice would send a `providerID` the user did
     * not pick and could not otherwise have chosen.
     */
    val canSearch: Boolean get() = query.isNotBlank() && !searching
}

/**
 * The web-search screen: the providers and a test query.
 *
 * **The answer names the provider that ran, not the one that was asked for.** A `null`
 * [WebSearchQueryRequest.providerID] means "the server's configured default", and a user testing
 * whether their default works needs to know *which* provider answered — so the screen shows
 * [WebSearchResponse.providerID] and never the requested value.
 */
@HiltViewModel
class WebSearchViewModel @Inject constructor(
    private val dataSets: ServerDataRegistry,
) : ViewModel() {

    private val _state = MutableStateFlow(WebSearchUiState())
    val state: StateFlow<WebSearchUiState> = _state.asStateFlow()

    private var collector: Job? = null

    fun open(directory: String) {
        val set = dataSets.active.value ?: return
        _state.value = _state.value.copy(directory = directory)
        collector?.cancel()
        collector = viewModelScope.launch {
            set.integrations.webSearchProviders(directory).state.collect { syncing ->
                _state.value = _state.value.copy(providers = syncing)
            }
        }
        viewModelScope.launch { set.integrations.webSearchProviders(directory).sync() }
    }

    fun resume() {
        val set = dataSets.active.value ?: return
        val directory = _state.value.directory ?: return
        viewModelScope.launch { set.integrations.webSearchProviders(directory).sync() }
    }

    fun select(providerID: String?) {
        _state.value = _state.value.copy(selected = providerID, error = null)
    }

    fun setQuery(query: String) {
        _state.value = _state.value.copy(query = query)
    }

    /**
     * `websearch.query`.
     *
     * **A `503` is reported as a failure with the providers list refreshed.** The `503` is what the
     * server answers when no provider is configured at all, so it is a fact about the configuration
     * rather than a fault, and the screen says so instead of showing an empty result set.
     */
    fun search() {
        val set = dataSets.active.value ?: return
        val directory = _state.value.directory ?: return
        val state = _state.value
        if (!state.canSearch) return
        _state.value = state.copy(searching = true, error = null, response = null)
        viewModelScope.launch {
            val result = set.integrations.queryWebSearch(directory, state.query.trim(), state.selected)
            _state.value = _state.value.copy(
                searching = false,
                error = result.exceptionOrNull()?.toActionError(),
                response = result.getOrNull(),
            )
        }
    }

    fun dismissError() {
        _state.value = _state.value.copy(error = null)
    }

    override fun onCleared() {
        collector?.cancel()
        super.onCleared()
    }
}
