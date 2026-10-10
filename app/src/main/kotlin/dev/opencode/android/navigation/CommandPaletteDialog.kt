package dev.opencode.android.navigation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.opencode.android.R

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
 */
@Composable
fun CommandPaletteDialog(
    commands: List<PaletteCommand>,
    onRun: (PaletteCommand) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val labels = commands.associateWith { stringResource(it.labelRes) }
    val matches = filterCommands(commands, query) { labels.getValue(it) }
    val focusRequester = remember { FocusRequester() }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        // The focus request is made here, not in the caller: the field is only attached inside the dialog's
        // own composition, and a request made before that throws.
        LaunchedEffect(Unit) { focusRequester.requestFocus() }

        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.palette_search_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                )
                if (matches.isEmpty()) {
                    Text(
                        text = stringResource(R.string.palette_no_match),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                }
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
                    items(matches, key = { it.id }) { command ->
                        Text(
                            text = labels.getValue(command),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onRun(command)
                                    onDismiss()
                                }
                                .semantics { role = Role.Button }
                                .padding(horizontal = 8.dp, vertical = 14.dp),
                        )
                    }
                }
            }
        }
    }
}
