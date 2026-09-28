package dev.opencode.android.feature.composer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.opencode.android.core.designsystem.format.Formatters
import dev.opencode.android.core.model.FileSystemEntry
import dev.opencode.android.core.model.ModelRef
import dev.opencode.android.feature.composer.R

/**
 * The new-session sheet: a location, an agent, a model and an optional title (plan §6).
 *
 * **The order is the server's dependency order.** A directory decides which agents and models exist,
 * because both catalogs are location-scoped; the agent and the model are then optional, because
 * leaving them out is what lets `model.default` and the configured default agent decide. The title is
 * optional for the same reason: the server generates one from the first turn.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewSessionSheet(
    state: NewSessionUiState,
    favorites: List<ModelRef>,
    recents: List<ModelRef>,
    modelSearch: String,
    onTitleChange: (String) -> Unit,
    onSelectProject: (LocationChoice) -> Unit,
    onSelectDirectory: (String) -> Unit,
    onOpenBrowser: () -> Unit,
    onSelectAgent: (String) -> Unit,
    onSelectModel: (ModelRef) -> Unit,
    onBrowseUp: () -> Unit,
    onBrowseEnter: (String) -> Unit,
    onBrowseUse: () -> Unit,
    onBrowseDismiss: () -> Unit,
    onCreate: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, modifier = modifier) {
        NewSessionContent(
            state = state,
            favorites = favorites,
            recents = recents,
            modelSearch = modelSearch,
            onTitleChange = onTitleChange,
            onSelectProject = onSelectProject,
            onSelectDirectory = onSelectDirectory,
            onOpenBrowser = onOpenBrowser,
            onSelectAgent = onSelectAgent,
            onSelectModel = onSelectModel,
            onCreate = onCreate,
        )
    }
    if (state.browsing) {
        DirectoryBrowser(
            state = state,
            onEnter = onBrowseEnter,
            onUp = onBrowseUp,
            onUse = onBrowseUse,
            onDismiss = onBrowseDismiss,
        )
    }
}

/**
 * The new-session form itself, without the sheet chrome.
 *
 * A `ModalBottomSheet` needs a sheet host to lay out, so the body is a composable of its own: the
 * sheet wraps it and a screenshot renders it directly, which means the baseline shows the form the
 * user actually reads rather than an empty overlay.
 */
@Composable
fun NewSessionContent(
    state: NewSessionUiState,
    favorites: List<ModelRef>,
    recents: List<ModelRef>,
    modelSearch: String,
    onTitleChange: (String) -> Unit,
    onSelectProject: (LocationChoice) -> Unit,
    onSelectDirectory: (String) -> Unit,
    onOpenBrowser: () -> Unit,
    onSelectAgent: (String) -> Unit,
    onSelectModel: (ModelRef) -> Unit,
    onCreate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.new_session_title), style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = state.title,
            onValueChange = onTitleChange,
            label = { Text(stringResource(R.string.new_session_title_hint)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions.Default,
            modifier = Modifier.fillMaxWidth(),
        )
        LocationSection(state, onSelectProject, onSelectDirectory, onOpenBrowser)
        AgentSection(state, onSelectAgent)
        ModelSection(state, favorites, recents, modelSearch, onSelectModel)
        state.error?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Button(
            onClick = onCreate,
            enabled = state.canCreate,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                stringResource(
                    if (state.creating) R.string.new_session_creating else R.string.new_session_create,
                ),
            )
        }
    }
}

/** Where the session will run: the projects, the recent directories, or the browser. */
@Composable
private fun LocationSection(
    state: NewSessionUiState,
    onSelectProject: (LocationChoice) -> Unit,
    onSelectDirectory: (String) -> Unit,
    onOpenBrowser: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(R.string.new_session_where),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = stringResource(R.string.new_session_where_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.projects.isNotEmpty()) {
            ModelSectionHeader(stringResource(R.string.new_session_projects))
            state.projects.forEach { project ->
                // A project may declare no name; its canonical directory's last segment is the
                // label the home screen already shows, so the two agree.
                val label = project.name?.takeIf { it.isNotBlank() }
                    ?: Formatters.directoryName(project.canonical)
                val choice = LocationChoice.ProjectChoice(project.id, label, project.canonical)
                LocationRow(
                    title = label,
                    subtitle = Formatters.directoryTail(project.canonical, 3),
                    selected = state.location == choice,
                    onClick = { onSelectProject(choice) },
                )
            }
        }
        if (state.recentDirectories.isNotEmpty()) {
            ModelSectionHeader(stringResource(R.string.new_session_recent_directories))
            state.recentDirectories.forEach { directory ->
                LocationRow(
                    title = Formatters.directoryName(directory),
                    subtitle = directory,
                    selected = state.location == LocationChoice.Browsed(directory, null),
                    onClick = { onSelectDirectory(directory) },
                )
            }
        }
        TextButton(onClick = onOpenBrowser, enabled = state.directory != null) {
            Text(stringResource(R.string.new_session_browse))
        }
    }
}

/** One place, as a selectable row. */
@Composable
private fun LocationRow(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.padding(start = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The agent, with its description. An empty list means the server decides. */
@Composable
private fun AgentSection(state: NewSessionUiState, onSelectAgent: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ModelSectionHeader(stringResource(R.string.new_session_agent))
        if (state.primaryAgents.isEmpty()) {
            Text(
                text = stringResource(R.string.new_session_no_agents),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
        state.primaryAgents.forEach { agent ->
            val label = dev.opencode.android.core.data.catalog.AgentCatalog.label(agent)
            LocationRow(
                title = label,
                subtitle = agent.description.orEmpty(),
                selected = state.agent == agent.id,
                onClick = { onSelectAgent(agent.id) },
            )
        }
    }
}

/** The model, grouped by provider. The server's default is pre-selected. */
@Composable
private fun ModelSection(
    state: NewSessionUiState,
    favorites: List<ModelRef>,
    recents: List<ModelRef>,
    search: String,
    onSelectModel: (ModelRef) -> Unit,
) {
    val groups = state.modelGroups(favorites, recents, search)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ModelSectionHeader(stringResource(R.string.new_session_model))
        if (state.model == null) {
            Text(
                text = stringResource(R.string.new_session_model_default),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (groups.isEmpty()) {
            ModelEmptyState()
            return@Column
        }
        groups.forEach { group ->
            Text(
                text = group.providerID,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, top = 4.dp),
            )
            group.entries.forEach { entry ->
                val current = state.model
                val active = current != null &&
                    current.id == entry.info.id &&
                    current.providerID == entry.info.providerID
                LocationRow(
                    title = entry.info.name,
                    subtitle = entry.ref.toString(),
                    // A selection with a variant is the same model, so the row stays selected and the
                    // variant chips in the model picker show which one is active.
                    selected = active,
                    onClick = { onSelectModel(entry.ref) },
                )
            }
        }
    }
}

/**
 * The `fs.list` browser.
 *
 * It shows the server's own listing, including the `..` entry it returns, because a path this client
 * assembled would be a path the server never offered. "Up" is therefore a button rather than a
 * gesture on a path string.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DirectoryBrowser(
    state: NewSessionUiState,
    onEnter: (String) -> Unit,
    onUp: () -> Unit,
    onUse: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, modifier = modifier) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onUp) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.new_session_browse_up),
                    )
                }
                Text(
                    text = state.browserPath ?: state.directory.orEmpty(),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            HorizontalDivider()
            if (state.browserLoading) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.padding(8.dp))
                    Text(stringResource(R.string.new_session_browse_loading))
                }
            } else if (state.entries.isEmpty()) {
                Text(
                    text = stringResource(R.string.new_session_browse_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                state.entries.forEach { entry ->
                    BrowserRow(entry) { onEnter(entry.path) }
                }
            }
            Button(onClick = onUse, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.new_session_browse_use))
            }
        }
    }
}

/** One directory or file; a file is shown but not selectable, because a location is a directory. */
@Composable
private fun BrowserRow(entry: FileSystemEntry, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(entry.path, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingContent = {
            Icon(
                imageVector = if (entry.isDirectory) Icons.Filled.Folder else Icons.AutoMirrored.Filled.InsertDriveFile,
                contentDescription = null,
            )
        },
        modifier = if (entry.isDirectory) {
            Modifier.clickable(onClick = onClick).semantics { contentDescription = entry.name }
        } else {
            Modifier
        },
    )
}

/** A filter chip reused by the model picker; kept here so the sheet and the picker agree. */
@Composable
fun VariantChip(variant: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(variant) })
}
