package dev.opencode.android.navigation

import androidx.annotation.StringRes
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import dev.opencode.android.R

/**
 * One command the command palette lists.
 *
 * [id] is what the graph dispatches on, and [labelRes] is the text the user reads. The model holds no
 * text, so the list can be built and filtered without a `Context`.
 */
data class PaletteCommand(val id: String, @StringRes val labelRes: Int)

/** The ids the palette's commands are dispatched by. Every id [paletteCommands] returns is handled in `OpenCodeApp`. */
object PaletteCommandIds {
    const val SERVERS = "servers"
    const val NEW_SESSION = "new_session"
    const val USAGE = "usage"
    const val QUICK_ASK = "quick_ask"
    const val RPC_CONSOLE = "rpc_console"
    const val PAIR_DEVICE = "pair_device"
    const val CHANGELOG = "changelog"
    const val EVENT_INSPECTOR = "event_inspector"
}

/**
 * The commands the palette lists, in the order it lists them.
 *
 * [hasActiveServer] decides which commands are offered. The commands that act on a server have no server
 * to act on without one, so they are left out rather than listed and then failing. Servers, Changelog and
 * the event inspector work with no server, so they are always listed. [isPairingAvailable] omits pairing
 * when the active server lacks pairing capability (e.g. POST /api/pair returned 404).
 */
fun paletteCommands(hasActiveServer: Boolean, isPairingAvailable: Boolean = true): List<PaletteCommand> = buildList {
    add(PaletteCommand(PaletteCommandIds.SERVERS, R.string.palette_servers))
    if (hasActiveServer) {
        add(PaletteCommand(PaletteCommandIds.NEW_SESSION, R.string.palette_new_session))
        add(PaletteCommand(PaletteCommandIds.USAGE, R.string.palette_usage))
        add(PaletteCommand(PaletteCommandIds.QUICK_ASK, R.string.palette_quick_ask))
        add(PaletteCommand(PaletteCommandIds.RPC_CONSOLE, R.string.palette_rpc_console))
        if (isPairingAvailable) {
            add(PaletteCommand(PaletteCommandIds.PAIR_DEVICE, R.string.palette_pair_device))
        }
    }
    add(PaletteCommand(PaletteCommandIds.CHANGELOG, R.string.palette_changelog))
    add(PaletteCommand(PaletteCommandIds.EVENT_INSPECTOR, R.string.palette_event_inspector))
}

/**
 * The commands whose label matches [query], best match first.
 *
 * The match is case-insensitive, and the query is trimmed. A label that starts with the query ranks first,
 * then one that contains it, then one that holds its letters in order with gaps (`qa` matches `Quick ask`).
 * Within a rank the commands keep the order they were given, so the list does not reshuffle as the user
 * types. An empty query returns every command in its own order.
 */
fun filterCommands(
    commands: List<PaletteCommand>,
    query: String,
    labelOf: (PaletteCommand) -> String,
): List<PaletteCommand> {
    val needle = query.trim().lowercase()
    if (needle.isEmpty()) return commands
    return commands
        .mapNotNull { command -> matchRank(labelOf(command).lowercase(), needle)?.let { command to it } }
        .sortedBy { (_, rank) -> rank }
        .map { (command, _) -> command }
}

/**
 * How well [needle] matches [label], lower is better, or `null` when it does not match at all.
 *
 * Both strings are already lowercase. A prefix is also a substring, so the prefix check comes first.
 */
private fun matchRank(label: String, needle: String): Int? = when {
    label.startsWith(needle) -> PREFIX
    label.contains(needle) -> SUBSTRING
    holdsInOrder(needle, label) -> SUBSEQUENCE
    else -> null
}

/** Whether every character of [needle] appears in [label] in the same order, with anything between them. */
private fun holdsInOrder(needle: String, label: String): Boolean {
    var matched = 0
    for (ch in label) {
        if (matched < needle.length && ch == needle[matched]) matched++
    }
    return matched == needle.length
}

private const val PREFIX = 0
private const val SUBSTRING = 1
private const val SUBSEQUENCE = 2

/**
 * Whether a key event is the palette's shortcut, Ctrl+P on its key-down.
 *
 * The parts are passed rather than the event, because a Compose key event cannot be built outside a
 * running UI and this decision has to be testable on its own. Only the key-down counts, so the release
 * does not open the palette a second time.
 */
fun isPaletteShortcut(key: Key, ctrl: Boolean, type: KeyEventType): Boolean =
    key == Key.P && ctrl && type == KeyEventType.KeyDown
