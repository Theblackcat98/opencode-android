package dev.opencode.android.core.data.tui

import dev.opencode.android.core.model.event.Event
import dev.opencode.android.core.model.event.EventPayload
import dev.opencode.android.core.model.json.OpenCodeJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The TUI control events, which are the one place the app obeys another client.
 *
 * Every assertion here is about a decision the app makes on its own: a toast it will show, a prompt
 * it will or will not take, a command it will or will not perform without the user watching. None
 * of them changes server state, so all of it is testable without a server.
 */
class TuiControlTest {

    private val control = TuiControl()

    private fun event(type: String, data: String, directory: String? = "/work"): Event {
        val envelope = buildJsonObject {
            put("id", "evt_1")
            put("type", type)
            put("created", 1_700_000_000_000L)
            directory?.let { put("location", buildJsonObject { put("directory", it) }) }
            put("data", Json.parseToJsonElement(data))
        }
        return Event.decode(envelope.toString())
    }

    @Test
    fun aToastBecomesASnackbarAtTheServersLevel() = runTest {
        val seen = mutableListOf<TuiControl.Toast>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { control.toasts.collect { seen += it } }

        control.apply(event("tui.toast.show", """{"title":"Done","message":"Saved","variant":"success"}"""))
        control.apply(event("tui.toast.show", """{"message":"Could not save","variant":"error"}"""))
        // A variant this build has never heard of still gets the message, styled as information.
        control.apply(event("tui.toast.show", """{"message":"Something new","variant":"shout"}"""))

        assertEquals(3, seen.size)
        assertEquals(TuiControl.ToastLevel.SUCCESS, seen[0].level)
        assertEquals("Done", seen[0].title)
        assertEquals("Could not save", seen[1].message)
        assertEquals(TuiControl.ToastLevel.ERROR, seen[1].level)
        assertEquals(TuiControl.ToastLevel.INFO, seen[2].level)
        assertEquals("/work", seen[0].directory)
        job.cancel()
    }

    @Test
    fun promptTextIsIgnoredUntilFollowDesktopIsOn() = runTest {
        val seen = mutableListOf<TuiControl.PromptAppend>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { control.promptAppends.collect { seen += it } }

        control.apply(event("tui.prompt.append", """{"text":"typed on the desktop"}"""))
        assertTrue("a prompt must not be taken while following is off", seen.isEmpty())

        control.setFollowDesktop(true)
        control.apply(event("tui.prompt.append", """{"text":"typed on the desktop"}"""))
        assertEquals(listOf("typed on the desktop"), seen.map { it.text })

        // Turning it back off stops it again, and drops what was queued rather than applying it late.
        seen.clear()
        control.setFollowDesktop(false)
        control.apply(event("tui.prompt.append", """{"text":"later"}"""))
        assertTrue(seen.isEmpty())
        job.cancel()
    }

    @Test
    fun sessionSelectionFollowsTheSameRule() = runTest {
        val seen = mutableListOf<TuiControl.SessionSelect>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { control.sessionSelections.collect { seen += it } }

        control.apply(event("tui.session.select", """{"sessionID":"ses_1"}"""))
        assertTrue(seen.isEmpty())

        control.setFollowDesktop(true)
        control.apply(event("tui.session.select", """{"sessionID":"ses_1"}"""))
        assertEquals(listOf("ses_1"), seen.map { it.sessionID })
        job.cancel()
    }

    @Test
    fun aCommandThatChangesTheServerIsNotPerformedOnItsOwn() = runTest {
        val seen = mutableListOf<TuiControl.Command>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { control.commands.collect { seen += it } }

        control.apply(event("tui.command.execute", """{"command":"session.interrupt"}"""))
        control.apply(event("tui.command.execute", """{"command":"session.compact"}"""))
        control.apply(event("tui.command.execute", """{"command":"session.page.up"}"""))
        control.apply(event("tui.command.execute", """{"command":"session.list"}"""))

        val byName = seen.associateBy { it.name }
        assertFalse("interrupting a turn is not the app's call", byName.getValue("session.interrupt").isSafeToPerform)
        assertFalse(byName.getValue("session.compact").isSafeToPerform)
        assertTrue(byName.getValue("session.page.up").isSafeToPerform)
        assertTrue(byName.getValue("session.list").isSafeToPerform)
        job.cancel()
    }

    /**
     * The published command list is a union with `(string & {})`, so a newer TUI sends names this
     * build has never seen. Dropping them would make "the desktop did something" invisible.
     */
    @Test
    fun anUnknownCommandIsKeptAndNamed() = runTest {
        val seen = mutableListOf<TuiControl.Command>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { control.commands.collect { seen += it } }

        control.apply(event("tui.command.execute", """{"command":"session.teleport"}"""))

        assertEquals(1, seen.size)
        assertEquals("session.teleport", seen.single().name)
        assertEquals(TuiControl.AppAction.UNKNOWN, seen.single().action)
        assertFalse(seen.single().isSafeToPerform)
        job.cancel()
    }

    @Test
    fun everyScrollCommandMapsToAScrollAction() {
        val scrolling = listOf(
            "session.page.up" to TuiControl.AppAction.SCROLL_UP,
            "session.page.down" to TuiControl.AppAction.SCROLL_DOWN,
            "session.line.up" to TuiControl.AppAction.SCROLL_UP,
            "session.line.down" to TuiControl.AppAction.SCROLL_DOWN,
            "session.half.page.up" to TuiControl.AppAction.SCROLL_UP,
            "session.half.page.down" to TuiControl.AppAction.SCROLL_DOWN,
            "session.first" to TuiControl.AppAction.SCROLL_TO_TOP,
            "session.last" to TuiControl.AppAction.SCROLL_TO_BOTTOM,
        )
        for ((name, action) in scrolling) {
            assertEquals(name, action, TuiControl.AppAction.of(name))
            assertTrue(name, TuiControl.AppAction.of(name).performsServerChange.not())
        }
    }

    @Test
    fun everyWriteCommandIsMarkedAsOne() {
        val writes = listOf(
            "session.interrupt",
            "session.background",
            "session.compact",
            "prompt.submit",
        )
        for (name in writes) {
            assertTrue(name, TuiControl.AppAction.of(name).performsServerChange)
        }
    }

    @Test
    fun pluginRpcEventsAreKeptForTheViewer() {
        control.apply(event("rpc.git.status", """{"clean":true,"branch":"main"}"""))
        control.apply(event("rpc.ci.finished", """{"ok":true}"""))

        val events = control.rpcEvents.value
        assertEquals(2, events.size)
        assertEquals("git", events[0].rpcID)
        assertEquals("status", events[0].event)
        assertEquals("rpc.git.status", events[0].type)
        assertEquals("ci", events[1].rpcID)
        assertEquals("finished", events[1].event)
    }

    @Test
    fun theRpcHistoryIsBounded() {
        repeat(TuiControl.RPC_HISTORY + 25) { control.apply(event("rpc.p.tick", """{"n":$it}""")) }
        val events = control.rpcEvents.value
        assertEquals(TuiControl.RPC_HISTORY, events.size)
        // The oldest are the ones dropped, so the newest is the last one that arrived.
        assertEquals(TuiControl.RPC_HISTORY + 24L, events.last().data.getValue("n").toString().toLong())
    }

    @Test
    fun anEventThatIsNotATuiControlIsNotClaimed() {
        assertFalse(control.apply(event("session.idle", """{"sessionID":"ses_1"}""")))
        assertFalse(control.apply(event("server.connected", "{}")))
        assertFalse(
            control.apply(
                event(
                    "session.text.delta",
                    """{"sessionID":"ses_1","assistantMessageID":"msg_1","ordinal":0,"delta":"hi"}""",
                ),
            ),
        )
    }

    @Test
    fun anRpcEventDecodesToTheTypedPayload() {
        val decoded = event("rpc.plugin.statusChanged", """{"clean":true}""")
        assertTrue(decoded.payload is EventPayload.Rpc)
        val rpc = decoded.payload as EventPayload.Rpc
        assertEquals("plugin", rpc.rpcID)
        assertEquals("statusChanged", rpc.event)
        assertTrue(OpenCodeJson.encodeToString(Event.serializer(), decoded).contains("rpc.plugin.statusChanged"))
    }
}
