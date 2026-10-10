package dev.opencode.android.navigation

import dev.opencode.android.core.data.tui.TuiControl
import dev.opencode.android.core.model.event.Event
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which TUI session selections the app opens: those sent while following is on, and none once it is turned off.
 *
 * The collector runs unconfined, so each selection is delivered before the next line of the test runs. The module has
 * no coroutines-test, which is why this does not use a test dispatcher.
 */
class TuiFollowTest {

    private fun select(sessionID: String): Event = Event.decode(
        """{"id":"evt_1","type":"tui.session.select","created":1,"location":{"directory":"/work"},""" +
            """"data":{"sessionID":"$sessionID"}}""",
    )

    @Test
    fun `a session the TUI selects is opened only while following is on`() = runBlocking {
        val tui = TuiControl()
        val opened = mutableListOf<String>()
        val job = launch(Dispatchers.Unconfined) {
            followedSessionSelections(tui).collect { opened += it.sessionID }
        }

        tui.apply(select("ses_off"))
        assertEquals(emptyList<String>(), opened)

        tui.setFollowDesktop(true)
        tui.apply(select("ses_on"))
        assertEquals(listOf("ses_on"), opened)

        // Turning it off stops the opening at once: the next selection is not collected.
        tui.setFollowDesktop(false)
        tui.apply(select("ses_after_off"))
        assertEquals(listOf("ses_on"), opened)

        job.cancel()
    }
}
