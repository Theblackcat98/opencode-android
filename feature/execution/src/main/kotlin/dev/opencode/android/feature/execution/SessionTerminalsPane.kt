package dev.opencode.android.feature.execution

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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
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
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag(SessionTerminalTags.TERMINAL + terminal.id),
                        )
                        HorizontalDivider()
                    }
                }
                TerminalScreenText(
                    screen = state.screen,
                    onRead = onRead,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * The rendered screen, as monospaced text.
 *
 * **Selectable, because a screen of terminal text is only useful if it can be copied out.** It is not
 * interactive: there is no cursor to move and nothing can be typed into a picture, and the pane says so
 * rather than looking like a terminal that has stopped accepting input.
 */
@Composable
private fun TerminalScreenText(
    screen: SessionTerminalRead?,
    onRead: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.session_terminals_screen),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRead) { Text(stringResource(R.string.session_terminals_read)) }
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
    const val LIST: String = "session-terminals-list"
    const val TERMINAL: String = "session-terminals-row-"
    const val SCREEN: String = "session-terminals-screen"
    const val AVAILABILITY: String = "session-terminals-availability"
    const val NOTICE: String = "session-terminals-notice-"
    const val OFF: String = "off"
    const val ABSENT: String = "absent"
    const val EMPTY: String = "empty"
}

private fun String.toAvailabilityRes(): Int = when (this) {
    "present" -> R.string.session_terminals_state_present
    "host-down" -> R.string.session_terminals_state_host_down
    "absent" -> R.string.session_terminals_state_absent
    else -> R.string.session_terminals_state_unknown
}
