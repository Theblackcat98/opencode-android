package dev.opencode.android.feature.integrations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.opencode.android.core.data.action.ActionError
import dev.opencode.android.core.data.action.toActionError
import dev.opencode.android.core.data.integrations.McpConfigForm
import dev.opencode.android.core.data.integrations.McpConfigProblem
import dev.opencode.android.core.data.integrations.McpServerDraft
import dev.opencode.android.core.data.preferences.ExperimentalPreferences
import dev.opencode.android.core.data.server.ServerDataRegistry
import dev.opencode.android.core.data.sync.SyncedState
import dev.opencode.android.core.model.McpProtocol
import dev.opencode.android.core.model.McpResource
import dev.opencode.android.core.model.McpResourceTemplate
import dev.opencode.android.core.model.McpServer
import dev.opencode.android.core.model.McpServerConfig
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The MCP screen's state (plan §6, "MCP"). */
data class McpUiState(
    val directory: String? = null,
    val servers: SyncedState<List<McpServer>> = SyncedState(),
    val resources: SyncedState<dev.opencode.android.core.model.McpResourceCatalog> = SyncedState(),
    /**
     * Whether the runtime writes may be offered.
     *
     * **Both the switch and the route's availability, folded into one value** so a screen cannot
     * offer a connect button on a server that has never heard of the route, nor hide it on one that
     * has it when the user has agreed to the switch.
     */
    val runtimeUsable: Boolean = false,
    val draft: McpServerDraft = McpServerDraft(),
    val addSheetOpen: Boolean = false,
    val resourcesOpen: Boolean = false,
    /** The server awaiting removal, held until the user confirms (plan §5.2). */
    val removeTarget: String? = null,
    /** The resource the user picked to attach to a prompt, handed back to the graph. */
    val attachTarget: McpResource? = null,
    val busy: Boolean = false,
    val error: ActionError? = null,
) {
    val rows: List<McpServer> get() = servers.value.orEmpty()
    val catalog: List<McpResource> get() = resources.value?.resources.orEmpty()
    val templates: List<McpResourceTemplate> get() = resources.value?.templates.orEmpty()

    /** The form's problems, which the sheet marks fields with. */
    val problems: List<McpConfigProblem> get() = McpConfigForm.problems(draft)

    /** Whether the add form may be sent. */
    val canAdd: Boolean get() = runtimeUsable && problems.isEmpty() && !busy

    /** Whether a runtime write on [server] may be sent, which is the switch plus the route. */
    fun canConnect(server: McpServer): Boolean = runtimeUsable && !server.isConnected && !busy
    fun canDisconnect(server: McpServer): Boolean = runtimeUsable && server.isConnected && !busy

    /** A `needs_auth` server can be fixed only if it names the integration that owns the flow. */
    fun canAuthenticate(server: McpServer): Boolean = server.isDisabledForAuth && server.integrationID != null
}

/**
 * The MCP screen: the server list with status, the runtime writes, the resource catalog and the
 * add form.
 *
 * **A `needs_auth` server is fixed by opening the *integration's* login, not by a call of its own**
 * (plan §6: "OAuth for `needs_auth` servers, through the server's integration"). [McpServer.integrationID]
 * is what makes that possible, and [openAuth] is the only path that reads it — a server that reports
 * `needs_auth` without one gets a row that says so and no button, because there is no flow this
 * client could start.
 */
class McpViewModel @Inject constructor(
    private val dataSets: ServerDataRegistry,
    private val experimental: ExperimentalPreferences,
) : ViewModel() {

    private val _state = MutableStateFlow(McpUiState())
    val state: StateFlow<McpUiState> = _state.asStateFlow()

    private var collector: Job? = null
    private var settingsJob: Job? = null

    fun open(directory: String) {
        val set = dataSets.active.value ?: return
        _state.value = _state.value.copy(directory = directory)
        collector?.cancel()
        collector = viewModelScope.launch {
            set.integrations.mcpServers(directory).state.collect { syncing ->
                _state.value = _state.value.copy(servers = syncing)
            }
        }
        resourcesJob?.cancel()
        resourcesJob = viewModelScope.launch {
            set.integrations.mcpResources(directory).state.collect { syncing ->
                _state.value = _state.value.copy(resources = syncing)
            }
        }
        settingsJob?.cancel()
        settingsJob = viewModelScope.launch {
            experimental.settings.collect { settings ->
                _state.value = _state.value.copy(
                    runtimeUsable = set.integrations.mcpUsable(allowedBySetting = settings.mcpRuntime),
                )
            }
        }
        viewModelScope.launch {
            set.integrations.mcpServers(directory).sync()
            set.integrations.mcpResources(directory).sync()
        }
    }

    fun resume() {
        val set = dataSets.active.value ?: return
        val directory = _state.value.directory ?: return
        viewModelScope.launch {
            set.integrations.mcpServers(directory).sync()
            set.integrations.mcpResources(directory).sync()
        }
    }

    // ------------------------------------------------------------------------------ runtime writes

    fun connect(server: McpServer) = runtimeWrite { it.connectMcpServer(_directory(), server.name) }

    fun disconnect(server: McpServer) = runtimeWrite { it.disconnectMcpServer(_directory(), server.name) }

    fun requestRemove(server: McpServer) {
        _state.value = _state.value.copy(removeTarget = server.name, error = null)
    }

    fun cancelRemove() {
        _state.value = _state.value.copy(removeTarget = null)
    }

    /** `experimental.mcp.remove`, after the confirmation. */
    fun confirmRemove() {
        val target = _state.value.removeTarget ?: return
        _state.value = _state.value.copy(removeTarget = null)
        runtimeWrite { it.removeMcpServer(_directory(), target) }
    }

    // ------------------------------------------------------------------------------ the add form

    fun openAddSheet() {
        _state.value = _state.value.copy(addSheetOpen = true, draft = McpServerDraft(), error = null)
    }

    fun dismissAddSheet() {
        _state.value = _state.value.copy(addSheetOpen = false, draft = McpServerDraft())
    }

    fun updateDraft(transform: (McpServerDraft) -> McpServerDraft) {
        _state.value = _state.value.copy(draft = transform(_state.value.draft))
    }

    fun setKind(kind: McpServerDraft.Kind) = updateDraft { it.copy(kind = kind) }

    /**
     * `experimental.mcp.add`.
     *
     * **The config is built by [McpConfigForm], not by the view model**, so a form that collects
     * fields the union does not have cannot produce a request the server rejects. The sheet closes on
     * success and the list is re-read, because the new server arrives through `mcp.status.changed`
     * and nothing else.
     */
    fun addServer() {
        val set = dataSets.active.value ?: return
        val directory = _directory()
        val config: McpServerConfig = McpConfigForm.toConfig(_state.value.draft) ?: return
        val name = _state.value.draft.name.trim()
        _state.value = _state.value.copy(busy = true, error = null)
        viewModelScope.launch {
            val result = set.integrations.addMcpServer(directory, name, config)
            val error = result.exceptionOrNull()?.toActionError()
            _state.value = _state.value.copy(
                busy = false,
                error = error,
                addSheetOpen = if (error == null) false else _state.value.addSheetOpen,
                draft = if (error == null) McpServerDraft() else _state.value.draft,
            )
            if (error == null) viewModelScope.launch { set.integrations.mcpServers(directory).sync(force = true) }
        }
    }

    // ------------------------------------------------------------------------------ resources

    fun openResources() {
        _state.value = _state.value.copy(resourcesOpen = true)
        val set = dataSets.active.value ?: return
        viewModelScope.launch { set.integrations.mcpResources(_directory()).sync(force = true) }
    }

    fun closeResources() {
        _state.value = _state.value.copy(resourcesOpen = false)
    }

    /**
     * "Attach resource to prompt" (plan §6).
     *
     * **A selection the graph consumes, not a write.** The resource is a URI the server already
     * knows; what the user wants is for it to be in the next prompt, and the composer owns prompt
     * contents. So the picked resource is published in the state and the host passes it to the
     * composer, which is the same arrangement the attach flows of Phase 5 use.
     */
    fun attach(resource: McpResource) {
        _state.value = _state.value.copy(attachTarget = resource, resourcesOpen = false)
    }

    /** Called by the host once the composer has taken it. */
    fun consumeAttach() {
        _state.value = _state.value.copy(attachTarget = null)
    }

    fun dismissError() {
        _state.value = _state.value.copy(error = null)
    }

    override fun onCleared() {
        collector?.cancel()
        resourcesJob?.cancel()
        settingsJob?.cancel()
        super.onCleared()
    }

    private var resourcesJob: Job? = null

    private fun _directory(): String = _state.value.directory.orEmpty()

    private fun runtimeWrite(block: suspend (dev.opencode.android.core.data.integrations.IntegrationSurface) -> Result<Unit>) {
        val set = dataSets.active.value ?: return
        _state.value = _state.value.copy(busy = true, error = null)
        viewModelScope.launch {
            val result = block(set.integrations)
            val error = result.exceptionOrNull()?.toActionError()
            _state.value = _state.value.copy(busy = false, error = error)
            if (error == null) {
                val directory = _directory()
                viewModelScope.launch { set.integrations.mcpServers(directory).sync(force = true) }
            }
        }
    }
}

/** The three protocols, for the picker. Reads [McpProtocol] so the wire's values are the only ones. */
val mcpProtocols: List<String> get() = McpProtocol.ALL
