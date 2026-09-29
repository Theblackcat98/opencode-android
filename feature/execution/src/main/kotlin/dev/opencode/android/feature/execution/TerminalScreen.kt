package dev.opencode.android.feature.execution

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.opencode.android.core.data.terminal.ExtraKeys
import dev.opencode.android.core.data.terminal.TerminalBridgeMessage
import dev.opencode.android.core.data.terminal.TerminalGrid
import dev.opencode.android.core.data.terminal.TerminalInput
import dev.opencode.android.core.data.terminal.TerminalKey
import dev.opencode.android.core.network.PtyStreamState

/**
 * The terminal screen (plan §6, "PTY terminals").
 *
 * **Four parts, and the three that matter are not the drawing.** The list of terminals, the live
 * surface, the extra-keys row and the state header. The live surface is a [TerminalWebView] because the
 * plan asks for xterm.js "with full VT fidelity matching the web app", and hand-writing a VT emulator
 * on a phone is not a smaller job than the one xterm.js already does. Everything *around* it — the
 * grid arithmetic, the resize, the key encoding, the connection state — is this client's, and is
 * testable without a WebView, which is why it lives here and not in the page.
 *
 * **The extra-keys row is horizontal and scrollable, and the page is not.** The plan's row (Esc, Tab,
 * Ctrl, Alt, arrows, `|`, `~`, `/`) is wider than a phone in portrait at a readable size, and a row
 * that clips is a row missing the keys a terminal needs. The page, by contrast, must not scroll: a
 * terminal that scrolls the document loses the bottom line, which is the line being typed on.
 */
@Composable
fun TerminalScreen(
    state: TerminalUiState,
    channel: TerminalChannel,
    onOpenTerminal: (String) -> Unit,
    onNewTerminal: () -> Unit,
    onCreate: (String?, List<String>?) -> Unit,
    onClosePicker: () -> Unit,
    onRunProjectStart: () -> Unit,
    onRequestTicket: () -> Unit,
    onKeys: (List<TerminalKey>) -> Unit,
    onBridgeMessage: (TerminalBridgeMessage) -> Unit,
    onOutputConsumed: (Int) -> Unit,
    onReconnect: () -> Unit,
    onRequestKill: (String) -> Unit,
    onConfirmKill: () -> Unit,
    onCancelKill: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        TerminalHeader(
            state = state,
            onNewTerminal = onNewTerminal,
            onRunProjectStart = onRunProjectStart,
            onRequestTicket = onRequestTicket,
            onReconnect = onReconnect,
        )
        HorizontalDivider()

        if (state.terminals.isEmpty()) {
            TerminalEmpty(Modifier.weight(1f))
        } else {
            TerminalList(
                state = state,
                onOpen = onOpenTerminal,
                onRequestKill = onRequestKill,
                modifier = Modifier.weight(if (state.open == null) 1f else 0.4f),
            )
        }

        state.open?.let { terminal ->
            HorizontalDivider()
            TerminalSurface(
                state = state,
                channel = channel,
                onBridgeMessage = onBridgeMessage,
                onOutputConsumed = onOutputConsumed,
                modifier = Modifier.weight(1f),
            )
            ExtraKeysRow(onKeys = onKeys)
        }
    }

    if (state.pickerOpen) {
        TerminalPickerSheet(
            shells = state.shells,
            onSelect = { shell -> onCreate(shell.path, listOf("-l")) },
            onUseDefault = { onCreate(null, null) },
            onDismiss = onClosePicker,
        )
    }

    state.killTarget?.let { target ->
        AlertDialog(
            onDismissRequest = onCancelKill,
            title = { Text(stringResource(R.string.terminal_kill_title)) },
            text = { Text(stringResource(R.string.terminal_kill_body)) },
            confirmButton = {
                TextButton(onClick = onConfirmKill) { Text(stringResource(R.string.terminal_kill_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = onCancelKill) { Text(stringResource(R.string.action_close)) }
            },
        )
    }

    state.error?.let { error ->
        AlertDialog(
            onDismissRequest = onDismissError,
            title = { Text(stringResource(R.string.terminal_failed)) },
            text = { Text(error) },
            confirmButton = {
                TextButton(onClick = onDismissError) { Text(stringResource(R.string.action_close)) }
            },
        )
    }
}

/**
 * Everything around the live terminal: the header, the list, and the extra-keys row.
 *
 * **Separated from the WebView so a screenshot can draw it.** A Roborazzi capture of a `WebView` is an
 * empty rectangle — xterm.js paints on a canvas the compositor owns, and P6 learned the same about a
 * sheet — so the baselines record this chrome and the page itself is asserted rather than photographed.
 * It is also the honest shape: everything this client decides about a terminal is here, and the page
 * only draws what it is handed.
 */
/**
 * The header: the stream's own state, the two quick actions, and the reconnect.
 *
 * **The state is the socket's, not a guess.** `Live` only after the server's cursor frame, so a header
 * that says "live" means the replay finished — which is the one thing a user of a slow `vim` needs to
 * know.
 */
@Composable
fun TerminalChrome(
    state: TerminalUiState,
    onNewTerminal: () -> Unit,
    onRunProjectStart: () -> Unit,
    onRequestTicket: () -> Unit,
    onReconnect: () -> Unit,
    onExtraKeys: (List<TerminalKey>) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        TerminalHeader(
            state = state,
            onNewTerminal = onNewTerminal,
            onRunProjectStart = onRunProjectStart,
            onRequestTicket = onRequestTicket,
            onReconnect = onReconnect,
        )
        HorizontalDivider()
        if (state.terminals.isEmpty()) {
            TerminalEmpty(Modifier.weight(1f))
        } else {
            TerminalList(
                state = state,
                onOpen = {},
                onRequestKill = {},
                modifier = Modifier.weight(if (state.open == null) 1f else 0.4f),
            )
        }
        ExtraKeysRow(onKeys = onExtraKeys)
    }
}

@Composable
private fun TerminalHeader(
    state: TerminalUiState,
    onNewTerminal: () -> Unit,
    onRunProjectStart: () -> Unit,
    onRequestTicket: () -> Unit,
    onReconnect: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = state.open?.title ?: stringResource(R.string.terminal_none),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = stringResource(state.statusKey.toStatusRes()),
            style = MaterialTheme.typography.labelSmall,
            color = if (state.connected) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.error
            },
            modifier = Modifier.testTag(TerminalTags.STATUS),
        )
        if (state.startCommand != null) {
            IconButton(onClick = onRunProjectStart, enabled = state.canStartProject) {
                Icon(Icons.Filled.Terminal, stringResource(R.string.terminal_run_start))
            }
        }
        if (state.ticketRefused) {
            // The server refused the header-authenticated upgrade. A `pty.connect.token` is the one
            // thing that can still work, so the row appears *because* it failed rather than before:
            // offering it on a healthy terminal would spend a single-use ticket for nothing.
            IconButton(onClick = onRequestTicket, enabled = state.ticket == null) {
                Icon(Icons.Filled.VpnKey, stringResource(R.string.terminal_use_ticket))
            }
        }
        IconButton(onClick = onReconnect, enabled = state.open != null) {
            Icon(Icons.Filled.Refresh, stringResource(R.string.terminal_reconnect))
        }
        IconButton(onClick = onNewTerminal, enabled = !state.creating) {
            Icon(Icons.Filled.Add, stringResource(R.string.terminal_new))
        }
    }
}

@Composable
private fun TerminalList(
    state: TerminalUiState,
    onOpen: (String) -> Unit,
    onRequestKill: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth().testTag(TerminalTags.LIST),
        contentPadding = PaddingValues(bottom = 8.dp),
    ) {
        items(state.terminals, key = { it.id }) { terminal ->
            ListItem(
                headlineContent = {
                    Text(terminal.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                supportingContent = {
                    Text(
                        text = terminal.command + terminal.args.joinToString(" ", prefix = " "),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                trailingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        terminal.exitCode?.let { code ->
                            Text(
                                text = stringResource(R.string.terminal_exit, code),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        IconButton(onClick = { onRequestKill(terminal.id) }) {
                            Icon(Icons.Filled.Close, stringResource(R.string.terminal_kill))
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(TerminalTags.TERMINAL + terminal.id)
                    .semantics { contentDescription = terminal.title },
            )
        }
    }
}

/**
 * The live surface: the WebView, and the output the view model has buffered for it.
 *
 * **The output is written in a `LaunchedEffect` on the buffer, then handed back.** A
 * `WebView.evaluateJavascript` is a call onto the browser's thread, and a terminal producing a thousand
 * chunks a second would make that call a thousand times a second; folding them into the state and
 * writing on each publication means one evaluation per recomposition, in order. The length is then
 * reported back through [onOutputConsumed], because the buffer is a queue and a queue nobody drains is
 * a terminal that re-sends its whole history on every frame.
 *
 * **The bridge goes to the view model whole.** Everything the page sends — `ready`, input, a grid, a
 * selection, a failure — is handed over as it arrived. Filtering here is how a terminal that draws and
 * types nothing gets shipped: the page's `onData` is the only route from a hardware keyboard to the
 * socket, and a screen that forwards only `resize` drops it.
 */
@Composable
private fun TerminalSurface(
    state: TerminalUiState,
    channel: TerminalChannel,
    onBridgeMessage: (TerminalBridgeMessage) -> Unit,
    onOutputConsumed: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(state.pendingOutput) {
        if (state.pendingOutput.isEmpty()) return@LaunchedEffect
        channel.write(state.pendingOutput)
        onOutputConsumed(state.pendingOutput.length)
    }
    LaunchedEffect(state.statusKey, state.stream) {
        channel.publishState(state.statusKey, (state.stream as? PtyStreamState.Live)?.cursor)
    }
    TerminalWebView(
        channel = channel,
        onMessage = { message -> relayBridgeMessage(channel, message, onBridgeMessage) },
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant),
    )
}

/**
 * What the screen does with a message from the page, before the view model sees it.
 *
 * **One function, and the screen's only decision, so it can be tested.** The screen's job here is
 * narrow: `ready` releases the channel's buffer, because the channel is the composition's and the view
 * model does not hold it. Everything else — input, the grid, a selection, a failure — is forwarded
 * whole. Filtering it here is how a terminal that renders and then accepts nothing gets shipped: the
 * page's `onData` is the only route from a hardware keyboard or a paste to the socket, so a `when`
 * with no `Input` branch is invisible until someone types.
 *
 * Extracted as a function rather than left as a lambda so that "every kind the codec produces reaches
 * the host" is a test rather than a claim. `TerminalSurfaceTest` asserts it.
 */
internal fun relayBridgeMessage(
    channel: TerminalChannel,
    message: TerminalBridgeMessage,
    onBridgeMessage: (TerminalBridgeMessage) -> Unit,
) {
    if (message is TerminalBridgeMessage.Ready) channel.onPageReady()
    onBridgeMessage(message)
}

/**
 * The extra-keys row (plan §6: Esc, Tab, Ctrl, Alt, arrows, `|`, `~`, `/`).
 *
 * **The pressed set is local state, and the row hands the caller the whole sequence.** A modifier latch
 * is a property of the row the user is pressing, and putting it in a view model would make a rotation
 * reset a `Ctrl` the user is halfway through — the exact moment a modifier must not be lost. Handing
 * over "the latches that are armed, then this key" rather than just the key is what lets
 * [TerminalInput] encode it as a pure fold with no mutable state of its own, and it is the same list a
 * hardware keyboard path would produce.
 */
@Composable
fun ExtraKeysRow(
    onKeys: (List<TerminalKey>) -> Unit,
    modifier: Modifier = Modifier,
) {
    var pressed by remember { mutableStateOf(setOf<TerminalKey>()) }
    val label = stringResource(R.string.terminal_extra_keys)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp, vertical = 4.dp)
            .testTag(TerminalTags.EXTRA_KEYS)
            .semantics { contentDescription = label },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ExtraKeys.row.forEach { key ->
            val isPressed = key in pressed
            val latches = pressed.filter(ExtraKeys::isModifier)
            Box(
                modifier = Modifier
                    .width(KEY_WIDTH)
                    .height(KEY_HEIGHT)
                    .clip(MaterialTheme.shapes.small)
                    .background(
                        if (isPressed) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surface
                        },
                    )
                    .clickable {
                        if (ExtraKeys.isModifier(key)) {
                            pressed = if (isPressed) pressed - key else pressed + key
                        }
                        onKeys(latches + key)
                    }
                    .testTag(TerminalTags.KEY + ExtraKeys.label(key)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = ExtraKeys.label(key),
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

/**
 * The shell picker, from `config.shell`.
 *
 * **"Cancel" creates nothing.** A `null` command is the server's own default shell, and it is offered
 * as its own row rather than as what happens when the dialog is dismissed: a picker whose dismiss
 * button starts a shell is a picker that starts a shell by accident.
 */
@Composable
private fun TerminalPickerSheet(
    shells: List<dev.opencode.android.core.model.ShellOption>,
    onSelect: (dev.opencode.android.core.model.ShellOption) -> Unit,
    onUseDefault: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.terminal_picker_title)) },
        text = {
            if (shells.isEmpty()) {
                Text(stringResource(R.string.terminal_picker_empty))
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    shells.forEach { shell ->
                        FilterChip(
                            selected = false,
                            enabled = shell.acceptable,
                            onClick = { onSelect(shell) },
                            label = { Text(shell.name) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onUseDefault) { Text(stringResource(R.string.terminal_picker_default)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        },
    )
}

@Composable
private fun TerminalEmpty(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.terminal_empty),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = stringResource(R.string.terminal_empty_body),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** The tags the tests and the baselines address. */
object TerminalTags {
    const val VIEW: String = "terminal-view"
    const val LIST: String = "terminal-list"
    const val TERMINAL: String = "terminal-row-"
    const val STATUS: String = "terminal-status"
    const val EXTRA_KEYS: String = "terminal-extra-keys"
    const val KEY: String = "terminal-key-"
}

private val KEY_WIDTH = 46.dp
private val KEY_HEIGHT = 38.dp

/** The status word a key resolves to, kept as one mapping so a new state cannot miss a string. */
private fun String.toStatusRes(): Int = when (this) {
    "idle" -> R.string.terminal_status_idle
    "connecting" -> R.string.terminal_status_connecting
    "live" -> R.string.terminal_status_live
    "reconnecting" -> R.string.terminal_status_reconnecting
    else -> R.string.terminal_status_closed
}
