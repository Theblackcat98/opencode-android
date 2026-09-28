package dev.opencode.android.core.data.review

import dev.opencode.android.core.data.composer.AttachmentDraft
import dev.opencode.android.core.data.composer.AttachmentKind
import dev.opencode.android.core.data.composer.LineRange
import dev.opencode.android.core.data.composer.ServerPath
import dev.opencode.android.core.data.review.CommentSelection.onDiff
import dev.opencode.android.core.data.review.CommentSelection.onFile
import dev.opencode.android.core.model.Delivery
import dev.opencode.android.core.model.FileDiffStatus
import dev.opencode.android.core.model.SessionRevert
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The undo/redo rules, the review comment's wire format, and the changed-files extraction.
 *
 * These are the three things a user can be harmed by getting wrong in a way a device test cannot
 * see: a revert staged out of order restores the wrong tree, a comment written in the wrong shape is
 * invisible to the desktop, and a file link built from the wrong field points at nothing.
 */
class RevertPlanTest {

    private fun revert(files: List<String> = emptyList()) = SessionRevert(
        messageID = "msg_1",
        snapshot = "abc",
        files = files.map {
            dev.opencode.android.core.model.FileDiff(it, "@@ -1 +1 @@\n-a\n+b\n", 1, 1, FileDiffStatus.Modified)
        },
    )

    @Test
    fun `an idle session is interrupted once and then staged`() {
        val steps = RevertPlan.stepsBeforeStage(busy = true, pendingUserInboxIDs = emptyList())

        assertEquals(listOf(PreStageStep.Interrupt, PreStageStep.Stage), steps)
    }

    @Test
    fun `an idle session is not interrupted`() {
        val steps = RevertPlan.stepsBeforeStage(busy = false, pendingUserInboxIDs = emptyList())

        assertEquals(listOf(PreStageStep.Stage), steps)
    }

    @Test
    fun `pending user items are cancelled before the stage, not after`() {
        val steps = RevertPlan.stepsBeforeStage(busy = true, pendingUserInboxIDs = listOf("ib_1", "ib_2"))

        // The order is the server's requirement: a queued prompt delivered against a rolled-back
        // working copy is the failure this sequence exists to prevent.
        assertEquals(
            listOf(
                PreStageStep.Interrupt,
                PreStageStep.CancelInbox("ib_1"),
                PreStageStep.CancelInbox("ib_2"),
                PreStageStep.Stage,
            ),
            steps,
        )
    }

    @Test
    fun `the stage is always last, whatever the session was doing`() {
        listOf(false to emptyList(), true to emptyList(), false to listOf("a"), true to listOf("a", "b"))
            .forEach { (busy, ids) ->
                assertEquals(
                    "busy=$busy ids=$ids",
                    PreStageStep.Stage,
                    RevertPlan.stepsBeforeStage(busy, ids).last(),
                )
            }
    }

    @Test
    fun `the restored files are the server's, with its counts`() {
        val files = RevertPlan.restoredFiles(revert(listOf("src/a.kt", "src/b.kt")))

        assertEquals(listOf("src/a.kt", "src/b.kt"), files.map { it.file })
        assertTrue(files.all { it.additions == 1 && it.deletions == 1 })
    }

    @Test
    fun `a revert with no files restores the transcript and nothing else`() {
        // `snapshots: false` turns the per-step snapshots off, so this is a real answer rather than
        // a failure, and the banner has to say "nothing in the working copy" rather than nothing.
        val staged = revert()

        assertTrue(staged.files.isNullOrEmpty())
        assertFalse(RevertPlan.restoresFiles(staged))
        assertEquals(emptyList<RestoredFile>(), RevertPlan.restoredFiles(staged))
    }

    @Test
    fun `an idle send is a send`() {
        assertEquals(SendPreparation.Send, RevertPlan.beforeSend(RevertState.Idle))
    }

    @Test
    fun `a staged revert makes the next send a commit-then-send`() {
        assertEquals(SendPreparation.CommitThenSend, RevertPlan.beforeSend(RevertState.Staged(revert(), null)))
    }

    @Test
    fun `a revert in flight blocks the send`() {
        assertTrue(RevertPlan.beforeSend(RevertState.Staging("msg_1")) is SendPreparation.Wait)
        assertTrue(RevertPlan.beforeSend(RevertState.Committing(revert())) is SendPreparation.Wait)
    }

    @Test
    fun `staging and committing block a send, being staged does not`() {
        assertTrue(RevertState.Staging("msg_1").blocksSend)
        assertTrue(RevertState.Committing(revert()).blocksSend)
        assertFalse(RevertState.Idle.blocksSend)
        assertFalse(RevertState.Staged(revert(), null).blocksSend)
    }

    @Test
    fun `a restored prompt carries the text, the attachments, the comments and the delivery`() {
        val prompt = RestoredPrompt(
            messageID = "msg_7",
            text = "add the flag",
            attachments = listOf(
                AttachmentDraft("a1", "shot.png", "data:image/png;base64,AA", AttachmentKind.IMAGE, 10, "image/png"),
            ),
            comments = listOf(ReviewComment("src/a.kt", LineRange(3, 5), "this is wrong", " a\n-b\n+c")),
            delivery = Delivery.Queue,
        )

        // Every one of the four is a different thing a user notices losing, so the value holds all
        // of them rather than only the text.
        assertEquals("add the flag", prompt.text)
        assertEquals(1, prompt.attachments.size)
        assertEquals(1, prompt.comments.size)
        assertEquals(Delivery.Queue, prompt.delivery)
    }
}

/**
 * The review comment's wire format.
 *
 * The format is the **web app's**, not this client's, and the only way to know the two agree is to
 * round-trip: write one the way the desktop writes it, read it back, and require the same file,
 * range, text and preview.
 */
class ReviewCommentsTest {

    private fun comment(start: Int, end: Int? = null, text: String = "use a guard here", preview: String? = "if (x) {") =
        ReviewComment(path = "src/a.ts", range = LineRange(start, end), text = text, preview = preview)

    @Test
    fun `one comment is an object, not an array`() {
        val metadata = ReviewComments.metadataOf(listOf(comment(12)))

        // The web app's spelling: a single comment is the object itself. A reader that only
        // understood arrays would lose the commonest case on the planet.
        assertTrue(metadata[OpenCodeComment.KEY] is JsonObject)
    }

    @Test
    fun `several comments are an array`() {
        val metadata = ReviewComments.metadataOf(listOf(comment(1), comment(9)))

        assertTrue(metadata[OpenCodeComment.KEY] is JsonArray)
        assertEquals(2, (metadata[OpenCodeComment.KEY] as JsonArray).size)
    }

    @Test
    fun `no comments produce no metadata at all`() {
        assertEquals(emptyMap<String, Any>(), ReviewComments.metadataOf(emptyList()))
    }

    @Test
    fun `a single comment round trips through the wire format`() {
        val original = comment(20, 24)

        val read = ReviewComments.read(ReviewComments.metadataOf(listOf(original)))

        assertEquals(1, read.size)
        assertEquals(original, read.single())
    }

    @Test
    fun `several comments round trip in order`() {
        val originals = listOf(comment(1), comment(40, 44, "this whole block", "a\nb"), comment(90, 91))

        val read = ReviewComments.read(ReviewComments.metadataOf(originals))

        assertEquals(originals, read)
    }

    @Test
    fun `the wire fields are the web app's spelling`() {
        val encoded = ReviewComments.metadataOf(listOf(comment(7, 9)))["opencodeComment"] as JsonObject

        assertEquals(
            setOf("path", "selection", "comment", "preview", "origin"),
            encoded.keys,
        )
        assertEquals(JsonPrimitive("7-9"), encoded["selection"])
        assertEquals(JsonPrimitive("src/a.ts"), encoded["path"])
        assertEquals(JsonPrimitive("android"), encoded["origin"])
    }

    @Test
    fun `a single-line selection is a bare number`() {
        assertEquals("12", comment(12).selection)
        assertEquals("12-14", comment(12, 14).selection)
    }

    @Test
    fun `a comment the desktop wrote is read back`() {
        // Exactly the shape the web app stores, hand-written rather than produced by this client.
        val fromDesktop = mapOf(
            "opencodeComment" to JsonObject(
                mapOf(
                    "path" to JsonPrimitive("src/b.ts"),
                    "selection" to JsonPrimitive("31-34"),
                    "comment" to JsonPrimitive("rename this"),
                    "preview" to JsonPrimitive("const a = 1"),
                    "origin" to JsonPrimitive("web"),
                ),
            ),
        )

        val read = ReviewComments.read(fromDesktop)

        assertEquals(1, read.size)
        assertEquals("src/b.ts", read.single().path)
        assertEquals(LineRange(31, 34), read.single().range)
        assertEquals("rename this", read.single().text)
    }

    @Test
    fun `an array the desktop wrote is read back`() {
        val fromDesktop = mapOf(
            "opencodeComment" to JsonArray(
                listOf(
                    JsonObject(
                        mapOf(
                            "path" to JsonPrimitive("a.ts"),
                            "selection" to JsonPrimitive("1"),
                            "comment" to JsonPrimitive("first"),
                        ),
                    ),
                    JsonObject(
                        mapOf(
                            "path" to JsonPrimitive("b.ts"),
                            "selection" to JsonPrimitive("2-3"),
                            "comment" to JsonPrimitive("second"),
                        ),
                    ),
                ),
            ),
        )

        assertEquals(listOf("first", "second"), ReviewComments.read(fromDesktop).map { it.text })
    }

    @Test
    fun `a comment with an unreadable range is dropped rather than widened to the file`() {
        // Attaching a comment with no range would tell the agent "everything above this line", which
        // is a different statement from the one the user made.
        val metadata = mapOf(
            "opencodeComment" to JsonObject(
                mapOf(
                    "path" to JsonPrimitive("a.ts"),
                    "selection" to JsonPrimitive("not a range"),
                    "comment" to JsonPrimitive("x"),
                ),
            ),
        )

        assertEquals(emptyList<ReviewComment>(), ReviewComments.read(metadata))
    }

    @Test
    fun `a backwards range is not a range`() {
        assertNull(ReviewComments.parseSelection("40-20"))
        assertNull(ReviewComments.parseSelection("0"))
        assertNull(ReviewComments.parseSelection("-5"))
        assertNull(ReviewComments.parseSelection(""))
    }

    @Test
    fun `an open-ended selection is from the line to the end of the file`() {
        assertEquals(LineRange(12), ReviewComments.parseSelection("12"))
        assertEquals(LineRange(12), ReviewComments.parseSelection("12-"))
    }

    @Test
    fun `unknown keys on a comment are ignored rather than fatal`() {
        val metadata = mapOf(
            "opencodeComment" to JsonObject(
                mapOf(
                    "path" to JsonPrimitive("a.ts"),
                    "selection" to JsonPrimitive("3"),
                    "comment" to JsonPrimitive("x"),
                    "aKeyTheNextReleaseAdds" to JsonPrimitive("ignored"),
                ),
            ),
        )

        assertEquals(1, ReviewComments.read(metadata).size)
    }

    @Test
    fun `metadata that is not a comment is not a comment`() {
        assertEquals(emptyList<ReviewComment>(), ReviewComments.read(null))
        assertEquals(emptyList<ReviewComment>(), ReviewComments.read(emptyMap()))
        assertEquals(
            emptyList<ReviewComment>(),
            ReviewComments.read(mapOf("opencodeComment" to JsonPrimitive("nonsense"))),
        )
    }

    @Test
    fun `the readable text names the file, the lines and the comment`() {
        val text = ReviewComments.readableText(listOf(comment(12, 14, "this leaks", "  val token = 1")))

        assertTrue(text, text.contains("src/a.ts"))
        assertTrue(text, text.contains("#12-14"))
        assertTrue(text, text.contains("this leaks"))
        assertTrue(text, text.contains("val token = 1"))
    }

    @Test
    fun `several comments read as a numbered list`() {
        val text = ReviewComments.readableText(listOf(comment(1, text = "one"), comment(2, text = "two")))

        assertTrue(text, text.startsWith("Review comments (2):"))
        assertTrue(text.contains("one"))
        assertTrue(text.contains("two"))
    }

    @Test
    fun `a comment's line range is the attachment the agent reads`() {
        val uri = comment(20, 24).attachmentUri("/home/dev/project")

        assertEquals("file:///home/dev/project/src/a.ts?start=20&end=24", uri)
        assertTrue(uri.startsWith("file://"))
    }

    @Test
    fun `a comment on a relative path resolves against the location`() {
        val uri = comment(3).attachmentUri("/home/dev/project")

        assertEquals("file:///home/dev/project/src/a.ts?start=3", uri)
    }
}

/** Turning a selection in a diff or a file into a comment, with the range clamped. */
class CommentSelectionTest {

    private val lines = listOf(
        DiffLine(DiffLineKind.CONTEXT, "package a", 1, 1),
        DiffLine(DiffLineKind.ADDED, "val x = 1", null, 2),
        DiffLine(DiffLineKind.CONTEXT, "val y = 2", 2, 3),
        DiffLine(DiffLineKind.REMOVED, "val z = 3", 3, null),
        DiffLine(DiffLineKind.CONTEXT, "val w = 4", 4, 4),
    )

    @Test
    fun `a selection becomes a range and a preview of exactly those lines`() {
        val comment = onDiff("src/a.kt", 2, 3, "rename this", lines)

        assertEquals(LineRange(2, 3), comment.range)
        assertEquals("+val x = 1\n val y = 2", comment.preview)
    }

    @Test
    fun `a selection past the end of what was rendered is clamped`() {
        val comment = onDiff("src/a.kt", 3, 900, "past the end", lines)

        // A comment about line 900 of a five-line hunk would attach a range the server cannot
        // honour, so the range is clamped to what exists.
        assertEquals(LineRange(3, 4), comment.range)
    }

    @Test
    fun `a backwards selection is normalised to a forward range`() {
        // 5..1 is not a range a user can draw; it is clamped into the lines that exist, and the
        // selection's own shape is preserved.
        assertEquals(LineRange(4, 4), onDiff("a.kt", 5, 1, "x", lines).range)
    }

    @Test
    fun `a selection on one line is the common case and stays one line`() {
        assertEquals(LineRange(2, 2), onDiff("a.kt", 2, 2, "x", lines).range)
        assertEquals("2", onDiff("a.kt", 2, 2, "x", lines).selection)
    }

    @Test
    fun `a selection with no diff lines has no preview`() {
        val comment = onDiff("a.kt", 1, 2, "x", emptyList())

        assertNull(comment.preview)
        assertEquals(LineRange(1, 2), comment.range)
    }

    @Test
    fun `a very long preview is truncated rather than sent whole`() {
        val many = (1..500).map { DiffLine(DiffLineKind.CONTEXT, "x".repeat(50), it, it) }

        val preview = onDiff("a.kt", 1, 500, "x", many).preview

        assertTrue(preview!!.endsWith("\u2026"))
        assertTrue(preview.length <= CommentSelection.MAX_PREVIEW_CHARS + 2)
    }

    @Test
    fun `a file selection is clamped to the file's line count`() {
        assertEquals(LineRange(10, 40), onFile("a.kt", 10, 40, "x", totalLines = 40).range)
        assertEquals(LineRange(10, 60), onFile("a.kt", 10, 60, "x", totalLines = null).range)
    }
}

/** The changed-files links in the transcript. */
class ChangedFilesTest {

    @Test
    fun `a snapshot's files are the paths, deduplicated and never blank`() {
        assertEquals(
            listOf("src/a.kt", "src/b.kt"),
            ChangedFiles.fromSnapshot(listOf("src/a.kt", "src/b.kt", "src/a.kt", "", "  ")),
        )
    }

    @Test
    fun `a step that changed nothing has no files`() {
        assertEquals(emptyList<String>(), ChangedFiles.fromSnapshot(null))
        assertEquals(emptyList<String>(), ChangedFiles.fromSnapshot(emptyList()))
    }

    @Test
    fun `tool metadata as a list of strings is read`() {
        val metadata = mapOf("files" to kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("a.kt"), JsonPrimitive("b.kt"))))

        assertEquals(listOf("a.kt", "b.kt"), ChangedFiles.fromToolMetadata(metadata))
    }

    @Test
    fun `tool metadata as a list of objects with a file field is read`() {
        val metadata = mapOf(
            "files" to kotlinx.serialization.json.JsonArray(
                listOf(
                    JsonObject(mapOf("file" to JsonPrimitive("a.kt"))),
                    JsonObject(mapOf("path" to JsonPrimitive("ignored.kt"))),
                ),
            ),
        )

        // Only a real file name is a link; a path guessed out of another field would point nowhere.
        assertEquals(listOf("a.kt"), ChangedFiles.fromToolMetadata(metadata))
    }

    @Test
    fun `a single object under files is read as a one-entry list`() {
        val metadata = mapOf("files" to JsonObject(mapOf("file" to JsonPrimitive("a.kt"))))

        assertEquals(listOf("a.kt"), ChangedFiles.fromToolMetadata(metadata))
    }

    @Test
    fun `metadata with no files key has none`() {
        assertEquals(emptyList<String>(), ChangedFiles.fromToolMetadata(null))
        assertEquals(emptyList<String>(), ChangedFiles.fromToolMetadata(emptyMap()))
        assertEquals(
            emptyList<String>(),
            ChangedFiles.fromToolMetadata(mapOf("files" to JsonPrimitive("a.kt"))),
        )
    }
}
