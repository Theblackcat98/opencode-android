package dev.opencode.android.core.designsystem.text

/**
 * Decides when a text field's own [androidx.compose.ui.text.input.TextFieldValue] should adopt the text a
 * view model publishes.
 *
 * A view model's state echoes every keystroke a little late: a `combine(…).stateIn(…)` by construction, and
 * even a plain `MutableStateFlow`, because `collectAsStateWithLifecycle` hands the write to the composition a
 * frame later. A field that copied `state.text` whenever it differed from its own text would, while the
 * user types quickly, be rewound to a stale echo and lose or reorder the characters typed in between. The
 * field therefore
 * remembers what *it* reported: an echo of one of those texts is the view model catching up and is
 * ignored, and only a text the field never reported — a completion, a history step, a restored draft, the
 * box cleared after a send — replaces what is on screen.
 */
class TextSync {
    private val reported = ArrayDeque<String>()

    /** The user changed the field to [text]. */
    fun onEdited(text: String) {
        if (reported.lastOrNull() != text) reported.addLast(text)
    }

    /**
     * The view model published [stateText] while the field shows [fieldText].
     *
     * Returns the text the field must adopt, or `null` when it should keep what it has.
     */
    fun onState(stateText: String, fieldText: String): String? {
        val echoed = reported.indexOf(stateText)
        if (echoed >= 0) {
            repeat(echoed + 1) { reported.removeFirst() }
            return null
        }
        reported.clear()
        return stateText.takeIf { it != fieldText }
    }
}
