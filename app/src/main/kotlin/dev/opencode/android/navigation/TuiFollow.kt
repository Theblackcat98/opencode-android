package dev.opencode.android.navigation

import dev.opencode.android.core.data.tui.TuiControl
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest

/**
 * The TUI's session selections while the app follows the desktop, and nothing at all otherwise.
 *
 * **Turning follow-desktop off stops the navigation at once.** The selections are collected inside `flatMapLatest` on
 * the setting, so when the setting goes false the subscription to the TUI's selections is cancelled, and nothing is
 * collected until it is on again. The `filter` reads the setting again on delivery, because a selection the TUI sent
 * while following was on can still be waiting in the flow's buffer when the user turns it off.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun followedSessionSelections(tui: TuiControl): Flow<TuiControl.SessionSelect> =
    tui.followDesktop.flatMapLatest { following ->
        if (following) tui.sessionSelections.filter { tui.followDesktop.value } else emptyFlow()
    }

/**
 * The text the TUI put in its composer while the app follows the desktop, and nothing at all otherwise.
 *
 * The same gate as [followedSessionSelections], and for the same two reasons: the subscription is cancelled when the
 * setting goes false, and the `filter` reads the setting again on delivery, so text buffered while following was on is
 * not added once the user has turned it off.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun followedPromptAppends(tui: TuiControl): Flow<TuiControl.PromptAppend> =
    tui.followDesktop.flatMapLatest { following ->
        if (following) tui.promptAppends.filter { tui.followDesktop.value } else emptyFlow()
    }

/**
 * The draft after the TUI's [appended] text is added to the end of it.
 *
 * **The TUI's text is added after the draft, never over it.** An empty draft becomes exactly the appended text. A draft
 * that already ends in whitespace takes the appended text as it is. Any other draft gets one newline between the two, so
 * the TUI's words do not run into the last word typed on the phone. Blank appended text changes nothing, so an empty
 * append does not leave a separator behind.
 */
internal fun appendedDraft(draft: String, appended: String): String = when {
    appended.isBlank() -> draft
    draft.isEmpty() -> appended
    draft.last().isWhitespace() -> draft + appended
    else -> "$draft\n$appended"
}
