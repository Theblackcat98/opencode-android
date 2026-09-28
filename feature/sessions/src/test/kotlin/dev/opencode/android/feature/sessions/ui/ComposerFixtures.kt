package dev.opencode.android.feature.sessions.ui

import dev.opencode.android.core.data.composer.AttachmentDraft
import dev.opencode.android.core.data.composer.AttachmentKind
import dev.opencode.android.core.data.composer.Completion
import dev.opencode.android.core.data.composer.CompletionKind
import dev.opencode.android.core.data.composer.LineRange
import dev.opencode.android.core.data.composer.PromptIntent
import dev.opencode.android.core.data.composer.StashEntry
import dev.opencode.android.core.data.composer.TriggerKind
import dev.opencode.android.core.model.Delivery
import dev.opencode.android.core.model.PromptSkillInput
import dev.opencode.android.core.model.ReferenceInfo
import dev.opencode.android.core.model.ReferenceSource
import dev.opencode.android.feature.composer.ui.ComposerProblem
import dev.opencode.android.feature.composer.ui.ComposerUiState
import dev.opencode.android.feature.composer.ui.SideQuestion

/**
 * The Phase 5 composer states, as values.
 *
 * A composer's states are hard to photograph and easy to enumerate, and a state that only appears in
 * a unit test is a state nobody has looked at. Each fixture here is a state a real session can be in
 * — a mention half typed, a picture waiting for a confirmation, a stash with something in it — and
 * the screenshots are captured from exactly these.
 */
object ComposerFixtures {

    /** A mention half typed, with the completion list open under it. */
    fun mentionList(): ComposerUiState = base().copy(
        text = "have a look at @src/a",
        completions = listOf(
            Completion(
                kind = CompletionKind.FILE,
                label = "a.ts",
                insertText = "@src/a.ts",
                target = "/work/src/a.ts",
                detail = "src/a.ts",
            ),
            Completion(
                kind = CompletionKind.FILE,
                label = "alpha.ts",
                insertText = "@src/alpha.ts",
                target = "/work/src/alpha.ts",
                detail = "src/alpha.ts",
            ),
            Completion(
                kind = CompletionKind.AGENT,
                label = "Build",
                insertText = "@build",
                target = "build",
                detail = "Implements and verifies a change end to end.",
            ),
        ),
        trigger = TriggerKind.MENTION,
    )

    /** The slash palette, with a server command, an MCP prompt and an app command. */
    fun commandList(): ComposerUiState = base().copy(
        text = "/rev",
        completions = listOf(
            Completion(CompletionKind.COMMAND, "/review", "/review", "review", "Review the last turn"),
            Completion(
                CompletionKind.MCP_COMMAND,
                "/github:pr",
                "/github:pr",
                "github:pr",
                "Open a pull request",
            ),
            Completion(CompletionKind.CLIENT_COMMAND, "/redo", "/redo", "redo", null),
        ),
        trigger = TriggerKind.COMMAND,
        intent = PromptIntent.Prompt("/rev"),
    )

    /** Shell mode: the box is a command line, and the send button runs it. */
    fun shellMode(): ComposerUiState = base().copy(
        text = "!git status --short",
        trigger = TriggerKind.SHELL,
        intent = PromptIntent.Shell("git status --short"),
    )

    /** A prompt with a picture, a mention and a skill: the full context row. */
    fun carryingContext(): ComposerUiState = base().copy(
        text = "this is the screenshot, and @src/a.ts explains it",
        attachments = listOf(
            AttachmentDraft(
                id = "shot",
                label = "screenshot.png",
                uri = "data:image/png;base64,AAAA",
                kind = AttachmentKind.IMAGE,
                sizeBytes = 412_331,
                mime = "image/png",
            ),
            AttachmentDraft(
                id = "file:src/a.ts",
                label = "a.ts",
                uri = "file:///work/src/a.ts",
                kind = AttachmentKind.TEXT,
                range = LineRange(20, 45),
            ),
        ),
        skills = listOf(PromptSkillInput("review")),
        references = listOf(
            ReferenceInfo("docs", "/work/docs", source = ReferenceSource.Local("/work/docs")),
        ),
        intent = PromptIntent.Prompt("this is the screenshot"),
    )

    /** The picture the model cannot see, with the confirmation button beside it. */
    fun imageNeedsConfirmation(): ComposerUiState = base().copy(
        text = "what do you make of this?",
        attachments = listOf(
            AttachmentDraft(
                id = "shot",
                label = "screenshot.png",
                uri = "data:image/png;base64,AAAA",
                kind = AttachmentKind.IMAGE,
                sizeBytes = 412_331,
                mime = "image/png",
            ),
        ),
        problem = ComposerProblem.ATTACHMENT_NEEDS_CONFIRMATION,
        problemDetail = "screenshot.png",
        intent = PromptIntent.Prompt("what do you make of this?"),
    )

    /** A prompt waiting for a verdict: a format the model is not sent. */
    fun attachmentBlocked(): ComposerUiState = base().copy(
        text = "read the spec",
        attachments = listOf(
            AttachmentDraft(
                id = "spec",
                label = "spec.pdf",
                uri = "file:///work/spec.pdf",
                kind = AttachmentKind.BINARY,
                sizeBytes = 3_400_000,
                mime = "application/pdf",
            ),
        ),
        problem = ComposerProblem.ATTACHMENT_BLOCKED,
        problemDetail = "spec.pdf",
    )

    /** The stash, with two prompts put away. */
    fun stashed(): ComposerUiState = base().copy(
        text = "",
        stash = listOf(
            StashEntry("st1", "Also check the migration that drops the column.", created = 2L),
            StashEntry("st2", "And write down why the retry is idempotent.", created = 1L),
        ),
    )

    /** A side question, answered. */
    fun sideQuestionAnswered(): ComposerUiState = base().copy(
        text = "",
        sideQuestion = SideQuestion(
            question = "why is the retry idempotent?",
            answer = "Because the client generates the message id before the call, so a repeated id " +
                "with the same payload is the same request rather than a second one.",
            loading = false,
        ),
    )

    /** A side question still running. */
    fun sideQuestionWaiting(): ComposerUiState = base().copy(
        text = "",
        sideQuestion = SideQuestion(question = "what does this function return?", loading = true),
    )

    private fun base(): ComposerUiState = DrivingFixtures.composerState().copy(
        text = "",
        busy = false,
        pending = emptyList(),
        delivery = Delivery.Steer,
    )
}
