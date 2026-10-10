package dev.opencode.android.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The breakpoint at which the session route draws the list beside the session. The composable itself is only seen on a
 * device; this is the decision it is drawn from.
 */
class SessionListPaneTest {

    @Test
    fun aWindowNarrowerThanTheBreakpointShowsTheSessionAlone() {
        assertFalse(sessionListShown(screenWidthDp = SESSION_LIST_MIN_WIDTH_DP - 1, sessionOnActiveServer = true))
        assertFalse(sessionListShown(screenWidthDp = 360, sessionOnActiveServer = true))
    }

    @Test
    fun aWindowAtTheBreakpointOrWiderShowsTheListBesideTheSession() {
        assertTrue(sessionListShown(screenWidthDp = SESSION_LIST_MIN_WIDTH_DP, sessionOnActiveServer = true))
        assertTrue(sessionListShown(screenWidthDp = 1280, sessionOnActiveServer = true))
    }

    @Test
    fun aSessionOnAnotherServerIsNeverShownBesideTheActiveServersList() {
        assertFalse(sessionListShown(screenWidthDp = 1280, sessionOnActiveServer = false))
    }

    @Test
    fun theBreakpointAndTheListLengthAreTheDocumentedOnes() {
        assertEquals(840, SESSION_LIST_MIN_WIDTH_DP)
        assertEquals(30, SESSION_LIST_MAX)
    }
}
