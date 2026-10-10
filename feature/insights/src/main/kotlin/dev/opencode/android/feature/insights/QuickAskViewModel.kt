package dev.opencode.android.feature.insights

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.opencode.android.core.data.action.ActionError
import dev.opencode.android.core.data.action.toActionError
import dev.opencode.android.core.data.capability.RouteAvailability
import dev.opencode.android.core.data.integrations.ActionFailure
import dev.opencode.android.core.data.server.ServerDataRegistry
import dev.opencode.android.core.model.ModelRef
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Quick ask's state: what the user typed, and what came back.
 *
 * **Nothing is kept between asks.** `experimental.generate.text` has no session and no history, so the
 * answer on screen is the only record of it, and the next ask replaces it rather than adding to it.
 */
data class QuickAskUiState(
    val prompt: String = "",
    /**
     * The model to ask, as the user typed it: `provider/model`, optionally `#variant`. Blank means the
     * server's default, and a server with no default says so as the error, so the field is how the user
     * gets an answer from such a server at all.
     */
    val model: String = "",
    val modelInvalid: Boolean = false,
    val asking: Boolean = false,
    val answer: String? = null,
    val error: ActionError? = null,
    /** Whether the server answered the route at all. An absent route disables the screen, not each ask. */
    val availability: RouteAvailability = RouteAvailability.Unknown,
) {
    val available: Boolean get() = availability !is RouteAvailability.Absent
    val canAsk: Boolean get() = available && !asking && prompt.isNotBlank()
}

/**
 * Quick ask (matrix H5; issue #20).
 *
 * This is the in-app surface only. The home-screen widget and the Quick Settings tile the plan also names
 * are not built, and they would make the same [dev.opencode.android.core.data.insights.InsightsSurface.generateText]
 * call, so there is one request path whichever surface asks.
 */
@HiltViewModel
class QuickAskViewModel @Inject constructor(
    private val dataSets: ServerDataRegistry,
) : ViewModel() {

    private val _state = MutableStateFlow(QuickAskUiState())
    val state: StateFlow<QuickAskUiState> = _state.asStateFlow()

    fun setPrompt(prompt: String) {
        _state.value = _state.value.copy(prompt = prompt)
    }

    fun setModel(model: String) {
        _state.value = _state.value.copy(model = model, modelInvalid = false)
    }

    /**
     * Sends the prompt. A second ask while one is in flight does nothing, so a double tap is one request.
     *
     * A model that does not parse is refused here, before anything is sent, rather than sent with a guess at
     * which provider the user meant.
     */
    fun ask() {
        val set = dataSets.active.value ?: return
        val current = _state.value
        if (!current.canAsk) return
        val prompt = current.prompt.trim()
        val modelText = current.model.trim()
        val model = if (modelText.isEmpty()) {
            null
        } else {
            parseModelRef(modelText) ?: run {
                _state.value = current.copy(modelInvalid = true, error = null, answer = null)
                return
            }
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(asking = true, answer = null, error = null)
            set.insights.generateText(prompt, model)
                .onSuccess { result -> _state.value = _state.value.copy(asking = false, answer = result.text) }
                .onFailure { failure ->
                    _state.value = _state.value.copy(
                        asking = false,
                        error = (failure as? ActionFailure)?.error ?: failure.toActionError(),
                    )
                }
            _state.value = _state.value.copy(availability = set.insights.generate.value)
        }
    }

    fun dismissError() {
        _state.value = _state.value.copy(error = null)
    }
}

/**
 * Reads `provider/model` or `provider/model#variant`, the form config files and the composer use.
 *
 * `null` for anything else, including a missing provider or model, so the caller refuses the ask instead of
 * sending a model it had to guess at.
 */
internal fun parseModelRef(text: String): ModelRef? {
    val selection = text.substringBefore('#')
    val variant = text.substringAfter('#', missingDelimiterValue = "").trim().takeIf { it.isNotEmpty() }
    val provider = selection.substringBefore('/', missingDelimiterValue = "").trim()
    val id = selection.substringAfter('/', missingDelimiterValue = "").trim()
    if (provider.isEmpty() || id.isEmpty()) return null
    return ModelRef(id = id, providerID = provider, variant = variant)
}
