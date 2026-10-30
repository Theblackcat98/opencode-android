package dev.opencode.android.feature.insights

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.opencode.android.core.data.action.toActionError
import dev.opencode.android.core.data.integrations.ActionFailure
import dev.opencode.android.core.data.server.ServerDataRegistry
import dev.opencode.android.core.data.tui.TuiControl
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject

/** The tab chosen on the RPC console. */
enum class RpcConsoleTab { CALL, EVENTS }

/**
 * The RPC console's state: which plugin method to call, the JSON to send, and what came back.
 *
 * [inputInvalid] is a flag rather than a message because the words belong in `strings.xml`, and a view
 * model has no context to read them from.
 */
data class RpcConsoleUiState(
    val tab: RpcConsoleTab = RpcConsoleTab.CALL,
    val rpcID: String = "",
    val method: String = "",
    val input: String = "",
    val calling: Boolean = false,
    val inputInvalid: Boolean = false,
    val output: String? = null,
    val error: String? = null,
) {
    val canCall: Boolean get() = !calling && rpcID.isNotBlank() && method.isNotBlank()
}

/**
 * The plugin RPC console (matrix H6 and H7; issue #17).
 *
 * **The server decides what a method means, and the console does not guess.** No plugin method is listed
 * in the spec, so the input is passed through as JSON and the answer is shown as the JSON that came back,
 * including an error. A rpc id or method that would break the URL is refused by the surface before any
 * request is sent, and that refusal is what the user sees, not a server error.
 */
@HiltViewModel
class RpcConsoleViewModel @Inject constructor(
    private val dataSets: ServerDataRegistry,
) : ViewModel() {

    private val _state = MutableStateFlow(RpcConsoleUiState())
    val state: StateFlow<RpcConsoleUiState> = _state.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val rpcEvents: StateFlow<List<TuiControl.RpcEvent>> = dataSets.active
        .flatMapLatest { set -> set?.tui?.rpcEvents ?: flowOf(emptyList()) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    fun setTab(tab: RpcConsoleTab) {
        _state.value = _state.value.copy(tab = tab)
    }

    fun setRpcID(rpcID: String) {
        _state.value = _state.value.copy(rpcID = rpcID)
    }

    fun setMethod(method: String) {
        _state.value = _state.value.copy(method = method)
    }

    fun setInput(input: String) {
        _state.value = _state.value.copy(input = input, inputInvalid = false)
    }

    /**
     * Sends the call. Input that is not JSON is refused here, so nothing is sent for it; an empty input is
     * sent as no input at all, which is what the spec's optional `input` means.
     */
    fun call() {
        val set = dataSets.active.value ?: return
        val current = _state.value
        if (!current.canCall) return

        val input = current.input.trim()
        val parsed = if (input.isEmpty()) {
            null
        } else {
            runCatching { Json.parseToJsonElement(input) }.getOrNull() ?: run {
                _state.value = current.copy(inputInvalid = true, output = null, error = null)
                return
            }
        }

        viewModelScope.launch {
            _state.value = _state.value.copy(calling = true, output = null, error = null)
            set.insights.callRpc(current.rpcID.trim(), current.method.trim(), parsed)
                .onSuccess { body ->
                    val output = PRETTY.encodeToString(JsonObject.serializer(), body)
                    // Bound the rendered output: a plugin can return megabytes of JSON, which would
                    // freeze the composition. Truncate with a marker so the cut is visible.
                    val bounded = if (output.length <= MAX_OUTPUT_CHARS) {
                        output
                    } else {
                        output.take(MAX_OUTPUT_CHARS) +
                            "\n… [output truncated: ${output.length - MAX_OUTPUT_CHARS} more characters]"
                    }
                    _state.value = _state.value.copy(calling = false, output = bounded)
                }
                .onFailure { failure ->
                    val error = (failure as? ActionFailure)?.error ?: failure.toActionError()
                    _state.value = _state.value.copy(calling = false, error = error.message)
                }
        }
    }

    fun dismissError() {
        _state.value = _state.value.copy(error = null)
    }

    private companion object {
        val PRETTY = Json { prettyPrint = true }

        /**
         * Maximum characters of RPC output rendered (issue #17).
         *
         * A plugin method can return an arbitrarily large JSON payload; composing megabytes of text
         * freezes the UI. Ten thousand characters is several screens of pretty-printed JSON.
         */
        const val MAX_OUTPUT_CHARS = 10_000
    }
}
