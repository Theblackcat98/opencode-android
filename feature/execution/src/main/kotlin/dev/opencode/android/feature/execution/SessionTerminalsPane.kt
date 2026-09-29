package dev.opencode.android.feature.execution

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.opencode.android.core.model.SessionTerminalRead

/**
 * A session's persistent terminals (features doc §32; plan §6, "Persistent PTYs").
 *
 * **The pane explains itself when it cannot work, and says which of the two reasons it is.** There are
 * two: the installation's switch is off, and the server does not have the routes or its host is not
 * running. They are different problems with different fixes — one is a setting on this phone, the
 * other is a service on the server machine — and a single "unavailable" would send the user to the
 * wrong one.
 *
 * **The screen text is a fallback, and it says so.** `terminal/read` returns the *rendered* screen of
 * the terminal the session last controlled: a picture of what it looked like, with a cursor. It is not
 * the live stream — that is the WebSocket and the same pane as a location's terminals — so this pane
 * labels it as a snapshot rather than implying the terminal is attached.
 */
@Composable
fun SessionTerminalsPane(
    state: SessionTerminalsUiState,
    onCreate: () -> Unit,
    onRead: () -> Unit,
    onInspect: (String) -> Unit,
    onResize: (String, Int, Int) -> Unit,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.session_terminals_title),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(R.string.session_terminals_availability, stringResource(state.availabilityKey.toAvailabilityRes())),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.testTag(SessionTerminalTags.AVAILABILITY),
            )
            IconButton(onClick = onCreate, enabled = state.usable) {
                Icon(Icons.Filled.Add, stringResource(R.string.session_terminals_create))
            }
        }
        HorizontalDivider()

        when {
            !state.allowedBySetting -> Notice(
                stringResource(R.string.session_terminals_off_body),
                SessionTerminalTags.OFF,
            )

            !state.usable -> Notice(
                stringResource(
                    if (state.availabilityKey == "host-down") {
                        R.string.session_terminals_host_down_body
                    } else {
                        R.string.session_terminals_absent_body
                    },
                ),
                SessionTerminalTags.ABSENT,
            )

            state.terminals.isEmpty() -> Notice(
                stringResource(R.string.session_terminals_empty_body),
                SessionTerminalTags.EMPTY,
            )

            else -> {
                LazyColumn(
                    modifier = Modifier.weight(1f).testTag(SessionTerminalTags.LIST),
                    contentPadding = PaddingValues(bottom = 8.dp),
                ) {
                    items(state.terminals, key = { it.id }) { terminal ->
                        ListItem(
                            headlineContent = { Text(terminal.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = {
                                Text(
                                    text = listOf(
                                        terminal.foregroundProcess ?: terminal.cwd,
                                        "${terminal.cols}×${terminal.rows}",
                                        terminal.exitCode?.toString() ?: "",
                                    ).filter { it.isNotBlank() }.joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            trailingContent = {
                                IconButton(onClick = { onRemove(terminal.id) }) {
                                    Icon(Icons.Filled.Delete, stringResource(R.string.session_terminals_remove))
                                }
                            },
                        )
                        HorizontalDivider()
                    }
                }
                TerminalScreenText(
                    state = state,
                    onRead = onRead,
                    onResize = onResize,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * The rendered screen, as monospaced text, with the one control a picture of a terminal can offer.
 *
 * **Selectable, because a screen of terminal text is only useful if it can be copied out.** It is not
 * interactive: there is no cursor to move and nothing can be typed into a picture, and the pane says so
 * rather than looking like a terminal that has stopped accepting input.
 *
 * **The size control sends `persistent-pty.update`.** A resize on a live terminal is a REST call on the
 * server's own route, and it is the only way a full-screen program's layout can be changed from here —
 * a picture of a screen cannot be reflowed by the client, so the server has to be told a new grid. The
 * row it shows is the grid the server last reported, so the two cannot drift.
 */
@Composable
private fun TerminalScreenText(
    state: SessionTerminalsUiState,
    onRead: () -> Unit,
    onResize: (String, Int, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val screen = state.screen
    val inspected = state.terminals.firstOrNull { it.id == state.inspected } ?: state.latest
    Column(modifier = modifier.fillMaxWidth().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.session_terminals_screen),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRead) { Text(stringResource(R.string.session_terminals_read)) }
        }
        if (inspected != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "${inspected.cols}×${inspected.rows}",
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.testTag(SessionTerminalTags.SIZE),
                )
                IconButton(
                    onClick = { onResize(inspected.id, inspected.cols - STEP, inspected.rows) },
                    enabled = inspected.cols - STEP >= MIN_COLS,
                    modifier = Modifier.testTag(SessionTerminalTags.NARROWER),
                ) {
                    Icon(Icons.Filled.Remove, stringResource(R.string.session_terminals_narrower))
                }
                IconButton(
                    onClick = { onResize(inspected.id, inspected.cols + STEP, inspected.rows) },
                    enabled = inspected.cols + STEP <= MAX_COLS,
                    modifier = Modifier.testTag(SessionTerminalTags.WIDER),
                ) {
                    Icon(Icons.Filled.Add, stringResource(R.string.session_terminals_wider))
                }
                IconButton(
                    onClick = { onResize(inspected.id, inspected.cols, inspected.rows - STEP) },
                    enabled = inspected.rows - STEP >= MIN_ROWS,
                    modifier = Modifier.testTag(SessionTerminalTags.SHORTER),
                ) {
                    Icon(Icons.Filled.Remove, stringResource(R.string.session_terminals_shorter))
                }
                IconButton(
                    onClick = { onResize(inspected.id, inspected.cols, inspected.rows + STEP) },
                    enabled = inspected.rows + STEP <= MAX_ROWS,
                    modifier = Modifier.testTag(SessionTerminalTags.TALLER),
                ) {
                    Icon(Icons.Filled.Add, stringResource(R.string.session_terminals_taller))
                }
            }
        }
        state.snapshot?.checkpoint?.let { checkpoint ->
            Text(
                text = stringResource(R.string.session_terminals_checkpoint, checkpoint.take(12)),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Surface(
            modifier = Modifier.fillMaxWidth().weight(1f),
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.small,
        ) {
            SelectionContainer {
                Text(
                    text = when (screen) {
                        is SessionTerminalRead.Screen -> screen.text
                        else -> stringResource(R.string.session_terminals_no_screen)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .padding(8.dp)
                        .horizontalScroll(rememberScrollState())
                        .testTag(SessionTerminalTags.SCREEN),
                )
            }
        }
    }
}

@Composable
private fun Notice(text: String, tag: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        modifier = modifier
            .fillMaxWidth()
            .padding(24.dp)
            .testTag(SessionTerminalTags.NOTICE + tag),
    )
}

/** The tags the tests and the baselines address. */
object SessionTerminalTags {
    const val SIZE: String = "session-terminals-size"
    const val WIDER: String = "session-terminals-wider"
    const val NARROWER: String = "session-terminals-narrower"
    const val TALLER: String = "session-terminals-taller"
    const val SHORTER: String = "session-terminals-shorter"
    const val LIST: String = "session-terminals-list"
    const val TERMINAL: String = "session-terminals-row-"
    const val SCREEN: String = "session-terminals-screen"
    const val AVAILABILITY: String = "session-terminals-availability"
    const val NOTICE: String = "session-terminals-notice-"
    const val OFF: String = "off"
    const val ABSENT: String = "absent"
    const val EMPTY: String = "empty"
}

/** A resize step. Four columns at a time is a cell a finger can aim at. */
private const val STEP = 4

/** The bounds the bridge codec already enforces on a grid from the page; the pane obeys them too. */
private const val MIN_COLS = 20
private const val MAX_COLS = 500
private const val MIN_ROWS = 5
private const val MAX_ROWS = 200

private fun String.toAvailabilityRes(): Int = when (this) {
    "present" -> R.string.session_terminals_state_present
    "host-down" -> R.string.session_terminals_state_host_down
    "absent" -> R.string.session_terminals_state_absent
    else -> R.string.session_terminals_state_unknown
}
