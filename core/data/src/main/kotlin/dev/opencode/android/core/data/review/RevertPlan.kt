package dev.opencode.android.core.data.review

import dev.opencode.android.core.data.composer.AttachmentDraft
import dev.opencode.android.core.data.composer.LineRange
import dev.opencode.android.core.model.Delivery
import dev.opencode.android.core.model.FileDiff
import dev.opencode.android.core.model.SessionRevert

/**
 * What the client knows about a staged revert, and what undo is allowed to do next.
 *
 * **The server owns the revert; this is the plan for talking to it.** `revert.stage` is a request
 * that answers with a [SessionRevert], and the server then refuses to accept anything else while
 * the revert is staged — the next prompt is compared against the reverted state. So the state
 * machine is not "what did the user click" but "what must the client do, in what order, so that the
 * server's own sequence is not broken":
 *
 * ```
 *  Idle ──stage(message)──▶ Staging ──ok──▶ Staged ──clear──▶ Idle
 *                              │                │
 *                              │                ├──commit──▶ Committing ──ok──▶ Idle
 *                              │                │                  (then the prompt is sent)
 *                              └──fail──▶ Idle  └──fail──▶ Staged
 * ```
 *
 * **Interrupt-then-cancel-then-stage is a rule, not a suggestion.** The server answers `409` to a
 * stage while the session is busy (features doc §21), and the reference clients interrupt first and
 * cancel the pending user inbox items, because a queued prompt would otherwise be delivered
 * against a working copy that has just been rolled back. The plan spells that order out and this
 * type is where it is encoded, so no caller can invent a different one.
 */
sealed interface RevertState {

    /** Nothing staged. The only move is to stage a message. */
    data object Idle : RevertState

    /** A `revert.stage` is in flight. Nothing else may be sent while it is. */
    data class Staging(val messageID: String) : RevertState

    /**
     * The server has a revert staged.
     *
     * [revert] is the server's own answer, whose [SessionRevert.files] are the files it will
     * restore — the staged banner lists them, so they have to be the server's and not a local
     * guess about which files a turn touched.
     */
    data class Staged(val revert: SessionRevert, val restoredPrompt: RestoredPrompt?) : RevertState

    /** A `revert.commit` is in flight; the prompt goes out immediately after it succeeds. */
    data class Committing(val revert: SessionRevert) : RevertState

    /** Whether a send may go out right now. */
    val blocksSend: Boolean get() = this is Staging || this is Committing
}

/**
 * The prompt a staged revert put back into the composer.
 *
 * **The whole prompt, not the text.** Undo has to restore what the user actually sent, and a prompt
 * is text plus attachments plus review comments (features doc §6). Losing the attachments because
 * only the text was kept is the failure a user notices immediately, so the attachments are carried
 * as [AttachmentDraft]s and the comments as [ReviewComment]s, and the composer puts all three back.
 *
 * [delivery] is the mode the original prompt used, because resending a steer as a queue changes
 * what happens next and the user did not ask for that.
 */
data class RestoredPrompt(
    val messageID: String,
    val text: String,
    val attachments: List<AttachmentDraft> = emptyList(),
    val comments: List<ReviewComment> = emptyList(),
    val delivery: Delivery = Delivery.Steer,
)

/**
 * The undo/redo rules, as a pure function.
 *
 * Every method answers "what does the client do next", never "what does the screen show", and
 * every one of them is a case that a test can name. There is no I/O here at all: the I/O lives in
 * [dev.opencode.android.core.data.server.RevertCommands], and this is what it is allowed to ask
 * for.
 */
object RevertPlan {

    /**
     * The steps that must happen before a stage may be sent.
     *
     * The order is the reference clients' order and the server's requirement: a busy session is
     * interrupted, the pending *user* items are cancelled, and only then is the stage sent. A
     * cancelled item that is not user input (a compaction, a move) is left alone — cancelling a
     * compaction would be changing something the user did not ask to change.
     */
    fun stepsBeforeStage(busy: Boolean, pendingUserInboxIDs: List<String>): List<PreStageStep> = buildList {
        if (busy) add(PreStageStep.Interrupt)
        pendingUserInboxIDs.forEach { add(PreStageStep.CancelInbox(it)) }
        add(PreStageStep.Stage)
    }

    /**
     * The banner's contents for a staged revert.
     *
     * Empty when the server reported no files, which is a real answer: `snapshots: false` turns the
     * per-step snapshots off (features doc §21), so a revert can restore the transcript and have
     * nothing to restore in the working copy. The banner says so rather than claiming a file list
     * it does not have.
     */
    fun restoredFiles(revert: SessionRevert): List<RestoredFile> =
        revert.files.orEmpty().map { diff -> RestoredFile(diff.file, diff.additions, diff.deletions) }

    /** Whether the staged revert will restore any file, which is what the banner's wording turns on. */
    fun restoresFiles(revert: SessionRevert): Boolean = !revert.files.isNullOrEmpty()

    /**
     * What the next send has to do about a staged revert.
     *
     * **Commit, then send.** The TUI commits before submitting the edited prompt (features doc
     * §21), because the server applies the working-copy restore at commit time and a prompt sent
     * against an uncommitted revert would be compared against files that are about to change under
     * it. So the answer is a *sequence*, and a caller that ignores the second step has sent a
     * prompt the server will judge against the wrong tree.
     */
    fun beforeSend(state: RevertState): SendPreparation = when (state) {
        is RevertState.Idle -> SendPreparation.Send
        is RevertState.Staging -> SendPreparation.Wait("a revert is being staged")
        is RevertState.Committing -> SendPreparation.Wait("the revert is being committed")
        is RevertState.Staged -> SendPreparation.CommitThenSend
    }
}

/** One step of the "before staging" sequence (features doc §21, reference clients). */
sealed interface PreStageStep {
    /** `session.interrupt`, so a busy session is not busy any more. */
    data object Interrupt : PreStageStep

    /** `session.inbox.cancel` for one pending *user* item. */
    data class CancelInbox(val inboxID: String) : PreStageStep

    /** `session.revert.stage`, the only step that is the point of the sequence. */
    data object Stage : PreStageStep
}

/** One file a staged revert will restore, as the staged banner lists it. */
data class RestoredFile(val file: String, val additions: Int, val deletions: Int)

/** What a send has to do about a staged revert. */
sealed interface SendPreparation {
    /** Nothing staged: send. */
    data object Send : SendPreparation

    /** Commit the staged revert and then send the prompt, in that order. */
    data object CommitThenSend : SendPreparation

    /** A revert operation is in flight and a send would be judged against the wrong state. */
    data class Wait(val reason: String) : SendPreparation
}

/**
 * The comment a diff line carries, and the anchor a selection produces.
 *
 * **A selection is a range of line numbers on one file, and it becomes three things**: a ranged
 * `file:` URI the agent reads, a `preview` of those lines, and the web app's `opencodeComment`
 * metadata. Keeping them in one function is what stops a comment from pointing at one range and
 * quoting another.
 */
object CommentSelection {

    /**
     * The comment for a selection on a file.
     *
     * The range is clamped into the file's line count when one is known, because a user can drag a
     * handle past the end of what was rendered and a comment about line 900 of a 40-line file would
     * attach a range the server cannot honour. A selection of one line is the common case and
     * becomes a start-only range.
     */
    fun onDiff(
        path: String,
        startLine: Int,
        endLine: Int,
        text: String,
        lines: List<DiffLine> = emptyList(),
    ): ReviewComment {
        val lastLine = lines.maxOfOrNull { it.anchorNumber }?.takeIf { it > 0 }
        val start = startLine.coerceAtLeast(1)
        val end = endLine.coerceAtLeast(start)
        val clamped = if (lastLine != null) {
            val high = end.coerceAtMost(lastLine)
            LineRange(start.coerceAtMost(high), high)
        } else {
            LineRange(start, end)
        }
        return ReviewComment(
            path = path,
            range = clamped,
            text = text,
            preview = previewOf(lines, clamped),
        )
    }

    /** The comment for a selection in a whole file, numbered from one. */
    fun onFile(path: String, startLine: Int, endLine: Int, text: String, totalLines: Int? = null): ReviewComment {
        val start = startLine.coerceAtLeast(1)
        val end = endLine.coerceAtLeast(start)
        val range = if (totalLines != null) {
            val high = end.coerceAtMost(totalLines.coerceAtLeast(1))
            LineRange(start.coerceAtMost(high), high)
        } else {
            LineRange(start, end)
        }
        return ReviewComment(path = path, range = range, text = text)
    }

    /**
     * The text of the selected lines, which the comment carries as its preview.
     *
     * Only lines that exist in the diff are quoted: an added line has no old number, so a selection
     * that spans it and a context line quotes the context line and says nothing about the addition,
     * which is honest rather than empty. A very long selection is truncated, because a preview is
     * what the model is shown and a whole file is not.
     */
    fun previewOf(lines: List<DiffLine>, range: LineRange): String? {
        if (lines.isEmpty()) return null
        // The range is a **new-file** range, because `?start=&end=` on an attachment is, and so the
        // filter is on the new number. A removed line has none and is therefore not quoted: the
        // server will not send it either, and quoting it would tell the agent about a line the
        // attachment does not contain.
        val selected = lines.filter { line ->
            val number = line.newNumber ?: return@filter false
            number >= range.start && (range.end == null || number <= range.end)
        }
        if (selected.isEmpty()) return null
        val quoted = selected.joinToString("\n") { line ->
            buildString {
                append(
                    when (line.kind) {
                        DiffLineKind.ADDED -> "+"
                        DiffLineKind.REMOVED -> "-"
                        else -> " "
                    },
                )
                append(line.text)
            }
        }
        return if (quoted.length <= MAX_PREVIEW_CHARS) quoted else quoted.take(MAX_PREVIEW_CHARS) + "\n…"
    }

    /** How much of a selection the preview carries, which is a reviewer-facing limit not a protocol one. */
    const val MAX_PREVIEW_CHARS: Int = 4_000
}

/** The diffs a message changed, for the "changed files" links in the transcript. */
object ChangedFiles {

    /**
     * The files an assistant step changed, from `assistant.snapshot.files` (features doc §5).
     *
     * The snapshot's file list is a list of *paths*, not of diffs, so what a link needs is the path
     * and nothing more; the counts are not invented. An empty or absent list means the step changed
     * nothing on disk, which is the normal case for a step that only read files.
     */
    fun fromSnapshot(files: List<String>?): List<String> =
        files.orEmpty().filter { it.isNotBlank() }.distinct()

    /**
     * The files a tool call's `metadata.files` names (features doc §5, "Tool metadata worth
     * rendering").
     *
     * The metadata is free-form, so both shapes a server has been seen to use are read: a list of
     * strings, and a list of objects with a `file` field. Anything else is ignored, because a file
     * name invented from a number is a link to nowhere.
     */
    fun fromToolMetadata(metadata: Map<String, kotlinx.serialization.json.JsonElement>?): List<String> {
        val files = metadata?.get("files") ?: return emptyList()
        val array = when (files) {
            is kotlinx.serialization.json.JsonArray -> files
            is kotlinx.serialization.json.JsonObject -> kotlinx.serialization.json.JsonArray(listOf(files))
            else -> return emptyList()
        }
        return array.mapNotNull { element ->
            when (element) {
                is kotlinx.serialization.json.JsonPrimitive -> element.content.takeIf { it.isNotBlank() }
                is kotlinx.serialization.json.JsonObject -> (element["file"] as? kotlinx.serialization.json.JsonPrimitive)
                    ?.content?.takeIf { it.isNotBlank() }

                else -> null
            }
        }.distinct()
    }
}
