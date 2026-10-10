package dev.opencode.android.navigation

import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The session tabs: what opening, closing and forgetting a tab does to the list, and where the screen goes when the
 * tab on screen is closed. Every tab here is on one server unless a test says otherwise.
 */
class SessionTabsTest {

    private fun tab(session: String, server: String = "srv") = OpenSessionTab(server, session)

    private fun tabsOf(vararg sessions: String) = SessionTabs(sessions.map { tab(it) })

    @Test
    fun aNewSessionIsAppendedAtTheEnd() {
        val tabs = SessionTabs().opened(tab("a")).opened(tab("b")).opened(tab("c"))

        assertEquals(listOf(tab("a"), tab("b"), tab("c")), tabs.tabs)
    }

    @Test
    fun openingASessionAlreadyListedChangesNothing() {
        val before = tabsOf("a", "b", "c")

        // Re-entering a listed session, which is what a tab tap or a rotation does, must not reorder the strip.
        assertEquals(before, before.opened(tab("a")))
        assertEquals(before, before.opened(tab("c")))
    }

    @Test
    fun aNinthSessionEvictsTheOldest() {
        var tabs = SessionTabs()
        for (n in 1..SessionTabs.MAX) tabs = tabs.opened(tab("s$n"))
        assertEquals(SessionTabs.MAX, tabs.tabs.size)

        tabs = tabs.opened(tab("s9"))

        assertEquals(SessionTabs.MAX, tabs.tabs.size)
        assertEquals(tab("s2"), tabs.tabs.first())
        assertEquals(tab("s9"), tabs.tabs.last())
    }

    @Test
    fun evictionKeepsTheMostRecentlyOpenedEight() {
        var tabs = SessionTabs()
        for (n in 1..10) tabs = tabs.opened(tab("s$n"))

        assertEquals((3..10).map { "s$it" }, tabs.tabs.map { it.sessionId })
    }

    @Test
    fun theSameSessionOnTwoServersIsTwoTabs() {
        val tabs = SessionTabs().opened(tab("a", server = "one")).opened(tab("a", server = "two"))

        assertEquals(2, tabs.tabs.size)
    }

    @Test
    fun forgettingATabRemovesOnlyThatTab() {
        val tabs = tabsOf("a", "b", "c").forgotten(tab("b"))

        assertEquals(listOf(tab("a"), tab("c")), tabs.tabs)
    }

    @Test
    fun onServerKeepsOnlyThatServersTabsInOrder() {
        val tabs = SessionTabs(
            listOf(tab("a", "one"), tab("b", "two"), tab("c", "one")),
        )

        assertEquals(listOf(tab("a", "one"), tab("c", "one")), tabs.onServer("one"))
        assertTrue(tabs.onServer("three").isEmpty())
    }

    @Test
    fun closingABackgroundTabStaysWhereItIs() {
        val tabs = tabsOf("a", "b", "c")

        val closed = tabs.closed(tab("a"), current = tab("c"))

        assertEquals(TabClose.Stay(tabsOf("b", "c")), closed)
    }

    @Test
    fun closingTheTabOnScreenOpensTheOneAfterIt() {
        val tabs = tabsOf("a", "b", "c")

        val closed = tabs.closed(tab("b"), current = tab("b"))

        assertEquals(TabClose.Open(tabsOf("a", "c"), next = tab("c")), closed)
    }

    @Test
    fun closingTheFirstTabOnScreenOpensTheNewFirst() {
        val tabs = tabsOf("a", "b", "c")

        val closed = tabs.closed(tab("a"), current = tab("a"))

        assertEquals(TabClose.Open(tabsOf("b", "c"), next = tab("b")), closed)
    }

    @Test
    fun closingTheLastTabOnScreenOpensThePreviousOne() {
        val tabs = tabsOf("a", "b", "c")

        val closed = tabs.closed(tab("c"), current = tab("c"))

        assertEquals(TabClose.Open(tabsOf("a", "b"), next = tab("b")), closed)
    }

    @Test
    fun closingTheOnlyTabOnAServerGoesToThatServersHome() {
        val tabs = SessionTabs(listOf(tab("a", "one"), tab("b", "two")))

        val closed = tabs.closed(tab("a", "one"), current = tab("a", "one"))

        assertEquals(TabClose.Home(SessionTabs(listOf(tab("b", "two"))), serverId = "one"), closed)
    }

    @Test
    fun closingTheOnScreenTabWithAnotherServersTabsLeftGoesHome() {
        // The other server's tabs are not this server's strip, so they do not stop the screen going home.
        val tabs = SessionTabs(listOf(tab("a", "one"), tab("b", "two"), tab("c", "two")))

        val closed = tabs.closed(tab("a", "one"), current = tab("a", "one"))

        assertTrue(closed is TabClose.Home)
    }

    @Test
    fun closingAListedTabWhenNothingIsOnScreenOnlyRemovesIt() {
        val tabs = tabsOf("a", "b")

        assertEquals(TabClose.Stay(tabsOf("b")), tabs.closed(tab("a"), current = null))
    }

    @Test
    fun closingATabNotListedStaysAndChangesNothing() {
        val tabs = tabsOf("a", "b")

        assertEquals(TabClose.Stay(tabs), tabs.closed(tab("z"), current = tab("z")))
    }

    @Test
    fun theListSurvivesASaveAndRestore() {
        val tabs = SessionTabs(listOf(tab("a", "one"), tab("b", "two")))

        val saved = with(sessionTabsSaver) { SaverScope { true }.save(tabs) }
        val restored = sessionTabsSaver.restore(saved!!)

        assertEquals(tabs, restored)
    }

    @Test
    fun aRestoredListIsCappedAndDeduplicated() {
        val oversized = SessionTabs((1..12).map { tab("s$it") } + tab("s12"))

        val saved = with(sessionTabsSaver) { SaverScope { true }.save(oversized) }
        val restored = sessionTabsSaver.restore(saved!!)

        assertEquals(SessionTabs.MAX, restored!!.tabs.size)
        assertEquals((5..12).map { "s$it" }, restored.tabs.map { it.sessionId })
    }

    @Test
    fun anEmptyListSavesAsNothingSoTheRememberedDefaultIsEmpty() {
        // Compose saves an empty list as null, and rememberSaveable then starts from SessionTabs(), which is empty too.
        val saved = with(sessionTabsSaver) { SaverScope { true }.save(SessionTabs()) }

        assertNull(saved)
    }
}
