package dev.opencode.android.core.data.server

import dev.opencode.android.core.data.action.ActionError
import dev.opencode.android.core.data.action.toActionError
import dev.opencode.android.core.data.capability.CapabilityPolicy
import dev.opencode.android.core.data.capability.ExperimentalRoute
import dev.opencode.android.core.data.capability.RouteAvailability
import dev.opencode.android.core.data.review.ParsedFile
import dev.opencode.android.core.data.review.ReviewComment
import dev.opencode.android.core.data.review.ReviewComments
import dev.opencode.android.core.data.review.ReviewNavigator
import dev.opencode.android.core.data.review.ReviewPosition
import dev.opencode.android.core.data.review.ReviewScope
import dev.opencode.android.core.data.review.ReviewTarget
import dev.opencode.android.core.data.review.ReviewedFiles
import dev.opencode.android.core.data.review.UnifiedDiff
import dev.opencode.android.core.model.FileDiff
import dev.opencode.android.core.model.LocationPublicRef
import dev.opencode.android.core.model.SessionImportRequest
import dev.opencode.android.core.model.SessionInfo
import dev.opencode.android.core.model.SessionMessage
import dev.opencode.android.core.model.SessionTransfer
import dev.opencode.android.core.network.ServerApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonElement

/**
 * The review itself: which scope, the diffs it produced, where the user is in it, and the comments
 * waiting to go on the next prompt.
 *
 * **The diffs are parsed once, off the main thread, and kept as structure.** A `session.diff` or a
 * `vcs.diff` answers with a list of patches as text; the file tree, the hunk navigation, the line
 * numbers a comment is anchored to and the highlighting all need them parsed. Parsing is a pure
 * function ([UnifiedDiff]), so the store parses into a value and the UI never re-parses per frame —
 * which is the "diff tokenization off the main thread" of plan §5.4, and a thousand-line patch costs
 * one parse rather than one per row.
 *
 * **The comments are the client's, and they are not the server's.** A review comment is not a
 * message; it becomes one, on the next prompt, in the web app's `metadata.opencodeComment` format
 * (features doc §6). So they are held here, keyed by nothing in particular, and [takeComments] hands
 * them to the composer and clears them — the composer is the only thing that can turn them into a
 * prompt, and a comment left behind would be re-sent forever.
 */
class ReviewStore(
    private val serverId: String,
    private val api: ServerApi,
) {
    private val _state = MutableStateFlow(ReviewState())
    val state: StateFlow<ReviewState> = _state.asStateFlow()

    private val _comments = MutableStateFlow<List<ReviewComment>>(emptyList())

    /** The review's comments, in the order they were written. */
    val comments: StateFlow<List<ReviewComment>> = _comments.asStateFlow()

    private val _capabilities = MutableStateFlow<Map<ExperimentalRoute, RouteAvailability>>(emptyMap())
    val capabilities: StateFlow<Map<ExperimentalRoute, RouteAvailability>> = _capabilities.asStateFlow()

    /** The undo/redo operations. One per session, held by the set; this is a view onto it. */
    lateinit var reverts: RevertCommands

    /**
     * Runs one scope and publishes its files, parsed.
     *
     * A scope that needs a session and has none, or a scope that needs a base the server has not
     * answered for, is refused *before* a call, and the refusal is a message rather than an
     * exception: a review scope that cannot run is a normal state (a session with no turns yet, a
     * directory that is not a repository) and the screen has something to say about it.
     */
    suspend fun load(
        session: SessionInfo?,
        scope: ReviewScope,
        base: String? = null,
        context: String? = null,
    ): ActionError? {
        val directory = session?.location?.directory
        val mode = scope.mode
        _state.value = _state.value.copy(scope = scope, base = base, loading = true, error = null)
        val result: Result<List<FileDiff>> = when {
            mode == null && session != null -> call {
                api.sessionDiff(
                    session.id,
                    (scope as? ReviewScope.LastTurn)?.from,
                    (scope as? ReviewScope.LastTurn)?.to,
                    context,
                ).data
            }

            mode == null -> {
                _state.value = _state.value.copy(loading = false, error = NULL_SESSION)
                return null
            }

            directory == null -> {
                _state.value = _state.value.copy(loading = false, error = NULL_LOCATION)
                return null
            }

            else -> call { api.vcsDiff(directory = directory, mode = mode, base = base, context = context).data }
        }
        val failure = result.actionErrorOrNull
        val files = result.getOrNull().orEmpty().map(UnifiedDiff::parse)
        _state.value = _state.value.copy(
            files = files,
            raw = result.getOrNull().orEmpty(),
            position = _state.value.position.clampedTo(files).takeIf { it.hasFile } ?: ReviewNavigator.start(files),
            loading = false,
            error = failure?.message,
        )
        return failure
    }

    /** Moves the position, clamping it first so a re-fetch cannot leave it out of range. */
    fun move(position: ReviewPosition) {
        _state.value = _state.value.copy(position = position.clampedTo(_state.value.files))
    }

    /** Opens one file, at its first hunk, which is what a file tree row does. */
    fun open(target: ReviewTarget) {
        _state.value = _state.value.copy(
            position = ReviewPosition(file = target.file, hunk = target.hunk)
                .clampedTo(_state.value.files),
        )
    }

    /** Marks a file reviewed or not, and keeps the mark on the device across scopes. */
    fun markReviewed(file: String, reviewed: Boolean) {
        val key = _state.value.files.firstOrNull { it.file == file }?.key ?: file
        _state.value = _state.value.copy(reviewed = _state.value.reviewed.with(key, reviewed))
    }

    /** The wrap toggle, which is a device preference rather than a server one. */
    fun setWrap(wrap: Boolean) {
        _state.value = _state.value.copy(wrap = wrap)
    }

    /** The unified/split choice; a phone offers unified only, and says so by not offering split. */
    fun setSplit(split: Boolean) {
        _state.value = _state.value.copy(split = split)
    }

    /**
     * Adds a comment, replacing any comment that covers the same lines of the same file.
     *
     * Two comments on overlapping lines of one file are two things the model has to reconcile with
     * no way to tell them apart, so the newer one wins and the older is dropped.
     */
    fun addComment(comment: ReviewComment) {
        val kept = _comments.value.filterNot { existing ->
            existing.path == comment.path &&
                existing.range.start <= (comment.range.end ?: comment.range.start) &&
                (comment.range.end ?: comment.range.start) <= (existing.range.end ?: existing.range.start)
        }
        _comments.value = kept + comment
    }

    fun removeComment(index: Int) {
        _comments.value = _comments.value.filterIndexed { position, _ -> position != index }
    }

    /** The comments, and then none: the composer turns them into the next prompt. */
    fun takeComments(): List<ReviewComment> {
        val taken = _comments.value
        if (taken.isNotEmpty()) _comments.value = emptyList()
        return taken
    }

    /** The prompt metadata a list of comments becomes, in the web app's format. */
    fun commentMetadata(comments: List<ReviewComment>): Map<String, JsonElement> =
        ReviewComments.metadataOf(comments)

    /** Records what a call to an experimental route proved about it. */
    fun recordCapability(route: ExperimentalRoute, availability: RouteAvailability) {
        _capabilities.value = _capabilities.value + (route to availability)
    }

    /** Records what a failed call to an experimental route proved, or proved nothing. */
    fun recordCapability(route: ExperimentalRoute, error: ActionError?) {
        val availability = CapabilityPolicy.from(error?.kind) ?: return
        _capabilities.value = _capabilities.value + (route to availability)
    }

    /** Whether [route] may be used: not known absent, and the switch is on. */
    fun isUsable(route: ExperimentalRoute, allowedBySetting: Boolean): Boolean {
        if (!allowedBySetting) return false
        return CapabilityPolicy.isUsable(_capabilities.value[route] ?: RouteAvailability.Unknown)
    }

    /** `experimental.session.export`. The `sanitize` flag is a string on the wire. */
    suspend fun export(sessionID: String, sanitize: Boolean): Result<SessionTransfer> = call {
        api.exportSession(sessionID, sanitize.toString()).data
    }

    /**
     * `experimental.session.import`.
     *
     * Parents before children, and a session the server already knows is a conflict the caller
     * reports rather than retries: re-importing the same transcript is the user's second thought,
     * not a flaky network.
     */
    suspend fun import(transfer: SessionTransfer, location: String? = null): Result<SessionInfo> = call {
        api.importSession(
            SessionImportRequest(
                info = transfer.info,
                messages = transfer.messages,
                location = location?.let { LocationPublicRef(directory = it) },
            ),
        ).data
    }

    /** `session.context`: the messages after the last compaction. */
    suspend fun context(sessionID: String): Result<List<SessionMessage>> = call {
        api.getSessionContext(sessionID).data
    }

    private suspend inline fun <T> call(crossinline block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        Result.failure(ActionFailure(error.toActionError()))
    }

    companion object {
        /** Why a review scope could not run without a session. */
        const val NULL_SESSION: String = "no-session"

        /** Why a repository scope could not run without a location. */
        const val NULL_LOCATION: String = "no-location"
    }
}

/**
 * What a review shows right now.
 *
 * [reviewed] is on the device and keyed by file and change kind, so it survives a scope switch and
 * a refetch: the point of marking a file reviewed is that the next fetch of the same review does not
 * make the user find it again. [wrap] and [split] are the two view toggles, and they are state here
 * rather than composable-local so a rotation does not lose them.
 */
data class ReviewState(
    val scope: ReviewScope = ReviewScope.Uncommitted,
    /** The base branch override the picker set, or `null` to use the server's own base. */
    val base: String? = null,
    val files: List<ParsedFile> = emptyList(),
    /** The server's unparsed answer, kept for a "show the raw patch" affordance. */
    val raw: List<FileDiff> = emptyList(),
    val position: ReviewPosition = ReviewPosition(),
    val reviewed: ReviewedFiles = ReviewedFiles(),
    val wrap: Boolean = false,
    val split: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
) {
    /** The file the position names, or `null` when the review is empty. */
    val current: ParsedFile? get() = files.firstOrNull { it.file == position.file }

    /** The hunk the position names, or `null` when the file has none. */
    val currentHunk get() = current?.hunks?.getOrNull(position.hunk)

    /** The files' paths, which is what the file tree is built from. */
    val paths: List<String> get() = files.map { it.file }

    /** Whether the scope is one that a session answers, which is what the "Last turn" row needs. */
    val isTurnScope: Boolean get() = scope is ReviewScope.LastTurn
}
