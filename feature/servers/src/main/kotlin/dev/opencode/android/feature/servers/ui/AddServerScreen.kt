package dev.opencode.android.feature.servers.ui

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.opencode.android.core.data.repository.AddServerErrorType
import dev.opencode.android.feature.servers.R
import dev.opencode.android.feature.servers.camera.QrCodeScannerView

/**
 * The three ways to add a server, as tabs: scan the `opencode pair` code, paste a link, or type an
 * address and a password.
 *
 * A shared or deep-linked link arrives with the paste tab already filled in, so the "share to
 * OpenCode" path and a link opened in the browser do not have to be told apart.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddServerScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AddServerViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // A re-pair replaces one credential, so the scan and manual tabs, which create a new profile,
    // are not offered; the link tab is the only way in.
    val visibleTabs = if (viewModel.isRePairing) listOf(AddServerTab.PASTE) else AddServerTab.entries

    LaunchedEffect(uiState.addedServerId) {
        if (uiState.addedServerId != null) onNavigateBack()
    }

    Scaffold(
        modifier = modifier.testTag(AddServerTags.SCREEN),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (viewModel.isRePairing) R.string.repair_server_title else R.string.add_server_title,
                        ),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.status_navigate_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            PrimaryTabRow(selectedTabIndex = uiState.selectedTab.ordinal) {
                visibleTabs.forEach { tab ->
                    Tab(
                        selected = uiState.selectedTab == tab,
                        onClick = { viewModel.selectTab(tab) },
                        text = { Text(stringResource(tab.titleRes())) },
                        icon = { Icon(tab.icon(), contentDescription = null) },
                        modifier = Modifier.testTag(tab.testTag()),
                    )
                }
            }

            Box(modifier = Modifier.weight(1f)) {
                when (uiState.selectedTab) {
                    AddServerTab.SCAN -> ScanQrTab(
                        onPayload = viewModel::onPayloadReceived,
                        onCameraUnavailable = viewModel::onCameraUnavailable,
                        onSwitchToPaste = { viewModel.selectTab(AddServerTab.PASTE) },
                    )

                    AddServerTab.PASTE -> PasteLinkTab(
                        state = uiState,
                        onLinkChange = viewModel::updatePairingLinkInput,
                        onPair = viewModel::pairWithPastedLink,
                    )

                    AddServerTab.MANUAL -> ManualEntryTab(
                        state = uiState,
                        onUrlChange = viewModel::updateManualUrlInput,
                        onNameChange = viewModel::updateManualNameInput,
                        onPasswordChange = viewModel::updateManualPasswordInput,
                        onTrustUserCertificatesChange = viewModel::setTrustUserCertificates,
                        onConnect = viewModel::connectManually,
                    )
                }

                if (uiState.isWorking) {
                    WorkingOverlay(
                        message = stringResource(
                            if (uiState.selectedTab == AddServerTab.MANUAL) {
                                R.string.saving_in_progress
                            } else {
                                R.string.pairing_in_progress
                            },
                        ),
                    )
                }
            }
        }
    }

    uiState.error?.let { error ->
        ConnectionErrorDialog(
            error = error,
            technicalDetail = uiState.errorTechnicalDetail,
            onPairAgain = {
                viewModel.clearError()
                viewModel.selectTab(AddServerTab.PASTE)
            },
            onDismiss = viewModel::clearError,
        )
    }
}

object AddServerTags {
    const val SCREEN = "add_server_screen"
    const val PASTE_LINK_INPUT = "paste_link_input"
    const val PAIR_BUTTON = "pair_button"
    const val MANUAL_URL_INPUT = "manual_url_input"
    const val MANUAL_PASSWORD_INPUT = "manual_password_input"
    const val MANUAL_CONNECT_BUTTON = "manual_connect_button"
}

private fun AddServerTab.titleRes(): Int = when (this) {
    AddServerTab.SCAN -> R.string.tab_scan_qr
    AddServerTab.PASTE -> R.string.tab_paste_link
    AddServerTab.MANUAL -> R.string.tab_manual
}

private fun AddServerTab.icon(): ImageVector = when (this) {
    AddServerTab.SCAN -> Icons.Default.CameraAlt
    AddServerTab.PASTE -> Icons.Default.ContentPaste
    AddServerTab.MANUAL -> Icons.Default.Edit
}

private fun AddServerTab.testTag(): String = when (this) {
    AddServerTab.SCAN -> "tab_scan_qr"
    AddServerTab.PASTE -> "tab_paste_link"
    AddServerTab.MANUAL -> "tab_manual"
}

@Composable
private fun BoxScope.WorkingOverlay(message: String) {
    Card(
        modifier = Modifier
            .align(Alignment.Center)
            .padding(32.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.padding(24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.semantics { contentDescription = message })
            Text(text = message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/**
 * The failure dialog: what went wrong, then the help for that specific class.
 *
 * The help is the point. "Nothing answered on that address" is only actionable next to the exact
 * commands that fix it, which is what [AddServerErrorType.helpRes] supplies. A rejected credential
 * gets a "pair again" action instead, because no amount of reading recovers from it.
 */
@Composable
fun ConnectionErrorDialog(
    error: AddServerErrorType,
    technicalDetail: String?,
    onPairAgain: () -> Unit,
    onDismiss: () -> Unit,
    onEditServer: (() -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.error_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(error.messageRes()), style = MaterialTheme.typography.bodyMedium)
                error.helpRes()?.let { help ->
                    Text(
                        text = stringResource(help),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                if (technicalDetail != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = stringResource(R.string.error_technical_details),
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Text(
                            text = technicalDetail,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (error == AddServerErrorType.UNAUTHORIZED) {
                TextButton(onClick = onPairAgain) { Text(stringResource(R.string.error_pair_button)) }
            } else {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.error_dismiss)) }
            }
        },
        dismissButton = {
            when {
                onEditServer != null -> TextButton(onClick = onEditServer) {
                    Text(stringResource(R.string.error_edit_button))
                }

                error == AddServerErrorType.UNAUTHORIZED -> TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.error_dismiss))
                }
            }
        },
    )
}

@Composable
private fun ScanQrTab(
    onPayload: (String) -> Unit,
    onCameraUnavailable: () -> Unit,
    onSwitchToPaste: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted -> hasCameraPermission = granted },
    )

    if (hasCameraPermission) {
        Box(modifier = modifier.fillMaxSize()) {
            QrCodeScannerView(
                onQrCodeScanned = onPayload,
                onCameraUnavailable = onCameraUnavailable,
                modifier = Modifier.fillMaxSize(),
            )
            Card(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                ),
            ) {
                Text(
                    text = stringResource(R.string.qr_instructions),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
    } else {
        Column(
            modifier = modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResource(R.string.qr_permission_required),
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) {
                Text(stringResource(R.string.qr_grant_permission))
            }
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(onClick = onSwitchToPaste) {
                Text(stringResource(R.string.tab_paste_link))
            }
            Spacer(modifier = Modifier.height(24.dp))
            OnboardingGuideCard()
        }
    }
}

@Composable
private fun PasteLinkTab(
    state: AddServerUiState,
    onLinkChange: (String) -> Unit,
    onPair: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        OutlinedTextField(
            value = state.pairingLinkInput,
            onValueChange = onLinkChange,
            label = { Text(stringResource(R.string.paste_link_label)) },
            placeholder = { Text(stringResource(R.string.paste_link_hint)) },
            supportingText = { Text(stringResource(R.string.paste_link_help)) },
            modifier = Modifier
                .fillMaxWidth()
                .testTag(AddServerTags.PASTE_LINK_INPUT),
            singleLine = true,
            enabled = !state.isWorking,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = { onLinkChange(context.clipboardText()) },
                modifier = Modifier.weight(1f),
                enabled = !state.isWorking,
            ) {
                Icon(Icons.Default.ContentPaste, contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.paste_from_clipboard))
            }

            Button(
                onClick = onPair,
                enabled = state.pairingLinkInput.isNotBlank() && !state.isWorking,
                modifier = Modifier
                    .weight(1f)
                    .testTag(AddServerTags.PAIR_BUTTON),
            ) {
                Text(stringResource(R.string.pair_and_connect))
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        OnboardingGuideCard()
    }
}

@Composable
private fun ManualEntryTab(
    state: AddServerUiState,
    onUrlChange: (String) -> Unit,
    onNameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onTrustUserCertificatesChange: (Boolean) -> Unit,
    onConnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        OutlinedTextField(
            value = state.manualUrlInput,
            onValueChange = onUrlChange,
            label = { Text(stringResource(R.string.server_url_label)) },
            placeholder = { Text(stringResource(R.string.server_url_hint)) },
            modifier = Modifier
                .fillMaxWidth()
                .testTag(AddServerTags.MANUAL_URL_INPUT),
            singleLine = true,
            enabled = !state.isWorking,
        )

        OutlinedTextField(
            value = state.manualNameInput,
            onValueChange = onNameChange,
            label = { Text(stringResource(R.string.server_name_label)) },
            placeholder = { Text(stringResource(R.string.server_name_hint)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            enabled = !state.isWorking,
        )

        CredentialField(
            value = state.manualPasswordInput,
            onValueChange = onPasswordChange,
            enabled = !state.isWorking,
            modifier = Modifier.testTag(AddServerTags.MANUAL_PASSWORD_INPUT),
        )

        TrustUserCertificatesToggle(
            checked = state.trustUserCertificates,
            onCheckedChange = onTrustUserCertificatesChange,
            enabled = !state.isWorking,
        )

        Button(
            onClick = onConnect,
            enabled = state.manualUrlInput.isNotBlank() && !state.isWorking,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(AddServerTags.MANUAL_CONNECT_BUTTON),
        ) {
            Text(stringResource(R.string.connect_and_save))
        }

        Spacer(modifier = Modifier.height(8.dp))
        OnboardingGuideCard()
    }
}

/** A credential field with a show/hide toggle, so a long token can be checked without leaking it. */
@Composable
fun CredentialField(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(R.string.server_password_label)) },
        placeholder = { Text(stringResource(R.string.server_password_hint)) },
        supportingText = supportingText?.let { text -> { Text(text) } },
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    imageVector = if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = stringResource(
                        if (visible) R.string.server_password_hide else R.string.server_password_show,
                    ),
                )
            }
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        enabled = enabled,
    )
}

/**
 * The opt-in for a server whose HTTPS certificate is signed by an authority the user installed.
 *
 * It says plainly that this weakens verification for that server only, because that is the trade
 * being made (plan §2.4, §5.2).
 */
@Composable
fun TrustUserCertificatesToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(R.string.trust_user_certificates)
    val help = stringResource(R.string.trust_user_certificates_help)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .semantics { contentDescription = "$label. $help" }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = help,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun Context.clipboardText(): String {
    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    val clip = clipboard?.primaryClip ?: return ""
    if (clip.itemCount == 0) return ""
    return clip.getItemAt(0).text?.toString().orEmpty()
}
