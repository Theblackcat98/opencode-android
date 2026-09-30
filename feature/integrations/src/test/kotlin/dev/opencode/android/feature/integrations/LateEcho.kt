package dev.opencode.android.feature.integrations

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Stands in for a view model whose state echoes each edit late.
 *
 * A real one does this by construction: the field reports an edit, the view model stores it, and the
 * screen reads it back through `collectAsStateWithLifecycle`, which hands it over on a later frame. By
 * then the user has typed more, so a field that shows what the state holds shows an older text than its
 * own. Here the delay is the test's to set: every edit the screen reports is queued, and [value], the only
 * thing the screen reads, changes when the test publishes one.
 *
 * [T] is the text itself for a field of its own, or the whole draft for a form that reports a draft.
 */
internal class LateEcho<T>(initial: T) {
    private val unpublished = ArrayDeque<T>()

    /** What the screen is told the state is. */
    var value: T by mutableStateOf(initial)
        private set

    /** The last state the screen reported, which is what a real view model holds and what Save would send. */
    var lastReported: T = initial
        private set

    /** Number of reported edits the screen has not been told about yet. */
    val pending: Int get() = unpublished.size

    /** The `onValueChange` the screen is given. */
    fun report(edited: T) {
        lastReported = edited
        unpublished.addLast(edited)
    }

    /** The oldest edit the screen has not been told about reaches it. */
    fun publishOldest() {
        unpublished.removeFirstOrNull()?.let { value = it }
    }
}
