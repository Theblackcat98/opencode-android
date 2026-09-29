package dev.opencode.android.feature.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.opencode.android.core.data.action.ActionError
import dev.opencode.android.core.data.config.ConfigDocuments
import dev.opencode.android.core.data.config.ConfigFileFacts
import dev.opencode.android.core.data.config.ConfigFileRead
import dev.opencode.android.core.data.config.ConfigRedaction
import dev.opencode.android.core.data.config.ConfigRow
import dev.opencode.android.core.data.config.ConfigSchema
import dev.opencode.android.core.data.config.ConfigSurface
import dev.opencode.android.core.data.config.DocumentParseFailure
import dev.opencode.android.core.data.config.Jsonc
import dev.opencode.android.core.data.config.ParsedDocument
import dev.opencode.android.core.data.config.SchemaDiagnostic
import dev.opencode.android.core.data.config.TemplateOutcome
import dev.opencode.android.core.data.config.TemplateProblem
import dev.opencode.android.core.data.config.WritePlan
import dev.opencode.android.core.data.config.ConfigDocument
import dev.opencode.android.core.data.integrations.ActionFailure
import dev.opencode.android.core.data.integrations.McpConfigForm
import dev.opencode.android.core.data.preferences.ExperimentalPreferences
import dev.opencode.android.core.data.server.ServerDataRegistry
import dev.opencode.android.core.model.ConfigEntry
import dev.opencode.android.core.model.McpServerConfig
import dev.opencode.android.core.model.PermissionEffect
import dev.opencode.android.core.model.SafeNavigationUrl
import dev.opencode.android.core.data.config.AgentTemplate
import dev.opencode.android.core.data.config.McpTemplate
import dev.opencode.android.core.data.config.ModelTemplate
import dev.opencode.android.core.data.config.PermissionTemplate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/**
 * The config explorer: one row per top-level key, with its source, its precedence and the server's own
 * effective value (plan §6, "Config explorer").
 *
 * **Every key has a row, whether or not anything sets it.** The rows come from walking the vendored
 * schema, so the list cannot silently lose a key, and the screen's "configured only" filter changes
 * what is *visible* rather than what is *known*. That is the difference between a screen that shows
 * the user's configuration and one that shows the parts of it the app happened to understand.
 */
data class ConfigUiState(
    val directory: String? = null,
    val documents: ConfigDocuments = ConfigDocuments(emptyList(), emptyList()),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    /** Whether the switch and the route both say the shell setting may be changed. */
    val shellUsable: Boolean = false,
    /** Whether a file write may be made, which is what the editor and definition buttons need. */
    val writesUsable: Boolean = false,
    /** The shell the server reported, and the one the field is on. */
    val currentShell: String? = null,
    val shellChoice: String? = null,
    /** The shell waiting for its confirmation. */
    val shellPlan: WritePlan? = null,
    val busy: Boolean = false,
    val error: ActionError? = null,
) {
    val rows: List<ConfigRow> get() = documents.rows
    val documentsRead: List<ConfigEntry.Document> get() = documents.documents

    /** The directories the server searched and found nothing in, which is how a wrong path is explained. */
    val searched: List<String> get() = documents.searched

    /** The rows that set something or that the server reports, for the "configured only" filter. */
    val configuredRows: List<ConfigRow> get() = documents.configured
}

/**
 * The explorer, and the one configuration value the server will change for us.
 *
 * **The shell is the only key with a setter** (features doc §33.2), and its confirmation names the
 * *global* file rather than the location's, because `experimental.config.update` writes
 * `~/.config/opencode/opencode.json` for the whole server. A user editing their project file would not
 * expect a field to change the file every project on the box reads, so [requestShell] says so
 * explicitly rather than the row saying "shell" and nothing else.
 */
class ConfigViewModel(
    private val dataSets: ServerDataRegistry,
    private val experimental: ExperimentalPreferences,
) : ViewModel() {

    private val _state = MutableStateFlow(ConfigUiState())
    val state: StateFlow<ConfigUiState> = _state.asStateFlow()

    private var settingsJob: Job? = null

    fun open(directory: String) {
        val set = dataSets.active.value ?: return
        _state.value = _state.value.copy(directory = directory)
        settingsJob?.cancel()
        settingsJob = viewModelScope.launch {
            experimental.settings.collect { settings ->
                _state.value = _state.value.copy(
                    shellUsable = set.configuration.configUpdateUsable(settings.configUpdate),
                    writesUsable = set.configuration.fsUsable(settings.fileWrites),
                )
            }
        }
        load()
    }

    fun resume() = load()

    private fun load() {
        val set = dataSets.active.value ?: return
        _state.value = _state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            val first = set.configuration.documents(_state.value.directory)
            // A second read with the file's own keys, so the "set by" column is exact where the client
            // has read the file and "reported by" where it has not. See ConfigFileFacts for why the
            // projection alone cannot answer it.
            val facts = nearestDocumentFacts(set.configuration, first)
            val documents = if (facts == null) first else set.configuration.documents(_state.value.directory, facts)
            _state.value = _state.value.copy(
                documents = documents,
                loading = false,
                loaded = true,
                error = documents.failure?.error,
                currentShell = documents.documents.lastOrNull()?.info?.shell,
            )
        }
    }

    /**
     * What the nearest configuration document's own text sets, keyed by its position in the answer.
     *
     * **Only the nearest one, and a file that cannot be read is not a failure of the screen.** The
     * projections alone answer every key the server reports, so a file that does not exist — the common
     * case — or cannot be read leaves the explorer complete with the "reported by" column saying so.
     */
    private suspend fun nearestDocumentFacts(
        surface: ConfigSurface,
        documents: ConfigDocuments,
    ): Map<Int, ConfigFileFacts>? {
        val nearest = documents.documents.lastOrNull() ?: return null
        val path = nearest.pathOrNull ?: return null
        val index = documents.entries.indexOf(nearest)
        val text = (surface.readFile(_state.value.directory, path) as? ConfigFileRead.Found)?.text ?: return null
        return mapOf(index to ConfigFileFacts(index, ConfigDocument.topLevelKeys(text)))
    }

    // ------------------------------------------------------------------------------ the shell setting

    fun selectShell(shell: String) {
        _state.value = _state.value.copy(shellChoice = shell, error = null)
    }

    fun cancelShell() {
        _state.value = _state.value.copy(shellPlan = null, shellChoice = null)
    }

    /**
     * Builds the shell's confirmation without sending it.
     *
     * The plan is a [WritePlan] even though nothing is written to disk, because it is the same
     * structure the file editor's confirmation renders: a target, a consequence and a flag saying the
     * change is a privilege one. A shell decides what the agent can execute, so the flag is set and the
     * dialog leads with it.
     */
    fun requestShell() {
        val set = dataSets.active.value ?: return
        val shell = _state.value.shellChoice?.trim().orEmpty()
        if (shell.isEmpty()) return
        _state.value = _state.value.copy(
            shellPlan = set.configuration.planSetting(
                target = GLOBAL_CONFIG,
                consequence = "The bash tool and every terminal on this server will run $shell",
                isPrivilegeChange = true,
            ),
            error = null,
        )
    }

    /** `experimental.config.update`, after the confirmation. */
    fun confirmShell() {
        val shell = _state.value.shellChoice?.trim().orEmpty()
        val set = dataSets.active.value ?: return
        _state.value = _state.value.copy(shellPlan = null, busy = true, error = null)
        viewModelScope.launch {
            set.configuration.setShell(shell).fold(
                onSuccess = {
                    _state.value = _state.value.copy(busy = false, shellChoice = null)
                    load()
                },
                onFailure = { _state.value = _state.value.copy(busy = false, error = it.asActionError()) },
            )
        }
    }

    fun dismissError() {
        _state.value = _state.value.copy(error = null)
    }

    companion object {
        /**
         * The global configuration file, named in the shell confirmation.
         *
         * The path features doc §33.1 gives, written out so the dialog quotes the file rather than
         * describing it. Nothing writes to it by path: the route owns that.
         */
        const val GLOBAL_CONFIG: String = "~/.config/opencode/opencode.json"
    }
}

// ------------------------------------------------------------------------------------------ editor

/** The config file editor, its guided templates and its write confirmation. */
data class ConfigEditorUiState(
    val directory: String? = null,
    /** The path being edited, which is the location's own file until the user changes it. */
    val path: String = "",
    /** The text as the server holds it, which is what a save is based on. */
    val text: String = "",
    /** The text in the field, which may not be valid yet. */
    val draft: String = "",
    /** The file did not exist, so the editor is creating it rather than editing it. */
    val isNewFile: Boolean = false,
    val reading: Boolean = false,
    val validating: Boolean = false,
    val readError: ActionError? = null,
    /** The syntax error, when the text cannot be read at all. */
    val parseFailure: DocumentParseFailure? = null,
    /** The schema's complaints about the draft, with a line each where one could be resolved. */
    val diagnostics: List<SchemaDiagnostic> = emptyList(),
    /** Whether the switch and the route both say a file write may be made. */
    val writesUsable: Boolean = false,
    val template: ConfigTemplateDraft? = null,
    val templateOutcome: TemplateOutcome? = null,
    val plan: WritePlan? = null,
    val outcome: dev.opencode.android.core.data.config.WriteOutcome? = null,
    val saving: Boolean = false,
    val error: ActionError? = null,
) {
    val isValid: Boolean get() = parseFailure == null && diagnostics.isEmpty()
    val canSave: Boolean get() = writesUsable && isValid && !saving && draft.isNotBlank()
    val hasChanges: Boolean get() = draft != text
}

/** Which guided template the editor is offering, and what it has collected so far. */
enum class ConfigTemplateChoice(val id: String) {
    MODEL("model"),
    PERMISSION("permission"),
    AGENT("agent"),
    MCP("mcp"),
}

/** The fields one template collects. Flat, because a phone keyboard is one field at a time. */
data class ConfigTemplateDraft(
    val choice: ConfigTemplateChoice,
    val name: String = "",
    val provider: String = "",
    val model: String = "",
    val variant: String = "",
    val action: String = "bash",
    val resource: String = "*",
    val effect: String = "ask",
    val mode: String = "primary",
    val mcpKind: String = "remote",
    val mcpUrl: String = "",
    val mcpCommand: String = "",
) {
    /**
     * The template this draft describes, or `null` when a required field is empty.
     *
     * **A remote MCP URL is parsed with [SafeNavigationUrl] here rather than at the store.** The server
     * will fetch whatever is sent, so a `file://` or `intent://` would make it read a local file or
     * hand a URL to another app on the user's own machine. The template cannot produce one.
     */
    fun toTemplate(): dev.opencode.android.core.data.config.ConfigTemplate? = when (choice) {
        ConfigTemplateChoice.MODEL -> {
            val text = "$provider/$model"
            if (provider.isBlank() || model.isBlank()) {
                null
            } else {
                ModelTemplate(provider.trim(), model.trim(), variant.trim().takeIf(String::isNotEmpty))
            }.takeIf { dev.opencode.android.core.model.ConfigModel.parse(text) != null }
        }

        ConfigTemplateChoice.PERMISSION ->
            if (action.isBlank()) null else PermissionTemplate(action.trim(), resource.trim(), PermissionEffect(effect.trim()))

        ConfigTemplateChoice.AGENT ->
            if (name.isBlank()) null else AgentTemplate(name = name.trim(), mode = mode.trim().takeIf(String::isNotBlank))

        ConfigTemplateChoice.MCP -> when (mcpKind) {
            "local" -> if (mcpCommand.isBlank() || name.isBlank()) {
                null
            } else {
                McpTemplate(name.trim(), McpServerConfig.Local(command = McpConfigForm.splitCommand(mcpCommand)))
            }

            else -> {
                val url = SafeNavigationUrl.parse(mcpUrl.trim())
                if (url == null || name.isBlank()) null else McpTemplate(name.trim(), McpServerConfig.Remote(url = url))
            }
        }
    }
}

/**
 * The editor for one configuration file, with the guided templates and the write confirmation.
 *
 * **Validation runs off the main thread on every change, and the answer is discarded if it is stale.**
 * A large `opencode.jsonc` means walking 1,385 local schema references per keystroke, which is exactly
 * the work plan §5.4 says must not happen on a frame. A validator that can finish out of order would
 * put an error on a line the user has already fixed, which is worse than no validation because it
 * teaches the user to ignore the editor — so [validate] carries a request number and only the newest
 * answer is published.
 *
 * **There is no path from the text field to a write that does not pass through a plan.** [requestSave]
 * validates and builds a [WritePlan] and sends nothing; [confirmSave] is the only caller of
 * [ConfigSurface.commit]. That is what makes "a write requires a confirmation naming the file and the
 * change" a property of the code rather than a rule someone could forget, and `ConfirmedWriteTest`
 * asserts on the plan a screen would render.
 */
class ConfigEditorViewModel(
    private val dataSets: ServerDataRegistry,
    private val experimental: ExperimentalPreferences,
) : ViewModel() {

    private val _state = MutableStateFlow(ConfigEditorUiState())
    val state: StateFlow<ConfigEditorUiState> = _state.asStateFlow()

    private var settingsJob: Job? = null
    private var validationJob: Job? = null
    private var validationRequest = 0

    fun open(directory: String, path: String? = null) {
        val set = dataSets.active.value ?: return
        _state.value = _state.value.copy(directory = directory, path = path ?: LOCATION_CONFIG)
        settingsJob?.cancel()
        settingsJob = viewModelScope.launch {
            experimental.settings.collect { settings ->
                _state.value = _state.value.copy(writesUsable = set.configuration.fsUsable(settings.fileWrites))
            }
        }
        read()
    }

    fun resume() = read()

    private fun read() {
        val set = dataSets.active.value ?: return
        _state.value = _state.value.copy(reading = true, readError = null)
        viewModelScope.launch {
            when (val read = set.configuration.readFile(_state.value.directory, _state.value.path)) {
                is ConfigFileRead.Found -> {
                    val text = read.file.text.orEmpty()
                    _state.value = _state.value.copy(
                        text = text,
                        draft = text,
                        isNewFile = false,
                        readError = null,
                        parseFailure = null,
                        diagnostics = emptyList(),
                        reading = false,
                    )
                    validate(text)
                }

                // A file that is not there is the editor's "create it" case, not a failure.
                is ConfigFileRead.Missing -> _state.value = _state.value.copy(
                    text = "",
                    draft = "",
                    isNewFile = true,
                    readError = null,
                    parseFailure = null,
                    diagnostics = emptyList(),
                    reading = false,
                )

                is ConfigFileRead.Failed ->
                    _state.value = _state.value.copy(readError = read.error, reading = false)
            }
        }
    }

    fun setPath(path: String) {
        val trimmed = path.trim()
        if (trimmed.isEmpty()) return
        _state.value = _state.value.copy(path = trimmed)
        read()
    }

    /** The text as the user is editing it, validated off the main thread. */
    fun setDraft(text: String) {
        _state.value = _state.value.copy(draft = text, error = null, outcome = null)
        validate(text)
    }

    private fun validate(text: String) {
        val set = dataSets.active.value ?: return
        val request = ++validationRequest
        _state.value = _state.value.copy(validating = true)
        validationJob?.cancel()
        validationJob = viewModelScope.launch {
            val answer = withContext(Dispatchers.Default) { check(text, set.configuration.schema()) }
            if (request != validationRequest) return@launch
            _state.value = _state.value.copy(
                parseFailure = answer.failure,
                diagnostics = answer.diagnostics,
                validating = false,
            )
        }
    }

    internal data class Answer(val failure: DocumentParseFailure?, val diagnostics: List<SchemaDiagnostic>)

    /**
     * The syntax and schema verdict for [text], as a pure function.
     *
     * **A syntax error and a schema error do not both apply.** A document that cannot be parsed has no
     * tree to check against the schema, so reporting "unreadable" plus every schema complaint it would
     * have had would be noise. The caller is a background dispatcher and the only impure part is the
     * line lookup, which is what makes this testable on the JVM.
     */
    internal fun check(text: String, schema: ConfigSchema): Answer = when (val parsed = ConfigDocument.parse(text)) {
        is ParsedDocument.Failed -> Answer(parsed.failure, emptyList())
        is ParsedDocument.Parsed -> Answer(
            null,
            schema.validator().validate(parsed.document).map { it.withLine(text) },
        )
    }

    // ------------------------------------------------------------------------------ templates

    fun openTemplate(choice: ConfigTemplateChoice) {
        _state.value = _state.value.copy(template = ConfigTemplateDraft(choice), templateOutcome = null)
    }

    fun closeTemplate() {
        _state.value = _state.value.copy(template = null, templateOutcome = null)
    }

    fun updateTemplate(transform: (ConfigTemplateDraft) -> ConfigTemplateDraft) {
        val draft = _state.value.template ?: return
        _state.value = _state.value.copy(template = transform(draft), templateOutcome = null)
    }

    /**
     * Applies the template to the document and shows the result, **without writing it**.
     *
     * A template is a starting point the user reviews and edits, so its output goes to [templateOutcome]
     * and only [insertTemplate] moves it into the field. A template that wrote on selection would be an
     * unconfirmed write to the server's own filesystem, which is what plan §5.2 forbids.
     */
    fun applyTemplate() {
        val set = dataSets.active.value ?: return
        val draft = _state.value.template ?: return
        val template = draft.toTemplate()
        _state.value = _state.value.copy(
            templateOutcome = if (template == null) {
                TemplateOutcome.NotReady(TemplateProblem.REQUIRED_FIELD)
            } else {
                val document = ConfigDocument.parse(_state.value.draft).documentOrNull ?: JsonObject(emptyMap())
                dev.opencode.android.core.data.config.ConfigTemplates.build(set.configuration.schema(), template, document)
            },
        )
    }

    /** Puts the template's value into the text field, where the user can edit it. */
    fun insertTemplate() {
        val set = dataSets.active.value ?: return
        val outcome = _state.value.templateOutcome as? TemplateOutcome.Ready ?: return
        val template = _state.value.template?.toTemplate() ?: return
        val value = (outcome.document as? JsonObject)?.get(template.key) ?: return
        val edited = ConfigDocument.setInText(_state.value.draft, template.key, value)
        _state.value = _state.value.copy(template = null, templateOutcome = null, draft = edited)
        validate(edited)
        set.configuration.let { }
    }

    // ------------------------------------------------------------------------------ the write

    /**
     * Builds the confirmation. Nothing is sent.
     *
     * The consequence names what changes, and the flag says whether it is a privilege change: a
     * permissions or agent-definition edit decides what the agent may do, and the user has to be told
     * that before the bytes move (plan §5.2).
     */
    fun requestSave() {
        val set = dataSets.active.value ?: return
        val text = _state.value.draft
        val schema = set.configuration.schema()
        val keys = ConfigDocument.topLevelKeys(text)
        val isPrivilege = keys.any { schema.key(it)?.isPrivilegeChange == true }
        _state.value = _state.value.copy(
            plan = set.configuration.planFileWrite(
                path = _state.value.path,
                text = text,
                consequence = if (_state.value.isNewFile) {
                    "This creates ${_state.value.path}; the server reads it after a reload"
                } else {
                    "This replaces ${_state.value.path}; the server reads it after a reload"
                },
                isPrivilegeChange = isPrivilege,
                existing = _state.value.text.takeIf { !_state.value.isNewFile },
                validate = { schema.validator().validate(it) },
            ),
            error = null,
        )
    }

    fun cancelSave() {
        _state.value = _state.value.copy(plan = null)
    }

    /** `experimental.fs.write`, then `location.reload`, then `config.get` again. */
    fun confirmSave() {
        val set = dataSets.active.value ?: return
        val plan = _state.value.plan ?: return
        val directory = _state.value.directory
        val keys = ConfigDocument.topLevelKeys(plan.text)
        _state.value = _state.value.copy(plan = null, saving = true, error = null)
        viewModelScope.launch {
            set.configuration.commit(plan, directory, keys).fold(
                onSuccess = { outcome ->
                    _state.value = _state.value.copy(
                        saving = false,
                        text = plan.text,
                        draft = plan.text,
                        isNewFile = false,
                        outcome = outcome,
                        diagnostics = outcome.diagnostics,
                    )
                },
                onFailure = { _state.value = _state.value.copy(saving = false, error = it.asActionError()) },
            )
        }
    }

    fun dismissOutcome() {
        _state.value = _state.value.copy(outcome = null)
    }

    fun dismissError() {
        _state.value = _state.value.copy(error = null)
    }

    companion object {
        /**
         * Where a location's own configuration lives, relative to the directory.
         *
         * `.opencode/opencode.jsonc` is the first entry of the precedence chain a location controls
         * (features doc §33.1). It is relative because `fs.read` resolves against the location and
         * `fs.write` is given the location beside it; the app never joins a home directory it does not
         * know.
         */
        const val LOCATION_CONFIG: String = ".opencode/opencode.jsonc"
    }
}

/**
 * Attaches a line to a diagnostic that has a path but no position.
 *
 * **Only when the key appears exactly once, and that is the honest limit.** A JSON pointer says *which*
 * key is wrong, not where it was typed — the same key can appear twice and a search finds the first, so
 * attaching a line unconditionally would point at the wrong occurrence. When the key is unique the line
 * is right; when it is not, the diagnostic keeps its path and shows no line, which is better than a
 * confident wrong answer.
 */
internal fun SchemaDiagnostic.withLine(text: String): SchemaDiagnostic {
    val key = path.substringAfterLast('/', "").replace("~1", "/").replace("~0", "~")
    if (key.isEmpty()) return this
    val needle = "\"$key\""
    val first = text.indexOf(needle)
    if (first < 0 || text.indexOf(needle, first + needle.length) >= 0) return this
    val position = Jsonc.positionOf(text, first)
    return copy(line = position.line, column = position.column)
}

/** The failure of a call this phase made, as the classification a screen renders. */
internal fun Throwable.asActionError(): ActionError? = (this as? ActionFailure)?.error

/**
 * How a value is shown in a row, with a secret never rendered.
 *
 * **There is one function and screens call it.** A screen that reached for a value's own `toString`
 * would be one line away from putting an API key on screen and into a screenshot, and the redaction
 * rules live in `ConfigRedaction` precisely so that they cannot be bypassed by a formatting choice.
 */
internal fun showValue(key: String?, value: kotlinx.serialization.json.JsonElement?): String =
    value?.let { ConfigRedaction.describe(key, it) } ?: ""
