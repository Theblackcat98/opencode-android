package dev.opencode.android.feature.execution

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.opencode.android.core.model.Project

/**
 * Project settings as a destination, not a sheet (plan §6, "Project settings").
 *
 * **It is a screen because it is reached from a project row and from a worktree panel.** The worktree
 * panel is where a project's `commands.start` and its canonical checkout are relevant — a worktree is
 * made from the checkout and can be launched with the project's start command — so the settings live one
 * tap from the panel that needs them. A sheet over a panel would have to be dismissed to reach
 * anything else, and a sheet that cannot be dismissed to do a job is a dead end.
 *
 * **The icon is edited as one object, which is what the server replaces.** `Project.Icon` is not
 * merged field by field, so three independent-looking controls would let a user set a colour on one call
 * and an emoji on another and lose the first. What is shown is the icon as it will be — the emoji on
 * the colour, and the URL stated rather than fetched — plus the URL field itself, because a project can
 * have either an override or an image and the plan asks for both.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectSettingsHost(
    projectId: String,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ProjectSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(projectId) { viewModel.open(projectId) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = state.project?.name?.takeIf { it.isNotBlank() }
                            ?: stringResource(R.string.project_settings_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        ProjectSettingsSheet(
            state = state,
            onNameChange = viewModel::setName,
            onColorChange = viewModel::setColor,
            onEmojiChange = viewModel::setEmoji,
            onIconUrlChange = viewModel::setIconUrl,
            onStartCommandChange = viewModel::setStartCommand,
            onCanonicalChange = viewModel::setCanonical,
            onSave = viewModel::save,
            onDismiss = onNavigateBack,
            modifier = Modifier.padding(padding),
        )
    }
}

/**
 * The project's name, icon, start command and canonical checkout (`project.update`).
 *
 * **The button is enabled by [ProjectSettingsUiState.dirty] and nothing else**, so a Save that has
 * nothing to send cannot be pressed — the server would answer `200` with an unchanged project and the
 * user would be told their change was saved.
 *
 * **The canonical directory is editable because the plan makes it a setting.** It is the checkout the
 * project resolves to, and a user whose server resolved a repository to the wrong directory needs to
 * be able to say so; that is also why it is shown rather than hidden as "the path, which is fixed".
 */
@Composable
fun ProjectSettingsSheet(
    state: ProjectSettingsUiState,
    onNameChange: (String) -> Unit,
    onColorChange: (String) -> Unit,
    onEmojiChange: (String) -> Unit,
    onIconUrlChange: (String) -> Unit,
    onStartCommandChange: (String) -> Unit,
    onCanonicalChange: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .testTag(ProjectSettingsTags.SHEET),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProjectIconBadge(state)
            Text(
                text = stringResource(R.string.project_settings_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
        ProjectField(state.name, onNameChange, R.string.project_settings_name)
        ProjectField(state.emoji, onEmojiChange, R.string.project_settings_emoji)
        ProjectField(state.color, onColorChange, R.string.project_settings_color, monospace = true)
        ProjectField(state.iconUrl, onIconUrlChange, R.string.project_settings_icon_url, monospace = true)
        ProjectField(state.startCommand, onStartCommandChange, R.string.project_settings_start, monospace = true)
        ProjectField(state.canonical, onCanonicalChange, R.string.project_settings_canonical, monospace = true)
        Text(
            text = stringResource(R.string.project_settings_start_hint),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            text = stringResource(R.string.project_settings_icon_hint),
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onDismiss) {
                Text(stringResource(R.string.action_close))
            }
            Button(
                onClick = onSave,
                enabled = state.dirty && !state.saving,
                modifier = Modifier.testTag(ProjectSettingsTags.SAVE),
            ) {
                Text(
                    if (state.saving) {
                        stringResource(R.string.project_settings_saving)
                    } else {
                        stringResource(R.string.project_settings_save)
                    },
                )
            }
        }
        state.error?.let { error ->
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag(ProjectSettingsTags.ERROR),
            )
        }
        if (state.saved) {
            Text(
                text = stringResource(R.string.project_settings_saved),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag(ProjectSettingsTags.SAVED),
            )
        }
    }
}

/**
 * The icon as it will be drawn in the project list.
 *
 * **A URL is stated, not fetched.** `blockNetworkLoads` is on for the terminal's WebView and this
 * screen is plain Compose with no image loader of its own; a remote icon is the project list's business
 * and this badge says when one is set rather than pretending a remote image is available offline.
 */
@Composable
private fun ProjectIconBadge(state: ProjectSettingsUiState) {
    val background = state.color.toComposeColorOrDefault(MaterialTheme.colorScheme.secondaryContainer)
    Column(
        modifier = Modifier
            .testTag(ProjectSettingsTags.BADGE)
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = state.emoji.ifBlank { state.project?.icon?.override.orEmpty().ifBlank { "·" } },
            style = MaterialTheme.typography.titleLarge,
            color = background,
        )
        if (state.project?.icon?.url != null) {
            Text(
                text = stringResource(R.string.project_settings_url_set),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

/** One labelled field, so the seven of them are one composable rather than seven copies. */
@Composable
private fun ProjectField(
    value: String,
    onValueChange: (String) -> Unit,
    labelRes: Int,
    monospace: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(labelRes)) },
        singleLine = true,
        textStyle = if (monospace) {
            MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
        } else {
            MaterialTheme.typography.bodyMedium
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * A `#rgb`, `#rrggbb` or `#aarrggbb` colour, or the theme's own when it is not one.
 *
 * **Parsed here rather than through `Color.parseColor`,** so that a `#rgb` shorthand — which
 * `parseColor` rejects — is accepted: the value comes from a free-text field and the shorthand is what
 * a person types. Alpha is defaulted to opaque rather than to zero, which is what a three-digit colour
 * with no alpha component means.
 */
private fun String.toComposeColorOrDefault(fallback: androidx.compose.ui.graphics.Color): androidx.compose.ui.graphics.Color {
    val hex = trim().removePrefix("#")
    val expanded = when (hex.length) {
        3, 4 -> hex.map { "$it$it" }.joinToString("")
        6, 8 -> hex
        else -> return fallback
    }
    val value = expanded.toLongOrNull(16) ?: return fallback
    val argb = if (expanded.length == 6) value or 0xFF00_0000L else value
    return androidx.compose.ui.graphics.Color(argb.toInt())
}

/** The tags the tests and the baselines address. */
object ProjectSettingsTags {
    const val SHEET: String = "project-settings"
    const val SAVE: String = "project-settings-save"
    const val SAVED: String = "project-settings-saved"
    const val BADGE: String = "project-settings-badge"
    const val ERROR: String = "project-settings-error"
}

/** The icon a project list row draws, kept beside the settings sheet that sets it. */
@Composable
fun ProjectIconLabel(project: Project): String = project.icon?.override
    ?: project.icon?.url?.substringAfterLast('/')
    ?: project.name.orEmpty()
