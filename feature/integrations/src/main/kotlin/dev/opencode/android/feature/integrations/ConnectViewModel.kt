package dev.opencode.android.feature.integrations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.opencode.android.core.data.action.ActionError
import dev.opencode.android.core.data.action.ActionErrorKind
import dev.opencode.android.core.data.action.toActionError
import dev.opencode.android.core.data.integrations.ConnectAttemptPoller
import dev.opencode.android.core.data.integrations.ConnectAttemptProgress
import dev.opencode.android.core.data.integrations.ConnectAttemptState
import dev.opencode.android.core.data.integrations.ConnectOutcome
import dev.opencode.android.core.data.integrations.CredentialAction
import dev.opencode.android.core.data.integrations.IntegrationFlow
import dev.opencode.android.core.data.integrations.IntegrationFlows
import dev.opencode.android.core.data.integrations.IntegrationForm
import dev.opencode.android.core.data.integrations.McpConfigForm
import dev.opencode.android.core.data.integrations.outcome
import dev.opencode.android.core.data.preferences.ExperimentalPreferences
import dev.opencode.android.core.data.server.ServerDataRegistry
import dev.opencode.android.core.data.sync.SyncedState
import dev.opencode.android.core.model.ConnectionInfo
import dev.opencode.android.core.model.FormAnswer
import dev.opencode.android.core.model.IntegrationInfo
import dev.opencode.android.core.model.IntegrationMethod
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import javax.inject.Inject

/** One integration row: the integration, its methods, and the logins that already exist. */
data class IntegrationRow(
    val info: IntegrationInfo,
) {
    val id: String get() = info.id
    val hasCredential: Boolean get() = info.isConnected
    val hasEnvironment: Boolean get() = info.environment.isNotEmpty()
}

/** The flow a sheet is currently running, and the method it was opened for. */
data class ActiveConnect(
    val integrationID: String,
    val integrationName: String,
    val method: IntegrationMethod,
    val flow: IntegrationFlow,
)

/**
 * The whole connect surface: the integration list, and whichever login sheet is open.
 *
 * **One state, one sheet, and the reason is that the plan's four flows share one lifecycle.** A user
 * who starts a key login, backs out, and starts an OAuth one must not find the previous attempt's
 * poller still running, so [active] is a single value rather than a set of flags — starting a new
 * flow replaces it, and [dismissConnect] cancels whatever it had.
 *
 * **The key lives in the state only while the sheet is open, and is cleared the moment it is
 * dismissed or sent.** [keyDraft] is a plain `String` rather than a
 * [dev.opencode.android.core.model.Secret] because the user has to see and edit it; the wrapping
 * into a `Secret` happens at [connectWithKey], and nothing between the two logs the state — which is
 * why the state's own `toString` is not something any test asserts on.
 */
data class ConnectUiState(
    val directory: String? = null,
    val integrations: SyncedState<List<IntegrationRow>> = SyncedState(),
    val active: ActiveConnect? = null,
    /** The label the credential gets; blank means the integration's own default. */
    val labelDraft: String = "",
    /** The API key, while a key sheet is open. */
    val keyDraft: String = "",
    /** The answers to the method's form, keyed by field. */
    val formAnswers: FormAnswer = emptyMap(),
    /** The device code the user typed, for a `mode=code` attempt. */
    val codeDraft: String = "",
    val progress: ConnectAttemptProgress = ConnectAttemptProgress(),
    /**
     * The URL the Custom Tab may open, already scheme-checked by the model layer.
     *
     * **`null` when there is no attempt, and `null` when the server sent something unsafe.** The two
     * are different reasons and the sheet shows a button only in the first case, so the difference is
     * in the value rather than in a flag: the raw URL is not stored anywhere on this object, so there
     * is no path by which a renderer could open an unvalidated one.
     */
    val oauthAttemptUrl: String? = null,
    /** The server's own instructions, which are untrusted text and shown verbatim. */
    val oauthInstructions: String = "",
    /** The attempt's `mode`: `auto` polls, `code` asks for a code. */
    val oauthMode: String? = null,
    val wellknownUrl: String = "",
    val wellknownUsable: Boolean = false,
    val busy: Boolean = false,
    /** The credential awaiting confirmation, and which action armed it (plan §5.2). */
    val confirm: PendingCredentialAction? = null,
    val error: ActionError? = null,
) {
    val rows: List<IntegrationRow> get() = integrations.value.orEmpty()

    /** Whether the sheet's method declares a form. */
    val hasForm: Boolean
        get() = active?.let { IntegrationFlows.of(it.method).showsForm(it.method) } == true

    /** Whether the sheet's submit button is enabled. */
    val canSubmit: Boolean
        get() = when (active?.flow) {
            // A key login needs the key *and* a valid form; an OAuth one needs the form only,
            // because the browser is where the key-equivalent is entered.
            IntegrationFlow.KEY -> keyDraft.isNotBlank() && IntegrationForm.isReady(active.method, formAnswers)

            IntegrationFlow.OAUTH -> IntegrationForm.isReady(active.method, formAnswers)

            IntegrationFlow.COMMAND -> IntegrationForm.isReady(active.method, formAnswers)

            IntegrationFlow.ENVIRONMENT, IntegrationFlow.UNSUPPORTED -> false

            null -> false
        }

    /** Whether a button that opens the provider in a Custom Tab should be shown. */
    val canOpenOauthUrl: Boolean get() = active?.flow == IntegrationFlow.OAUTH && oauthAttemptUrl != null

    /** Whether the attempt is a `mode=code` one, which is the only one that asks for a code. */
    val needsCode: Boolean get() = active?.flow == IntegrationFlow.OAUTH && oauthMode == "code" && !progress.isTerminal

    /** Whether the flow is still polling, which is what the sheet's progress row means. */
    val isPolling: Boolean get() = progress.state.isRunning

    val canCancel: Boolean get() = progress.attemptID != null && !progress.isTerminal

    /** The device code a command's output contains, which is what the copy button offers. */
    val deviceCode: String? get() = McpConfigForm.deviceCodeIn(progress.output)

    /** Whether a `mode=code` attempt's code can be submitted. */
    val canSubmitCode: Boolean get() = needsCode && codeDraft.isNotBlank() && !busy

    /**
     * Whether the well-known form's submit button is enabled.
     *
     * **The URL is checked here and again in the surface.** The duplicate is deliberate: the store is
     * the only caller that may run without a UI, so the check cannot live only in the view model —
     * and a button that is enabled for something the store will reject is a worse experience than a
     * greyed one.
     */
    val canAddWellknown: Boolean
        get() = wellknownUsable && wellknownUrl.isNotBlank() &&
            McpConfigForm.isWellknownUrl(wellknownUrl) && !busy
}

/** A credential action the user has to confirm before it is sent. */
data class PendingCredentialAction(
    val connection: ConnectionInfo.Credential,
    val action: CredentialAction,
    /** The new label, for a rename; empty for the others. */
    val label: String = "",
)

/**
 * The connect screen: parity with `/connect` (plan §6, Phase 8).
 *
 * **The four flows are one flow with a `when` on [IntegrationFlow], and the decision of which is
 * data** ([IntegrationFlows.of]), so the dispatch is a test rather than something a reader has to
 * infer from a composable.
 *
 * **Cancel is available in every phase of a login, and it is the only way out of a wrong one.**
 * [dismissConnect] sets the poller's state locally *before* telling the server, so the sheet closes
 * on the tap rather than after a round trip that may be on a failing network — the server is still
 * told, because an attempt nobody cancelled keeps a browser tab and a pending credential alive.
 */
@HiltViewModel
class ConnectViewModel @Inject constructor(
    private val dataSets: ServerDataRegistry,
    private val experimental: ExperimentalPreferences,
) : ViewModel() {

    private val _state = MutableStateFlow(ConnectUiState())
    val state: StateFlow<ConnectUiState> = _state.asStateFlow()

    private val poller = ConnectAttemptPoller()
    private var collector: Job? = null
    private var settingsJob: Job? = null

    /** Binds the list to a checkout and publishes it as the one being managed. */
    fun open(directory: String) {
        val set = dataSets.active.value ?: return
        _state.value = _state.value.copy(directory = directory)
        collector?.cancel()
        collector = viewModelScope.launch {
            set.integrations.integrations(directory).state.collect { syncing ->
                _state.value = _state.value.copy(
                    integrations = SyncedState(value = syncing.value?.map(::IntegrationRow), status = syncing.status),
                )
            }
        }
        settingsJob?.cancel()
        settingsJob = viewModelScope.launch {
            // **The switch is read, not taken from the settings screen.** The two can disagree —
            // the switch can be turned on in settings while this screen was never told, and a screen
            // that trusted its own copy of the flag would offer a button the store then refuses.
            experimental.settings.collect { settings ->
                val allowed = set.integrations.wellknownUsable(allowedBySetting = settings.wellknownIntegrations)
                _state.value = _state.value.copy(wellknownUsable = allowed)
            }
        }
        viewModelScope.launch { set.integrations.integrations(directory).sync() }
    }

    /** Re-reads on resume, which is when a login started elsewhere may have landed. */
    fun resume() {
        val set = dataSets.active.value ?: return
        val directory = _state.value.directory ?: return
        viewModelScope.launch { set.integrations.integrations(directory).sync() }
    }

    // ------------------------------------------------------------------------------ opening a flow

    /**
     * Opens the sheet for [method], choosing the flow from the method's type.
     *
     * **A no-op for a flow with nothing to do.** An `env` method and an unknown one are listed and
     * described, but opening a sheet for them would be a sheet with no action, which is the dead
     * control Phase 7 shipped a Fork button as.
     */
    fun openFlow(integration: IntegrationInfo, method: IntegrationMethod) {
        val flow = IntegrationFlows.of(method)
        if (!flow.isStartable) return
        _state.value = _state.value.copy(
            active = ActiveConnect(integration.id, integration.name, method, flow),
            labelDraft = "",
            keyDraft = "",
            formAnswers = IntegrationForm.defaultsOf(method),
            codeDraft = "",
            progress = ConnectAttemptProgress(),
            oauthAttemptUrl = null,
            oauthInstructions = "",
            oauthMode = null,
            error = null,
        )
    }

    fun setLabel(label: String) {
        _state.value = _state.value.copy(labelDraft = label)
    }

    fun setKey(key: String) {
        _state.value = _state.value.copy(keyDraft = key)
    }

    fun setCode(code: String) {
        _state.value = _state.value.copy(codeDraft = code)
    }

    /** Records a form answer. The engine decides visibility, validity and the answer map. */
    fun setAnswer(key: String, value: JsonElement?) {
        val method = _state.value.active?.method ?: return
        val answers = if (value == null) {
            _state.value.formAnswers - key
        } else {
            _state.value.formAnswers + (key to value)
        }
        // Answers to fields that are not visible are dropped here rather than at submit: the engine
        // already omits them from `toAnswer`, and keeping them would let a `when` condition keep
        // firing on a value the user can no longer see or change.
        val visible = IntegrationForm.fieldsOf(method).filter { IntegrationFlows.of(method).showsForm(method) }
            .filter { dev.opencode.android.core.data.forms.FormEngine.isVisible(it, answers) }
            .map { it.key }
            .toSet()
        _state.value = _state.value.copy(formAnswers = answers.filterKeys { it in visible })
    }

    /** Clears the error line. The sheet stays open, because the user has not finished with it. */
    fun dismissError() {
        _state.value = _state.value.copy(error = null)
    }

    fun dismissConnect() {
        val active = _state.value.active
        val attemptID = _state.value.progress.attemptID
        poller.cancel()
        _state.value = ConnectUiState(
            directory = _state.value.directory,
            integrations = _state.value.integrations,
            wellknownUrl = _state.value.wellknownUrl,
            wellknownUsable = _state.value.wellknownUsable,
        )
        // The server is told after the state is reset, so the sheet is gone before the round trip.
        if (active != null && attemptID != null) {
            viewModelScope.launch {
                val set = dataSets.active.value ?: return@launch
                val directory = _state.value.directory ?: return@launch
                when (IntegrationFlows.of(active.method)) {
                    IntegrationFlow.OAUTH -> set.integrations.cancelOauth(directory, active.integrationID, attemptID)
                    IntegrationFlow.COMMAND -> set.integrations.cancelCommand(directory, active.integrationID, attemptID)
                    else -> Unit
                }
            }
        }
    }

    // ------------------------------------------------------------------------------ running a flow

    /**
     * Runs the active flow.
     *
     * **Key, OAuth and command each send their own request, and none of them guesses the method's
     * `methodID`.** A `key` method has no id in the schema, which is why [IntegrationFlow.KEY] does
     * not need one; the other two carry it and a server that returns `IntegrationMethodNotFound` is
     * reported as such rather than as a generic failure.
     */
    fun submit() {
        val state = _state.value
        val active = state.active ?: return
        if (!state.canSubmit) return
        val set = dataSets.active.value ?: return
        val directory = state.directory ?: return
        val answer = IntegrationForm.answerOf(active.method, state.formAnswers)
        _state.value = state.copy(busy = true, error = null)
        viewModelScope.launch {
            when (active.flow) {
                IntegrationFlow.KEY -> {
                    val result = set.integrations.connectWithKey(
                        directory = directory,
                        integrationID = active.integrationID,
                        key = state.keyDraft,
                        answer = answer,
                        label = state.labelDraft.trim().takeIf { it.isNotEmpty() },
                    )
                    finish(result)
                }

                IntegrationFlow.OAUTH -> {
                    val methodID = (active.method as? IntegrationMethod.OAuth)?.id
                    if (methodID == null) {
                        finish(Result.failure<Unit>(IllegalStateException("An OAuth method has no id")))
                        return@launch
                    }
                    val result = set.integrations.startOauth(
                        directory = directory,
                        integrationID = active.integrationID,
                        methodID = methodID,
                        answer = answer,
                        label = state.labelDraft.trim().takeIf { it.isNotEmpty() },
                    )
                    result
                        .onSuccess { attempt -> onAttemptStarted(directory, active, attempt) }
                        .onFailure { finish(Result.failure<Unit>(it)) }
                }

                IntegrationFlow.COMMAND -> {
                    val methodID = (active.method as? IntegrationMethod.Command)?.id
                    if (methodID == null) {
                        finish(Result.failure<Unit>(IllegalStateException("A command method has no id")))
                        return@launch
                    }
                    val result = set.integrations.startCommand(
                        directory = directory,
                        integrationID = active.integrationID,
                        methodID = methodID,
                        label = state.labelDraft.trim().takeIf { it.isNotEmpty() },
                    )
                    result
                        .onSuccess { attempt -> onCommandStarted(directory, active, attempt) }
                        .onFailure { finish(Result.failure<Unit>(it)) }
                }

                IntegrationFlow.ENVIRONMENT, IntegrationFlow.UNSUPPORTED -> finish(Result.success(Unit))
            }
        }
    }

    /**
     * An OAuth attempt has started.
     *
     * **The URL is validated before it is shown, and the poll starts either way.** A `mode=code`
     * attempt has no URL worth opening but still has to be polled, and a `mode=auto` attempt with an
     * unsafe URL is shown without a button rather than blocked — the user can still finish it if the
     * provider redirects on its own. Refusing the whole attempt because the URL was `javascript:`
     * would be less safe, not more: the server may complete it without the app navigating anywhere.
     */
    private fun onAttemptStarted(directory: String, active: ActiveConnect, attempt: dev.opencode.android.core.model.OAuthAttempt) {
        _state.value = _state.value.copy(
            busy = false,
            progress = ConnectAttemptProgress(
                attemptID = attempt.attemptID,
                state = ConnectAttemptState.Pending,
            ),
            oauthAttemptUrl = attempt.safeUrl,
            oauthInstructions = attempt.instructions,
            oauthMode = attempt.mode,
        )
        poller.startOauth(
            scope = viewModelScope,
            attemptID = attempt.attemptID,
            read = { _ -> dataSets.active.value?.integrations?.oauthStatus(directory, active.integrationID, attempt.attemptID) },
            onState = { progress -> onPolled(progress) },
        )
    }

    private fun onCommandStarted(directory: String, active: ActiveConnect, attempt: dev.opencode.android.core.model.CommandAttempt) {
        _state.value = _state.value.copy(
            busy = false,
            progress = ConnectAttemptProgress(attemptID = attempt.attemptID, state = ConnectAttemptState.Pending),
            oauthAttemptUrl = null,
            oauthInstructions = "",
            oauthMode = null,
        )
        poller.startCommand(
            scope = viewModelScope,
            attemptID = attempt.attemptID,
            read = { _ -> dataSets.active.value?.integrations?.commandStatus(directory, active.integrationID, attempt.attemptID) },
            onState = { progress -> onPolled(progress) },
        )
    }

    /**
     * Folds one poll.
     *
     * **A terminal state re-reads the catalog and closes the sheet.** The client never guesses that a
     * credential now exists (plan §4.2): `integration.list` is asked, and the answer is what the row
     * shows. A failure or an expiry keeps the sheet open with the reason, because "try again" is the
     * useful next action and closing would throw it away.
     */
    private fun onPolled(progress: ConnectAttemptProgress) {
        _state.value = _state.value.copy(progress = progress)
        if (!progress.isTerminal) return
        poller.stop()
        // The decision is `ConnectAttemptState.outcome()`, in the data layer, and it is total. A
        // `when` here is how Phase 7 shipped a terminal whose branches did nothing.
        when (progress.state.outcome()) {
            ConnectOutcome.KEEP_WAITING -> Unit

            ConnectOutcome.REFRESH_AND_CLOSE -> {
                val set = dataSets.active.value
                val directory = _state.value.directory
                if (set != null && directory != null) {
                    viewModelScope.launch { set.integrations.integrations(directory).sync(force = true) }
                }
                dismissConnect()
            }

            ConnectOutcome.SHOW_FAILURE -> {
                val failure = progress.state
                _state.value = _state.value.copy(
                    error = ActionError(ActionErrorKind.SERVER, (failure as? ConnectAttemptState.Failed)?.message.orEmpty()),
                )
            }

            ConnectOutcome.SHOW_EXPIRY -> _state.value = _state.value.copy(
                error = ActionError(ActionErrorKind.CONFLICT, "The login attempt expired"),
            )

            ConnectOutcome.STAY_CLOSED -> dismissConnect()
        }
    }

    /** `…/complete` for a `mode=code` attempt. */
    fun submitCode() {
        val state = _state.value
        val active = state.active ?: return
        val attemptID = state.progress.attemptID ?: return
        val set = dataSets.active.value ?: return
        val directory = state.directory ?: return
        if (state.codeDraft.isBlank()) return
        poller.stop()
        _state.value = state.copy(busy = true, error = null)
        viewModelScope.launch {
            val result = set.integrations.completeOauth(
                directory = directory,
                integrationID = active.integrationID,
                attemptID = attemptID,
                code = state.codeDraft.trim(),
            )
            finish(result)
        }
    }

    /** Cancels an attempt without closing the sheet, so the reason can still be read. */
    fun cancelAttempt() {
        val state = _state.value
        val active = state.active ?: return
        val attemptID = state.progress.attemptID ?: return
        val set = dataSets.active.value ?: return
        val directory = state.directory ?: return
        poller.cancel()
        _state.value = state.copy(progress = state.progress.copy(state = ConnectAttemptState.Cancelled))
        viewModelScope.launch {
            when (active.flow) {
                IntegrationFlow.OAUTH -> set.integrations.cancelOauth(directory, active.integrationID, attemptID)
                IntegrationFlow.COMMAND -> set.integrations.cancelCommand(directory, active.integrationID, attemptID)
                else -> Unit
            }
        }
    }

    // ------------------------------------------------------------------------------ credentials

    /** Arms a credential action. Nothing is sent until [confirmCredential]. */
    fun requestCredentialAction(connection: ConnectionInfo.Credential, action: CredentialAction) {
        _state.value = _state.value.copy(
            confirm = PendingCredentialAction(connection, action, label = connection.label),
            error = null,
        )
    }

    fun setConfirmLabel(label: String) {
        _state.value = _state.value.copy(confirm = _state.value.confirm?.copy(label = label))
    }

    fun cancelCredentialAction() {
        _state.value = _state.value.copy(confirm = null)
    }

    /**
     * Sends the confirmed action.
     *
     * **The three actions are the three routes, and nothing else happens for any of them.** A
     * removal is the destructive one, which is why [CredentialAction.REMOVE] needed a confirmation
     * in the first place: the server has no undo and no way to recover the key.
     */
    fun confirmCredential() {
        val pending = _state.value.confirm ?: return
        val set = dataSets.active.value ?: return
        val directory = _state.value.directory ?: return
        _state.value = _state.value.copy(confirm = null, busy = true, error = null)
        viewModelScope.launch {
            val result = when (pending.action) {
                CredentialAction.RENAME -> set.integrations.renameCredential(
                    pending.connection.id,
                    pending.label.trim(),
                )

                CredentialAction.ACTIVATE -> set.integrations.activateCredential(pending.connection.id)

                CredentialAction.REMOVE -> set.integrations.removeCredential(pending.connection.id)
            }
            finish(result, directory = directory)
        }
    }

    // ------------------------------------------------------------------------------ well-known sources

    fun setWellknownUrl(url: String) {
        _state.value = _state.value.copy(wellknownUrl = url, error = null)
    }

    /** `experimental.integration.wellknown`, with the URL checked before it is sent. */
    fun addWellknownSource() {
        val state = _state.value
        val set = dataSets.active.value ?: return
        val directory = state.directory ?: return
        if (state.wellknownUrl.isBlank()) return
        _state.value = state.copy(busy = true, error = null)
        viewModelScope.launch {
            val result = set.integrations.addWellknownSource(directory, state.wellknownUrl.trim())
            finish(result, clearWellknown = true)
        }
    }

    // ------------------------------------------------------------------------------ internals

    private fun finish(
        result: Result<*>,
        directory: String? = null,
        clearWellknown: Boolean = false,
    ) {
        val error = result.exceptionOrNull()?.toActionError()
        val target = directory ?: _state.value.directory
        _state.value = _state.value.copy(
            busy = false,
            error = error,
            // The key is dropped the moment the sheet is no longer showing it. A `String` in a state
            // object outliving the sheet is a secret sitting in memory for no reason.
            keyDraft = if (error == null && _state.value.active != null) "" else _state.value.keyDraft,
            wellknownUrl = if (clearWellknown && error == null) "" else _state.value.wellknownUrl,
        )
        if (error == null && target != null) {
            val set = dataSets.active.value
            if (set != null) viewModelScope.launch { set.integrations.integrations(target).sync(force = true) }
        }
        if (error == null && clearWellknown) dismissWellknownSheet()
    }

    private fun dismissWellknownSheet() {
        _state.value = _state.value.copy(wellknownUrl = "")
    }

    override fun onCleared() {
        poller.stop()
        collector?.cancel()
        settingsJob?.cancel()
        super.onCleared()
    }
}
