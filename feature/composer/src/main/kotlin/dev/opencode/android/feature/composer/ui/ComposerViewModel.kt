package dev.opencode.android.feature.composer.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.opencode.android.core.data.action.ActionError
import dev.opencode.android.core.data.catalog.AgentCatalog
import dev.opencode.android.core.data.catalog.ModelCatalog
import dev.opencode.android.core.data.preferences.ModelPreferences
import dev.opencode.android.core.data.server.PendingRequest
import dev.opencode.android.core.data.server.ServerDataRegistry
import dev.opencode.android.core.data.server.ServerDataSet
import dev.opencode.android.core.data.server.SessionActivity
import dev.opencode.android.core.data.server.actionErrorOrNull
import dev.opencode.android.core.data.timeline.PendingInboxItem
import dev.opencode.android.core.model.AgentInfo
import dev.opencode.android.core.model.Delivery
import dev.opencode.android.core.model.FormAnswer
import dev.opencode.android.core.model.FormInfo
import dev.opencode.android.core.model.ModelInfo
import dev.opencode.android.core.model.ModelRef
import dev.opencode.android.core.model.PermissionReply
import dev.opencode.android.core.model.PermissionRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The composer's own state, as far as it is the client's and not the server's. */
data class ComposerUiState(
    val sessionID: String? = null,
    val text: String = "",
    /** Steer is the default for prompts (features doc §6). */
    val delivery: Delivery = Delivery.Steer,
    /** Admit the input without starting the agent loop. */
    val resume: Boolean = false,
    val sending: Boolean = false,
    val error: ActionError? = null,
    val agents: List<AgentInfo> = emptyList(),
    val agent: String? = null,
    val models: List<ModelInfo> = emptyList(),
    val model: ModelRef? = null,
    /** True when the location offers no usable model, which is what the empty state turns on. */
    val hasAnyModel: Boolean = false,
    /** The session has a live execution, which is what enables Stop and steering input. */
    val busy: Boolean = false,
    val pending: List<PendingInboxItem> = emptyList(),
    val requests: List<PendingRequest> = emptyList(),
    val directory: String? = null,
) {
    val canSend: Boolean get() = text.isNotBlank() && !sending

    /** The variant currently selected, which the cycle button advances. */
    val variant: String? get() = model?.variant

    /** The selectable agents, primary only. */
    val primaryAgents: List<AgentInfo> get() = AgentCatalog.primary(agents)

    /** The next agent the cycle button would select. */
    val nextAgent: AgentInfo? get() = ModelCatalog.nextAgent(primaryAgents, agent)

    /** The next variant the cycle button would select for the current model. */
    val nextVariant: String?
        get() {
            val current = model ?: return null
            return models.firstOrNull { it.id == current.id && it.providerID == current.providerID }
                ?.let { ModelCatalog.nextVariant(it, current.variant) }
        }
}

/**
 * Everything the composer and the session's control buttons do.
 *
 * **The state is a projection, not a cache.** The text, the delivery mode and the resume flag are
 * the client's; the agent, the model, the pending inbox items and the waiting requests are all
 * projected from the stores, so nothing here can disagree with what the server said. The optimistic
 * inbox item is the one exception the plan allows, and it is reconciled by the event under the same
 * id.
 *
 * **A failed action reports and changes nothing.** Every write returns a failure class rather than
 * throwing, and the composer's text is only cleared on success, so a dropped connection leaves the
 * user's words in the box instead of losing them.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ComposerViewModel @Inject constructor(
    private val dataSets: ServerDataRegistry,
    private val modelPreferences: ModelPreferences,
) : ViewModel() {

    private val local = MutableStateFlow(LocalState())
    private val sessionID = MutableStateFlow<String?>(null)

    private data class LocalState(
        val text: String = "",
        val delivery: Delivery = Delivery.Steer,
        val resume: Boolean = false,
        val sending: Boolean = false,
        val error: ActionError? = null,
    )

    /** The state of the open session's composer, or an idle one before a session is opened. */
    val state: StateFlow<ComposerUiState> = combine(sessionID, local, dataSets.active) { id, mine, set ->
        if (id == null || set == null) {
            return@combine ComposerUiState(
                text = mine.text,
                delivery = mine.delivery,
                resume = mine.resume,
                sending = mine.sending,
                error = mine.error,
            )
        }
        val info = set.sessions.info.value[id]
        val directory = info?.location?.directory
        val models = directory?.let { set.models(it).value }.orEmpty()
        ComposerUiState(
            sessionID = id,
            text = mine.text,
            delivery = mine.delivery,
            resume = mine.resume,
            sending = mine.sending,
            error = mine.error,
            agents = directory?.let { set.agents(it).value }.orEmpty(),
            agent = info?.agent,
            models = models,
            model = info?.model,
            hasAnyModel = models.any { it.enabled },
            busy = set.sessions.activity.value[id] == SessionActivity.Running,
            pending = set.timeline(id).state.value.pending,
            requests = set.requests.forSession(id).value,
            directory = directory,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), ComposerUiState())

    /** The requests waiting anywhere on this server, for the global inbox badge. */
    val allRequests: StateFlow<List<PendingRequest>> = dataSets.active
        .flatMapLatest { set -> set?.requests?.pending ?: flowOf(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), emptyList())

    /**
     * The model picker's client-side knowledge: recents and favorites for this server.
     *
     * Read from the store rather than kept in state, because they change from another screen (the
     * new-session sheet pins a model) and a picker showing a stale pin would be wrong.
     */
    val modelFavorites: StateFlow<List<ModelRef>> = dataSets.active
        .map { set -> set?.serverId }
        .distinctUntilChanged()
        .flatMapLatest { serverId ->
            serverId?.let(modelPreferences::favorites) ?: flowOf(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), emptyList())

    /** The models this client used most recently on this server, newest first. */
    val modelRecents: StateFlow<List<ModelRef>> = dataSets.active
        .map { set -> set?.serverId }
        .distinctUntilChanged()
        .flatMapLatest { serverId ->
            serverId?.let(modelPreferences::recents) ?: flowOf(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), emptyList())

    /** Opens a session: the composer follows it, and its catalogs are read. */
    fun open(sessionID: String) {
        val set = dataSets.active.value ?: return
        this.sessionID.value = sessionID
        set.timeline(sessionID).start()
        viewModelScope.launch { set.sessions.loadSession(sessionID) }
        set.requests.forSession(sessionID)
    }

    fun setText(text: String) {
        local.value = local.value.copy(text = text, error = null)
    }

    fun setDelivery(delivery: Delivery) {
        local.value = local.value.copy(delivery = delivery)
    }

    /** The long-press affordance: send this one as queued without changing the default. */
    fun sendQueued() = send(Delivery.Queue)

    fun setResume(resume: Boolean) {
        local.value = local.value.copy(resume = resume)
    }

    /**
     * Sends the text as a prompt.
     *
     * The id is generated before the call by [SessionCommands], which is what makes a retry safe,
     * and the pending item appears immediately; the server's `session.inbox.enqueued` event
     * reconciles it. The box is only cleared once the server accepted the prompt.
     */
    fun send(delivery: Delivery = local.value.delivery) {
        val id = sessionID.value ?: return
        val set = dataSets.active.value ?: return
        val text = local.value.text.trim()
        if (text.isEmpty() || local.value.sending) return
        local.value = local.value.copy(sending = true, error = null)
        viewModelScope.launch {
            val result = set.commands.prompt(
                sessionID = id,
                text = text,
                delivery = delivery,
                resume = local.value.resume.takeIf { it },
            )
            val error = result.actionErrorOrNull
            local.value = if (error == null) {
                local.value.copy(text = "", sending = false, resume = false)
            } else {
                local.value.copy(sending = false, error = error)
            }
        }
    }

    /** `session.interrupt`, optionally resuming pending steering input. */
    fun interrupt(resumeSteering: Boolean) {
        val id = sessionID.value ?: return
        val set = dataSets.active.value ?: return
        viewModelScope.launch {
            val error = set.commands.interrupt(id, resumeSteering).actionErrorOrNull
            if (error != null) local.value = local.value.copy(error = error)
        }
    }

    /** `session.background`: moves blocking tools out of the way. */
    fun background() {
        val id = sessionID.value ?: return
        val set = dataSets.active.value ?: return
        viewModelScope.launch {
            val error = set.commands.background(id).actionErrorOrNull
            if (error != null) local.value = local.value.copy(error = error)
        }
    }

    fun selectAgent(agent: String) = withSession { set, id -> set.commands.switchAgent(id, agent) }

    /** The cycle button: the next primary agent after the current one. */
    fun cycleAgent() {
        val next = state.value.nextAgent ?: return
        selectAgent(next.id)
    }

    fun selectModel(model: ModelRef) {
        val id = sessionID.value ?: return
        val set = dataSets.active.value ?: return
        val serverId = set.serverId
        viewModelScope.launch {
            val error = set.commands.switchModel(id, model).actionErrorOrNull
            if (error != null) {
                local.value = local.value.copy(error = error)
            } else {
                modelPreferences.markUsed(serverId, model)
            }
        }
    }

    /** The variant cycle, which rides in `Model.Ref.variant`. */
    fun cycleVariant() {
        val current = state.value.model ?: return
        val next = state.value.nextVariant ?: return
        selectModel(current.copy(variant = next))
    }

    fun toggleFavorite(model: ModelRef) {
        val serverId = dataSets.active.value?.serverId ?: return
        viewModelScope.launch { modelPreferences.toggleFavorite(serverId, model) }
    }

    /** `session.inbox.cancel` for one pending item. */
    fun cancelInboxItem(inboxID: String) = withSession { set, id ->
        val error = set.commands.cancelInboxItem(id, inboxID).actionErrorOrNull
        if (error != null) local.value = local.value.copy(error = error)
    }

    /** `session.inbox.update`: switches a pending item between queue and steer. */
    fun setInboxDelivery(inboxID: String, delivery: Delivery) = withSession { set, id ->
        val error = set.commands.setInboxDelivery(id, inboxID, delivery).actionErrorOrNull
        if (error != null) local.value = local.value.copy(error = error)
    }

    /** Answers a permission request. The event removes it; a failure leaves it pending. */
    fun replyPermission(request: PermissionRequest, decision: PermissionReply, feedback: String? = null) =
        withSession { set, _ ->
        val error = set.requests.replyPermission(request, decision, feedback)
        if (error != null) local.value = local.value.copy(error = error)
    }

    /** Answers a form. */
    fun submitForm(form: FormInfo, answer: FormAnswer) = withSession { set, _ ->
        val error = set.requests.replyForm(form, answer)
        if (error != null) local.value = local.value.copy(error = error)
    }

    /** Dismisses a form, which cancels it. */
    fun cancelForm(form: FormInfo) = withSession { set, _ ->
        val error = set.requests.cancelForm(form)
        if (error != null) local.value = local.value.copy(error = error)
    }

    fun dismissError() {
        local.value = local.value.copy(error = null)
    }

    private fun withSession(block: suspend (ServerDataSet, String) -> Unit) {
        val id = sessionID.value ?: return
        val set = dataSets.active.value ?: return
        viewModelScope.launch { block(set, id) }
    }

    private companion object {
        const val STOP_TIMEOUT = 5_000L
    }
}
