package dev.opencode.android.navigation

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import dev.opencode.android.feature.composer.R
import dev.opencode.android.feature.composer.ui.AttachSource
import java.io.File

/**
 * The three attachment sources, and the platform contracts behind them.
 *
 * **Each source has the contract the platform actually offers, not one the app wished for.**
 *
 * - The **photo picker** is `PickVisualMedia`, which needs no storage permission on any API level the
 *   app supports and hands back a URI rather than a path.
 * - The **camera** is `TakePicture` with a URI this app owns through a [FileProvider] scoped to its
 *   cache directory. Without a provider the camera app has no way to write the file, and without a
 *   cache path the capture would outlive the prompt.
 * - **Files** is `OpenDocument`, which returns something the user chose rather than something this app
 *   enumerated — a document URI is the only kind of URI whose read permission survives the picker
 *   returning.
 *
 * The launcher registration has to live in a composable, which is one of the reasons the session
 * screen's wiring lives in the app module rather than in the composer feature.
 */
@Composable
fun rememberAttachmentPicker(
    onPicked: (Uri) -> Unit,
    onCameraUnavailable: () -> Unit = {},
): (String) -> Unit {
    val context = LocalContext.current
    var cameraTarget by remember { mutableStateOf<Uri?>(null) }

    val photo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(onPicked)
    }
    val file = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(onPicked)
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        val target = cameraTarget
        cameraTarget = null
        if (taken && target != null) onPicked(target)
    }

    return { source ->
        when (source) {
            AttachSource.PHOTO -> photo.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
            )

            AttachSource.FILE -> file.launch(arrayOf("*/*"))

            AttachSource.CAMERA -> {
                val uri = context.newCaptureUri()
                if (uri == null) {
                    onCameraUnavailable()
                } else {
                    cameraTarget = uri
                    camera.launch(uri)
                }
            }
        }
    }
}

/**
 * A URI in the app's own cache for the camera to write into.
 *
 * `null` when the cache cannot be written, which is the honest answer: the camera would fail anyway,
 * and an unreadable file is worse than no offer.
 */
private fun Context.newCaptureUri(): Uri? = runCatching {
    val directory = File(cacheDir, "captures").apply { mkdirs() }
    val file = File(directory, "capture-${System.currentTimeMillis()}.jpg")
    FileProvider.getUriForFile(this, "$packageName.captures", file)
}.getOrNull()

/**
 * The sheet that asks which source to use.
 *
 * Three rows and no other choice, because each one opens a different system UI and picking a source
 * blind would mean guessing which one the user wanted.
 */
@Composable
fun AttachSourceSheet(
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(dev.opencode.android.feature.composer.R.string.composer_attach)) },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                SourceRow(stringResource(dev.opencode.android.feature.composer.R.string.composer_attach_photo)) {
                    onPick(AttachSource.PHOTO)
                }
                SourceRow(stringResource(dev.opencode.android.feature.composer.R.string.composer_attach_camera)) {
                    onPick(AttachSource.CAMERA)
                }
                SourceRow(stringResource(dev.opencode.android.feature.composer.R.string.composer_attach_file)) {
                    onPick(AttachSource.FILE)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(dev.opencode.android.feature.composer.R.string.composer_dismiss))
            }
        },
    )
}

@Composable
private fun SourceRow(label: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    )
}
