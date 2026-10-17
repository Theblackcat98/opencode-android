package dev.opencode.android.navigation

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The palette's matching, its shortcut and the list it offers. These are the parts that decide what the user
 * sees and what Ctrl+P does, and none of them needs a composition: the dialog is driven from the caller.
 *
 * Each test command's `labelRes` is its index into [labels], so the label function can be a plain lookup.
 */
class CommandPaletteTest {

    private val labels = listOf(
        "Servers",
        "New session",
        "Usage",
        "Quick ask",
        "RPC console",
        "Pair another device",
        "Changelog",
        "Event inspector",
    )
    private val commands = labels.mapIndexed { index, _ -> PaletteCommand(id = "c$index", labelRes = index) }
    private val labelOf: (PaletteCommand) -> String = { labels[it.labelRes] }

    private fun labelsOf(matches: List<PaletteCommand>): List<String> = matches.map(labelOf)

    @Test
    fun `an empty query returns every command in its own order`() {
        assertEquals(commands, filterCommands(commands, "", labelOf))
        assertEquals(commands, filterCommands(commands, "   ", labelOf))
    }

    @Test
    fun `the match ignores case`() {
        assertEquals(listOf("New session"), labelsOf(filterCommands(commands, "NEW SESSION", labelOf)))
        assertEquals(listOf("Quick ask"), labelsOf(filterCommands(commands, "quick ASK", labelOf)))
    }

    @Test
    fun `the query is trimmed before it is matched`() {
        assertEquals(listOf("Usage"), labelsOf(filterCommands(commands, "  usage  ", labelOf)))
    }

    @Test
    fun `a prefix match ranks before a substring match even when it comes later in the list`() {
        val items = listOf("Show device info", "Device settings", "Undevice")
        val list = items.mapIndexed { index, _ -> PaletteCommand(id = "i$index", labelRes = index) }
        val found = filterCommands(list, "device") { items[it.labelRes] }
        assertEquals(listOf("Device settings", "Show device info", "Undevice"), found.map { items[it.labelRes] })
    }

    @Test
    fun `substring matches before letters-in-order matches`() {
        val items = listOf("Quick ask", "Xqa", "Qa tools")
        val list = items.mapIndexed { index, _ -> PaletteCommand(id = "i$index", labelRes = index) }
        val found = filterCommands(list, "qa") { items[it.labelRes] }
        // "Qa tools" starts with the query, "Xqa" contains it, and "Quick ask" only holds q then a in order.
        assertEquals(listOf("Qa tools", "Xqa", "Quick ask"), found.map { items[it.labelRes] })
    }

    @Test
    fun `within one rank the commands keep the order they were given`() {
        val items = listOf("Xdevice a", "Ydevice b", "Zdevice c")
        val list = items.mapIndexed { index, _ -> PaletteCommand(id = "i$index", labelRes = index) }
        val found = filterCommands(list, "device") { items[it.labelRes] }
        assertEquals(items, found.map { items[it.labelRes] })
    }

    @Test
    fun `letters held in order with gaps match`() {
        assertEquals(listOf("Quick ask"), labelsOf(filterCommands(commands, "qa", labelOf)))
    }

    @Test
    fun `a query that matches no label returns nothing`() {
        assertTrue(filterCommands(commands, "zzz", labelOf).isEmpty())
        // The letters are all there, but not in order.
        assertTrue(filterCommands(commands, "ksa", labelOf).none { labelOf(it) == "Quick ask" })
    }

    @Test
    fun `the shortcut is Ctrl+P on its key-down`() {
        assertTrue(isPaletteShortcut(Key.P, ctrl = true, type = KeyEventType.KeyDown))
    }

    @Test
    fun `the shortcut is refused without Ctrl, on a key-up, or for another key`() {
        assertFalse(isPaletteShortcut(Key.P, ctrl = false, type = KeyEventType.KeyDown))
        assertFalse(isPaletteShortcut(Key.P, ctrl = true, type = KeyEventType.KeyUp))
        assertFalse(isPaletteShortcut(Key.Q, ctrl = true, type = KeyEventType.KeyDown))
        assertFalse(isPaletteShortcut(Key.Enter, ctrl = true, type = KeyEventType.KeyDown))
    }

    @Test
    fun `with no active server only the commands that need none are offered`() {
        val ids = paletteCommands(hasActiveServer = false).map { it.id }
        assertEquals(
            listOf(
                PaletteCommandIds.SERVERS,
                PaletteCommandIds.CHANGELOG,
                PaletteCommandIds.EVENT_INSPECTOR,
            ),
            ids,
        )
    }

    @Test
    fun `with an active server every command is offered, in the order the palette shows them`() {
        val ids = paletteCommands(hasActiveServer = true).map { it.id }
        assertEquals(
            listOf(
                PaletteCommandIds.SERVERS,
                PaletteCommandIds.NEW_SESSION,
                PaletteCommandIds.USAGE,
                PaletteCommandIds.QUICK_ASK,
                PaletteCommandIds.RPC_CONSOLE,
                PaletteCommandIds.PAIR_DEVICE,
                PaletteCommandIds.CHANGELOG,
                PaletteCommandIds.EVENT_INSPECTOR,
            ),
            ids,
        )
    }

    @Test
    fun `with an active server lacking pairing capability pair_device is omitted`() {
        val ids = paletteCommands(hasActiveServer = true, isPairingAvailable = false).map { it.id }
        assertEquals(
            listOf(
                PaletteCommandIds.SERVERS,
                PaletteCommandIds.NEW_SESSION,
                PaletteCommandIds.USAGE,
                PaletteCommandIds.QUICK_ASK,
                PaletteCommandIds.RPC_CONSOLE,
                PaletteCommandIds.CHANGELOG,
                PaletteCommandIds.EVENT_INSPECTOR,
            ),
            ids,
        )
    }

    @Test
    fun `no command is listed twice and every label resource is distinct`() {
        val all = paletteCommands(hasActiveServer = true)
        assertEquals(all.size, all.map { it.id }.toSet().size)
        assertEquals(all.size, all.map { it.labelRes }.toSet().size)
    }
}
