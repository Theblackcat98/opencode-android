package dev.opencode.android.navigation

import dev.opencode.android.core.data.tui.TuiControl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * What a TUI command is shown as. The templates below repeat the two sentences in `res/values/strings.xml`, because a
 * JVM test cannot read the resources; the test pins the shape, and the resource is what the app shows.
 */
class TuiCommandsTest {

    private fun offer(name: String): CommandOffer =
        commandOffer(TuiControl.Command.of(name, directory = null), UNKNOWN, OFFERED)

    @Test
    fun `an unknown command is shown with its name exactly as the server sent it`() {
        val offer = offer("session.teleport")
        assertEquals("The TUI asked for: session.teleport", offer.text)
        assertFalse(offer.performable)
    }

    @Test
    fun `the name of an unknown command is shown literally, not read as a format pattern`() {
        assertEquals("The TUI asked for: plugin.%d.run%n", offer("plugin.%d.run%n").text)
    }

    @Test
    fun `a known command that changes the server is shown as a message and is not performed`() {
        val offer = offer("session.interrupt")
        assertEquals("The TUI asked to run session.interrupt. Nothing was run.", offer.text)
        assertFalse(offer.performable)
    }

    @Test
    fun `a known safe command is shown as a message too, because performing one is not built`() {
        val offer = offer("session.page.up")
        assertEquals("The TUI asked to run session.page.up. Nothing was run.", offer.text)
        assertFalse(offer.performable)
    }

    @Test
    fun `no command in this build is performable, whatever its action`() {
        TuiControl.AppAction.entries.forEach { action ->
            val command = TuiControl.Command(action.name, action, directory = null)
            assertFalse(action.name, commandOffer(command, UNKNOWN, OFFERED).performable)
        }
    }

    private companion object {
        const val UNKNOWN = "The TUI asked for: %1\$s"
        const val OFFERED = "The TUI asked to run %1\$s. Nothing was run."
    }
}
