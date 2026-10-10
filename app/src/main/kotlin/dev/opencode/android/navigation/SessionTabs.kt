package dev.opencode.android.navigation

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver

/** One session the user has open as a tab: the server it was opened on, and the session id. */
data class OpenSessionTab(val serverId: String, val sessionId: String)

/**
 * What closing a tab leaves the screen to do. [tabs] is the list after the close.
 */
sealed interface TabClose {
    val tabs: SessionTabs

    /** The closed tab was not the one on screen, so nothing is navigated to. */
    data class Stay(override val tabs: SessionTabs) : TabClose

    /** The closed tab was on screen, and [next] takes its place on the same server. */
    data class Open(override val tabs: SessionTabs, val next: OpenSessionTab) : TabClose

    /** The closed tab was on screen and the server has no other tab, so the screen goes to that server's home. */
    data class Home(override val tabs: SessionTabs, val serverId: String) : TabClose
}

/**
 * The session tabs of this app run: the sessions the user has opened, oldest first, at most [MAX] of them.
 *
 * **A new tab goes at the end, and opening a session that is already listed changes nothing.** Switching tabs
 * therefore never reorders the strip, so a tab stays where the user last saw it. When a ninth session is opened,
 * the tab at the front, which is the oldest, is dropped.
 *
 * The strip shows only the tabs of the active server (see [onServer]). A session id names a session on one
 * server, so a tab opened on another would open the wrong session.
 *
 * The list is plain data: the screen holds it, and [sessionTabsSaver] keeps it across a rotation.
 */
data class SessionTabs(val tabs: List<OpenSessionTab> = emptyList()) {

    /** The list with [tab] open: appended when it is new, and unchanged when it is already listed. */
    fun opened(tab: OpenSessionTab): SessionTabs =
        if (tab in tabs) this else copy(tabs = (tabs + tab).takeLast(MAX))

    /** The list without [tab], for a session that no longer exists. Nothing is navigated to. */
    fun forgotten(tab: OpenSessionTab): SessionTabs = copy(tabs = tabs - tab)

    /** The tabs opened on [serverId], in strip order. */
    fun onServer(serverId: String): List<OpenSessionTab> = tabs.filter { it.serverId == serverId }

    /**
     * Closes [tab], when [current] is the session on screen, and says where the screen goes.
     *
     * Closing the tab on screen opens the tab that slides into its place, which is the one after it, or the one
     * before it when it was the last. It goes to the server's home when no tab is left on that server. Closing a
     * tab that is not on screen only removes it.
     */
    fun closed(tab: OpenSessionTab, current: OpenSessionTab?): TabClose {
        val index = onServer(tab.serverId).indexOf(tab)
        val without = forgotten(tab)
        if (tab != current || index < 0) return TabClose.Stay(without)
        val remaining = without.onServer(tab.serverId)
        if (remaining.isEmpty()) return TabClose.Home(without, tab.serverId)
        return TabClose.Open(without, remaining.getOrElse(index) { remaining.last() })
    }

    companion object {
        /** How many tabs the strip keeps. */
        const val MAX = 8
    }
}

/**
 * Keeps the tabs across a rotation or a process death, as a flat list of strings: the server id and the session id
 * of each tab, in turn. A restored list is capped and de-duplicated, so a value saved by something else cannot grow
 * it past [SessionTabs.MAX].
 */
val sessionTabsSaver: Saver<SessionTabs, Any> = listSaver(
    save = { saved -> saved.tabs.flatMap { listOf(it.serverId, it.sessionId) } },
    restore = { flat ->
        val tabs = flat.chunked(2).filter { it.size == 2 }.map { OpenSessionTab(it[0], it[1]) }
        SessionTabs(tabs.distinct().takeLast(SessionTabs.MAX))
    },
)
