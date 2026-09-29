package dev.opencode.android.navigation

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.opencode.android.feature.review.ExperimentalSettingsContent
import dev.opencode.android.feature.review.ExperimentalSettingsViewModel
import dev.opencode.android.feature.review.HistoryPanel
import dev.opencode.android.feature.review.HistoryViewModel
import dev.opencode.android.feature.review.TransferFormat
import dev.opencode.android.feature.review.TransferResult
import java.io.File
import dev.opencode.android.feature.review.R as ReviewR

/**
 * The history panel and the experimental switches, over a session (plan §6, "History tools").
 *
 * **They are composed here and not in a feature** because both need something only the app module
 * has: a document picker and a share sheet. The panel decides *what* an export is and *what* an
 * import contains, and this file is what turns either into a file the user chose.
 *
 * **The switch and the panel read the same preference.** The experimental sheet is where the user
 * grants `experimental.session.export`; the history panel's export button is what that grant turns
 * on. Reading it in one place and writing it in another is how a button ends up offering a route the
 * user has not agreed to, so the panel is told the value and the sheet writes it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryHost(
    sessionId: String?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    history: HistoryViewModel = hiltViewModel(),
    experimental: ExperimentalSettingsViewModel = hiltViewModel(),
) {
    val state by history.state.collectAsStateWithLifecycle()
    val switches by experimental.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // The document picker. It is a launcher rather than a permission: `OpenDocument` returns
    // something the user chose, and its read grant survives the picker returning, which a path read
    // would not.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        val chosen = uri ?: return@rememberLauncherForActivityResult
        runCatching { context.contentResolver.openInputStream(chosen)?.bufferedReader()?.use { it.readText() } }
            .getOrNull()
            ?.let(history::import)
    }

    LaunchedEffect(sessionId) { history.open(sessionId) }

    // The transfer switch is the user's grant, and the export button is what it turns on. The panel
    // is told rather than reading the preference itself, so there is one reader and one writer.
    LaunchedEffect(switches.sessionTransfer) { history.setTransferAllowed(switches.sessionTransfer) }

    // An export is a file, and the only URI this app may hand out is one its own `FileProvider`
    // owns. The bytes go to the cache for the same reason the file viewer's share does: a transcript
    // the user merely exported must not outlive the session on the device.
    LaunchedEffect(state.transfer, state.exported) {
        if (state.transfer is TransferResult.Exported) {
            state.exported?.let { text ->
                shareText(context, text)
                history.dismissTransfer()
            }
        }
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, modifier = modifier) {
        HistoryPanel(
            state = state,
            onPreviousPrompt = history::previousPrompt,
            onNextPrompt = history::nextPrompt,
            onSearchChange = history::search,
            onOpenContext = history::loadContext,
            onCloseContext = history::closeContext,
            onSanitizeChange = history::setSanitize,
            onExportJson = { history.export(TransferFormat.JSON) },
            onExportMarkdown = { history.export(TransferFormat.MARKDOWN) },
            onImport = { picker.launch(arrayOf("application/json", "*/*")) },
            onDismissTransfer = history::dismissTransfer,
        )
    }
}

/**
 * The experimental switches, as their own sheet.
 *
 * **It is separate from the history panel** even though one switch governs one of its buttons: the
 * panel is something a user opens to answer "what happened", and the switches are something they
 * open once to answer "what am I allowed to do". Putting the switches above the panel would make
 * every visit to a transcript begin with a settings page.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExperimentalHost(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    experimental: ExperimentalSettingsViewModel = hiltViewModel(),
) {
    val switches by experimental.settings.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, modifier = modifier) {
        ExperimentalSettingsContent(
            fileWrites = switches.fileWrites,
            sessionTransfer = switches.sessionTransfer,
            onFileWritesChange = experimental::setFileWrites,
            onSessionTransferChange = experimental::setSessionTransfer,
            persistentPty = switches.persistentPty,
            mcpRuntime = switches.mcpRuntime,
            wellknownIntegrations = switches.wellknownIntegrations,
            onPersistentPtyChange = experimental::setPersistentPty,
            onMcpRuntimeChange = experimental::setMcpRuntime,
            onWellknownIntegrationsChange = experimental::setWellknownIntegrations,
            onDismiss = onDismiss,
        )
    }
}

/**
 * Hands an exported transcript to another app.
 *
 * It is written into the cache and shared through the app's own `FileProvider`, for the same reason
 * the file viewer's share is: the app has no durable copy of an export, and a URI it may grant has
 * to be one it owns. The grant expires with the chooser.
 */
private fun shareText(context: Context, text: String) {
    val target = File(context.cacheDir, "shares").apply { mkdirs() }.resolve("opencode-session.json")
    runCatching { target.writeText(text) }.onFailure { return }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.captures", target)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/json"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(intent, context.getString(ReviewR.string.history_export))
    if (context !is android.app.Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(chooser) }
}
