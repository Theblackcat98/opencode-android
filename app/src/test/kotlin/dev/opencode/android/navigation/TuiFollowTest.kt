package dev.opencode.android.navigation

import dev.opencode.android.core.data.tui.TuiControl
import dev.opencode.android.core.model.event.Event
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the TUI sends that the app takes in while following: session selections and prompt text, and none of either
 * once following is turned off. The merge of prompt text into a draft is checked here too, since it is the same file.
 *
 * The collector runs unconfined, so each event is delivered before the next line of the test runs. The module has no
 * coroutines-test, which is why this does not use a test dispatcher.
 */
class TuiFollowTest {

    private fun select(sessionID: String): Event = Event.decode(
        """{"id":"evt_1","type":"tui.session.select","created":1,"location":{"directory":"/work"},""" +
            """"data":{"sessionID":"$sessionID"}}""",
    )

    private fun prompt(text: String): Event = Event.decode(
        """{"id":"evt_2","type":"tui.prompt.append","created":1,"location":{"directory":"/work"},""" +
            """"data":{"text":"$text"}}""",
    )

    @Test
    fun `prompt text the TUI sends is taken in only while following is on`() = runBlocking {
        val tui = TuiControl()
        val appended = mutableListOf<String>()
        val job = launch(Dispatchers.Unconfined) {
            followedPromptAppends(tui).collect { appended += it.text }
        }

        tui.apply(prompt("typed before following"))
        assertEquals(emptyList<String>(), appended)

        tui.setFollowDesktop(true)
        tui.apply(prompt("typed while following"))
        assertEquals(listOf("typed while following"), appended)

        // Turning it off stops the taking-in at once: the next prompt text is not collected.
        tui.setFollowDesktop(false)
        tui.apply(prompt("typed after turning off"))
        assertEquals(listOf("typed while following"), appended)

        job.cancel()
    }

    @Test
    fun `an empty draft becomes exactly the appended text`() {
        assertEquals("ask about the build", appendedDraft("", "ask about the build"))
    }

    @Test
    fun `a non-empty draft gets one newline before the appended text`() {
        assertEquals("fix the\nbuild", appendedDraft("fix the", "build"))
    }

    @Test
    fun `a draft that already ends in whitespace gets no second separator`() {
        assertEquals("first line\nsecond", appendedDraft("first line\n", "second"))
        assertEquals("fix the build", appendedDraft("fix the ", "build"))
    }

    @Test
    fun `blank appended text leaves the draft exactly as it was`() {
        assertEquals("draft", appendedDraft("draft", ""))
        assertEquals("draft", appendedDraft("draft", "   "))
        assertEquals("", appendedDraft("", ""))
    }

    @Test
    fun `appended text keeps its own line breaks`() {
        assertEquals("notes\nline one\nline two", appendedDraft("notes", "line one\nline two"))
    }

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
