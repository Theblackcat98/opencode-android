package dev.opencode.android.core.data.server

import dev.opencode.android.core.data.action.ActionError
import dev.opencode.android.core.data.action.toActionError
import dev.opencode.android.core.model.SessionMessage
import dev.opencode.android.core.model.VcsBase
import dev.opencode.android.core.model.VcsFileStatus
import dev.opencode.android.core.model.VcsInfo
import dev.opencode.android.core.network.ServerApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The repository side of a review: the VCS header, the base branch and the branch list.
 *
 * **One store per `(server, directory)`, because VCS is a property of a checkout.** A session in
 * one project and a session in another are on different branches, and a header that showed the
 * first one's branch above the second one's diff would be worse than no header. So the store is
 * created per directory by [ServerDataSet], exactly like the catalogs.
 *
 * **The header is a projection, not a cache.** `vcs.get` and `vcs.status` are re-read together,
 * because they are the two halves of one question — "where am I and what is changed here" — and
 * `vcs.branch.updated` re-reads them again. A branch that changes on the desktop moves the header
 * without the user doing anything.
 */
class VcsStore(
    private val serverId: String,
    val directory: String,
    private val api: ServerApi,
) {
    private val _state = MutableStateFlow(VcsState(directory = directory))
    val state: StateFlow<VcsState> = _state.asStateFlow()

    /** `vcs.get` and `vcs.status`, together. */
    suspend fun refresh(): ActionError? {
        _state.value = _state.value.copy(loading = true, error = null)
        val info = call { api.getVcs(directory) }
        if (info.isFailure) {
            val failure = info.exceptionOrNull()?.toActionError()
            _state.value = _state.value.copy(loading = false, error = failure?.message)
            return failure
        }
        val status = call { api.getVcsStatus(directory) }
        val failure = (info.exceptionOrNull() ?: status.exceptionOrNull())?.toActionError()
        _state.value = _state.value.copy(
            info = info.getOrNull()?.data,
            files = status.getOrNull()?.data.orEmpty(),
            loading = false,
            error = failure?.message,
        )
        return failure
    }

    /** `vcs.base`: the ref the review is taken against. A `null` answer is a fact, not a failure. */
    suspend fun loadBase(): ActionError? {
        val result = call { api.getVcsBase(directory) }
        val failure = result.exceptionOrNull()?.toActionError()
        _state.value = _state.value.copy(base = result.getOrNull()?.data, error = failure?.message)
        return failure
    }

    /** `vcs.branch.list`, with the server's own [search] and [limit]. */
    suspend fun loadBranches(search: String? = null, limit: String? = null): Result<List<String>> {
        val result = call { api.listVcsBranches(directory, search, limit).data }
        if (result.isSuccess) {
            _state.value = _state.value.copy(branches = result.getOrThrow(), branchError = null)
        } else {
            _state.value = _state.value.copy(branchError = result.exceptionOrNull()?.toActionError()?.message)
        }
        return result
    }

    /**
     * Applies `vcs.branch.updated {branch}`.
     *
     * The event names the branch, and the header is the branch plus the file list, so the whole
     * header is re-read rather than the name being patched in. A checkout that switched branches
     * has a different working copy, and a header that kept the old file list would be describing a
     * tree that no longer exists.
     */
    fun applyBranchUpdated(branch: String?) {
        if (branch == null) return
        val current = _state.value.info ?: return
        _state.value = _state.value.copy(
            info = current.copy(branch = current.branch.copy(current = branch)),
        )
    }

    /**
     * Whether a `filesystem.changed` event concerns this directory.
     *
     * The event carries one file and no location, so the file's own path is the only thing to match
     * on, and it is matched against the directory rather than normalized: the server's spelling is
     * the spelling this store was created with.
     */
    fun covers(file: String): Boolean = file == directory || file.startsWith("${directory.trimEnd('/')}/")

    private suspend inline fun <T> call(crossinline block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        Result.failure(ActionFailure(error.toActionError()))
    }

    companion object {
        /** Builds the `filesystem.changed` filter for one directory, for the event dispatcher. */
        fun directoryFilter(directory: String): (String) -> Boolean {
            val prefix = directory.trimEnd('/')
            return { file -> file == directory || file.startsWith("$prefix/") }
        }
    }
}

/**
 * What the VCS header shows.
 *
 * [isRepository] is a real answer rather than an absence: a directory that is not a checkout answers
 * `vcs.get` with no provider and no branch, and "this project is not under version control" is a
 * sentence the header can say, where a blank branch would be a bug.
 */
data class VcsState(
    val directory: String? = null,
    val info: VcsInfo? = null,
    val files: List<VcsFileStatus> = emptyList(),
    val base: VcsBase? = null,
    val branches: List<String> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val branchError: String? = null,
) {
    /** The branch the working copy is on, or `null` for a detached HEAD. */
    val branch: String? get() = info?.branch?.current

    /** The repository's default branch, which is what the base picker offers first. */
    val defaultBranch: String? get() = info?.branch?.default

    /** The provider's name, or `null` when the directory is not a repository. */
    val provider: String? get() = info?.provider

    /**
     * Whether this directory is a checkout at all.
     *
     * `vcs.get` answers `{"branch":{}}` for a directory that is not a repository — a bare `data`
     * object rather than a `404`, because "this is not a repository" is an answer and not an error.
     * A provider, a current branch or a default branch are the three things that make it one.
     */
    val isRepository: Boolean
        get() = info != null && (info.provider != null || info.branch.current != null || info.branch.default != null)

    /** The base the review is taken against, preferring an explicit override over the server's. */
    fun baseFor(override: String?): String? = override?.takeIf { it.isNotBlank() } ?: base?.name
}

/**
 * `session.context`: the messages after the last compaction, which is what the model can actually
 * see (features doc §4.2, "Active model context").
 *
 * **It is a context inspector and not a second timeline.** The value is a small summary per message
 * — who spoke, how big it is, and what it changed — because the question a user asks of it is "is
 * the agent still seeing the beginning of this conversation", and the answer is a list with sizes
 * in it, not a transcript they have already read.
 */
object SessionContextInspector {

    /** One line of the inspector. */
    data class Entry(
        val id: String,
        /** `user`, `assistant`, `compaction`, and so on: the message's own type. */
        val type: String,
        /** A first line of what the message says, which is what a row shows. */
        val preview: String,
        /** Rough size in characters, which is what makes the size of the context visible. */
        val characters: Int,
        /** The files this message changed, for an assistant step's snapshot. */
        val files: List<String> = emptyList(),
    )

    /** The whole context, in order, oldest first. */
    fun inspect(messages: List<SessionMessage>): List<Entry> = messages.map { message ->
        Entry(
            id = message.id,
            type = typeOf(message),
            preview = previewOf(message),
            characters = sizeOf(message),
            files = filesOf(message),
        )
    }

    /** The characters the context occupies, which is the number the header's gauge wants. */
    fun totalCharacters(entries: List<Entry>): Int = entries.sumOf { it.characters }

    private fun typeOf(message: SessionMessage): String = when (message) {
        is SessionMessage.User -> "user"
        is SessionMessage.Assistant -> "assistant"
        is SessionMessage.Compaction -> "compaction"
        is SessionMessage.Synthetic -> "synthetic"
        is SessionMessage.System -> "system"
        is SessionMessage.Skill -> "skill"
        is SessionMessage.Shell -> "shell"
        is SessionMessage.Idle -> "idle"
        is SessionMessage.AgentSwitched -> "agent-switched"
        is SessionMessage.ModelSwitched -> "model-switched"
        is SessionMessage.LocationSwitched -> "location-switched"
        is SessionMessage.Unknown -> message.discriminator ?: "unknown"
    }

    private fun previewOf(message: SessionMessage): String = when (message) {
        is SessionMessage.User -> message.text.lineSequence().firstOrNull { it.isNotBlank() }?.take(160).orEmpty()
        is SessionMessage.Assistant -> message.content
            .filterIsInstance<dev.opencode.android.core.model.AssistantContent.Text>()
            .firstOrNull()?.text?.lineSequence()?.firstOrNull { it.isNotBlank() }?.take(160).orEmpty()

        is SessionMessage.Compaction -> message.summary?.lineSequence()?.firstOrNull { it.isNotBlank() }?.take(160).orEmpty()
        is SessionMessage.Synthetic -> message.text.lineSequence().firstOrNull { it.isNotBlank() }?.take(160).orEmpty()
        is SessionMessage.System -> message.text.lineSequence().firstOrNull { it.isNotBlank() }?.take(160).orEmpty()
        is SessionMessage.Skill -> message.name
        is SessionMessage.Shell -> message.command.take(160)
        else -> ""
    }

    private fun sizeOf(message: SessionMessage): Int = when (message) {
        is SessionMessage.User -> message.text.length
        is SessionMessage.Assistant -> message.content.sumOf { part ->
            when (part) {
                is dev.opencode.android.core.model.AssistantContent.Text -> part.text.length
                is dev.opencode.android.core.model.AssistantContent.Reasoning -> part.text.length
                is dev.opencode.android.core.model.AssistantContent.Tool -> 0
                is dev.opencode.android.core.model.AssistantContent.Unknown -> 0
            }
        }

        is SessionMessage.Compaction -> (message.summary?.length ?: 0)
        is SessionMessage.Synthetic -> message.text.length
        is SessionMessage.System -> message.text.length
        else -> 0
    }

    private fun filesOf(message: SessionMessage): List<String> =
        (message as? SessionMessage.Assistant)?.snapshot?.files.orEmpty().filter { it.isNotBlank() }.distinct()
}
