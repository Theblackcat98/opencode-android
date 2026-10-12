package dev.opencode.android.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.opencode.android.R
import dev.opencode.android.core.designsystem.component.OpenCodeEmptyState
import dev.opencode.android.core.designsystem.theme.OpenCodeThemeExtras

/**
 * The command palette: a search field over the commands the app can run from here.
 *
 * A full-width dialog rather than a sheet, so the search field sits where the eye lands and the list
 * grows under it. Choosing a row runs it and closes the palette, in that order, so a command that navigates
 * does not leave the palette open over the screen it opened.
 *
 * **The query is kept with `rememberSaveable`**, so a rotation keeps what was typed. The commands are a
 * parameter rather than read here, so the caller decides which ones exist and the dialog cannot offer a
 * command the caller would not run.
 *
 * The dialog is width-capped, so on a tablet it reads as a palette rather than a full-bleed sheet.
 */
@Composable
fun CommandPaletteDialog(
    commands: List<PaletteCommand>,
    onRun: (PaletteCommand) -> Unit,
    onDismiss: () -> Unit,
) {
    val spacing = OpenCodeThemeExtras.spacing
    var query by rememberSaveable { mutableStateOf("") }
    val labels = commands.associateWith { stringResource(it.labelRes) }
    val matches = filterCommands(commands, query) { labels.getValue(it) }
    val focusRequester = remember { FocusRequester() }
    // Saveable, keyed on the matches: a rotation keeps the position, a new query resets it.
    var selectedIndex by rememberSaveable(matches) { mutableStateOf(0) }
    val listState = rememberLazyListState()
    // Keep the arrow-key selection visible: without this, arrowing past the fold moves state the
    // user cannot see.
    LaunchedEffect(selectedIndex) {
        if (matches.isNotEmpty()) listState.scrollToItem(selectedIndex)
    }
    // The soft keyboard's action button runs the top match: without an imeAction it does nothing on a phone.
    val runSelected: () -> Unit = {
        matches.getOrNull(selectedIndex)?.let {
            onRun(it)
            onDismiss()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        // The focus request is made here, not in the caller: the field is only attached inside the dialog's
        // own composition, and a request made before that throws.
        LaunchedEffect(Unit) { focusRequester.requestFocus() }

        Surface(
            modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(horizontal = spacing.lg),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(modifier = Modifier.padding(spacing.lg)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = {
                        query = it
                        selectedIndex = 0
                    },
                    label = { Text(stringResource(R.string.palette_search_label)) },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = ""; selectedIndex = 0 }) {
                                Icon(
                                    Icons.Filled.Clear,
                                    contentDescription = stringResource(R.string.palette_clear),
                                )
                            }
                        }
                    },
                    singleLine = true,
                    // A command field, not prose: no autocorrect, no capitalization, and the keyboard's
                    // action button searches.
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        imeAction = ImeAction.Search,
                    ),
                    keyboardActions = KeyboardActions(onSearch = { runSelected() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown) {
                                when (event.key) {
                                    Key.DirectionDown -> {
                                        if (matches.isNotEmpty()) {
                                            selectedIndex = (selectedIndex + 1).coerceAtMost(matches.size - 1)
                                            true
                                        } else {
                                            false
                                        }
                                    }

                                    Key.DirectionUp -> {
                                        if (matches.isNotEmpty()) {
                                            selectedIndex = (selectedIndex - 1).coerceAtLeast(0)
                                            true
                                        } else {
                                            false
                                        }
                                    }

                                    Key.Enter, Key.NumPadEnter -> {
                                        runSelected()
                                        matches.getOrNull(selectedIndex) != null
                                    }

                                    else -> false
                                }
                            } else {
                                false
                            }
                        },
                )
                if (matches.isEmpty()) {
                    OpenCodeEmptyState(
                        title = stringResource(R.string.palette_no_match),
                        icon = Icons.Filled.SearchOff,
                        modifier = Modifier.padding(vertical = spacing.md),
                    )
                }
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp),
                    state = listState,
                ) {
                    itemsIndexed(matches, key = { _, command -> command.id }) { index, command ->
                        val isSelected = index == selectedIndex
                        val containerColor = if (isSelected) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            Color.Transparent
                        }
                        val contentColor = if (isSelected) {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                        val hintColor = if (isSelected) {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(color = containerColor, shape = MaterialTheme.shapes.small)
                                // `selectable` rather than `clickable`: the arrow-key selection is state a
                                // TalkBack user must hear, and this publishes it where `clickable` plus a
                                // manual role would stay silent about which row is current.
                                .selectable(
                                    selected = isSelected,
                                    onClick = {
                                        onRun(command)
                                        onDismiss()
                                    },
                                    role = Role.Button,
                                )
                                .padding(horizontal = spacing.sm, vertical = spacing.md + spacing.xxs),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = labels.getValue(command),
                                style = MaterialTheme.typography.bodyLarge,
                                color = contentColor,
                                modifier = Modifier.weight(1f),
                            )
                            // The leader-key mnemonic, so the palette shows the key that runs the same command.
                            leaderLetterOf(command.id)?.let { letter ->
                                Text(
                                    text = stringResource(R.string.palette_leader_hint, letter),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = hintColor,
                                    modifier = Modifier.padding(start = spacing.lg),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
