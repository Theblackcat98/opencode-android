package dev.opencode.android.feature.sessions.ui.timeline

import dev.opencode.android.core.designsystem.code.CodeLanguage
import dev.opencode.android.core.designsystem.diff.DiffRow
import dev.opencode.android.core.designsystem.diff.DiffRowKind

/**
 * What an edit, a write or a patch looked like, drawn from the arguments the model sent.
 *
 * **The arguments, not the metadata.** The spec types a tool's `metadata` as an open object, so what a
 * server puts there is its own business and differs between tools and versions. The input is the one
 * thing every server echoes back unchanged, and it is also what the CLI draws: `oldString` against
 * `newString` for an edit, `content` for a write, the patch text for a patch. A preview built from it
 * cannot silently go blank when a server changes its metadata.
 *
 * **It is a preview, and it says so.** A card in a transcript is read in passing, so the rows are
 * capped at [MAX_ROWS] and [hiddenRows] counts what was left out; the file itself is one tap away in
 * the review screen.
 */
data class ToolDiff(val rows: List<DiffRow>, val hiddenRows: Int, val language: CodeLanguage)

internal const val MAX_ROWS = 40

/** The preview for [kind] given the tool's string arguments, or `null` when there is nothing to draw. */
internal fun toolDiffOf(kind: ToolCardKind, input: Map<String, String>): ToolDiff? {
    val rows: List<DiffRow> = when (kind) {
        ToolCardKind.EDIT -> editRows(
            old = input["oldString"] ?: input["old_string"],
            new = input["newString"] ?: input["new_string"],
        )

        ToolCardKind.WRITE -> (input["content"] ?: input["contents"])?.let { changeRows(emptyList(), it.lines()) }.orEmpty()

        ToolCardKind.PATCH -> (input["patch"] ?: input["patchText"])?.let(::patchRows).orEmpty()

        else -> emptyList()
    }
    if (rows.isEmpty()) return null
    val path = input["filePath"] ?: input["path"] ?: input["file"]
    return ToolDiff(
        rows = rows.take(MAX_ROWS),
        hiddenRows = (rows.size - MAX_ROWS).coerceAtLeast(0),
        language = path?.let(CodeLanguage::ofPath) ?: CodeLanguage.PLAIN_TEXT,
    )
}

private fun editRows(old: String?, new: String?): List<DiffRow> {
    if (old == null && new == null) return emptyList()
    // An empty side is an insertion or a deletion, not a one-line change to an empty line.
    val before = old?.takeIf { it.isNotEmpty() }?.lines().orEmpty()
    val after = new?.takeIf { it.isNotEmpty() }?.lines().orEmpty()
    return changeRows(before, after)
}

private fun changeRows(before: List<String>, after: List<String>): List<DiffRow> =
    before.mapIndexed { index, line -> DiffRow(index + 1, null, '-', line, DiffRowKind.REMOVED) } +
        after.mapIndexed { index, line -> DiffRow(null, index + 1, '+', line, DiffRowKind.ADDED) }

/** A patch's own lines, classified by their first character. File headers are kept as headers. */
private fun patchRows(patch: String): List<DiffRow> = patch.lines().dropLastWhile { it.isEmpty() }.map { line ->
    when {
        line.startsWith("+++") || line.startsWith("---") || line.startsWith("@@") ||
            line.startsWith("*** ") -> DiffRow(null, null, ' ', line, DiffRowKind.HEADER)

        line.startsWith("+") -> DiffRow(null, null, '+', line.drop(1), DiffRowKind.ADDED)

        line.startsWith("-") -> DiffRow(null, null, '-', line.drop(1), DiffRowKind.REMOVED)

        else -> DiffRow(null, null, ' ', line.removePrefix(" "), DiffRowKind.CONTEXT)
    }
}
