package dev.opencode.android.feature.composer.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.opencode.android.core.data.catalog.AgentCatalog
import dev.opencode.android.core.data.catalog.ModelCatalog
import dev.opencode.android.core.data.preferences.ModelPreferences
import dev.opencode.android.core.data.server.ServerDataRegistry
import dev.opencode.android.core.data.server.actionErrorOrNull
import dev.opencode.android.core.model.AgentInfo
import dev.opencode.android.core.model.FileSystemEntry
import dev.opencode.android.core.model.ModelInfo
import dev.opencode.android.core.model.ModelRef
import dev.opencode.android.core.model.Project
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Where a new session will run. */
sealed interface LocationChoice {
    /** A project, whose canonical directory is the location. */
    data class ProjectChoice(val id: String, val name: String, val directory: String) : LocationChoice

    /** A directory reached with the `fs.list` browser. */
    data class Browsed(val directory: String, val path: String?) : LocationChoice

    /** The directory this choice resolves to, which is what the catalogs and the browser need. */
    fun directoryOrNull(): String? = when (this) {
        is ProjectChoice -> directory
        is Browsed -> directory
    }
}

/** The new-session form, and what the user has chosen so far. */
data class NewSessionUiState(
    val title: String = "",
    val location: LocationChoice? = null,
    val agent: String? = null,
    val model: ModelRef? = null,
    val models: List<ModelInfo> = emptyList(),
    val agents: List<AgentInfo> = emptyList(),
    val projects: List<Project> = emptyList(),
    /** The directories recent sessions ran in, which is the "recent directories" the plan lists. */
    val recentDirectories: List<String> = emptyList(),
    val browsing: Boolean = false,
    val entries: List<FileSystemEntry> = emptyList(),
    val browserPath: String? = null,
    val browserLoading: Boolean = false,
    val creating: Boolean = false,
    val error: String? = null,
) {
    /** The location's directory, or `null` before one is chosen. */
    val directory: String? get() = location?.directoryOrNull()

    /** The agents a user may select here; subagents and hidden agents are not offered. */
    val primaryAgents: List<AgentInfo> get() = AgentCatalog.primary(agents)

    val canCreate: Boolean get() = location != null && !creating

    /** The grouped, filtered catalog the model picker shows. */
    fun modelGroups(
        favorites: List<ModelRef>,
        recents: List<ModelRef>,
        search: String,
    ): List<ModelCatalog.ProviderGroup> = ModelCatalog.group(models, favorites, recents, search)
}

/**
 * Creating a session: a location, an agent, a model and an optional title (plan §6, New session).
 *
 * **The location comes first, because everything else is scoped to it.** `agent.list` and
 * `model.list` are location-scoped (features doc §2.6), so choosing a directory is what makes the
 * agent and model lists meaningful; the catalogs are re-read whenever it changes.
 *
 * **Omitting a choice leaves it to the server.** The create body leaves the model out when the user
 * did not pick one, so `model.default` decides, and the agent out when the location defines no
 * selectable agent. Guessing a default here would break the "the client never guesses" rule
 * (plan §4.2) for the one action that starts everything else.
 *
 * **The browser keeps the server's path spelling.** `fs.list` answers with paths relative to the
 * location, including a `..` entry, so the store re-asks with the path the server gave rather than
 * joining and normalizing locally: a path this client invented is one the server never offered.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NewSessionViewModel @Inject constructor(
    private val dataSets: ServerDataRegistry,
    private val modelPreferences: ModelPreferences,
) : ViewModel() {

    private val local = MutableStateFlow(LocalState())
    private val choice = MutableStateFlow<LocationChoice?>(null)
    private val createdSession = MutableStateFlow<String?>(null)

    private data class LocalState(
        val title: String = "",
        val agent: String? = null,
        val model: ModelRef? = null,
        val browsing: Boolean = false,
        val browserPath: String? = null,
        val creating: Boolean = false,
        val error: String? = null,
    )

    /** The catalogs of the chosen location, or empty ones before a location is chosen. */
    private val catalog: Flow<Catalog> = combine(dataSets.active, choice) { set, location -> set to location }
        .flatMapLatest { (set, location) ->
            val directory = location?.directoryOrNull()
            if (set == null || directory == null) {
                flowOf(Catalog())
            } else {
                combine(
                    set.agents(directory).state,
                    set.models(directory).state,
                    set.sessions.info,
                ) { agents, models, sessions ->
                    Catalog(
                        agents = agents.value.orEmpty(),
                        models = models.value.orEmpty(),
                        recentDirectories = sessions.values
                            .mapNotNull { it.location.directory.takeIf(String::isNotBlank) }
                            .distinct()
                            .take(RECENT_LIMIT),
                    )
                }
            }
        }

    val state: StateFlow<NewSessionUiState> = combine(
        dataSets.active,
        choice,
        local,
        catalog,
    ) { set, location, mine, catalogs ->
        val browser = set?.browser?.state?.value
        NewSessionUiState(
            title = mine.title,
            location = location,
            agent = mine.agent,
            model = mine.model,
            models = catalogs.models,
            agents = catalogs.agents,
            projects = set?.projects?.value.orEmpty(),
            recentDirectories = catalogs.recentDirectories,
            browsing = mine.browsing,
            entries = browser?.sorted.orEmpty(),
            browserPath = browser?.path,
            browserLoading = browser?.loading == true,
            creating = mine.creating,
            error = mine.error,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), NewSessionUiState())

    /** The id of the session that was created, for the caller to navigate to. */
    val created: StateFlow<String?> = createdSession.asStateFlow()

    /**
     * The models this device pinned and used recently on this server.
     *
     * The client owns these (features doc §8), so they are read from the store rather than from the
     * server, and they follow the active server the way the session's picker does.
     */
    val modelFavorites: StateFlow<List<ModelRef>> = dataSets.active
        .map { it?.serverId }
        .distinctUntilChanged()
        .flatMapLatest { serverId -> serverId?.let(modelPreferences::favorites) ?: flowOf(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), emptyList())

    /** The models used most recently on this server, newest first. */
    val modelRecents: StateFlow<List<ModelRef>> = dataSets.active
        .map { it?.serverId }
        .distinctUntilChanged()
        .flatMapLatest { serverId -> serverId?.let(modelPreferences::recents) ?: flowOf(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), emptyList())

    /** Pins or unpins a model, which the new-session sheet's picker offers too. */
    fun toggleFavorite(model: ModelRef) {
        val serverId = dataSets.active.value?.serverId ?: return
        viewModelScope.launch { modelPreferences.toggleFavorite(serverId, model) }
    }

    fun setTitle(title: String) {
        local.value = local.value.copy(title = title)
    }

    /** Picks a location, clearing the scoped choices and re-reading the catalogs for it. */
    fun selectLocation(location: LocationChoice) {
        choice.value = location
        local.value = local.value.copy(agent = null, model = null, browserPath = null)
        loadCatalogs(location)
    }

    fun selectAgent(agent: String) {
        local.value = local.value.copy(agent = agent)
    }

    fun selectModel(model: ModelRef) {
        local.value = local.value.copy(model = model)
    }

    fun openBrowser() {
        val directory = choice.value?.directoryOrNull() ?: return
        local.value = local.value.copy(browsing = true, browserPath = null)
        dataSets.active.value?.browser?.list(directory, null)
    }

    fun closeBrowser() {
        local.value = local.value.copy(browsing = false)
        dataSets.active.value?.browser?.clear()
    }

    /** Walks into a directory the server listed, using the server's own spelling of the path. */
    fun enterDirectory(path: String) {
        val directory = choice.value?.directoryOrNull() ?: return
        local.value = local.value.copy(browserPath = path)
        dataSets.active.value?.browser?.list(directory, path)
    }

    /**
     * Goes up one level.
     *
     * `fs.list` answers `/` from any path, so up is the parent of the last segment; a path that is
     * already the root stays at the root rather than becoming empty, which the server would reject.
     */
    fun goUp() {
        val directory = choice.value?.directoryOrNull() ?: return
        val current = local.value.browserPath
        val parent = when {
            current.isNullOrEmpty() || current == "/" -> "/"
            else -> current.trimEnd('/').substringBeforeLast('/', "").ifEmpty { "/" }
        }
        local.value = local.value.copy(browserPath = parent)
        dataSets.active.value?.browser?.list(directory, parent)
    }

    /** Accepts the directory the browser is currently in. */
    fun useBrowsedDirectory() {
        val location = choice.value ?: return
        val directory = location.directoryOrNull() ?: return
        selectLocation(LocationChoice.Browsed(directory, local.value.browserPath))
        closeBrowser()
    }

    /**
     * `session.create`.
     *
     * Navigation is the caller's business: this only reports the id, so the sheet works from the
     * home, from a project and from a notification without knowing where it was opened.
     */
    fun create() {
        val set = dataSets.active.value ?: return
        val location = choice.value ?: return
        val directory = location.directoryOrNull() ?: return
        if (local.value.creating) return
        local.value = local.value.copy(creating = true, error = null)
        viewModelScope.launch {
            val result = set.commands.create(
                title = local.value.title.takeIf { it.isNotBlank() },
                agent = local.value.agent,
                model = local.value.model,
                directory = directory,
            )
            val error = result.actionErrorOrNull
            if (error == null) {
                createdSession.value = result.getOrNull()?.id
                local.value = local.value.copy(creating = false)
            } else {
                local.value = local.value.copy(creating = false, error = error.message)
            }
        }
    }

    /** Called when the sheet is dismissed, so the next one starts clean. */
    fun reset() {
        local.value = LocalState()
        choice.value = null
        createdSession.value = null
        dataSets.active.value?.browser?.clear()
    }

    fun dismissError() {
        local.value = local.value.copy(error = null)
    }

    /**
     * Reads the catalogs for a location, and adopts `model.default` when nothing was chosen.
     *
     * The default is only adopted while the user has not picked anything, because re-reading the
     * catalogs after a pick must not overwrite the pick.
     */
    private fun loadCatalogs(location: LocationChoice) {
        val set = dataSets.active.value ?: return
        val directory = location.directoryOrNull() ?: return
        viewModelScope.launch {
            set.agents(directory).sync()
            set.models(directory).sync()
            if (local.value.model == null) {
                val defaults = set.defaultModel(directory)
                defaults.sync()
                defaults.value?.let { default ->
                    local.value = local.value.copy(model = ModelRef(default.id, default.providerID))
                }
            }
        }
    }

    private data class Catalog(
        val agents: List<AgentInfo> = emptyList(),
        val models: List<ModelInfo> = emptyList(),
        val recentDirectories: List<String> = emptyList(),
    )

    private companion object {
        const val STOP_TIMEOUT = 5_000L

        /** How many recent directories the picker offers; a short list is easier to use than a log. */
        const val RECENT_LIMIT = 8
    }
}
