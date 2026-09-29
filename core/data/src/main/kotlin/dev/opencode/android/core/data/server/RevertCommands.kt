package dev.opencode.android.core.data.server

import dev.opencode.android.core.data.action.ActionError
import dev.opencode.android.core.data.action.toActionError
import dev.opencode.android.core.data.review.PreStageStep
import dev.opencode.android.core.data.review.RestoredPrompt
import dev.opencode.android.core.data.review.RevertPlan
import dev.opencode.android.core.model.FileDiff
import dev.opencode.android.core.model.SessionForkRequest
import dev.opencode.android.core.model.SessionInfo
import dev.opencode.android.core.model.SessionRevert
import dev.opencode.android.core.model.SessionRevertStageRequest
import dev.opencode.android.core.network.ServerApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The Phase 6 session operations: undo, redo, fork and the last turn's diff.
 *
 * **Every write answers with the server's own event, not with a local edit.** A staged revert
 * arrives as `session.revert.staged {revert}`, a commit as `session.revert.committed {to}`, a fork
 * as `session.forked`, and the P2 stores already apply them. What this class adds is the *order* the
 * calls have to happen in, which is the part a user can be harmed by getting wrong:
 *
 * - **`stage` interrupts first and cancels pending user input first.** The server answers `409` to a
 *   stage while the session is busy, and a queued prompt would be delivered against a working copy
 *   that has just been rolled back. [stage] therefore walks [RevertPlan.stepsBeforeStage] in order
 *   and says which step failed if one did.
 * - **`commit` then send**, and the send is the caller's: a commit that failed must not be followed
 *   by a prompt, because the server judges the prompt against the tree the commit is about to
 *   change. [RevertPlan.beforeSend] says which of the two the composer is in.
 * - **A stage that fails changes nothing.** Reverting is a dangerous action (plan §5.2), so a
 *   failure leaves the session as it was.
 */
class RevertCommands(
    private val api: ServerApi,
    private val timeline: (String) -> TimelineStore? = { null },
) {
    private val _state = MutableStateFlow(RevertFlowState())
    val state: StateFlow<RevertFlowState> = _state.asStateFlow()

    /**
     * `session.revert.stage` with the whole sequence around it.
     *
     * [busy] is the session's own report of a live execution. The client cannot ask the server
     * whether a session is busy and get an answer it can act on — it can only see the projection —
     * so this is the one input the caller must supply. [pendingUserInboxIDs] are the pending *user*
     * items only, because a compaction or a move in the inbox is not something an undo should
     * cancel.
     *
     * [prompt] is the prompt the caller wants back in the composer. It is recorded only on success,
     * so a refused revert cannot leave a composer holding a prompt that was not rolled back.
     */
    suspend fun stage(
        sessionID: String,
        messageID: String,
        busy: Boolean,
        pendingUserInboxIDs: List<String> = emptyList(),
        restoreFiles: Boolean = true,
        prompt: RestoredPrompt? = null,
    ): StageOutcome {
        _state.value = _state.value.copy(busy = true, error = null, step = StageStep.INTERRUPT)
        for (step in RevertPlan.stepsBeforeStage(busy, pendingUserInboxIDs)) {
            val failure: ActionError? = when (step) {
                PreStageStep.Interrupt -> guardedCall { api.interrupt(sessionID, resume = false) }
                    .exceptionOrNull()?.toActionError()

                is PreStageStep.CancelInbox -> guardedCall { api.cancelInboxItem(sessionID, step.inboxID) }
                    .exceptionOrNull()?.toActionError()

                PreStageStep.Stage -> {
                    _state.value = _state.value.copy(step = StageStep.STAGE)
                    val result =
                        guardedCall {
                            api.stageRevert(sessionID, SessionRevertStageRequest(messageID, restoreFiles)).data
                        }
                    if (result.isSuccess) {
                        _state.value = _state.value.copy(staged = result.getOrNull(), restored = prompt)
                    }
                    result.exceptionOrNull()?.toActionError()
                }
            }
            if (failure != null) {
                _state.value = _state.value.copy(busy = false, error = failure, step = null)
                return StageOutcome.Failed(nameOf(step), failure)
            }
        }
        _state.value = _state.value.copy(busy = false, step = null)
        val staged = _state.value.staged
        return StageOutcome.Done(staged ?: SessionRevert(messageID))
    }

    /**
     * `session.revert.clear`: `/redo`.
     *
     * The restored prompt is *dropped*, not re-applied: redo means "take the rollback back", so the
     * composer keeps whatever the user has since typed rather than being handed a prompt again. The
     * UI asks for confirmation first, because the working copy is about to change (plan §5.2).
     */
    suspend fun clear(sessionID: String): Result<Unit> {
        _state.value = _state.value.copy(busy = true, error = null)
        val result = guardedCall { api.clearRevert(sessionID) }
        if (result.isSuccess) _state.value = _state.value.copy(staged = null, restored = null)
        _state.value = _state.value.copy(busy = false, error = result.exceptionOrNull()?.toActionError())
        return result
    }

    /**
     * `session.revert.commit`: accept the rollback.
     *
     * The prompt goes out *after* this succeeds and not from here, which is what
     * [RevertPlan.SendPreparation.CommitThenSend] is for.
     */
    suspend fun commit(sessionID: String): Result<Unit> {
        _state.value = _state.value.copy(busy = true, error = null, step = StageStep.COMMIT)
        val result = guardedCall { api.commitRevert(sessionID) }
        if (result.isSuccess) _state.value = _state.value.copy(staged = null, restored = null)
        _state.value = _state.value.copy(busy = false, error = result.exceptionOrNull()?.toActionError(), step = null)
        return result
    }

    /** `session.fork`: a copy of the session, cut *before* [before] when one is given. */
    suspend fun fork(sessionID: String, before: String? = null): Result<SessionInfo> =
        guardedCall { api.forkSession(sessionID, SessionForkRequest(before)).data }

    /**
     * `session.diff`: what one turn changed.
     *
     * [from] and [to] are the `from`/`to` of the TUI's "Last turn" scope; both are left out to ask
     * the server for the newest user message's turn, which is what `/diff` with no arguments shows.
     * [context] is the number of unchanged lines around a hunk and `null` asks for full-file patches.
     */
    suspend fun lastTurnDiff(
        sessionID: String,
        from: String? = null,
        to: String? = null,
        context: String? = null,
    ): Result<List<FileDiff>> = guardedCall { api.sessionDiff(sessionID, from, to, context).data }

    /**
     * The mirror, fed by the store from `session.revert.staged` / `session.revert.cleared` and by
     * [stage].
     *
     * A [revert] of `null` clears it, which is what the cleared and committed events mean. A
     * non-null one keeps the already-recorded prompt, because a revert staged on the desktop is
     * still a revert the composer has to offer a prompt for.
     */
    fun applyStaged(revert: SessionRevert?, prompt: RestoredPrompt? = null) {
        _state.value = _state.value.copy(
            staged = revert,
            restored = if (revert == null) null else (prompt ?: _state.value.restored),
        )
    }

    /** The name a step reports in an error, which `strings.xml` maps to a sentence. */
    private fun nameOf(step: PreStageStep): String = when (step) {
        PreStageStep.Interrupt -> StageStep.INTERRUPT
        is PreStageStep.CancelInbox -> StageStep.CANCEL
        PreStageStep.Stage -> StageStep.STAGE
    }

    private suspend inline fun <T> guardedCall(crossinline block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        Result.failure(ActionFailure(error.toActionError()))
    }

    /** The outcome of one undo, so the UI can say which step went wrong rather than "it failed". */
    sealed interface StageOutcome {
        data class Done(val revert: SessionRevert) : StageOutcome
        data class Failed(val step: String, val error: ActionError) : StageOutcome
    }
}

/** The names of the steps of a revert, for a message that says which part failed. */
object StageStep {
    const val INTERRUPT: String = "interrupt"
    const val CANCEL: String = "cancel"
    const val STAGE: String = "stage"
    const val COMMIT: String = "commit"
}

/**
 * The client's mirror of one session's staged revert.
 *
 * **Only the staged revert and this class's own progress live here.** Everything else about a
 * session is the server's projection (P2), and a second copy of it would be a second thing to keep
 * in step. [restored] is the exception and it is the client's: the prompt an undo put back into the
 * composer is the client's own memory of an edit, which the server has no record of.
 */
data class RevertFlowState(
    /** A revert operation is in flight; the composer's send is disabled while it is. */
    val busy: Boolean = false,
    /** The staged revert, or `null` when there is none. */
    val staged: SessionRevert? = null,
    /** The prompt the undo put back into the composer. */
    val restored: RestoredPrompt? = null,
    val error: ActionError? = null,
    /** The step in flight, for a message that says *which* part failed. */
    val step: String? = null,
) {
    val isStaged: Boolean get() = staged != null
}
