package dev.opencode.android.core.data.review

import dev.opencode.android.core.model.FileDiff
import dev.opencode.android.core.model.FileDiffStatus

/**
 * A parsed unified patch.
 *
 * The server hands out a `FileDiff.Info` whose `patch` is a unified diff, and everything a diff
 * viewer can do — a file tree, hunk navigation, line-level comments, a wrap toggle, split and
 * unified views — needs it as structure rather than as text. So it is parsed once, into a value,
 * by a function with no Android and no server in it.
 *
 * **The parse is total.** A patch that is malformed, truncated, CRLF, or has no trailing newline
 * still produces a value: the lines that could be read are lines, and the lines that could not are
 * kept as [DiffLineKind.UNPARSED] so the user sees the server's bytes rather than a silently
 * shortened diff. A parser that throws would lose the one artifact a reviewer cannot reconstruct.
 *
 * **The counts are the server's.** [FileDiff.additions] and [FileDiff.deletion] are what the server
 * computed, and [ParsedFile.additions] and [ParsedFile.deletions] are copies of them. Recomputing
 * would replace the server's answer with the client's, and a disagreement about what changed is
 * exactly the question the user has.
 */
data class ParsedFile(
    val file: String,
    /** The old path, for a rename, or `null`. The server spells renames `old => new`. */
    val previousPath: String? = null,
    /** Hunks in order; a patch with no `@@` line is a [Header] entry with no hunks. */
    val hunks: List<DiffHunk>,
    val additions: Int,
    val deletions: Int,
    val status: FileDiffStatus,
    /**
     * True when the patch is not a unified diff at all.
     *
     * A binary file's `patch` is empty or a single `Binary files … differ` line, and a server
     * that cannot diff a file says so in the text. Both are shown as such rather than as a file
     * with no changes, because "nothing to show" and "cannot be shown" are different facts.
     */
    val binary: Boolean = false,
    /** The lines that could not be parsed, verbatim, so nothing the server sent is lost. */
    val unparsed: List<String> = emptyList(),
) {
    /** The lines of every hunk, flattened, which is what a list and a search walk. */
    val lines: List<DiffLine> get() = hunks.flatMap { it.lines }

    /** The number of changed lines, which is what a file tree shows next to the name. */
    val changeCount: Int get() = hunks.sumOf { it.lines.count { it.kind.isChange } }

    /** Whether this file carries anything a viewer can show. */
    val hasContent: Boolean get() = hunks.isNotEmpty() || unparsed.isNotEmpty() || binary

    /** The stable key a list must use: two files can share a name across scopes. */
    val key: String get() = "${status.value}:$file"
}

/** One `@@` section of a patch. */
data class DiffHunk(
    /** The `@@` header, with the range trimmed off, which is a function name in a git diff. */
    val heading: String,
    val oldStart: Int,
    val oldCount: Int,
    val newStart: Int,
    val newCount: Int,
    val lines: List<DiffLine>,
) {
    /** The stable key a list must use. */
    val key: String get() = "$oldStart:$newStart:$heading"

    val additions: Int get() = lines.count { it.kind == DiffLineKind.ADDED }
    val deletions: Int get() = lines.count { it.kind == DiffLineKind.REMOVED }
}

/** What one line of a hunk is. */
enum class DiffLineKind {
    /** Unchanged context. */
    CONTEXT,

    /** `+`: a line the change adds. */
    ADDED,

    /** `-`: a line the change removes. */
    REMOVED,

    /**
     * A line the parser could not classify.
     *
     * It happens: a patch can arrive truncated, or a context line can itself begin with `+` or `-`
     * when the producer was not careful. The bytes are kept rather than dropped, and rendered as
     * what they are, so a reviewer can see that something is wrong with the patch.
     */
    UNPARSED,
    ;

    /** Whether the line is part of the change rather than its surroundings. */
    val isChange: Boolean get() = this == ADDED || this == REMOVED
}

/**
 * One line of a hunk, with the numbers a comment and an anchor need.
 *
 * [oldNumber] and [newNumber] are one-based and `null` on the side the line does not exist on: an
 * added line has no old number, which is the same fact the unified format encodes by omitting it.
 * They are tracked rather than derived so a line number in a review comment is the number the
 * server's patch used, not a number this parser re-counted.
 */
data class DiffLine(
    val kind: DiffLineKind,
    val text: String,
    val oldNumber: Int? = null,
    val newNumber: Int? = null,
    /**
     * True when the file this line came from has no newline at the end of it.
     *
     * The unified format says so with a `\ No newline at end of file` line after the line it
     * qualifies. It matters for two things: the viewer marks the line, and a comment anchored to
     * it refers to a line that does not end where a reader expects one to.
     */
    val noNewlineAtEnd: Boolean = false,
) {
    /** The number a comment on this line is anchored to. */
    val anchorNumber: Int get() = newNumber ?: oldNumber ?: 0
}

/**
 * Parses unified patches.
 *
 * **Every rule here is a rule the server's own `git diff` output can break**, and each one is a
 * test rather than an assumption:
 *
 *  - A body line is classified by its **first** character, and an empty body line is context, not
 *    an unparsed line. A diff of an empty line is `+` with nothing after it, and a parser that
 *    required a character would call the most ordinary addition in a file unparseable.
 *  - A body line that begins with a space is context with that space stripped; a line that begins
 *    with no marker at all (some producers drop the leading space on context) is kept verbatim as
 *    context rather than discarded.
 *  - `\ No newline at end of file` follows the line it qualifies, is not a hunk line, and marks
 *    that line as having no trailing newline. A file without one is a real state and the renderer
 *    has to say so, because a comment on its last line refers to a line that ends mid-line.
 *  - CRLF is a transport detail: a `\r` immediately before the line ending is removed, and a `\r`
 *    that is part of the content is kept.
 *  - A hunk body that runs past its declared counts is kept, and a body that is short is padded
 *    with nothing. Neither is an error: the counts are the producer's claim and the lines are the
 *    artifact.
 *  - A line before the first `@@` is kept as [DiffLineKind.UNPARSED] only when it is not a known
 *    header; the `---`/`+++`/`diff --git`/`index`/`new file mode`/`deleted file mode`/
 *    `similarity index`/`rename from`/`rename to`/`Binary files`/`GIT binary patch` headers are
 *    recognized and consumed.
 */
object UnifiedDiff {

    /**
     * Parses one file's patch.
     *
     * [file] and the counts come from the server's `FileDiff.Info` and are never recomputed; an
     * empty or non-diff [FileDiff.patch] yields a [ParsedFile] that says so rather than an empty
     * one that says nothing.
     */
    fun parse(diff: FileDiff): ParsedFile = parse(
        patch = diff.patch,
        file = diff.file,
        additions = diff.additions,
        deletions = diff.deletions,
        status = diff.status,
    )

    /** Parses a patch on its own, for the tool `metadata.files` previews and for tests. */
    fun parse(
        patch: String,
        file: String = "",
        additions: Int = 0,
        deletions: Int = 0,
        status: FileDiffStatus = FileDiffStatus.Modified,
    ): ParsedFile {
        if (patch.isBlank()) {
            return ParsedFile(file, hunks = emptyList(), additions = additions, deletions = deletions, status = status, binary = true)
        }
        val lines = splitLines(patch)
        var index = 0
        var previousPath: String? = null
        val unparsed = mutableListOf<String>()

        // Everything before the first `@@` is a version-control header. It is scanned rather than
        // skipped, because three things live there that the viewer needs: the old path of a rename,
        // the fact that a file is binary, and nothing else.
        while (index < lines.size && !lines[index].startsWith(HUNK_HEADER_PREFIX)) {
            val line = lines[index]
            if (line.isBinaryMarker()) {
                return ParsedFile(
                    file = file,
                    hunks = emptyList(),
                    additions = additions,
                    deletions = deletions,
                    status = status,
                    binary = true,
                )
            }
            if (line.startsWith(HEADER_RENAME_FROM)) previousPath = line.removePrefix(HEADER_RENAME_FROM).trim().takeIf { it.isNotBlank() }
            if (line.startsWith(HEADER_OLD_FILE)) {
                headerPath(line.removePrefix(HEADER_OLD_FILE))?.let { previousPath = it }
            }
            // A line before the first hunk that is not a version-control header is kept. That is
            // what makes "this is not a patch" a value the viewer can show rather than a file with
            // no changes: a text that arrived where a patch was expected is the server's answer and
            // hiding it would leave the reviewer looking at an empty file.
            if (!line.isVersionControlHeader()) unparsed += line
            index++
        }

        val hunks = mutableListOf<DiffHunk>()
        while (index < lines.size) {
            val header = lines[index]
            val range = HunkHeader.parse(header)
            if (range == null) {
                // A line between hunks that is not a hunk header: kept, because dropping it would
                // hide a line the server sent.
                unparsed += header
                index++
                continue
            }
            index++
            val body = mutableListOf<DiffLine>()
            var oldLine = range.oldStart
            var newLine = range.newStart
            var oldSeen = 0
            var newSeen = 0
            while (index < lines.size && !lines[index].startsWith(HUNK_HEADER_PREFIX) && !lines[index].startsWith(GIT_HEADER_PREFIX)) {
                val raw = lines[index]
                if (raw.isNoNewlineMarker()) {
                    // It qualifies the previous line and is not itself part of the file.
                    body.lastOrNull()?.let { last ->
                        body[body.lastIndex] = last.copy(noNewlineAtEnd = true)
                    }
                    index++
                    continue
                }
                when {
                    raw.isEmpty() -> {
                        // An empty line is a context line whose marker is the line ending. It is the
                        // ordinary case for a diff of a file with a blank line in it.
                        body += DiffLine(
                            DiffLineKind.CONTEXT,
                            "",
                            oldLine.takeIf { oldSeen < range.oldCount },
                            newLine.takeIf { newSeen < range.newCount },
                        )
                        oldSeen++
                        newSeen++
                        oldLine++
                        newLine++
                    }

                    raw[0] == '+' -> {
                        body += DiffLine(DiffLineKind.ADDED, raw.substring(1), null, newLine.takeIf { newSeen < range.newCount })
                        newSeen++
                        newLine++
                    }

                    raw[0] == '-' -> {
                        body += DiffLine(DiffLineKind.REMOVED, raw.substring(1), oldLine.takeIf { oldSeen < range.oldCount }, null)
                        oldSeen++
                        oldLine++
                    }

                    raw[0] == '\\' -> {
                        // `\ No newline…` handled above; a stray backslash is content, kept as it is.
                        body += DiffLine(DiffLineKind.UNPARSED, raw.substring(1), null, null)
                    }

                    else -> {
                        // No marker at all. Some producers strip the leading space from context
                        // lines, and a line the parser cannot classify is kept rather than dropped.
                        val text = if (raw[0] == ' ') raw.substring(1) else raw
                        body += DiffLine(
                            DiffLineKind.CONTEXT,
                            text,
                            oldLine.takeIf { oldSeen < range.oldCount },
                            newLine.takeIf { newSeen < range.newCount },
                        )
                        oldSeen++
                        newSeen++
                        oldLine++
                        newLine++
                    }
                }
                index++
            }
            hunks += DiffHunk(
                heading = range.heading,
                oldStart = range.oldStart,
                oldCount = range.oldCount,
                newStart = range.newStart,
                newCount = range.newCount,
                lines = body,
            )
        }

        return ParsedFile(
            file = file,
            previousPath = previousPath,
            hunks = hunks,
            additions = additions,
            deletions = deletions,
            status = status,
            unparsed = unparsed,
        )
    }

    /**
     * Parses a multi-file patch, the shape a `git diff` of a whole tree has.
     *
     * A file boundary is `diff --git`, and the header that precedes it names the file; the entry
     * uses the server's own spelling when there is one, which is why [defaultFile] exists.
     */
    fun parseAll(patch: String, defaultFile: String = ""): List<ParsedFile> {
        if (patch.isBlank()) return emptyList()
        val blocks = mutableListOf<StringBuilder>()
        var current: StringBuilder? = null
        splitLines(patch).forEach { line ->
            if (line.startsWith(GIT_HEADER_PREFIX)) {
                current = StringBuilder().append(line).append('\n')
                blocks += current
            } else {
                current?.append(line)?.append('\n') ?: run { blocks += StringBuilder().append(line).append('\n') }
            }
        }
        return blocks.mapIndexed { index, block ->
            val text = block.toString().trimEnd('\n')
            val name = nameOf(text) ?: defaultFile
            val status = statusOf(text)
            val body = text.substringAfter('\n', text)
            parse(patch = body, file = name, status = status).let { parsed ->
                // The header lines carry the file name and the kind, which the single-file parser
                // consumes; the counts are counted here because a multi-file patch has no
                // `FileDiff.Info` to take them from.
                parsed.copy(
                    additions = parsed.lines.count { it.kind == DiffLineKind.ADDED },
                    deletions = parsed.lines.count { it.kind == DiffLineKind.REMOVED },
                )
            }
        }
    }

    /**
     * Splits a patch into lines, dropping the line endings and a terminal `\r`.
     *
     * A patch that does not end in a newline produces no trailing empty line, which is the point:
     * a hunk whose last line is a removed line without its newline is one line, not two.
     */
    internal fun splitLines(patch: String): List<String> {
        val out = mutableListOf<String>()
        var start = 0
        var index = 0
        while (index < patch.length) {
            when (patch[index]) {
                '\n' -> {
                    out += patch.substring(start, index).removeSuffix("\r")
                    start = index + 1
                }

                '\r' -> {
                    // A bare CR is a line ending in a patch written by a tool that uses them.
                    if (index + 1 < patch.length && patch[index + 1] == '\n') {
                        out += patch.substring(start, index)
                        start = index + 2
                        index++
                    } else {
                        out += patch.substring(start, index)
                        start = index + 1
                    }
                }

                else -> Unit
            }
            index++
        }
        if (start < patch.length) out += patch.substring(start)
        return out
    }

    /** The version-control header lines a `git diff` writes before its first hunk. */
    private fun String.isVersionControlHeader(): Boolean = VERSION_CONTROL_HEADERS.any { startsWith(it) }

    internal fun String.isBinaryMarker(): Boolean =
        startsWith("Binary files ") || startsWith("GIT binary patch")

    private fun String.isNoNewlineMarker(): Boolean = startsWith("\\ No newline")

    private fun headerPath(value: String): String? =
        value.trim().removePrefix("a/").removePrefix("b/").takeIf { it.isNotBlank() && it != "/dev/null" }

    /**
     * The file a `diff --git` header names.
     *
     * The header is `a/<old> b/<new>`, and a path may contain a space, so splitting on the first
     * space would truncate `a/my file.ts b/my file.ts` into nothing. The ` b/` marker is therefore
     * searched for first, and only a header without one falls back to a plain split — which is the
     * case a hand-written patch is in.
     */
    private fun nameOf(text: String): String? {
        val git = text.lineSequence().firstOrNull { it.startsWith(GIT_HEADER_PREFIX) } ?: return null
        val rest = git.removePrefix(GIT_HEADER_PREFIX)
        val marker = if (rest.startsWith("a/")) rest.indexOf(" b/") else -1
        val (left, right) = if (marker > 0) {
            rest.removePrefix("a/").substring(0, marker) to rest.substring(marker + 1)
        } else {
            val space = rest.indexOf(' ')
            if (space <= 0) return null
            rest.substring(0, space) to rest.substring(space + 1)
        }
        val newPath = right.removePrefix("b/")
        val oldPath = left.removePrefix("a/")
        return when {
            newPath == "/dev/null" -> oldPath
            newPath.isNotBlank() -> newPath
            else -> oldPath
        }
    }

    private fun statusOf(text: String): FileDiffStatus {
        val lines = text.lineSequence().take(HEADER_SCAN_LINES).toList()
        return when {
            lines.any { it.startsWith("new file mode") } -> FileDiffStatus.Added
            lines.any { it.startsWith("deleted file mode") } -> FileDiffStatus.Deleted
            else -> FileDiffStatus.Modified
        }
    }

    private const val GIT_HEADER_PREFIX = "diff --git "
    private const val HUNK_HEADER_PREFIX = "@@"

    private const val HEADER_RENAME_FROM = "rename from "
    private const val HEADER_OLD_FILE = "--- "

    /** How many leading lines of a file block are header lines rather than hunks. */
    private const val HEADER_SCAN_LINES = 8

    /**
     * The header lines `git diff` writes above the first `@@`.
     *
     * `--- ` is here *only* for the pre-hunk scan. Inside a hunk body `--- ` is a perfectly ordinary
     * removed line whose text is `-- something`, which is why the body loop terminates on `@@`
     * alone and never consults this list.
     */
    private val VERSION_CONTROL_HEADERS = listOf(
        "diff --git ",
        "index ",
        "--- ",
        "+++ ",
        "new file mode ",
        "deleted file mode ",
        "old mode ",
        "new mode ",
        "similarity index ",
        "dissimilarity index ",
        "rename from ",
        "rename to ",
        "copy from ",
        "copy to ",
    )
}

/**
 * The `@@ -a,b +c,d @@ heading` header.
 *
 * A producer may omit the counts when a side has one line (`@@ -3 +3 @@`), and a heading is
 * optional, so both are parsed rather than assumed. A header that does not parse is `null` and the
 * caller keeps the line, which is what makes the whole parse total.
 */
internal data class HunkHeader(
    val oldStart: Int,
    val oldCount: Int,
    val newStart: Int,
    val newCount: Int,
    val heading: String,
) {
    companion object {
        fun parse(line: String): HunkHeader? {
            if (!line.startsWith(HUNK_HEADER_PREFIX)) return null
            val body = line.removePrefix(HUNK_HEADER_PREFIX).substringBefore("@@")
            val parts = body.trim().split(' ')
            val old = parts.getOrNull(0) ?: return null
            val new = parts.getOrNull(1) ?: return null
            val oldRange = Range.parse(old.removePrefix("-")) ?: return null
            val newRange = Range.parse(new.removePrefix("+")) ?: return null
            return HunkHeader(
                oldStart = oldRange.start,
                oldCount = oldRange.count,
                newStart = newRange.start,
                newCount = newRange.count,
                // The heading is what follows the closing `@@`, and the line opens with one, so
                // both markers are consumed. `substringAfter` finds the first, hence the second.
                heading = line.substringAfter("@@", "").substringAfter("@@", "").trim(),
            )
        }

        private const val HUNK_HEADER_PREFIX = "@@ -"

        private data class Range(val start: Int, val count: Int) {
            companion object {
                fun parse(value: String): Range? {
                    val comma = value.indexOf(',')
                    val start = (if (comma < 0) value else value.substring(0, comma)).toIntOrNull() ?: return null
                    val count = if (comma < 0) 1 else value.substring(comma + 1).toIntOrNull() ?: return null
                    if (start < 0 || count < 0) return null
                    return Range(start, count)
                }
            }
        }
    }
}
