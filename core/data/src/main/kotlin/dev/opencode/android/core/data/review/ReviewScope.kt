package dev.opencode.android.core.data.review

/**
 * The four review scopes of the TUI's `/diff` (plan §6, "Review scopes"; features doc §28).
 *
 * The scope is the whole request, not a label: "Last turn" is a *session* diff between two
 * messages and the other three are *repository* diffs with a mode and an optional base. Modelling
 * it as one enum with a query-mode field would have every caller branch on a pair, so the
 * repository scopes and the turn scope are separate cases and the query the server takes is a
 * function of the case.
 */
sealed interface ReviewScope {

    /** A stable name for persistence and for the "keep my scope" preference. */
    val id: String

    /** `GET /api/vcs/diff`'s `mode`, or `null` for the scopes that are not a repository diff. */
    val mode: String?

    /** "Last turn": the turn of [from], optionally extended to [to]. */
    data class LastTurn(val from: String? = null, val to: String? = null) : ReviewScope {
        override val id: String get() = "last-turn"
        override val mode: String? get() = null
    }

    /** "Uncommitted": HEAD to the working copy. */
    data object Uncommitted : ReviewScope {
        override val id: String get() = "uncommitted"
        override val mode: String get() = VcsDiffMode.Working
    }

    /** "Committed": the merge base to HEAD. */
    data object Committed : ReviewScope {
        override val id: String get() = "committed"
        override val mode: String get() = VcsDiffMode.Committed
    }

    /** "All": the merge base to the working copy. */
    data object All : ReviewScope {
        override val id: String get() = "all"
        override val mode: String get() = VcsDiffMode.Branch
    }

    companion object {
        /** The four scopes in the order the TUI lists them. */
        val ALL: List<ReviewScope> = listOf(LastTurn(), Uncommitted, Committed, All)

        /** The scopes a repository-level review can take, for a directory that is not a session. */
        val REPOSITORY: List<ReviewScope> = listOf(Uncommitted, Committed, All)

        fun of(id: String): ReviewScope = ALL.firstOrNull { it.id == id } ?: Uncommitted

        /** Whether the scope needs a base branch, which is what the base picker is for. */
        val needsBase: Boolean get() = true
    }
}

/**
 * The `mode` of `GET /api/vcs/diff`, spelled as the server spells it (schema `Vcs.Mode`).
 *
 * Kept as constants rather than as a Kotlin enum for the reason every other V2 string union in this
 * codebase is: a value a newer server adds must decode and be passable through, not crash.
 */
object VcsDiffMode {
    /** HEAD to the working copy. */
    const val Working: String = "working"

    /** The merge base to HEAD. */
    const val Committed: String = "committed"

    /** The merge base to the working copy. */
    const val Branch: String = "branch"
}
