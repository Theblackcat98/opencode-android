package dev.opencode.android.feature.execution

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.opencode.android.core.data.action.toActionError
import dev.opencode.android.core.data.execution.WorktreeRemoval
import dev.opencode.android.core.data.execution.WorktreeState
import dev.opencode.android.core.data.server.ServerDataRegistry
import dev.opencode.android.core.model.Delivery
import dev.opencode.android.core.model.Project
import dev.opencode.android.core.model.WorktreeDirectory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The worktree panel's state (plan §6, "Worktrees"). */
data class WorktreesUiState(
    val projectID: String? = null,
    val projectName: String? = null,
    val directories: List<WorktreeDirectory> = emptyList(),
    /** The adoptions `worktree.resolved` reported, newest last. */
    val resolved: List<ResolvedRow> = emptyList(),
    val creating: Boolean = false,
    val draftFrom: String = "",
    val draftBranch: String = "",
    val draftName: String = "",
    /** The removal awaiting the user, and the server's reason when it refused the first attempt. */
    val removeTarget: String? = null,
    val removeReason: String? = null,
    val forceArmed: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
) {
    val canCreate: Boolean get() = !creating
}

/** One adoption, as the panel shows it. */
data class ResolvedRow(
    val directory: String,
    val previous: String,
    val adopted: List<String>,
)

/**
 * Worktrees: list, create, remove, refresh, and moving a session into one (features doc §29).
 *
 * **Removal is two round trips by design.** The first call sends `force = false`, and a worktree with
 * uncommitted work answers `400` with the server's own reason. Only then is the user asked, and the
 * second call is the one that deletes it. [forceArmed] is the state that remembers the first attempt
 * failed, and it is what makes the confirmation say *why* rather than asking about a risk the client
 * could have described anyway.
 *
 * **A session move is `session.move` and nothing else.** "Move session to a new worktree" is not a
 * special route: it is the worktree's directory handed to `session.move`, and the plan's "change
 * directory" is the same call. The delivery mode is passed through rather than chosen here, because
 * deciding how a queued prompt is delivered during a move is a change the user did not ask for.
 */
class WorktreesViewModel @Inject constructor(
    private val dataSets: ServerDataRegistry,
) : ViewModel() {

    private val _state = MutableStateFlow(WorktreesUiState())
    val state: StateFlow<WorktreesUiState> = _state.asStateFlow()

    /** Binds the panel to a project and reads its worktree inventory. */
    fun open(projectID: String) {
        val set = dataSets.active.value ?: return
        _state.value = WorktreesUiState(projectID = projectID, loading = true)
        set.execution.worktrees.start(projectID)
        viewModelScope.launch {
            set.execution.worktrees.state.collect { next -> fold(next) }
        }
        viewModelScope.launch {
            set.projects.state.collect { projects ->
                val project = projects.value?.firstOrNull { it.id == projectID }
                _state.value = _state.value.copy(
                    projectName = project?.name ?: project?.canonical?.substringAfterLast('/'),
                )
            }
        }
    }

    private fun fold(next: WorktreeState) {
        _state.value = _state.value.copy(
            projectID = next.projectID ?: _state.value.projectID,
            directories = next.directories,
            resolved = next.resolved.map { ResolvedRow(it.directory, it.previous, it.adopted) },
        )
    }

    fun setFrom(ref: String) {
        _state.value = _state.value.copy(draftFrom = ref)
    }

    fun setBranch(branch: String) {
        _state.value = _state.value.copy(draftBranch = branch)
    }

    fun setName(name: String) {
        _state.value = _state.value.copy(draftName = name)
    }

    /**
     * `worktree.create`.
     *
     * The server runs the project's setup script when it creates one, so the button is behind the
     * panel's own confirmation and the result says which directory came back — the server chooses the
     * path, and a client that invented one would send the user to a directory that does not exist.
     */
    fun create() {
        val set = dataSets.active.value ?: return
        val state = _state.value
        val projectID = state.projectID ?: return
        _state.value = state.copy(creating = true, error = null, notice = null)
        viewModelScope.launch {
            val result = set.execution.worktreeCommands.create(
                projectID = projectID,
                from = state.draftFrom.takeIf { it.isNotBlank() },
                branch = state.draftBranch.takeIf { it.isNotBlank() },
                name = state.draftName.takeIf { it.isNotBlank() },
            )
            val error = result.exceptionOrNull()?.toActionError()
            _state.value = _state.value.copy(
                creating = false,
                error = error?.message,
                notice = result.getOrNull()?.directory,
                draftFrom = "",
                draftBranch = "",
                draftName = "",
            )
            if (error == null) refresh()
        }
    }

    /** `worktree.refresh`: rediscovers and reconciles. */
    fun refresh() {
        val set = dataSets.active.value ?: return
        val projectID = _state.value.projectID ?: return
        viewModelScope.launch {
            val error = set.execution.worktreeCommands.refresh(projectID).exceptionOrNull()?.toActionError()
            if (error != null) _state.value = _state.value.copy(error = error.message)
        }
    }

    /**
     * Arms the confirmation for a removal and sends nothing.
     *
     * A removal is a dangerous action whether or not it will succeed (plan §5.2), so the panel asks
     * first; [remove] is what the answer sends.
     */
    fun requestRemove(directory: String) {
        _state.value = _state.value.copy(removeTarget = directory, removeReason = null, forceArmed = false)
    }

    /**
     * The first attempt: `force = false`.
     *
     * **A refusal is not an error, it is the second question.** The server answers `400` with its own
     * reason and `forceRequired`, and the panel shows that reason with a second button; only that
     * second button sends `force = true`.
     */
    fun remove() {
        val set = dataSets.active.value ?: return
        val projectID = _state.value.projectID ?: return
        val target = _state.value.removeTarget ?: return
        viewModelScope.launch {
            val result = set.execution.worktreeCommands.remove(projectID, target, force = false)
            when (val outcome = result.getOrNull()) {
                is WorktreeRemoval.Removed -> {
                    _state.value = _state.value.copy(removeTarget = null, notice = target)
                }

                is WorktreeRemoval.Refused -> {
                    _state.value = _state.value.copy(
                        removeReason = outcome.message,
                        forceArmed = outcome.forceRequired,
                    )
                }

                null -> _state.value = _state.value.copy(
                    error = result.exceptionOrNull()?.toActionError()?.message,
                )
            }
        }
    }

    /** The second attempt, with `force = true`, after the user has been told what is at stake. */
    fun forceRemove() {
        val set = dataSets.active.value ?: return
        val projectID = _state.value.projectID ?: return
        val target = _state.value.removeTarget ?: return
        viewModelScope.launch {
            val result = set.execution.worktreeCommands.remove(projectID, target, force = true)
            val error = result.exceptionOrNull()?.toActionError()
            _state.value = if (error != null) {
                _state.value.copy(error = error.message)
            } else {
                _state.value.copy(removeTarget = null, removeReason = null, forceArmed = false, notice = target)
            }
        }
    }

    fun cancelRemove() {
        _state.value = _state.value.copy(removeTarget = null, removeReason = null, forceArmed = false)
    }

    /**
     * `session.move`: the session into a worktree, or into any other directory.
     *
     * [delivery] is the composer's own mode, passed through unchanged. A move that silently switched a
     * queue to a steer would change what happens to a prompt the user has already sent.
     */
    fun moveSession(sessionID: String, directory: String, delivery: Delivery? = null) {
        val set = dataSets.active.value ?: return
        viewModelScope.launch {
            val error = set.execution.moveSession(sessionID, directory, delivery).exceptionOrNull()?.toActionError()
            _state.value = _state.value.copy(
                error = error?.message,
                notice = if (error == null) directory else null,
            )
        }
    }

    fun dismissNotice() {
        _state.value = _state.value.copy(notice = null, error = null)
    }
}

/** The project settings sheet's state (plan §6, "Project settings"). */
data class ProjectSettingsUiState(
    val project: Project? = null,
    val name: String = "",
    val color: String = "",
    val emoji: String = "",
    val iconUrl: String = "",
    val startCommand: String = "",
    val canonical: String = "",
    val saving: Boolean = false,
    val error: String? = null,
    val saved: Boolean = false,
) {
    /** Whether anything differs from what the server holds, which is what enables the button. */
    val dirty: Boolean
        get() = project != null && (
            name != project.name.orEmpty() ||
                color != project.icon?.color.orEmpty() ||
                emoji != project.icon?.override.orEmpty() ||
                iconUrl != project.icon?.url.orEmpty() ||
                startCommand != project.commands?.start.orEmpty() ||
                canonical != project.canonical
            )
}

/**
 * Project settings: name, icon, start command and the canonical checkout (`project.update`).
 *
 * **The icon is edited as a whole, not as three fields the server merges.** `Project.Icon` is replaced
 * rather than merged, so a colour and an emoji cannot be set independently of each other by two calls;
 * the sheet shows exactly what will be sent, and the icon it draws is the one the user last set — an
 * emoji if there is one, the URL if there is one, and the colour behind either.
 */
class ProjectSettingsViewModel @Inject constructor(
    private val dataSets: ServerDataRegistry,
) : ViewModel() {

    private val _state = MutableStateFlow(ProjectSettingsUiState())
    val state: StateFlow<ProjectSettingsUiState> = _state.asStateFlow()

    /** Binds the sheet to a project, filling the fields from what the server holds. */
    fun open(projectID: String) {
        val set = dataSets.active.value ?: return
        _state.value = ProjectSettingsUiState()
        viewModelScope.launch {
            set.projects.state.collect { projects ->
                val project = projects.value?.firstOrNull { it.id == projectID } ?: return@collect
                if (_state.value.project != null) return@collect
                _state.value = ProjectSettingsUiState(
                    project = project,
                    name = project.name.orEmpty(),
                    color = project.icon?.color.orEmpty(),
                    emoji = project.icon?.override.orEmpty(),
                    iconUrl = project.icon?.url.orEmpty(),
                    startCommand = project.commands?.start.orEmpty(),
                    canonical = project.canonical,
                )
            }
        }
    }

    fun setName(value: String) = edit { copy(name = value, saved = false) }
    fun setColor(value: String) = edit { copy(color = value, saved = false) }
    fun setEmoji(value: String) = edit { copy(emoji = value, saved = false) }
    fun setIconUrl(value: String) = edit { copy(iconUrl = value, saved = false) }
    fun setStartCommand(value: String) = edit { copy(startCommand = value, saved = false) }
    fun setCanonical(value: String) = edit { copy(canonical = value, saved = false) }

    private inline fun edit(transform: ProjectSettingsUiState.() -> ProjectSettingsUiState) {
        _state.value = _state.value.transform()
    }

    /**
     * `project.update`.
     *
     * The answer is the updated project, so the sheet keeps it and the store's `project.updated` event
     * refreshes the list behind it; nothing is re-read to find out whether the write landed, which is
     * what a write that reported success and changed nothing would otherwise hide.
     */
    fun save() {
        val set = dataSets.active.value ?: return
        val state = _state.value
        val project = state.project ?: return
        if (!state.dirty) return
        _state.value = state.copy(saving = true, error = null, saved = false)
        viewModelScope.launch {
            val icon = project.icon?.copy(
                color = state.color.takeIf { it.isNotBlank() },
                override = state.emoji.takeIf { it.isNotBlank() },
                url = state.iconUrl.takeIf { it.isNotBlank() },
            ) ?: state.color.takeIf { it.isNotBlank() }?.let { Project.Icon(color = it) }
            val result = set.execution.updateProject(
                projectID = project.id,
                name = state.name.takeIf { it.isNotBlank() },
                icon = icon,
                commands = state.startCommand.takeIf { it.isNotBlank() }?.let { Project.Commands(start = it) },
                canonical = state.canonical.takeIf { it.isNotBlank() },
            )
            val error = result.exceptionOrNull()?.toActionError()
            _state.value = _state.value.copy(
                saving = false,
                error = error?.message,
                saved = error == null,
                project = result.getOrNull() ?: _state.value.project,
            )
        }
    }

    fun dismissError() {
        _state.value = _state.value.copy(error = null)
    }
}
