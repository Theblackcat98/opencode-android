package dev.opencode.android.feature.composer.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.opencode.android.core.data.action.ActionError
import dev.opencode.android.core.data.catalog.AgentCatalog
import dev.opencode.android.core.data.catalog.ModelCatalog
import dev.opencode.android.core.data.action.ActionErrorKind
import dev.opencode.android.core.data.composer.Assembly
import dev.opencode.android.core.data.composer.AttachmentDraft
import dev.opencode.android.core.data.composer.AttachmentPolicy
import dev.opencode.android.core.data.composer.AttachmentVerdict
import dev.opencode.android.core.data.composer.ClientAction
import dev.opencode.android.core.data.composer.Completion
import dev.opencode.android.core.data.composer.CompletionEngine
import dev.opencode.android.core.data.composer.ComposerCatalog
import dev.opencode.android.core.data.composer.ComposerInput
import dev.opencode.android.core.data.composer.ComposerMemory
import dev.opencode.android.core.data.composer.HistoryCursor
import dev.opencode.android.core.data.composer.PromptAssembler
import dev.opencode.android.core.data.composer.PromptHistory
import dev.opencode.android.core.data.composer.PromptIntent
import dev.opencode.android.core.data.composer.PromptProblem
import dev.opencode.android.core.data.composer.StashEntry
import dev.opencode.android.core.data.composer.TriggerKind
import dev.opencode.android.core.data.composer.LineRange
import dev.opencode.android.core.data.composer.detectTrigger
import dev.opencode.android.core.data.review.RestoredFile
import dev.opencode.android.core.data.review.RestoredPrompt
import dev.opencode.android.core.data.review.ReviewComment
import dev.opencode.android.core.data.review.RevertPlan
import dev.opencode.android.core.data.review.SendPreparation
import dev.opencode.android.core.data.server.RevertCommands
import dev.opencode.android.core.model.SessionMessage
import dev.opencode.android.core.model.SessionRevert
import dev.opencode.android.core.data.preferences.ModelPreferences
import dev.opencode.android.core.data.server.FileSearchState
import dev.opencode.android.core.data.server.PendingRequest
import dev.opencode.android.core.data.server.ServerDataRegistry
import dev.opencode.android.core.data.server.ServerDataSet
import dev.opencode.android.core.data.server.SessionActivity
import dev.opencode.android.core.data.server.actionErrorOrNull
import dev.opencode.android.core.data.timeline.PendingInboxItem
import dev.opencode.android.core.model.AgentInfo
import dev.opencode.android.core.model.CommandInfo
import dev.opencode.android.core.model.Delivery
import dev.opencode.android.core.model.FormAnswer
import dev.opencode.android.core.model.FormInfo
import dev.opencode.android.core.model.ModelInfo
import dev.opencode.android.core.model.ModelRef
import dev.opencode.android.core.model.PermissionReply
import dev.opencode.android.core.model.PermissionRequest
import dev.opencode.android.core.model.PromptSkillInput
import dev.opencode.android.core.model.ReferenceInfo
import dev.opencode.android.core.model.SkillInfo
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * What the composer is currently in the middle of, and what the user should be told about it.
 *
 * The problem is a separate field from the error because they are different promises: an [ActionError]
 * says a call the server answered failed, while a [ComposerProblem] says this client is not going to
 * make the call. The second one is decided by [PromptAssembler] before anything leaves the phone.
 */
enum class ComposerProblem {
    /** An attachment the model will not be sent, so the prompt would silently lose it. */
    ATTACHMENT_BLOCKED,

    /** An attachment that is only sendable once the user has been told and agreed (plan §5.2). */
    ATTACHMENT_NEEDS_CONFIRMATION,

    /** The picked file could not be read or decoded. */
    ATTACHMENT_UNREADABLE,

    /** The picker is looking at no session, so there is nowhere to send anything. */
    NO_SESSION,

    /** The `fs.find` search behind `@` failed; the mention list is showing what it has. */
    SEARCH_FAILED,

    /** A revert is staged or in flight, so this send is not going out yet (plan §6, undo/redo). */
    REVERT_BLOCKED,
}

/** The answer to a `/btw` side question, and whether it is still being produced. */
data class SideQuestion(
    val question: String,
    val answer: String? = null,
    val loading: Boolean = true,
    val failure: String? = null,
) {
    val answered: Boolean get() = answer != null
}

/**
 * A one-shot thing the composer asks the screen to do, rather than something it can express in its
 * own state.
 *
 * Navigation and opening a picker belong to the composition root, and a `Channel` is what keeps them
 * one-shot: a `StateFlow` of "open the model picker" would reopen it on every recomposition after a
 * configuration change.
 */
sealed interface ComposerEffect {
    data object NewSession : ComposerEffect
    data object SessionList : ComposerEffect
    data object OpenAgentPicker : ComposerEffect
    data object OpenModelPicker : ComposerEffect
    data object OpenEditor : ComposerEffect

    /** The review screen, with the TUI's "last turn" scope already selected. */
    data object OpenDiff : ComposerEffect

    /**
     * A revert is staged and the prompt is back in the composer.
     *
     * The composer does not perform the stage itself: `RevertPlan` decides the order and
     * [RevertCommands] carries it out, and the composition root asks for the confirmation that plan
     * §5.2 requires before a revert is staged at all.
     */
    data class ConfirmUndo(val messageID: String, val text: String) : ComposerEffect

    /** The box is empty and ready; what to do about focus is the screen's business. */
    data object FocusComposer : ComposerEffect
}

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
    // ------------------------------------------------------------------ Phase 5: rich composer
    val attachments: List<AttachmentDraft> = emptyList(),
    val skills: List<PromptSkillInput> = emptyList(),
    val availableSkills: List<SkillInfo> = emptyList(),
    val serverCommands: List<CommandInfo> = emptyList(),
    val references: List<ReferenceInfo> = emptyList(),
    val completions: List<Completion> = emptyList(),
    val trigger: TriggerKind? = null,
    val intent: PromptIntent? = null,
    val searchingFiles: Boolean = false,
    val history: List<String> = emptyList(),
    val stash: List<StashEntry> = emptyList(),
    val problem: ComposerProblem? = null,
    /** The attachment that needs confirming, so the row can name it. */
    val problemDetail: String? = null,
    val sideQuestion: SideQuestion? = null,
    val compacting: Boolean = false,
    // ------------------------------------------------------------------ Phase 6: review
    /** The review comments waiting to go on the next prompt. */
    val reviewComments: List<ReviewComment> = emptyList(),
    /** A staged revert, which makes the next send a commit-then-send. */
    val stagedRevert: SessionRevert? = null,
    /** The files a staged revert will restore, which the banner lists. */
    val restoredFiles: List<RestoredFile> = emptyList(),
    /** A revert operation is in flight, which disables the send. */
    val reverting: Boolean = false,
) {
    val canSend: Boolean get() = !sending && !reverting && problem == null && assemblyIsSendable()

    /** Whether a revert is staged, which is what the banner and the commit-first send key on. */
    val isStaged: Boolean get() = stagedRevert != null

    /** The comment count the composer's context row shows. */
    val commentCount: Int get() = reviewComments.size

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

    /** Whether the box is in shell mode, which changes the hint and the send button. */
    val shellMode: Boolean get() = trigger == TriggerKind.SHELL

    /** The catalog entry of the selected model, which is what decides about pictures. */
    val modelInfo: ModelInfo? get() = models.firstOrNull { it.id == model?.id && it.providerID == model?.providerID }

    /** What the prompt will carry, for the context row: the count, not the contents. */
    val carriedAttachments: Int get() = attachments.size

    /** Whether a confirmation is being asked for, which is the only "send anyway" in the composer. */
    val needsConfirmation: Boolean get() = problem == ComposerProblem.ATTACHMENT_NEEDS_CONFIRMATION

    /** Whether the box has anything a send could act on at all. */
    private fun assemblyIsSendable(): Boolean {
        val intent = intent
        return when (intent) {
            is PromptIntent.Client -> intent.text.isNotEmpty()
            else -> text.isNotBlank() || attachments.isNotEmpty() || reviewComments.isNotEmpty()
        }
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
 *
 * **The composer decides nothing about the text itself.** What a send means, which mentions attach
 * and whether an attachment may go are [PromptAssembler]'s; this class collects the pieces, asks it,
 * and carries out the answer. The one judgement it does make is which catalog to complete from, and
 * that is a projection of the session's location like everything else.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ComposerViewModel @Inject constructor(
    private val dataSets: ServerDataRegistry,
    private val modelPreferences: ModelPreferences,
    private val memory: ComposerMemory,
    private val attachmentReader: AttachmentReader,
) : ViewModel() {

    private val local = MutableStateFlow(LocalState())
    private val sessionID = MutableStateFlow<String?>(null)
    private val effects = Channel<ComposerEffect>(Channel.BUFFERED)

    /** The one-shot actions the composition root carries out. */
    val effect: Flow<ComposerEffect> = effects.receiveAsFlow()

    private data class LocalState(
        val text: String = "",
        val cursor: Int = 0,
        val delivery: Delivery = Delivery.Steer,
        val resume: Boolean = false,
        val sending: Boolean = false,
        val error: ActionError? = null,
        val attachments: List<AttachmentDraft> = emptyList(),
        val skills: List<PromptSkillInput> = emptyList(),
        val completions: List<Completion> = emptyList(),
        val trigger: TriggerKind? = null,
        val problem: ComposerProblem? = null,
        val problemDetail: String? = null,
        val historyCursor: HistoryCursor = HistoryCursor(),
        val sideQuestion: SideQuestion? = null,
        val compacting: Boolean = false,
        val loadedDraft: Boolean = false,
        val reviewComments: List<ReviewComment> = emptyList(),
        val reverting: Boolean = false,
    )

    /** The prompt history of the active server, newest first, and the stash beside it. */
    private val memoryState: StateFlow<Pair<List<String>, List<StashEntry>>> = dataSets.active
        .map { it?.serverId }
        .distinctUntilChanged()
        .flatMapLatest { serverId ->
            if (serverId == null) {
                flowOf(emptyList<String>() to emptyList())
            } else {
                combine(memory.history(serverId), memory.stash(serverId)) { history, stash -> history to stash }
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList<String>() to emptyList())


    /**
     * The state of the open session's composer, or an idle one before a session is opened.
     *
     * `Eagerly` and not `WhileSubscribed`, because the view model also reads this while no screen is
     * collecting: a completion list is computed from the current catalogs, and a derived `StateFlow`
     * that is not running would answer with its initial value and offer a stale catalog. The cost is
     * one collector for the composer's lifetime, which is the screen's lifetime.
     */
    val state: StateFlow<ComposerUiState> = combine(sessionID, local, dataSets.active, memoryState) { id, mine, set, memory ->
        val (history, stash) = memory
        if (id == null || set == null) {
            return@combine ComposerUiState(
                text = mine.text,
                delivery = mine.delivery,
                resume = mine.resume,
                sending = mine.sending,
                error = mine.error,
                attachments = mine.attachments,
                skills = mine.skills,
                completions = mine.completions,
                trigger = mine.trigger,
                problem = mine.problem,
                problemDetail = mine.problemDetail,
                sideQuestion = mine.sideQuestion,
                compacting = mine.compacting,
            )
        }
        val info = set.sessions.info.value[id]
        val directory = info?.location?.directory
        val models = directory?.let { set.models(it).value }.orEmpty()
        val commands = directory?.let { set.composerCatalogs.commands(it).value }.orEmpty()
        val skills = directory?.let { set.composerCatalogs.skills(it).value }.orEmpty()
        val references = directory?.let { set.composerCatalogs.references(it).value }.orEmpty()
        val search = set.composerCatalogs.files.state.value
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
            attachments = mine.attachments,
            skills = mine.skills,
            availableSkills = skills,
            serverCommands = commands,
            references = references,
            completions = mine.completions,
            trigger = mine.trigger,
            intent = PromptAssembler.intentOf(mine.text, commands),
            searchingFiles = search.loading,
            history = history,
            stash = stash,
            problem = mine.problem,
            problemDetail = mine.problemDetail,
            sideQuestion = mine.sideQuestion,
            compacting = mine.compacting,
            reviewComments = mine.reviewComments,
            reverting = mine.reverting,
            stagedRevert = set?.revertCommands?.state?.value?.staged,
            restoredFiles = set?.revertCommands?.state?.value?.staged
                ?.let(RevertPlan::restoredFiles).orEmpty(),
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ComposerUiState())

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

    init {
        // The `fs.find` results are what the `@` list is completed from, so a response has to
        // recompute the completions. `refresh` only asks for a search when the query actually moved,
        // which is what stops this from feeding itself.
        viewModelScope.launch {
            dataSets.active.flatMapLatest { set -> set?.composerCatalogs?.files?.state ?: flowOf(FileSearchState()) }
                .collect { refresh() }
        }
    }

    /** The history and stash of the active server, read by the history walk. */
    private val lastHistory: List<String> get() = memoryState.value.first

    /**
     * Opens a session: the composer follows it, its timeline loads, and the catalogs the pickers
     * read are fetched for the session's location.
     *
     * The catalogs are location-scoped, so a session reached by tapping it in the list has never
     * loaded them — only the new-session flow has, and only for the location the user chose there.
     * [dev.opencode.android.core.data.sync.SyncedResource.sync] is a no-op when the value is not
     * stale, so this costs one request per catalog the first time and nothing afterwards. Phase 5
     * adds the three file-based catalogs, because a `/` palette with no commands in it would be
     * useless.
     */
    fun open(sessionID: String) {
        val set = dataSets.active.value ?: return
        this.sessionID.value = sessionID
        set.timeline(sessionID).start()
        viewModelScope.launch {
            val directory = set.sessions.loadSession(sessionID)?.location?.directory ?: return@launch
            set.agents(directory).sync()
            set.models(directory).sync()
            set.composerCatalogs.commands(directory).sync()
            set.composerCatalogs.skills(directory).sync()
            set.composerCatalogs.references(directory).sync()
            restoreDraft(directory, sessionID)
        }
    }

    fun setText(text: String, cursor: Int = text.length) {
        local.value = local.value.copy(
            text = text,
            cursor = cursor.coerceIn(0, text.length),
            // Typing clears the last complaint: it is about the previous text.
            error = null,
            problem = null,
            problemDetail = null,
        )
        refresh()
        persistDraft()
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
     * Sends the box.
     *
     * What it does is [PromptAssembler]'s decision, not this method's: the assembled value is a
     * prompt, a command, a shell line, a client action or a refusal, and each is carried out the way
     * the server's API says. The box is only cleared on success, and a client action that opens
     * something clears it because nothing was sent.
     *
     * [confirmed] is the user's answer to "this model cannot see this image", and it applies to this
     * one send only.
     */
    fun send(delivery: Delivery = local.value.delivery, confirmed: Boolean = false) {
        val id = sessionID.value ?: return
        val set = dataSets.active.value ?: return
        if (local.value.sending) return
        val snapshot = state.value
        val assembly = PromptAssembler.assemble(
            input = ComposerInput(
                text = local.value.text,
                attachments = local.value.attachments,
                skills = local.value.skills,
                delivery = delivery,
                resume = local.value.resume,
                location = snapshot.directory,
                model = snapshot.modelInfo,
                serverCommands = snapshot.serverCommands,
                agents = snapshot.agents,
                // The comments the review screen left here, which is the whole point of the
                // composer being the thing that turns a review into a prompt.
                reviewComments = snapshot.reviewComments,
            ),
            confirmed = confirmed,
        )
        when (assembly) {
            is Assembly.Client -> performClient(assembly.action, assembly.text)

            is Assembly.Prompt -> {
                local.value = local.value.copy(sending = true, error = null, problem = null)
                viewModelScope.launch {
                    // **Commit the staged revert, then send.** The server judges a prompt against the
                    // tree as it is when the prompt arrives, and the commit is what puts that tree
                    // back. A prompt sent first would be judged against files that are about to
                    // change under it, and a commit that failed must not be followed by a send.
                    val prepared = prepareSend(set, id)
                    if (prepared !is SendPreparation.Send) {
                        local.value = local.value.copy(sending = false)
                        return@launch
                    }
                    val result = set.commands.prompt(
                        sessionID = id,
                        text = assembly.request.text,
                        delivery = delivery,
                        resume = assembly.request.resume,
                        files = assembly.request.files,
                        agents = assembly.request.agents,
                        skills = assembly.request.skills,
                        metadata = assembly.request.metadata,
                    )
                    if (result.isSuccess) {
                        dataSets.active.value?.review?.takeComments()
                    }
                    onSent(result.actionErrorOrNull, assembly.request.text)
                }
            }

            is Assembly.Command -> {
                local.value = local.value.copy(sending = true, error = null, problem = null)
                viewModelScope.launch {
                    val result = set.commands.runCommand(
                        sessionID = id,
                        name = assembly.request.name,
                        text = assembly.request.text,
                        delivery = delivery,
                        files = assembly.request.files,
                        agents = assembly.request.agents,
                        skills = assembly.request.skills,
                    )
                    onSent(result.actionErrorOrNull, assembly.request.text)
                }
            }

            is Assembly.Shell -> {
                local.value = local.value.copy(sending = true, error = null, problem = null)
                viewModelScope.launch {
                    val error = set.commands.runShell(id, assembly.request.command).actionErrorOrNull
                    onSent(error, "!${assembly.request.command}")
                }
            }

            is Assembly.Refused -> refuse(assembly.problem)

            Assembly.Empty -> Unit
        }
    }

    /**
     * What has to happen before a send, given the staged revert.
     *
     * Returns [SendPreparation.Send] when the send may go, and records the error otherwise: a send
     * refused because a revert is in flight, or refused because its commit failed, is a message the
     * user has to see rather than a prompt that silently disappears.
     */
    private suspend fun prepareSend(set: ServerDataSet, id: String): SendPreparation {
        return when (val preparation = RevertPlan.beforeSend(set.revertCommands.state.value)) {
            SendPreparation.Send -> SendPreparation.Send
            SendPreparation.CommitThenSend -> {
                val error = set.revertCommands.commit(id).actionErrorOrNull
                if (error != null) {
                    local.value = local.value.copy(error = error)
                    SendPreparation.Wait("the revert could not be committed")
                } else {
                    SendPreparation.Send
                }
            }

            is SendPreparation.Wait -> {
                local.value = local.value.copy(problem = ComposerProblem.REVERT_BLOCKED, problemDetail = preparation.reason)
                SendPreparation.Wait(preparation.reason)
            }
        }
    }

    private fun performClient(action: ClientAction, text: String) {
        when (action) {
            ClientAction.NEW_SESSION -> {
                clearComposer()
                effects.trySend(ComposerEffect.NewSession)
            }

            ClientAction.SESSION_LIST -> {
                clearComposer()
                effects.trySend(ComposerEffect.SessionList)
            }

            ClientAction.MODEL_PICKER -> {
                clearComposer()
                effects.trySend(ComposerEffect.OpenModelPicker)
            }

            ClientAction.AGENT_PICKER -> {
                clearComposer()
                effects.trySend(ComposerEffect.OpenAgentPicker)
            }

            // `/editor` moves the text to the full-screen editor and leaves the way it came.
            ClientAction.EDITOR -> effects.trySend(ComposerEffect.OpenEditor)

            ClientAction.COMPACT -> compact()

            ClientAction.SIDE_QUESTION -> ask(text)

            // The three that arrived with the Phase 6 operations. `/diff` navigates; `/undo` and
            // `/redo` are the session's, and both ask before they change the working copy (§5.2).
            ClientAction.DIFF -> {
                clearComposer()
                effects.trySend(ComposerEffect.OpenDiff)
            }

            ClientAction.UNDO -> undo()

            ClientAction.REDO -> redo()
        }
    }

    /**
     * `/undo`: stage a rollback to before the newest user message.
     *
     * The composition root asks for the confirmation (plan §5.2) and then calls [stageUndo] with the
     * message the user chose — from the palette it is the newest one, and from a message menu it is
     * the one the row named. The stage itself interrupts a busy session and cancels pending user
     * input first, which is [RevertPlan]'s order and not this method's.
     */
    private fun undo() {
        val id = sessionID.value ?: return
        val set = dataSets.active.value ?: return
        val target = newestUserMessage(set, id)
        if (target == null) {
            local.value = local.value.copy(error = null)
            return
        }
        effects.trySend(ComposerEffect.ConfirmUndo(target.id, target.text))
    }

    /**
     * `session.revert.stage`, and the composer restore that follows it.
     *
     * The whole prompt goes back, not just the text: the attachments and the review comments are the
     * parts a user would be angry to lose, and the delivery is the mode the prompt was sent with.
     * A stage that fails changes nothing here, which is what plan §5.2 asks of a dangerous action.
     */
    fun stageUndo(messageID: String, restoreFiles: Boolean = true) {
        val id = sessionID.value ?: return
        val set = dataSets.active.value ?: return
        val message = messageById(set, id, messageID)
        local.value = local.value.copy(reverting = true, error = null)
        viewModelScope.launch {
            val outcome = set.revertCommands.stage(
                sessionID = id,
                messageID = messageID,
                busy = set.sessions.activity.value[id] == SessionActivity.Running,
                pendingUserInboxIDs = pendingUserInboxIds(set, id),
                restoreFiles = restoreFiles,
                prompt = message?.let { restoredFrom(it) },
            )
            local.value = local.value.copy(reverting = false)
            when (outcome) {
                is RevertCommands.StageOutcome.Done -> restore(outcome.revert, message)
                is RevertCommands.StageOutcome.Failed -> {
                    local.value = local.value.copy(error = outcome.error)
                }
            }
        }
    }

    /**
     * `/redo`: `session.revert.clear`.
     *
     * The restored prompt is *not* put back. Redo means "take the rollback back", so the composer
     * keeps whatever the user has typed since, which is the only reading that does not destroy work.
     */
    fun redo() {
        val id = sessionID.value ?: return
        val set = dataSets.active.value ?: return
        local.value = local.value.copy(reverting = true, error = null)
        viewModelScope.launch {
            val result = set.revertCommands.clear(id)
            local.value = local.value.copy(
                reverting = false,
                error = result.actionErrorOrNull,
            )
        }
    }

    /** The newest user message of a session, which is what the palette's `/undo` targets. */
    private fun newestUserMessage(set: ServerDataSet, id: String): SessionMessage.User? =
        set.timeline(id).state.value.messages
            .asReversed()
            .filterIsInstance<SessionMessage.User>()
            .firstOrNull()

    private fun messageById(set: ServerDataSet, id: String, messageID: String): SessionMessage.User? =
        set.timeline(id).state.value.messages
            .filterIsInstance<SessionMessage.User>()
            .firstOrNull { it.id == messageID }

    /**
     * The pending inbox items an undo cancels: the *user* ones only.
     *
     * A compaction or a move in the inbox is not something an undo should cancel, so the filter is
     * here rather than in [RevertPlan], which is a pure function and has no idea what an inbox item
     * is.
     */
    private fun pendingUserInboxIds(set: ServerDataSet, id: String): List<String> =
        // The pending item's own id is the `msg_…` the client generated, which is exactly what
        // `session.inbox.cancel` takes.
        set.timeline(id).state.value.pending
            .filter { it.item is dev.opencode.android.core.model.InboxItem.User }
            .map { it.id }

    /** The prompt an undo puts back, in the shape the composer holds. */
    private fun restoredFrom(message: SessionMessage.User): RestoredPrompt = RestoredPrompt(
        messageID = message.id,
        text = message.text,
        // A stored user message carries its files as decoded base64 (schema `Prompt.FileAttachment`),
        // so the attachment that goes back into the composer is a `data:` URL again — the one shape
        // the prompt route accepts for content the phone holds.
        attachments = message.files.orEmpty().mapIndexed { index, attachment ->
            val label = attachment.name ?: attachment.mime.ifEmpty { "file" }
            AttachmentDraft(
                id = "$label#$index",
                label = label,
                uri = "data:${attachment.mime};base64,${attachment.data}",
                kind = AttachmentPolicy.classify(attachment.mime),
                mime = attachment.mime,
            )
        },
        comments = dev.opencode.android.core.data.review.ReviewComments.read(message.metadata),
        delivery = local.value.delivery,
    )

    /** Puts the restored prompt into the box: text, attachments and comments together. */
    private fun restore(revert: SessionRevert, message: SessionMessage.User?) {
        val prompt = message?.let(::restoredFrom) ?: return
        local.value = local.value.copy(
            text = prompt.text,
            cursor = prompt.text.length,
            attachments = prompt.attachments,
            reviewComments = prompt.comments,
            problem = null,
            problemDetail = null,
        )
        dataSets.active.value?.revertCommands?.applyStaged(revert, prompt)
        refresh()
        persistDraft()
    }

    /** `session.compact`. A busy session is a conflict the composer reports rather than retries. */
    fun compact() {
        val id = sessionID.value ?: return
        val set = dataSets.active.value ?: return
        local.value = local.value.copy(compacting = true, error = null)
        viewModelScope.launch {
            val error = set.commands.compact(id, local.value.delivery).actionErrorOrNull
            local.value = local.value.copy(
                compacting = false,
                error = error,
                text = if (error == null) "" else local.value.text,
            )
        }
    }

    /**
     * `session.generate`: the `/btw` side question.
     *
     * The one driving call that answers with a body, because the answer is for the user and has
     * nowhere in the timeline to go. The question stays on screen while it is being produced, and a
     * failure keeps it so it can be retried rather than retyped.
     */
    fun ask(question: String) {
        val id = sessionID.value ?: return
        val set = dataSets.active.value ?: return
        if (question.isBlank()) return
        local.value = local.value.copy(sideQuestion = SideQuestion(question), text = "", error = null)
        viewModelScope.launch {
            val result = set.commands.generate(id, question)
            val error = result.actionErrorOrNull
            local.value = local.value.copy(
                sideQuestion = SideQuestion(
                    question = question,
                    answer = result.getOrNull(),
                    loading = false,
                    failure = error?.message,
                ),
            )
        }
    }

    /**
     * Takes the review comments the review screen left, so the next send carries them.
     *
     * The composer holds them rather than reaching into the review store, because the composer is
     * what turns a review into a prompt and a second holder of the same list would be a second
     * thing to keep in step.
     */
    fun setReviewComments(comments: List<ReviewComment>) {
        local.value = local.value.copy(reviewComments = comments, problem = null, problemDetail = null)
    }

    fun removeReviewComment(index: Int) {
        local.value = local.value.copy(
            reviewComments = local.value.reviewComments.filterIndexed { position, _ -> position != index },
        )
    }

    fun dismissSideQuestion() {
        local.value = local.value.copy(sideQuestion = null)
    }

    // ------------------------------------------------------------------ attachments

    /**
     * Adds the image the picker returned.
     *
     * The read and the re-encode happen off the main thread inside [AttachmentReader]; this method
     * only records the result. A file the phone cannot read is a problem in the composer rather than
     * a crash, because a camera app that hands back nothing is a normal thing to survive.
     */
    fun attachImage(source: android.net.Uri) {
        local.value = local.value.copy(problem = null, problemDetail = null)
        viewModelScope.launch {
            val draft = attachmentReader.readImage(source)
            draft.fold(
                onSuccess = { addAttachment(it) },
                onFailure = {
                    local.value = local.value.copy(
                        problem = ComposerProblem.ATTACHMENT_UNREADABLE,
                        problemDetail = it.message,
                    )
                },
            )
        }
    }

    /** Adds a file or directory that lives on the server, which the phone never reads. */
    fun attachServerFile(path: String, name: String, type: String) {
        val directory = state.value.directory
        addAttachment(attachmentReader.serverFile(path, name, type, directory))
    }

    /**
     * Adds a server file with a line range, which is the file viewer's "attach lines" action.
     *
     * The range becomes `?start=&end=` on the `file:` URI (features doc §6), which is the one thing
     * the paperclip cannot express and the reason this exists as its own call rather than a flag.
     */
    fun attachServerFileWithRange(path: String, name: String, type: String, range: LineRange) {
        val directory = state.value.directory
        addAttachment(attachmentReader.serverFile(path, name, type, directory).copy(range = range))
    }

    /** Adds a reference directory from `reference.list`. */
    fun attachReference(path: String, name: String) {
        val directory = state.value.directory
        addAttachment(attachmentReader.reference(path, name, directory))
    }

    fun removeAttachment(id: String) {
        local.value = local.value.copy(
            attachments = local.value.attachments.filterNot { it.id == id },
            problem = null,
            problemDetail = null,
        )
    }

    private fun addAttachment(draft: AttachmentDraft) {
        val model = state.value.modelInfo
        val verdict = AttachmentPolicy.verify(draft, model)
        val next = local.value.attachments + draft
        local.value = local.value.copy(
            attachments = next,
            problem = problemOf(verdict),
            problemDetail = detailOf(verdict, draft),
        )
    }

    private fun problemOf(verdict: AttachmentVerdict): ComposerProblem? = when (verdict) {
        AttachmentVerdict.Ok -> null
        is AttachmentVerdict.NeedsConfirmation -> ComposerProblem.ATTACHMENT_NEEDS_CONFIRMATION
        is AttachmentVerdict.Blocked -> ComposerProblem.ATTACHMENT_BLOCKED
    }

    private fun detailOf(verdict: AttachmentVerdict, draft: AttachmentDraft): String? = when (verdict) {
        AttachmentVerdict.Ok -> null
        is AttachmentVerdict.NeedsConfirmation -> draft.label
        is AttachmentVerdict.Blocked -> draft.label
    }

    private fun refuse(problem: PromptProblem) {
        local.value = local.value.copy(
            problem = when (problem) {
                PromptProblem.ATTACHMENT_BLOCKED -> ComposerProblem.ATTACHMENT_BLOCKED
                PromptProblem.ATTACHMENT_NEEDS_CONFIRMATION -> ComposerProblem.ATTACHMENT_NEEDS_CONFIRMATION
                PromptProblem.NO_SESSION -> ComposerProblem.NO_SESSION
                PromptProblem.EMPTY -> null
            },
            problemDetail = null,
        )
    }

    // ------------------------------------------------------------------ mentions, commands, skills

    /** Replaces the trigger under the caret with [completion] and puts the caret after it. */
    fun applyCompletion(completion: Completion) {
        val current = local.value
        val span = detectTrigger(current.text, current.cursor) ?: return
        val text = current.text
        val updated = text.substring(0, span.start) + completion.insertText + text.substring(span.end)
        local.value = current.copy(
            text = updated,
            cursor = span.start + completion.insertText.length,
            problem = null,
            problemDetail = null,
        )
        refresh()
        persistDraft()
    }

    /** Dismisses the completion list, for a keyboard action that has nothing to complete. */
    fun dismissCompletions() {
        if (local.value.completions.isEmpty()) return
        local.value = local.value.copy(completions = emptyList(), trigger = null)
        dataSets.active.value?.composerCatalogs?.files?.clear()
    }

    /** Attaches or detaches a skill, which travels on the next prompt as `skills[]`. */
    fun toggleSkill(id: String) {
        val current = local.value.skills
        local.value = local.value.copy(
            skills = if (current.any { it.id == id }) current.filterNot { it.id == id } else current + PromptSkillInput(id),
            problem = null,
        )
    }

    /**
     * `experimental.session.skill`: activates a skill in the running session.
     *
     * Experimental, so a missing route is the expected answer on a server without it, and the
     * fallback is the thing the API always has: attach it on the next prompt.
     */
    fun activateSkill(id: String) {
        val session = sessionID.value ?: return
        val set = dataSets.active.value ?: return
        viewModelScope.launch {
            val error = set.commands.activateSkill(session, id, resume = null).actionErrorOrNull
            if (error != null && error.kind == ActionErrorKind.NOT_FOUND) {
                toggleSkill(id)
            } else if (error != null) {
                local.value = local.value.copy(error = error)
            }
        }
    }

    // ------------------------------------------------------------------ history and stash

    fun olderHistory() = moveHistory(older = true)

    fun newerHistory() = moveHistory(older = false)

    private fun moveHistory(older: Boolean) {
        val current = local.value
        val history = lastHistory
        val cursor = if (older) {
            PromptHistory.older(history, current.historyCursor)
        } else {
            PromptHistory.newer(history, current.historyCursor)
        }
        val text = cursor.text(history)
        local.value = current.copy(historyCursor = cursor, text = text, cursor = text.length, problem = null)
        refresh()
        persistDraft()
    }

    /** Puts the box away. The text is the stash; the attachments stay, because a chip is not text. */
    fun stashCurrent() {
        val serverId = dataSets.active.value?.serverId ?: return
        val text = local.value.text
        if (text.isBlank()) return
        viewModelScope.launch {
            memory.pushStash(serverId, StashEntry(id = "st${System.currentTimeMillis()}", text = text, created = System.currentTimeMillis()))
            clearComposer()
        }
    }

    /** Takes the most recent stashed prompt back into the box. */
    fun popStash() {
        val serverId = dataSets.active.value?.serverId ?: return
        viewModelScope.launch {
            val entry = memory.popStash(serverId) ?: return@launch
            restore(entry)
        }
    }

    /**
     * Takes one stashed prompt back into the box, whichever one the row named.
     *
     * A row that popped the newest while claiming to restore itself would be the most confusing
     * control in the composer, so the row carries the id and the pop is a separate action.
     */
    fun restoreStash(entry: StashEntry) {
        val serverId = dataSets.active.value?.serverId ?: return
        viewModelScope.launch {
            memory.dropStash(serverId, entry.id)
            restore(entry)
        }
    }

    private fun restore(entry: StashEntry) {
        local.value = local.value.copy(
            text = entry.text,
            cursor = entry.text.length,
            problem = null,
            problemDetail = null,
        )
        refresh()
        persistDraft()
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
                // A model that takes images changes whether the pending attachment is sendable, so
                // the verdict is recomputed rather than left as it was decided.
                recheckAttachments()
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

    // ------------------------------------------------------------------ internals

    /**
     * Recomputes what the box means and what it can offer.
     *
     * Called after every text change and after every `fs.find` response, which is the only way the
     * two can be kept in step. The search is only asked for when the query has actually moved, so a
     * response cannot trigger the request that produced it.
     */
    private fun refresh() {
        val set = dataSets.active.value
        val current = local.value
        val span = detectTrigger(current.text, current.cursor)
        val snapshot = state.value
        if (set != null && span != null && span.kind == TriggerKind.MENTION && span.query.isNotEmpty()) {
            val directory = snapshot.directory
            if (directory != null) {
                val search = set.composerCatalogs.files
                if (search.state.value.query != span.query || search.state.value.directory != directory) {
                    search.search(directory, span.query)
                }
            }
        } else {
            set?.composerCatalogs?.files?.clear()
        }
        val catalog = ComposerCatalog(
            agents = snapshot.agents,
            commands = snapshot.serverCommands,
            references = snapshot.references,
            files = set?.composerCatalogs?.files?.state?.value?.results.orEmpty(),
            location = snapshot.directory,
        )
        val completions = if (span == null) emptyList() else CompletionEngine.complete(current.text, current.cursor, catalog)
        // A failed search is reported rather than shown as an empty list: "nothing matched" and "the
        // server said no" are different facts and only one of them is the user's fault to fix.
        val searchFailed = set?.composerCatalogs?.files?.state?.value?.error != null
        val problem = if (searchFailed && span?.kind == TriggerKind.MENTION) {
            current.problem ?: ComposerProblem.SEARCH_FAILED
        } else {
            current.problem
        }
        if (completions == current.completions && span?.kind == current.trigger && problem == current.problem) return
        local.value = current.copy(completions = completions, trigger = span?.kind, problem = problem)
    }

    private fun recheckAttachments() {
        val model = state.value.modelInfo
        val first = local.value.attachments.firstNotNullOfOrNull { AttachmentPolicy.verify(it, model) }
        local.value = local.value.copy(problem = problemOf(first ?: AttachmentVerdict.Ok), problemDetail = null)
    }

    private fun onSent(error: ActionError?, text: String) {
        if (error != null) {
            // The box keeps its words and its attachments; a dropped connection must not lose either.
            local.value = local.value.copy(sending = false, error = error)
            return
        }
        val serverId = dataSets.active.value?.serverId
        if (serverId != null) viewModelScope.launch { memory.record(serverId, text) }
        clearComposer()
        effects.trySend(ComposerEffect.FocusComposer)
    }

    private fun clearComposer() {
        val serverId = dataSets.active.value?.serverId
        val session = sessionID.value
        local.value = local.value.copy(
            text = "",
            cursor = 0,
            sending = false,
            resume = false,
            attachments = emptyList(),
            skills = emptyList(),
            completions = emptyList(),
            trigger = null,
            problem = null,
            problemDetail = null,
            historyCursor = HistoryCursor(),
        )
        if (serverId != null && session != null) {
            viewModelScope.launch { memory.setDraft(serverId, session, "") }
        }
    }

    /**
     * Saves the unsent text, debounced.
     *
     * A draft is the one piece of this screen a person would be angry to lose — it is the sentence
     * they have not sent yet — so it is written on a pause rather than on every keystroke, and it is
     * written off the UI thread by the store.
     */
    private fun persistDraft() {
        val serverId = dataSets.active.value?.serverId ?: return
        val session = sessionID.value ?: return
        val text = local.value.text
        viewModelScope.launch {
            delay(DRAFT_DEBOUNCE_MILLIS)
            memory.setDraft(serverId, session, text)
        }
    }

    private fun restoreDraft(directory: String, session: String) {
        if (local.value.loadedDraft) return
        val serverId = dataSets.active.value?.serverId ?: return
        viewModelScope.launch {
            val draft = memory.draft(serverId, session).first()
            local.value = local.value.copy(text = draft, cursor = draft.length, loadedDraft = true)
            refresh()
        }
    }

    private fun withSession(block: suspend (ServerDataSet, String) -> Unit) {
        val id = sessionID.value ?: return
        val set = dataSets.active.value ?: return
        viewModelScope.launch { block(set, id) }
    }

    private companion object {
        const val STOP_TIMEOUT = 5_000L

        /** Long enough to be one pause, short enough to survive a quick switch of screens. */
        const val DRAFT_DEBOUNCE_MILLIS = 400L
    }
}
