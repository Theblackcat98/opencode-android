package dev.opencode.android.feature.sessions.ui.timeline

import dev.opencode.android.core.designsystem.code.CodeLanguage
import dev.opencode.android.core.designsystem.diff.DiffRowKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What an edit, a write and a patch look like in the transcript.
 *
 * The preview is drawn from the model's own arguments, so these tests are the statement of what a
 * card says about a change, independent of whatever a server puts in `metadata`.
 */
class ToolDiffTest {

    @Test
    fun `an edit is the old lines removed and the new lines added`() {
        val diff = toolDiffOf(
            ToolCardKind.EDIT,
            mapOf("filePath" to "src/A.kt", "oldString" to "val a = 1\nval b = 2", "newString" to "val a = 10"),
        )!!
        assertEquals(
            listOf(DiffRowKind.REMOVED, DiffRowKind.REMOVED, DiffRowKind.ADDED),
            diff.rows.map { it.kind },
        )
        assertEquals(listOf("val a = 1", "val b = 2", "val a = 10"), diff.rows.map { it.text })
        assertEquals(listOf('-', '-', '+'), diff.rows.map { it.marker })
        assertEquals(CodeLanguage.KOTLIN, diff.language)
        assertEquals(0, diff.hiddenRows)
    }

    @Test
    fun `an insertion has no removed rows and a deletion has no added rows`() {
        val insertion = toolDiffOf(ToolCardKind.EDIT, mapOf("oldString" to "", "newString" to "x"))!!
        assertEquals(listOf(DiffRowKind.ADDED), insertion.rows.map { it.kind })
        val deletion = toolDiffOf(ToolCardKind.EDIT, mapOf("oldString" to "x", "newString" to ""))!!
        assertEquals(listOf(DiffRowKind.REMOVED), deletion.rows.map { it.kind })
    }

    @Test
    fun `a write is every line added`() {
        val diff = toolDiffOf(ToolCardKind.WRITE, mapOf("path" to "notes.md", "content" to "one\ntwo"))!!
        assertEquals(listOf("one", "two"), diff.rows.map { it.text })
        assertEquals(setOf(DiffRowKind.ADDED), diff.rows.map { it.kind }.toSet())
        assertEquals(listOf(1, 2), diff.rows.map { it.newNumber })
    }

    @Test
    fun `a patch keeps its headers and classifies its lines by their first character`() {
        val diff = toolDiffOf(
            ToolCardKind.PATCH,
            mapOf("patch" to "--- a/f\n+++ b/f\n@@ -1,2 +1,2 @@\n keep\n-old\n+new\n"),
        )!!
        assertEquals(
            listOf(
                DiffRowKind.HEADER,
                DiffRowKind.HEADER,
                DiffRowKind.HEADER,
                DiffRowKind.CONTEXT,
                DiffRowKind.REMOVED,
                DiffRowKind.ADDED,
            ),
            diff.rows.map { it.kind },
        )
        assertEquals(listOf("keep", "old", "new"), diff.rows.drop(3).map { it.text })
    }

    @Test
    fun `a large write is capped and says how much was left out`() {
        val diff = toolDiffOf(
            ToolCardKind.WRITE,
            mapOf("path" to "big.txt", "content" to (1..(MAX_ROWS + 25)).joinToString("\n") { "line $it" }),
        )!!
        assertEquals(MAX_ROWS, diff.rows.size)
        assertEquals(25, diff.hiddenRows)
    }

    @Test
    fun `a tool with nothing to draw has no diff`() {
        assertNull(toolDiffOf(ToolCardKind.EDIT, emptyMap()))
        assertNull(toolDiffOf(ToolCardKind.WRITE, mapOf("path" to "a.txt")))
        assertNull(toolDiffOf(ToolCardKind.SHELL, mapOf("command" to "ls")))
        assertNull(toolDiffOf(ToolCardKind.READ, mapOf("filePath" to "a.txt")))
    }
}
