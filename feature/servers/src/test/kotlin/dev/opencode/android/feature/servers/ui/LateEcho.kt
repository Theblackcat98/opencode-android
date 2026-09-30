package dev.opencode.android.feature.servers.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Stands in for a view model whose text state echoes each keystroke late.
 *
 * A real one does this by construction: the field reports an edit, the view model stores it, and the
 * screen reads it back through `collectAsStateWithLifecycle`, which hands it over on a later frame. By
 * then the user has typed more, so a field that shows what the state holds shows an older text than its
 * own. Here the delay is the test's to set: every edit the screen reports is queued, and [text], the only
 * thing the screen reads, changes when the test publishes one.
 */
internal class LateEcho(initial: String = "") {
    private val unpublished = ArrayDeque<String>()

    /** What the screen is told the text is. */
    var text by mutableStateOf(initial)
        private set

    /** The last text the screen reported, which is what a real view model holds and what Save would send. */
    var lastReported: String = initial
        private set

    /** Number of reported edits the screen has not been told about yet. */
    val pending: Int get() = unpublished.size

    /** The `onValueChange` the screen is given. */
    fun report(edited: String) {
        lastReported = edited
        unpublished.addLast(edited)
    }

    /** The oldest edit the screen has not been told about reaches it. */
    fun publishOldest() {
        unpublished.removeFirstOrNull()?.let { text = it }
    }

    /** Only the newest edit reaches the screen, as when a `StateFlow` conflates the ones in between. */
    fun publishNewest() {
        val newest = unpublished.lastOrNull() ?: return
        unpublished.clear()
        text = newest
    }

    /** A text the screen never reported, as a scan, a shared link or the clipboard button supplies. */
    fun replaceFromOutside(replacement: String) {
        unpublished.clear()
        lastReported = replacement
        text = replacement
    }
}
