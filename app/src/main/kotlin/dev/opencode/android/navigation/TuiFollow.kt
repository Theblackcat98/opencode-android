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
