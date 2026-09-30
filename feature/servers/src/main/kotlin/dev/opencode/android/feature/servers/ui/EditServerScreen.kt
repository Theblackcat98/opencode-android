package dev.opencode.android.feature.servers.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.opencode.android.core.designsystem.text.SyncedTextField
import dev.opencode.android.feature.servers.R

/**
 * Edits a saved server: name, address, credential, default flag, and whether to trust a
 * user-installed certificate authority.
 *
 * The credential field starts empty on purpose; leaving it empty keeps the stored one, which is how
 * a rotated password is replaced without ever showing the old token.
 */
@Composable
fun EditServerScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: EditServerViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(uiState.saved) {
        if (uiState.saved) onNavigateBack()
    }

    EditServerContent(
        uiState = uiState,
        onNavigateBack = onNavigateBack,
        onNameChange = viewModel::updateName,
        onUrlChange = viewModel::updateUrl,
        onPasswordChange = viewModel::updatePassword,
        onDefaultChange = viewModel::setDefault,
        onTrustUserCertificatesChange = viewModel::setTrustUserCertificates,
        onSave = viewModel::save,
        onClearError = viewModel::clearError,
        modifier = modifier,
    )
}

/**
 * The edit form for a given [uiState], with no view model behind it.
 *
 * Separate from [EditServerScreen] so a test can stand in for a view model that echoes each keystroke
 * late, which is the thing the fields have to survive.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EditServerContent(
    uiState: EditServerUiState,
    onNavigateBack: () -> Unit,
    onNameChange: (String) -> Unit,
    onUrlChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onDefaultChange: (Boolean) -> Unit,
    onTrustUserCertificatesChange: (Boolean) -> Unit,
    onSave: () -> Unit,
    onClearError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.testTag(EditServerTags.SCREEN),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.edit_server_title)) },
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
        if (uiState.profile == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator()
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                SyncedTextField(
                    value = uiState.name,
                    onValueChange = onNameChange,
                    label = { Text(stringResource(R.string.server_name_label)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(EditServerTags.NAME_INPUT),
                    singleLine = true,
                    enabled = !uiState.isWorking,
                )

                SyncedTextField(
                    value = uiState.url,
                    onValueChange = onUrlChange,
                    label = { Text(stringResource(R.string.server_url_label)) },
                    placeholder = { Text(stringResource(R.string.server_url_hint)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(EditServerTags.URL_INPUT),
                    singleLine = true,
                    enabled = !uiState.isWorking,
                )

                CredentialField(
                    value = uiState.password,
                    onValueChange = onPasswordChange,
                    enabled = !uiState.isWorking,
                    supportingText = stringResource(R.string.edit_server_password_hint),
                    modifier = Modifier.testTag(EditServerTags.PASSWORD_INPUT),
                )

                DefaultServerToggle(
                    checked = uiState.isDefault,
                    onCheckedChange = onDefaultChange,
                    enabled = !uiState.isWorking,
                )

                TrustUserCertificatesToggle(
                    checked = uiState.trustUserCertificates,
                    onCheckedChange = onTrustUserCertificatesChange,
                    enabled = !uiState.isWorking,
                )

                Button(
                    onClick = onSave,
                    enabled = !uiState.isWorking,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(EditServerTags.SAVE_BUTTON),
                ) {
                    Text(stringResource(R.string.edit_server_save))
                }

                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }

    uiState.error?.let { error ->
        ConnectionErrorDialog(
            error = error,
            technicalDetail = uiState.errorTechnicalDetail,
            onPairAgain = onClearError,
            onDismiss = onClearError,
        )
    }
}

object EditServerTags {
    const val SCREEN = "edit_server_screen"
    const val NAME_INPUT = "edit_server_name_input"
    const val URL_INPUT = "edit_server_url_input"
    const val PASSWORD_INPUT = "edit_server_password_input"
    const val SAVE_BUTTON = "edit_server_save_button"
}

@Composable
private fun DefaultServerToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(R.string.edit_server_default)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onCheckedChange)
            .semantics { contentDescription = label }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        androidx.compose.material3.Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
    }
}
