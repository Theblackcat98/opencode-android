package dev.opencode.android.feature.composer.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.opencode.android.core.designsystem.theme.OpenCodeThemeExtras
import dev.opencode.android.feature.composer.R

/**
 * The composer's text, full screen.
 *
 * A phone keyboard is a small window onto a long sentence, and a paste is often longer still. The
 * full-screen editor is where a person writes something they do not want to interrupt, and it is what
 * `/editor` opens; the text it hands back is the text the box would have had, so nothing else in the
 * composer changes when it closes.
 *
 * A [Dialog] with `usePlatformDefaultWidth = false` rather than a navigation destination, because it
 * is a mode of the composer rather than a place: the screen behind it is still the session, and coming
 * back to it must not reload anything.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullScreenEditor(
    initialText: String,
    onDone: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(initialText) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.composer_editor)) },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.composer_close))
                        }
                    },
                    actions = {
                        TextButton(onClick = { onDone(text) }) {
                            Icon(Icons.Filled.Check, contentDescription = null)
                            Text(
                                text = stringResource(R.string.composer_editor_done),
                                modifier = Modifier.padding(start = OpenCodeThemeExtras.spacing.sm),
                            )
                        }
                    },
                )
            },
        ) { insets ->
            // The editor runs in its own Dialog window, so the activity's adjustResize does not
            // reliably apply: without this the keyboard can cover the text being edited.
            Surface(Modifier.fillMaxSize().padding(insets).imePadding()) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { edited: String -> text = edited },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(OpenCodeThemeExtras.spacing.md)
                        .onPreviewKeyEvent { event ->
                            // Ctrl+Enter / Cmd+Enter confirms, matching the composer's send chord.
                            // Preview so the field never sees it as a newline.
                            if (
                                event.type == KeyEventType.KeyDown &&
                                (event.isCtrlPressed || event.isMetaPressed) &&
                                (event.key == Key.Enter || event.key == Key.NumPadEnter)
                            ) {
                                onDone(text)
                                true
                            } else {
                                false
                            }
                        },
                    maxLines = 40,
                )
            }
        }
    }
}

/**
 * The three sources an attachment can come from.
 *
 * Only the names: each one needs the platform's own contract — the photo picker is `PickVisualMedia`,
 * the camera is `TakePicture` with a URI this app owns, and files is `OpenDocument` — so the launcher
 * registration lives in the composition root and dispatches on these.
 */
object AttachSource {
    const val PHOTO = "photo"
    const val CAMERA = "camera"
    const val FILE = "file"
}
