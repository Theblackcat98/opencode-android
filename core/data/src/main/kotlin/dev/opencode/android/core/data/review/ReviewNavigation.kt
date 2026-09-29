package dev.opencode.android.core.data.review

/**
 * Where the user is inside a review, and what they have already looked at.
 *
 * **Navigation is a value, not a cursor.** "Next file", "previous hunk" and "open this file" are
 * three questions with the same answer shape, and answering them as arithmetic on list indices
 * spreads the same off-by-one into three places. So the position is a pair of keys — the file and
 * the hunk inside it — and every move is a function of the review's own contents, which makes each
 * of them a two-line case to test.
 *
 * **Wrapping is explicit, because a reviewer at the end of a diff does not mean "stop".** [next]
 * and [previous] wrap, and the tests say so; a review of one file therefore has a next file that is
 * the same file, which is why the UI can disable the buttons on a single-file review instead of
 * surprising the user with a wrap.
 */
data class ReviewPosition(
    val file: String? = null,
    val hunk: Int = 0,
) {
    val hasFile: Boolean get() = file != null

    /** The same position with [hunk] clamped, for a file that shrank while it was open. */
    fun clampedTo(files: List<ParsedFile>): ReviewPosition {
        val target = file ?: return this
        val index = files.indexOfFirst { it.file == target }
        if (index < 0) return ReviewPosition(file = null, hunk = 0)
        val hunks = files[index].hunks.size
        return copy(hunk = hunk.coerceIn(0, (hunks - 1).coerceAtLeast(0)))
    }
}

/** One place the review can go: a file, and optionally a hunk in it. */
data class ReviewTarget(val file: String, val hunk: Int = 0)

/**
 * The whole navigation rule set, as functions over the review's contents.
 *
 * Pure and total: every function takes the files it navigates and a position, and returns a
 * position. A review that is being re-fetched underneath the user cannot corrupt a position this
 * way, because a position is not an index into a mutable list — it is a pair of keys that is
 * re-clamped whenever the contents change.
 */
object ReviewNavigator {

    /** The position a review opens at: the first file, its first hunk. */
    fun start(files: List<ParsedFile>): ReviewPosition =
        files.firstOrNull()?.let { ReviewPosition(file = it.file, hunk = 0) } ?: ReviewPosition()

    /** Whether a file exists in the review, which is what enables the file buttons. */
    fun hasFiles(files: List<ParsedFile>): Boolean = files.isNotEmpty()

    /** Whether the review moves at all, which is what a single-file viewer uses to hide its arrows. */
    fun hasSeveralFiles(files: List<ParsedFile>): Boolean = files.size > 1

    /** The next file, wrapping. */
    fun nextFile(files: List<ParsedFile>, from: ReviewPosition): ReviewPosition {
        if (files.isEmpty()) return ReviewPosition()
        val index = files.indexOfFirst { it.file == from.file }
        val next = if (index < 0) 0 else (index + 1) % files.size
        return ReviewPosition(file = files[next].file, hunk = 0)
    }

    /** The previous file, wrapping. */
    fun previousFile(files: List<ParsedFile>, from: ReviewPosition): ReviewPosition {
        if (files.isEmpty()) return ReviewPosition()
        val index = files.indexOfFirst { it.file == from.file }
        val previous = if (index <= 0) files.size - 1 else index - 1
        return ReviewPosition(file = files[previous].file, hunk = 0)
    }

    /**
     * The next hunk, which crosses into the next file when the current one runs out.
     *
     * Crossing files rather than stopping is the TUI's behaviour and the useful one: a reviewer
     * working through a review wants the next thing to read, wherever it is.
     */
    fun nextHunk(files: List<ParsedFile>, from: ReviewPosition): ReviewPosition {
        val current = files.firstOrNull { it.file == from.file } ?: return start(files)
        val hunks = current.hunks.size
        return if (hunks > 0 && from.hunk + 1 < hunks) {
            ReviewPosition(file = current.file, hunk = from.hunk + 1)
        } else {
            val next = nextFile(files, from)
            if (next.file == current.file) next else next
        }
    }

    /** The previous hunk, crossing backwards into the previous file's last hunk. */
    fun previousHunk(files: List<ParsedFile>, from: ReviewPosition): ReviewPosition {
        val current = files.firstOrNull { it.file == from.file } ?: return start(files)
        if (from.hunk > 0) return ReviewPosition(file = current.file, hunk = from.hunk - 1)
        val previousFile = previousFile(files, from)
        val previous = files.firstOrNull { it.file == previousFile.file } ?: return from
        val hunk = (previous.hunks.size - 1).coerceAtLeast(0)
        return ReviewPosition(file = previous.file, hunk = hunk)
    }

    /**
     * The first file that is not marked reviewed, and its first hunk.
     *
     * This is the "jump to the next unreviewed thing" the TUI does when a review opens, and it is
     * why [ReviewedFiles] is part of the viewer's state rather than a decoration: without it, a
     * re-opened review starts at the top of files the user has already dealt with.
     */
    fun nextUnreviewed(files: List<ParsedFile>, reviewed: ReviewedFiles): ReviewPosition {
        val pending = files.firstOrNull { !reviewed.contains(it.key) } ?: files.firstOrNull()
        return pending?.let { ReviewPosition(file = it.file, hunk = 0) } ?: ReviewPosition()
    }

    /**
     * Whether the whole review is done.
     *
     * A file that cannot be shown — a binary one — counts as reviewed when the user says the review
     * is done, because "I cannot look at this" and "I have not looked at this" are the same state
     * for a progress count and different for a user.
     */
    fun isComplete(files: List<ParsedFile>, reviewed: ReviewedFiles): Boolean =
        files.isNotEmpty() && files.all { reviewed.contains(it.key) }
}

/**
 * The files the user has marked reviewed, kept on the device.
 *
 * **A set of keys, not a set of paths.** A review scope can contain the same file twice — a rename
 * shows the old and the new path, and `session.diff` over a range of turns can list a file once per
 * turn — so the key includes the change's status. What the user marked is the change, and the
 * change is what a progress count is about.
 */
class ReviewedFiles(initial: Set<String> = emptySet()) {
    private val keys: MutableSet<String> = initial.toMutableSet()

    operator fun contains(key: String): Boolean = key in keys

    fun add(key: String) {
        keys += key
    }

    fun remove(key: String) {
        keys -= key
    }

    /** Marks or unmarks, which is what a toggle does. */
    fun toggle(key: String): Boolean = if (key in keys) {
        keys -= key
        false
    } else {
        keys += key
        true
    }

    /** A copy with [key] marked or not, so a view model can publish the change as state. */
    fun with(key: String, reviewed: Boolean): ReviewedFiles =
        ReviewedFiles(if (reviewed) keys + key else keys - key)

    fun toSet(): Set<String> = keys.toSet()

    val size: Int get() = keys.size

    fun clear() = keys.clear()

    override fun equals(other: Any?): Boolean = this === other || (other is ReviewedFiles && keys == other.keys)

    override fun hashCode(): Int = keys.hashCode()
}
