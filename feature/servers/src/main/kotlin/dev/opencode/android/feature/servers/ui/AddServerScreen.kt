package dev.opencode.android.feature.servers.ui

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.opencode.android.feature.servers.R
import dev.opencode.android.feature.servers.camera.QrCodeScannerView

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddServerScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AddServerViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(uiState.isSuccess) {
        if (uiState.isSuccess) {
            onNavigateBack()
        }
    }

    Scaffold(
        modifier = modifier.testTag("add_server_screen"),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.add_server_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
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
            PrimaryTabRow(selectedTabIndex = uiState.selectedTab) {
                Tab(
                    selected = uiState.selectedTab == 0,
                    onClick = { viewModel.selectTab(0) },
                    text = { Text(stringResource(R.string.tab_scan_qr)) },
                    icon = { Icon(Icons.Default.CameraAlt, contentDescription = null) },
                    modifier = Modifier.testTag("tab_scan_qr"),
                )
                Tab(
                    selected = uiState.selectedTab == 1,
                    onClick = { viewModel.selectTab(1) },
                    text = { Text(stringResource(R.string.tab_paste_link)) },
                    icon = { Icon(Icons.Default.ContentPaste, contentDescription = null) },
                    modifier = Modifier.testTag("tab_paste_link"),
                )
                Tab(
                    selected = uiState.selectedTab == 2,
                    onClick = { viewModel.selectTab(2) },
                    text = { Text(stringResource(R.string.tab_manual)) },
                    icon = { Icon(Icons.Default.Edit, contentDescription = null) },
                    modifier = Modifier.testTag("tab_manual"),
                )
            }

            Box(modifier = Modifier.weight(1f)) {
                when (uiState.selectedTab) {
                    0 -> ScanQrTab(
                        onQrScanned = viewModel::onQrCodeScanned,
                        onSwitchToPaste = { viewModel.selectTab(1) },
                    )
                    1 -> PasteLinkTab(
                        pairingLink = uiState.pairingLinkInput,
                        onLinkChange = viewModel::updatePairingLinkInput,
                        onPairClick = viewModel::pairWithPastedLink,
                        isConnecting = uiState.isConnecting,
                    )
                    2 -> ManualEntryTab(
                        url = uiState.manualUrlInput,
                        name = uiState.manualNameInput,
                        password = uiState.manualPasswordInput,
                        onUrlChange = viewModel::updateManualUrlInput,
                        onNameChange = viewModel::updateManualNameInput,
                        onPasswordChange = viewModel::updateManualPasswordInput,
                        onConnectClick = viewModel::connectManual,
                        isConnecting = uiState.isConnecting,
                    )
                }

                if (uiState.isConnecting) {
                    Card(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(32.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                        ),
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            CircularProgressIndicator()
                            Text(
                                text = "Connecting & validating...",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
        }
    }

    uiState.errorMessage?.let { error ->
        AlertDialog(
            onDismissRequest = viewModel::clearError,
            title = { Text("Connection Issue") },
            text = { Text(error) },
            confirmButton = {
                TextButton(onClick = viewModel::clearError) {
                    Text("OK")
                }
            },
        )
    }
}

@Composable
private fun ScanQrTab(
    onQrScanned: (String) -> Unit,
    onSwitchToPaste: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA,
            ) == PackageManager.PERMISSION_GRANTED,
        )
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted -> hasCameraPermission = granted },
    )

    if (hasCameraPermission) {
        Box(modifier = modifier.fillMaxSize()) {
            QrCodeScannerView(
                onQrCodeScanned = onQrScanned,
                modifier = Modifier.fillMaxSize(),
            )
            Card(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
                    .fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
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
    pairingLink: String,
    onLinkChange: (String) -> Unit,
    onPairClick: () -> Unit,
    isConnecting: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        OutlinedTextField(
            value = pairingLink,
            onValueChange = onLinkChange,
            label = { Text(stringResource(R.string.paste_link_label)) },
            placeholder = { Text(stringResource(R.string.paste_link_hint)) },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("paste_link_input"),
            singleLine = true,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    val clip = clipboard?.primaryClip
                    if (clip != null && clip.itemCount > 0) {
                        val text = clip.getItemAt(0).text?.toString() ?: ""
                        onLinkChange(text)
                    }
                },
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Default.ContentPaste, contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.paste_from_clipboard))
            }

            Button(
                onClick = onPairClick,
                enabled = pairingLink.isNotBlank() && !isConnecting,
                modifier = Modifier
                    .weight(1f)
                    .testTag("pair_button"),
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
    url: String,
    name: String,
    password: String,
    onUrlChange: (String) -> Unit,
    onNameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onConnectClick: () -> Unit,
    isConnecting: Boolean,
    modifier: Modifier = Modifier,
) {
    var passwordVisible by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        OutlinedTextField(
            value = url,
            onValueChange = onUrlChange,
            label = { Text(stringResource(R.string.server_url_label)) },
            placeholder = { Text(stringResource(R.string.server_url_hint)) },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("manual_url_input"),
            singleLine = true,
        )

        OutlinedTextField(
            value = name,
            onValueChange = onNameChange,
            label = { Text(stringResource(R.string.server_name_label)) },
            placeholder = { Text(stringResource(R.string.server_name_hint)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        OutlinedTextField(
            value = password,
            onValueChange = onPasswordChange,
            label = { Text(stringResource(R.string.server_password_label)) },
            placeholder = { Text(stringResource(R.string.server_password_hint)) },
            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                val icon = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility
                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                    Icon(icon, contentDescription = "Toggle password visibility")
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("manual_password_input"),
            singleLine = true,
        )

        Button(
            onClick = onConnectClick,
            enabled = url.isNotBlank() && !isConnecting,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("manual_connect_button"),
        ) {
            Text(stringResource(R.string.connect_and_save))
        }

        Spacer(modifier = Modifier.height(8.dp))
        OnboardingGuideCard()
    }
}
