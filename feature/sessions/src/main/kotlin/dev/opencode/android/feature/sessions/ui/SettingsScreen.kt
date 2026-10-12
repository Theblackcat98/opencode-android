package dev.opencode.android.feature.sessions.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.PhonelinkSetup
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.opencode.android.core.designsystem.component.OpenCodeChip
import dev.opencode.android.core.designsystem.component.OpenCodeTopAppBar
import dev.opencode.android.core.designsystem.theme.OpenCodeThemeExtras
import dev.opencode.android.core.model.Project
import dev.opencode.android.feature.sessions.R

/**
 * Where a Settings row goes.
 *
 * [needsProject] marks the screens that are scoped to a checkout (accounts, providers, MCP, plugins, web search,
 * configuration, catalogs, permissions); they are disabled until there is a project to scope them to. The rest
 * are server-wide or app-wide.
 */
enum class ManageDestination(
    @StringRes val labelRes: Int,
    val icon: ImageVector,
    val needsProject: Boolean,
) {
    SERVER_STATUS(R.string.settings_server_status, Icons.Outlined.MonitorHeart, needsProject = false),
    EDIT_SERVER(R.string.settings_edit_server, Icons.Outlined.Edit, needsProject = false),
    PAIR_DEVICE(R.string.home_manage_pair_device, Icons.Outlined.PhonelinkSetup, needsProject = false),
    EVENT_INSPECTOR(R.string.settings_event_inspector, Icons.Outlined.Timeline, needsProject = false),
    SWITCH_SERVER(R.string.home_switch_server, Icons.Outlined.SwapHoriz, needsProject = false),

    ACCOUNTS(R.string.home_manage_accounts, Icons.Outlined.AccountCircle, needsProject = true),
    PROVIDERS(R.string.home_manage_providers, Icons.Outlined.Cloud, needsProject = true),
    MCP(R.string.home_manage_mcp, Icons.Outlined.Hub, needsProject = true),
    PLUGINS(R.string.home_manage_plugins, Icons.Outlined.Extension, needsProject = true),
    WEB_SEARCH(R.string.home_manage_web_search, Icons.Outlined.Public, needsProject = true),

    CONFIGURATION(R.string.home_manage_configuration, Icons.Outlined.Settings, needsProject = true),
    AGENTS(R.string.home_manage_agents, Icons.Outlined.SmartToy, needsProject = true),
    DEFINITIONS(R.string.home_manage_definitions, Icons.Outlined.Code, needsProject = true),
    PERMISSIONS(R.string.home_manage_permissions, Icons.Outlined.Lock, needsProject = true),

    USAGE(R.string.home_manage_usage, Icons.Outlined.BarChart, needsProject = false),
    QUICK_ASK(R.string.home_manage_quick_ask, Icons.AutoMirrored.Outlined.Chat, needsProject = false),
    RPC_CONSOLE(R.string.home_manage_rpc, Icons.Outlined.Terminal, needsProject = false),
    MAINTENANCE(R.string.home_manage_maintenance, Icons.Outlined.Build, needsProject = false),

    CHANGELOG(R.string.settings_changelog, Icons.AutoMirrored.Outlined.Article, needsProject = false),
}

/** The Settings sections, in the order they are drawn. */
enum class SettingsSection(@StringRes val titleRes: Int, val entries: List<ManageDestination>) {
    SERVER(
        R.string.settings_section_server,
        listOf(
            ManageDestination.SERVER_STATUS,
            ManageDestination.EDIT_SERVER,
            ManageDestination.PAIR_DEVICE,
            ManageDestination.EVENT_INSPECTOR,
            ManageDestination.SWITCH_SERVER,
        ),
    ),
    INTEGRATIONS(
        R.string.settings_section_integrations,
        listOf(
            ManageDestination.ACCOUNTS,
            ManageDestination.PROVIDERS,
            ManageDestination.MCP,
            ManageDestination.PLUGINS,
            ManageDestination.WEB_SEARCH,
        ),
    ),
    CONFIGURATION(
        R.string.settings_section_configuration,
        listOf(
            ManageDestination.CONFIGURATION,
            ManageDestination.AGENTS,
            ManageDestination.DEFINITIONS,
            ManageDestination.PERMISSIONS,
        ),
    ),
    INSIGHTS(
        R.string.settings_section_insights,
        listOf(ManageDestination.USAGE, ManageDestination.QUICK_ASK, ManageDestination.RPC_CONSOLE),
    ),
    MAINTENANCE(R.string.settings_section_maintenance, listOf(ManageDestination.MAINTENANCE)),
    APP(R.string.settings_section_app, listOf(ManageDestination.CHANGELOG)),
}

/** What the Settings screen draws. */
data class SettingsUiState(
    val serverName: String? = null,
    /** The projects a checkout-scoped page can be scoped to: the added ones, or every one when none is added. */
    val projects: List<Project> = emptyList(),
    val selectedProject: Project? = null,
)

/**
 * The server's settings, grouped: what used to be a fourteen-item overflow menu on the home and a second copy of
 * it as chips under the sessions.
 *
 * **The project row decides the directory.** Accounts, providers, MCP, plugins, web search, configuration, the
 * catalogs and permissions are all scoped to a checkout; the old menu silently used the first project the server
 * listed, which was whichever directory the server saw first. Here it is the one the user picks, remembered per
 * server, and those rows are disabled while there is no project at all.
 */
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onSelectProject: (String) -> Unit,
    onOpen: (ManageDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = OpenCodeThemeExtras.spacing
    Scaffold(
        modifier = modifier,
        topBar = {
            OpenCodeTopAppBar(
                title = stringResource(R.string.settings_title),
                subtitle = state.serverName?.let { name -> { Text(name) } },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(bottom = spacing.xl),
        ) {
            item(key = "project") {
                ProjectScope(state, onSelectProject)
            }
            SettingsSection.entries.forEach { section ->
                item(key = "section-${section.name}") {
                    Text(
                        text = stringResource(section.titleRes),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .padding(start = spacing.xl, end = spacing.xl, top = spacing.xl, bottom = spacing.xs)
                            .semantics { heading() },
                    )
                }
                items(items = section.entries, key = { it.name }) { destination ->
                    SettingsRow(
                        destination = destination,
                        enabled = !destination.needsProject || state.selectedProject != null,
                        onClick = { onOpen(destination) },
                    )
                }
            }
        }
    }
}

/** The tags the Settings screen's tests address. */
object SettingsTags {
    fun row(destination: ManageDestination): String = "settings:${destination.name}"
}

@Composable
private fun ProjectScope(state: SettingsUiState, onSelectProject: (String) -> Unit) {
    val spacing = OpenCodeThemeExtras.spacing
    Column(modifier = Modifier.padding(top = spacing.sm)) {
        Text(
            text = stringResource(R.string.settings_project_scope),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = spacing.xl, vertical = spacing.xs).semantics { heading() },
        )
        if (state.projects.isEmpty()) {
            Text(
                text = stringResource(R.string.settings_project_none),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = spacing.xl),
            )
        } else {
            LazyRow(
                contentPadding = PaddingValues(horizontal = spacing.xl),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                items(items = state.projects, key = { it.id }) { project ->
                    OpenCodeChip(
                        label = projectLabel(project),
                        selected = project.id == state.selectedProject?.id,
                        onClick = { onSelectProject(project.id) },
                    )
                }
            }
            state.selectedProject?.let {
                Text(
                    text = it.canonical,
                    style = OpenCodeThemeExtras.code.small,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = spacing.xl, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun SettingsRow(destination: ManageDestination, enabled: Boolean, onClick: () -> Unit) {
    val alpha = if (enabled) 1f else DISABLED_ALPHA
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .testTag(SettingsTags.row(destination))
            .padding(horizontal = OpenCodeThemeExtras.spacing.xl, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = destination.icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
        )
        Spacer(Modifier.size(20.dp))
        Text(
            text = stringResource(destination.labelRes),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
            modifier = Modifier.weight(1f),
        )
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
        )
    }
}

private const val DISABLED_ALPHA = 0.38f
