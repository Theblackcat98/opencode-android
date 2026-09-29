package dev.opencode.android.feature.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.opencode.android.core.data.action.ActionError
import dev.opencode.android.core.data.config.ConfigFileRead
import dev.opencode.android.core.data.config.DefinitionKind
import dev.opencode.android.core.data.config.DefinitionName
import dev.opencode.android.core.data.config.DefinitionTemplates
import dev.opencode.android.core.data.config.WritePlan
import dev.opencode.android.core.data.integrations.ActionFailure
import dev.opencode.android.core.data.preferences.ExperimentalPreferences
import dev.opencode.android.core.data.server.ServerDataRegistry
import dev.opencode.android.core.model.InstructionEntry
import dev.opencode.android.core.model.LoadedLocation
import dev.opencode.android.core.model.MigrationStatus
import dev.opencode.android.core.model.PermissionEffect
import dev.opencode.android.core.model.PermissionRule
import dev.opencode.android.core.model.SavedPermission
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// ------------------------------------------------------------------------------------ permissions

/** One rule in the session editor, as the form holds it. */
data class PermissionRuleDraft(
    val action: String = "",
    val resource: String = "*",
    val effect: PermissionEffect = PermissionEffect.Ask,
    /** The rule this draft came from, so editing one replaces it rather than appending a copy. */
    val original: PermissionRule? = null,
) {
    /** Whether the rule is complete enough to send. */
    val isValid: Boolean get() = action.isNotBlank() && resource.isNotBlank()

    fun toRule(): PermissionRule? = if (isValid) {
        PermissionRule(action = action.trim(), resource = resource.trim(), effect = effect)
    } else {
        null
    }
}

/**
 * The permissions screen: the saved approvals of a project, and a session's own rules.
 *
 * **Removing a saved approval is confirmed by name and by resource**, because it is a privilege change
 * in the other direction: the tool that was allowed once will ask again for exactly that action. A
 * confirmation that said "remove?" would not let a user notice they had just made a tool ask again.
 *
 * **The rules editor leads with the precedence warning and cannot skip it.** Session rules evaluate
 * last and can therefore override an agent's `deny`, which is the same reasoning Phase 4 recorded when
 * it made auto-approve client-side *and only ever `once`*: a server-side rule is the one mechanism
 * that can widen what runs without asking, so it is exactly the mechanism the warning is about. The
 * warning text lives in `strings.xml` and is rendered above the rules, not behind a disclosure the user
 * can skip past.
 */
data class PermissionsUiState(
    val directory: String? = null,
    val projectID: String? = null,
    val sessionID: String? = null,
    val loading: Boolean = false,
    val saved: List<SavedPermission> = emptyList(),
    val savedLoaded: Boolean = false,
    /** The saved approval awaiting its confirmation. */
    val removeTarget: SavedPermission? = null,
    /** The session's rules, and the drafts the editor holds. */
    val rules: List<PermissionRule> = emptyList(),
    val drafts: List<PermissionRuleDraft> = emptyList(),
    val editing: Boolean = false,
    val busy: Boolean = false,
    val error: ActionError? = null,
) {
    val canSave: Boolean get() = drafts.any { it.isValid } && !busy

    /** The rules the editor would send, with an edited rule replacing the one it came from. */
    val pending: List<PermissionRule>
        get() = drafts.mapNotNull { draft ->
            draft.toRule()?.let { rule ->
                when {
                    draft.original == null -> rule
                    draft.original == rule -> null
                    else -> rule
                }
            }
        }

    /** The rules as they would be after the drafts replace the originals. */
    val effective: List<PermissionRule>
        get() {
            val replaced = drafts.mapNotNull { it.original }.toSet()
            val merged = rules.filterNot { it in replaced }.toMutableList()
            merged += drafts.mapNotNull { it.toRule() }
            return merged
        }
}

/** The saved approvals and the session permission rules. */
class PermissionsViewModel(
    private val dataSets: ServerDataRegistry,
) : ViewModel() {

    private val _state = MutableStateFlow(PermissionsUiState())
    val state: StateFlow<PermissionsUiState> = _state.asStateFlow()

    fun open(directory: String, projectID: String?, sessionID: String?) {
        _state.value = _state.value.copy(directory = directory, projectID = projectID, sessionID = sessionID)
        load()
    }

    fun resume() = load()

    private fun load() {
        val set = dataSets.active.value ?: return
        _state.value = _state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            val saved = set.configuration.savedPermissions(_state.value.projectID)
            _state.value = saved.fold(
                onSuccess = { list ->
                    _state.value.copy(
                        saved = list,
                        savedLoaded = true,
                        loading = false,
                        error = null,
                    )
                },
                onFailure = { failure ->
                    _state.value.copy(
                        savedLoaded = true,
                        loading = false,
                        error = (failure as? ActionFailure)?.error,
                    )
                },
            )
            val sessionID = _state.value.sessionID ?: return@launch
            loadRules(sessionID)
        }
    }

    private suspend fun loadRules(sessionID: String) {
        val set = dataSets.active.value ?: return
        val session = set.sessions.loadSession(sessionID) ?: return
        _state.value = _state.value.copy(
            rules = session.permissions.orEmpty(),
            drafts = session.permissions.orEmpty().map(::draftOf),
        )
    }

    // ------------------------------------------------------------------------------ saved approvals

    fun requestRemove(permission: SavedPermission) {
        _state.value = _state.value.copy(removeTarget = permission, error = null)
    }

    fun cancelRemove() {
        _state.value = _state.value.copy(removeTarget = null)
    }

    /** `permission.saved.remove`, after the confirmation. */
    fun confirmRemove() {
        val set = dataSets.active.value ?: return
        val target = _state.value.removeTarget ?: return
        _state.value = _state.value.copy(removeTarget = null, busy = true, error = null)
        viewModelScope.launch {
            set.configuration.removeSavedPermission(target.id).fold(
                onSuccess = {
                    _state.value = _state.value.copy(busy = false)
                    load()
                },
                onFailure = { _state.value = _state.value.copy(busy = false, error = (it as? ActionFailure)?.error) },
            )
        }
    }

    // ------------------------------------------------------------------------------ session rules

    fun startEditing() {
        val rules = _state.value.rules
        _state.value = _state.value.copy(
            editing = true,
            drafts = rules.map(::draftOf),
        )
    }

    fun cancelEditing() {
        _state.value = _state.value.copy(
            editing = false,
            drafts = _state.value.rules.map(::draftOf),
        )
    }

    fun addRule() {
        _state.value = _state.value.copy(drafts = _state.value.drafts + PermissionRuleDraft())
    }

    fun updateRule(index: Int, transform: (PermissionRuleDraft) -> PermissionRuleDraft) {
        val drafts = _state.value.drafts.toMutableList()
        if (index !in drafts.indices) return
        drafts[index] = transform(drafts[index])
        _state.value = _state.value.copy(drafts = drafts)
    }

    fun removeRule(index: Int) {
        val drafts = _state.value.drafts.toMutableList()
        if (index !in drafts.indices) return
        drafts.removeAt(index)
        _state.value = _state.value.copy(drafts = drafts)
    }

    /**
     * `session.update` with the edited rules.
     *
     * **The whole ruleset is sent, not a patch**, because the route's body is `permissions: Ruleset |
     * null` — there is no per-rule route. The client therefore holds the list it read and writes it
     * back, which is why [PermissionsUiState.effective] exists: a rule the user removed has to be
     * absent from what is sent, not merely absent from the field.
     */
    fun saveRules() {
        val set = dataSets.active.value ?: return
        val sessionID = _state.value.sessionID ?: return
        val rules = _state.value.effective
        _state.value = _state.value.copy(busy = true, error = null)
        viewModelScope.launch {
            set.commands.update(sessionID = sessionID, permissions = rules).fold(
                onSuccess = {
                    _state.value = _state.value.copy(
                        busy = false,
                        editing = false,
                        rules = rules,
                        drafts = rules.map(::draftOf),
                    )
                },
                onFailure = { _state.value = _state.value.copy(busy = false, error = (it as? ActionFailure)?.error) },
            )
        }
    }

    fun dismissError() {
        _state.value = _state.value.copy(error = null)
    }
}

/**
 * A rule as the editor holds it, with the rule it came from.
 *
 * **Keeping the original is what makes "edited" different from "added".** The route takes a whole
 * ruleset rather than a patch, so the editor has to work out which rules the user changed and which they
 * added; without the original an edited rule would be indistinguishable from a second copy of itself.
 */
private fun draftOf(rule: PermissionRule) = PermissionRuleDraft(
    action = rule.action,
    resource = rule.resource,
    effect = rule.effect,
    original = rule,
)

// ----------------------------------------------------------------------------------- definitions

/** The definition editor: one Markdown file, its front matter and its body. */
data class DefinitionUiState(
    val directory: String? = null,
    val kind: DefinitionKind = DefinitionKind.AGENT,
    val name: String = "",
    val path: String = "",
    val text: String = "",
    val draft: String = "",
    val isNewFile: Boolean = true,
    val reading: Boolean = false,
    val writesUsable: Boolean = false,
    /** The name cannot be used, in words. */
    val nameProblem: String? = null,
    val plan: WritePlan? = null,
    val outcome: dev.opencode.android.core.data.config.WriteOutcome? = null,
    val saving: Boolean = false,
    val error: ActionError? = null,
) {
    val frontMatter: Map<String, String> get() = DefinitionTemplates.frontMatter(draft)
    val body: String get() = DefinitionTemplates.stripFrontMatter(draft)
    val canSave: Boolean get() = writesUsable && nameProblem == null && draft.isNotBlank() && !saving
    val hasChanges: Boolean get() = draft != text
}

/**
 * The editor for `AGENTS.md`, an agent, a command and a skill.
 *
 * **The name is checked here rather than in a text field's `onValueChange`,** because the form, the
 * template and the write path all build the path and only this class sees all three. A name is one path
 * segment, so `../AGENTS.md` and `a/b` are refused: `fs.write` is given a path the *server* resolves,
 * and a traversal would put a file outside the directory the user was looking at.
 *
 * **A definition file is a privilege change when it is an agent or an instruction file**, and the
 * confirmation says so. `AGENTS.md` is read by every agent in the project and an agent's front matter
 * carries its permission block, so both are [WritePlan.isPrivilegeChange]; a command and a skill are
 * not, and a screen that announced every write the same way would be training the user to click
 * through the two that matter.
 */
class DefinitionViewModel(
    private val dataSets: ServerDataRegistry,
    private val experimental: ExperimentalPreferences,
) : ViewModel() {

    private val _state = MutableStateFlow(DefinitionUiState())
    val state: StateFlow<DefinitionUiState> = _state.asStateFlow()

    private var settingsJob: Job? = null

    fun open(directory: String, kind: DefinitionKind, name: String) {
        val set = dataSets.active.value ?: return
        _state.value = _state.value.copy(directory = directory, kind = kind, name = name)
        settingsJob?.cancel()
        settingsJob = viewModelScope.launch {
            experimental.settings.collect { settings ->
                _state.value = _state.value.copy(writesUsable = set.configuration.fsUsable(settings.fileWrites))
            }
        }
        syncPath()
    }

    fun setKind(kind: DefinitionKind) {
        _state.value = _state.value.copy(kind = kind)
        syncPath()
    }

    fun setName(name: String) {
        _state.value = _state.value.copy(name = name)
        syncPath()
    }

    fun setDraft(text: String) {
        _state.value = _state.value.copy(draft = text, error = null)
    }

    /**
     * Recomputes the path and reads or creates the file.
     *
     * **A new name starts from a template rather than from nothing.** An empty Markdown file the user
     * has to know the front matter format for is a worse starting point than one that already has a
     * `---` block, a description and a body to replace — and the template is a *draft*, never a write.
     */
    private fun syncPath() {
        val set = dataSets.active.value ?: return
        val trimmed = _state.value.name.trim()
        val problem = DefinitionName.problem(trimmed)
        _state.value = _state.value.copy(
            nameProblem = problem,
            path = if (problem != null) "" else _state.value.kind.relativePath(trimmed),
        )
        if (problem != null) {
            _state.value = _state.value.copy(
                text = "",
                draft = "",
                isNewFile = true,
                reading = false,
            )
            return
        }
        _state.value = _state.value.copy(reading = true)
        viewModelScope.launch {
            when (val read = set.configuration.readFile(_state.value.directory, _state.value.path)) {
                is ConfigFileRead.Found -> {
                    val text = read.file.text.orEmpty()
                    _state.value = _state.value.copy(text = text, draft = text, isNewFile = false, reading = false)
                }

                is ConfigFileRead.Missing -> {
                    val template = DefinitionTemplates.newFile(_state.value.kind, trimmed)
                    _state.value = _state.value.copy(
                        text = "",
                        draft = template,
                        isNewFile = true,
                        reading = false,
                    )
                }

                is ConfigFileRead.Failed ->
                    _state.value = _state.value.copy(error = read.error, reading = false)
            }
        }
    }

    /** Replaces the front matter, keeping the body the user wrote. */
    fun setFrontMatter(key: String, value: String) {
        val merged = _state.value.frontMatter + (key to value)
        _state.value = _state.value.copy(draft = DefinitionTemplates.withFrontMatter(_state.value.draft, merged))
    }

    fun setBody(body: String) {
        _state.value = _state.value.copy(
            draft = DefinitionTemplates.withFrontMatter(_state.value.draft, _state.value.frontMatter)
                .trimEnd('\n') + "\n\n" + body.trimStart('\n'),
        )
    }

    fun requestSave() {
        val set = dataSets.active.value ?: return
        val kind = _state.value.kind
        val plan = set.configuration.planFileWrite(
            path = _state.value.path,
            text = _state.value.draft,
            consequence = when (kind) {
                DefinitionKind.AGENT ->
                    "The agent ${_state.value.name} becomes available to every session of this location"

                DefinitionKind.COMMAND ->
                    "/${_state.value.name} becomes a command the agent can be asked to run"

                DefinitionKind.SKILL ->
                    "The skill ${_state.value.name} becomes available to the agent"

                DefinitionKind.INSTRUCTIONS ->
                    "Every agent working in this location is told this instead of what it was told"
            },
            isPrivilegeChange = kind == DefinitionKind.AGENT || kind == DefinitionKind.INSTRUCTIONS,
            existing = _state.value.text.takeIf { !_state.value.isNewFile },
            // A Markdown definition has no schema: its validity is the server's business, and inventing
            // a validator for prose would reject files the server reads perfectly well.
            validate = null,
        )
        _state.value = _state.value.copy(plan = plan, error = null)
    }

    fun cancelSave() {
        _state.value = _state.value.copy(plan = null)
    }

    fun confirmSave() {
        val set = dataSets.active.value ?: return
        val plan = _state.value.plan ?: return
        _state.value = _state.value.copy(plan = null, saving = true, error = null)
        viewModelScope.launch {
            // A definition file is not one `config.get` reports, so the "is the server reading it?"
            // question is the server's catalog rather than the configuration chain.
            set.configuration.commit(
                plan = plan,
                directory = _state.value.directory,
                expectInConfig = false,
            ).fold(
                onSuccess = { outcome ->
                    _state.value = _state.value.copy(
                        saving = false,
                        text = plan.text,
                        isNewFile = false,
                        outcome = outcome,
                    )
                },
                onFailure = { _state.value = _state.value.copy(saving = false, error = (it as? ActionFailure)?.error) },
            )
        }
    }

    fun dismissOutcome() {
        _state.value = _state.value.copy(outcome = null)
    }

    fun dismissError() {
        _state.value = _state.value.copy(error = null)
    }
}

// ------------------------------------------------------------------------------------- maintenance

/** Reload, the loaded locations, the V1 migration and the update banner. */
data class MaintenanceUiState(
    val loading: Boolean = false,
    val locations: List<LoadedLocation> = emptyList(),
    val locationsLoaded: Boolean = false,
    /** The location awaiting its eviction confirmation. */
    val evictTarget: LoadedLocation? = null,
    val migration: MigrationStatus? = null,
    val migrationLoaded: Boolean = false,
    /** The version `installation.update-available` announced, if any. */
    val updateAvailable: String? = null,
    val reloading: Boolean = false,
    val outcome: String? = null,
    val error: ActionError? = null,
)

/**
 * The maintenance screen, and the two operations on it that are disruptive.
 *
 * **`location.reload` cancels pending permissions and forms** (the route's own description), so the
 * button says so rather than offering a bare "reload": a user tapping it mid-turn loses the answer they
 * were about to give.
 *
 * **Evicting a location is confirmed by naming the directory.** Nothing is lost — the caches are
 * rebuilt on next use, which is what the route's description says and what a resync recovers from — but
 * work in flight for that location is interrupted, and the user who evicts the wrong checkout has no
 * undo. The confirmation is therefore explicit, and the panel re-reads the list afterwards so what the
 * user sees is the server's answer rather than an optimistic removal.
 */
class MaintenanceViewModel(
    private val dataSets: ServerDataRegistry,
) : ViewModel() {

    private val _state = MutableStateFlow(MaintenanceUiState())
    val state: StateFlow<MaintenanceUiState> = _state.asStateFlow()

    fun open() {
        loadLocations()
        loadMigration()
        _state.value = _state.value.copy(
            updateAvailable = dataSets.active.value?.installation?.state?.value?.updateAvailable,
        )
    }

    fun resume() {
        loadLocations()
        _state.value = _state.value.copy(
            updateAvailable = dataSets.active.value?.installation?.state?.value?.updateAvailable,
        )
    }

    private fun loadLocations() {
        val set = dataSets.active.value ?: return
        _state.value = _state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            set.configuration.loadedLocations().fold(
                onSuccess = { list ->
                    _state.value = _state.value.copy(locations = list, locationsLoaded = true, loading = false)
                },
                onFailure = { failure ->
                    _state.value = _state.value.copy(
                        locationsLoaded = true,
                        loading = false,
                        error = (failure as? ActionFailure)?.error,
                    )
                },
            )
        }
    }

    private fun loadMigration() {
        val set = dataSets.active.value ?: return
        viewModelScope.launch {
            set.configuration.migrationStatus().fold(
                onSuccess = { status ->
                    _state.value = _state.value.copy(migration = status, migrationLoaded = true)
                },
                onFailure = { _state.value = _state.value.copy(migrationLoaded = true) },
            )
        }
    }

    fun reload() {
        val set = dataSets.active.value ?: return
        _state.value = _state.value.copy(reloading = true, error = null)
        viewModelScope.launch {
            set.configuration.reloadLocations().fold(
                onSuccess = {
                    _state.value = _state.value.copy(reloading = false, outcome = RELOADED)
                    loadLocations()
                },
                onFailure = { _state.value = _state.value.copy(reloading = false, error = (it as? ActionFailure)?.error) },
            )
        }
    }

    fun requestEvict(location: LoadedLocation) {
        _state.value = _state.value.copy(evictTarget = location, error = null)
    }

    fun cancelEvict() {
        _state.value = _state.value.copy(evictTarget = null)
    }

    fun confirmEvict() {
        val set = dataSets.active.value ?: return
        val target = _state.value.evictTarget ?: return
        _state.value = _state.value.copy(evictTarget = null, loading = true, error = null)
        viewModelScope.launch {
            set.configuration.evictLocation(target.directory).fold(
                onSuccess = {
                    _state.value = _state.value.copy(loading = false, outcome = EVICTED)
                    loadLocations()
                },
                onFailure = { failure ->
                    _state.value = _state.value.copy(loading = false, error = (failure as? ActionFailure)?.error)
                },
            )
        }
    }

    fun dismissOutcome() {
        _state.value = _state.value.copy(outcome = null)
    }

    fun dismissError() {
        _state.value = _state.value.copy(error = null)
    }

    private companion object {
        /** Stable ids a screenshot and a test can address, rather than the prose a screen renders. */
        const val RELOADED = "reloaded"
        const val EVICTED = "evicted"
    }
}

// ------------------------------------------------------------------------------------- instructions

/** A session's durable instruction entries, and the one being edited. */
data class InstructionsUiState(
    val sessionID: String? = null,
    val loading: Boolean = false,
    /** Whether the switch and the route both say the entries may be read and written. */
    val usable: Boolean = false,
    val entries: List<InstructionEntry> = emptyList(),
    val loaded: Boolean = false,
    /** The key of the entry awaiting removal. */
    val removeTarget: String? = null,
    val key: String = "",
    val value: String = "",
    val busy: Boolean = false,
    val error: ActionError? = null,
) {
    /** Whether the entry form is complete enough to send. */
    val canPut: Boolean get() = key.isNotBlank() && usable && !busy
}

/**
 * The session's durable instruction entries (features doc §26).
 *
 * **A value is typed as JSON and sent as JSON, and a bare word becomes a string.** The spec's body is
 * `{"value": …}` with no type, and an entry is useful with an object or a list — "these tools are off
 * for this session" — which a phone keyboard cannot enter into a typed field. [ConfigValues] decides,
 * and a `413` for a value that is too large is reported by class.
 *
 * **Removal is confirmed by key**, because an entry is announced to the running session at the next step
 * boundary and taking one back is a change to what the agent is being told, not a tidy-up.
 */
class InstructionsViewModel(
    private val dataSets: ServerDataRegistry,
    private val experimental: ExperimentalPreferences,
) : ViewModel() {

    private val _state = MutableStateFlow(InstructionsUiState())
    val state: StateFlow<InstructionsUiState> = _state.asStateFlow()

    private var settingsJob: Job? = null

    fun open(sessionID: String) {
        val set = dataSets.active.value ?: return
        _state.value = _state.value.copy(sessionID = sessionID)
        settingsJob?.cancel()
        settingsJob = viewModelScope.launch {
            experimental.settings.collect { settings ->
                val usable = set.configuration.instructionsUsable(settings.sessionInstructions)
                _state.value = _state.value.copy(usable = usable)
                if (usable) load()
            }
        }
    }

    fun resume() {
        if (_state.value.usable) load()
    }

    private fun load() {
        val set = dataSets.active.value ?: return
        val sessionID = _state.value.sessionID ?: return
        _state.value = _state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            set.configuration.instructionEntries(sessionID).fold(
                onSuccess = { list ->
                    _state.value = _state.value.copy(entries = list, loaded = true, loading = false)
                },
                onFailure = { failure ->
                    _state.value = _state.value.copy(
                        loaded = true,
                        loading = false,
                        error = (failure as? ActionFailure)?.error,
                    )
                },
            )
        }
    }

    fun setKey(key: String) {
        _state.value = _state.value.copy(key = key)
    }

    fun setValue(value: String) {
        _state.value = _state.value.copy(value = value)
    }

    fun put() {
        val set = dataSets.active.value ?: return
        val sessionID = _state.value.sessionID ?: return
        val key = _state.value.key.trim()
        if (key.isEmpty()) return
        _state.value = _state.value.copy(busy = true, error = null)
        viewModelScope.launch {
            set.configuration.putInstructionEntry(sessionID, key, _state.value.value).fold(
                onSuccess = {
                    _state.value = _state.value.copy(busy = false, key = "", value = "")
                    load()
                },
                onFailure = { _state.value = _state.value.copy(busy = false, error = (it as? ActionFailure)?.error) },
            )
        }
    }

    fun requestRemove(key: String) {
        _state.value = _state.value.copy(removeTarget = key, error = null)
    }

    fun cancelRemove() {
        _state.value = _state.value.copy(removeTarget = null)
    }

    fun confirmRemove() {
        val set = dataSets.active.value ?: return
        val sessionID = _state.value.sessionID ?: return
        val key = _state.value.removeTarget ?: return
        _state.value = _state.value.copy(removeTarget = null, busy = true, error = null)
        viewModelScope.launch {
            set.configuration.removeInstructionEntry(sessionID, key).fold(
                onSuccess = {
                    _state.value = _state.value.copy(busy = false)
                    load()
                },
                onFailure = { _state.value = _state.value.copy(busy = false, error = (it as? ActionFailure)?.error) },
            )
        }
    }

    fun dismissError() {
        _state.value = _state.value.copy(error = null)
    }
}
