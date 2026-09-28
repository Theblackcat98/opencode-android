package dev.opencode.android.feature.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.opencode.android.core.data.action.ActionError
import dev.opencode.android.core.data.capability.ExperimentalRoute
import dev.opencode.android.core.data.capability.RouteAvailability
import dev.opencode.android.core.data.review.CommentSelection
import dev.opencode.android.core.data.review.DiffHunk
import dev.opencode.android.core.data.review.FileNode
import dev.opencode.android.core.data.review.FileTree
import dev.opencode.android.core.data.review.ParsedFile
import dev.opencode.android.core.data.review.RestoredFile
import dev.opencode.android.core.data.review.ReviewComment
import dev.opencode.android.core.data.review.ReviewComments
import dev.opencode.android.core.data.review.ReviewNavigator
import dev.opencode.android.core.data.review.ReviewPosition
import dev.opencode.android.core.data.review.ReviewScope
import dev.opencode.android.core.data.review.ReviewTarget
import dev.opencode.android.core.data.review.ReviewedFiles
import dev.opencode.android.core.data.review.RevertPlan
import dev.opencode.android.core.data.server.ReviewState
import dev.opencode.android.core.data.server.ServerDataRegistry
import dev.opencode.android.core.data.server.SessionContextInspector
import dev.opencode.android.core.data.server.VcsState
import dev.opencode.android.core.model.SessionRevert
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.JsonElement
import javax.inject.Inject

/**
 * The review screen's state, as one value.
 *
 * **Everything on it is either the server's projection or the device's own memory, and the type
 * says which.** The diffs, the VCS header and the base branch are the server's; the review position,
 * the wrap and split toggles, the reviewed marks and the comments are the client's. Keeping them in
 * one value is what lets a screenshot be taken from a fixture instead of from a server, which is the
 * only way this screen's states can be reviewed at all.
 */
data class ReviewUiState(
    val sessionID: String? = null,
    val directory: String? = null,
    val scope: ReviewScope = ReviewScope.Uncommitted,
    /** The scopes this screen may offer: the turn scope only when there is a session. */
    val scopes: List<ReviewScope> = ReviewScope.REPOSITORY,
    val files: List<ParsedFile> = emptyList(),
    val tree: FileNode = FileTree.build(emptyList()),
    val position: ReviewPosition = ReviewPosition(),
    val reviewed: ReviewedFiles = ReviewedFiles(),
    val wrap: Boolean = false,
    /** Null means "this screen cannot tell", which is what a phone in portrait shows. */
    val split: Boolean? = null,
    val vcs: VcsState = VcsState(),
    val baseOverride: String? = null,
    val branches: List<String> = emptyList(),
    val basePickerOpen: Boolean = false,
    val comments: List<ReviewComment> = emptyList(),
    /** The comment being written, which is the draft of one line range. */
    val commentDraft: CommentDraft? = null,
    val stagedRevert: SessionRevert? = null,
    val restoredFiles: List<RestoredFile> = emptyList(),
    val filesOpen: Boolean = false,
    val loading: Boolean = false,
    val error: ActionError? = null,
    /** Whether the user has allowed remote file writes at all (plan §5.2, the setting). */
    val editingEnabled: Boolean = false,
    val capabilities: Map<ExperimentalRoute, RouteAvailability> = emptyMap(),
) {
    /** The file the position names. */
    val currentFile: ParsedFile? get() = files.firstOrNull { it.file == position.file }

    /** The hunk the position names. */
    val currentHunk: DiffHunk? get() = currentFile?.hunks?.getOrNull(position.hunk)

    /** Whether the review has anything to show at all. */
    val isEmpty: Boolean get() = files.isEmpty() && !loading

    /** How many files are still unreviewed, which is the progress count. */
    val remaining: Int get() = files.count { !reviewed.contains(it.key) }

    /** Whether the review moves between files, which is what enables the file arrows. */
    val hasSeveralFiles: Boolean get() = ReviewNavigator.hasSeveralFiles(files)

    /** The branch the header shows. */
    val branch: String? get() = vcs.branch

    /** The base the diff is taken against, preferring the picker over the server's answer. */
    val effectiveBase: String? get() = vcs.baseFor(baseOverride)

    /** Whether the current file has been marked reviewed. */
    val currentReviewed: Boolean get() = currentFile?.key?.let { reviewed.contains(it) } == true

    /** Whether the review is complete, which is what the progress row says. */
    val complete: Boolean get() = ReviewNavigator.isComplete(files, reviewed)

    /** Whether a write route may be offered: the setting and the probe must both say yes. */
    val editingUsable: Boolean
        get() = editingEnabled && (capabilities[ExperimentalRoute.FS_WRITE] ?: RouteAvailability.Unknown).let {
            it !is RouteAvailability.Absent
        }
}

/** The comment being written on one line range. */
data class CommentDraft(
    val path: String,
    val startLine: Int,
    val endLine: Int,
    val text: String = "",
) {
    val canSubmit: Boolean get() = text.isNotBlank()
}

/** What a prompt carrying review comments needs, built in one place so the three cannot disagree. */
data class PromptParts(
    val comments: List<ReviewComment>,
    val metadata: Map<String, JsonElement>,
    val text: String,
) {
    val isEmpty: Boolean get() = comments.isEmpty()
}

/**
 * The review: scopes, navigation, comments, the VCS header, and the file browser underneath it.
 *
 * **One view model, because the pieces are one conversation.** A user reads a diff, comments on a
 * line, attaches a file and then sends the prompt; splitting that across three view models would
 * mean the comment list and the composer disagreeing about what the next send carries. The state is
 * published as one value and the *decisions* stay in the pure types in `core:data` — this class only
 * performs operations and folds their results in.
 *
 * **The parse is off the main thread.** [ReviewStore.load] is a suspending call and the parsing
 * happens inside it, so a thousand-line patch costs one pass on a background dispatcher and the UI
 * thread only ever sees a finished value (plan §5.4).
 */
class ReviewViewModel @Inject constructor(
    private val dataSets: ServerDataRegistry,
) : ViewModel() {

    private data class LocalState(
        val sessionID: String? = null,
        val scope: ReviewScope = ReviewScope.Uncommitted,
        val baseOverride: String? = null,
        val basePickerOpen: Boolean = false,
        val commentDraft: CommentDraft? = null,
        val wrap: Boolean = false,
        val split: Boolean? = null,
        val editingEnabled: Boolean = false,
        val filesOpen: Boolean = false,
    )

    private val local = MutableStateFlow(LocalState())
    private val _state = MutableStateFlow(ReviewUiState())

    /** The published review. */
    val state: StateFlow<ReviewUiState> = _state.asStateFlow()

    private val set get() = dataSets.active.value

    /** Binds the review to a session, or to a directory when there is no session. */
    fun open(sessionID: String?) {
        local.value = local.value.copy(sessionID = sessionID)
        val directory = sessionID?.let { id -> set?.sessions?.info?.value?.get(id)?.location?.directory }
        _state.value = _state.value.copy(
            sessionID = sessionID,
            directory = directory,
            // The turn scope needs a session to diff; the other three need a checkout. A screen with
            // neither offers the scopes it can actually run.
            scopes = if (sessionID != null) ReviewScope.ALL else ReviewScope.REPOSITORY,
            scope = local.value.scope,
            wrap = local.value.wrap,
            split = local.value.split,
            baseOverride = local.value.baseOverride,
            basePickerOpen = local.value.basePickerOpen,
            commentDraft = local.value.commentDraft,
            editingEnabled = local.value.editingEnabled,
            filesOpen = local.value.filesOpen,
        )
        if (directory != null) {
            set?.vcs(directory)?.let { store -> fold(store.state.value) }
        }
        load()
    }

    /** Runs the current scope and publishes what it produced. */
    fun load() {
        val set = set ?: return
        val review = set.review
        val session = local.value.sessionID?.let { id -> set.sessions.info.value[id] }
        _state.value = _state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            val error = review.load(session = session, scope = local.value.scope, base = local.value.baseOverride)
            val directory = session?.location?.directory
            if (directory != null) {
                withContext(Dispatchers.Default) {
                    set.vcs(directory).refresh()
                    set.vcs(directory).loadBase()
                }
                fold(set.vcs(directory).state.value)
            }
            _state.value = _state.value.copy(loading = false, error = error)
            fold(review.state.value)
            foldComments()
        }
    }

    /** Switches scope, which is the TUI's four-way diff picker (features doc §38). */
    fun selectScope(scope: ReviewScope) {
        local.value = local.value.copy(scope = scope)
        _state.value = _state.value.copy(scope = scope)
        load()
    }

    fun nextFile() = move(ReviewNavigator::nextFile)

    fun previousFile() = move(ReviewNavigator::previousFile)

    fun nextHunk() = move(ReviewNavigator::nextHunk)

    fun previousHunk() = move(ReviewNavigator::previousHunk)

    private fun move(block: (List<ParsedFile>, ReviewPosition) -> ReviewPosition) {
        val store = set?.review ?: return
        store.move(block(_state.value.files, _state.value.position))
        fold(store.state.value)
    }

    /** Opens one file from the tree, at the first unreviewed hunk of it. */
    fun openFile(file: String, hunk: Int = 0) {
        val store = set?.review ?: return
        store.open(ReviewTarget(file, hunk))
        fold(store.state.value)
    }

    /** Marks the current file reviewed, or not. The mark is on the device, not the server. */
    fun toggleReviewed() {
        val store = set?.review ?: return
        val file = _state.value.currentFile ?: return
        store.markReviewed(file.file, !_state.value.currentReviewed)
        fold(store.state.value)
    }

    fun setWrap(wrap: Boolean) {
        val store = set?.review
        store?.setWrap(wrap)
        local.value = local.value.copy(wrap = wrap)
        _state.value = _state.value.copy(wrap = wrap)
    }

    /**
     * The unified/split choice.
     *
     * `null` is the answer for a layout that cannot split — a phone in portrait, or any width the
     * caller has not measured — and it is kept as a value rather than a default so the caller can
     * decide from the real window size instead of a guess.
     */
    fun setSplit(split: Boolean?) {
        val store = set?.review
        if (split != null) store?.setSplit(split)
        local.value = local.value.copy(split = split)
        _state.value = _state.value.copy(split = split)
    }

    /** The base-branch picker, which is `vcs.base` and `vcs.branch.list` (features doc §28). */
    fun openBasePicker() {
        val directory = _state.value.directory
        local.value = local.value.copy(basePickerOpen = true)
        _state.value = _state.value.copy(basePickerOpen = true)
        val store = directory?.let { set?.vcs(it) } ?: return
        viewModelScope.launch {
            withContext(Dispatchers.Default) { store.loadBranches() }
            fold(store.state.value)
        }
    }

    fun closeBasePicker() {
        local.value = local.value.copy(basePickerOpen = false)
        _state.value = _state.value.copy(basePickerOpen = false)
    }

    /** The `base` override for `vcs.diff`, which is what the picker sets (features doc §28). */
    fun selectBase(branch: String?) {
        local.value = local.value.copy(baseOverride = branch, basePickerOpen = false)
        _state.value = _state.value.copy(baseOverride = branch, basePickerOpen = false)
        load()
    }

    /** Starts a comment on a range of a diff, which is where a line selection ends up. */
    fun beginComment(path: String, startLine: Int, endLine: Int) {
        val draft = CommentDraft(path, startLine, endLine)
        local.value = local.value.copy(commentDraft = draft)
        _state.value = _state.value.copy(commentDraft = draft)
    }

    fun editComment(text: String) {
        val draft = _state.value.commentDraft ?: return
        val next = draft.copy(text = text)
        local.value = local.value.copy(commentDraft = next)
        _state.value = _state.value.copy(commentDraft = next)
    }

    fun cancelComment() {
        local.value = local.value.copy(commentDraft = null)
        _state.value = _state.value.copy(commentDraft = null)
    }

    /**
     * Files the comment.
     *
     * The preview is the selected lines of the diff, taken here rather than in the composable, so the
     * comment the agent receives and the comment the user read are built from the same list.
     */
    fun submitComment() {
        val store = set?.review ?: return
        val draft = _state.value.commentDraft ?: return
        if (!draft.canSubmit) return
        val file = _state.value.files.firstOrNull { it.file == draft.path }
        store.addComment(
            CommentSelection.onDiff(
                path = draft.path,
                startLine = draft.startLine,
                endLine = draft.endLine,
                text = draft.text,
                lines = file?.lines.orEmpty(),
            ),
        )
        cancelComment()
        foldComments()
    }

    fun removeComment(index: Int) {
        val store = set?.review ?: return
        store.removeComment(index)
        foldComments()
    }

    /**
     * The comments, the metadata and the readable text a prompt carrying them needs.
     *
     * One function so the three can never disagree: the composer's chips show the readable text, the
     * request carries the metadata, and both come from the same list.
     */
    fun promptParts(): PromptParts {
        val comments = _state.value.comments
        return PromptParts(
            comments = comments,
            metadata = set?.review?.commentMetadata(comments).orEmpty(),
            text = ReviewComments.readableText(comments),
        )
    }

    /** Clears the comments after a send has taken them, so the next send does not repeat them. */
    fun commentsTaken() {
        val store = set?.review ?: return
        store.takeComments()
        foldComments()
    }

    /** `session.context`: the messages after the last compaction, for the context inspector. */
    suspend fun loadContext(): List<SessionContextInspector.Entry> {
        val id = local.value.sessionID ?: return emptyList()
        val messages = set?.review?.context(id)?.getOrNull().orEmpty()
        return withContext(Dispatchers.Default) { SessionContextInspector.inspect(messages) }
    }

    /** Records what a call proved about an experimental route, so the feature can be hidden. */
    fun recordCapability(route: ExperimentalRoute, error: ActionError?) {
        val store = set?.review ?: return
        store.recordCapability(route, error)
        _state.value = _state.value.copy(capabilities = store.capabilities.value)
    }

    fun setEditingEnabled(enabled: Boolean) {
        local.value = local.value.copy(editingEnabled = enabled)
        _state.value = _state.value.copy(editingEnabled = enabled)
    }

    fun toggleFiles() {
        val open = !local.value.filesOpen
        local.value = local.value.copy(filesOpen = open)
        _state.value = _state.value.copy(filesOpen = open)
    }

    fun dismissError() {
        _state.value = _state.value.copy(error = null)
    }

    private fun fold(review: ReviewState) {
        _state.value = _state.value.copy(
            files = review.files,
            tree = FileTree.build(review.paths),
            position = review.position,
            reviewed = review.reviewed,
            wrap = review.wrap,
            split = review.split,
        )
    }

    private fun foldVcs(vcs: VcsState) {
        _state.value = _state.value.copy(vcs = vcs, branches = vcs.branches)
    }

    private fun foldComments() {
        val comments = set?.review?.comments?.value.orEmpty()
        val staged = set?.revertCommands?.state?.value?.staged
        _state.value = _state.value.copy(
            comments = comments,
            stagedRevert = staged,
            restoredFiles = staged?.let(RevertPlan::restoredFiles).orEmpty(),
            capabilities = set?.review?.capabilities?.value.orEmpty(),
        )
    }

    private fun fold(vcs: VcsState) = foldVcs(vcs)
}
