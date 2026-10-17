package dev.opencode.android.feature.sessions.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.opencode.android.core.data.capability.RouteAvailability
import dev.opencode.android.core.data.composer.FileContentKind
import dev.opencode.android.core.data.preferences.ProjectPreferences
import dev.opencode.android.core.data.preferences.ThemePreferences
import dev.opencode.android.core.data.server.ServerDataRegistry
import dev.opencode.android.core.designsystem.theme.ImportedThemePalette
import dev.opencode.android.core.designsystem.theme.OpenCodeThemeImporter
import dev.opencode.android.core.designsystem.theme.ThemeImportException
import dev.opencode.android.core.designsystem.theme.ThemeImportFailure
import dev.opencode.android.core.model.Project
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The Settings tab: which project the checkout-scoped pages act on, and theme import choices.
 *
 * The server name is the home's ([HomeViewModel.serverName]); this owns only the project scope, which is a
 * per-server choice kept in [ProjectPreferences] so it survives leaving the tab, and the theme import state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val dataSets: ServerDataRegistry,
    private val projectPreferences: ProjectPreferences,
    private val themePreferences: ThemePreferences,
) : ViewModel() {

    private val _themeImportState = MutableStateFlow(ThemeImportUiState())
    val themeImportState: StateFlow<ThemeImportUiState> = _themeImportState.asStateFlow()

    val state: StateFlow<SettingsUiState> = dataSets.active
        .flatMapLatest { set ->
            if (set == null) {
                flowOf(SettingsUiState())
            } else {
                combine(
                    set.projects.state,
                    projectPreferences.added(set.serverId),
                    projectPreferences.settingsProject(set.serverId),
                    set.insights.pairDevice,
                ) { projects, added, chosen, pairAvailability ->
                    val offered = settingsProjects(projects.value.orEmpty(), added)
                    SettingsUiState(
                        projects = offered,
                        selectedProject = offered.firstOrNull { it.id == chosen } ?: offered.firstOrNull(),
                        isPairingAvailable = pairAvailability !is RouteAvailability.Absent,
                    )
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), SettingsUiState())

    init {
        viewModelScope.launch {
            themePreferences.importedTheme.collect { stored ->
                val name = ImportedThemePalette.fromStoredJson(stored)?.name
                _themeImportState.update { it.copy(appliedThemeName = name) }
            }
        }
    }

    fun selectProject(projectId: String) {
        val serverId = dataSets.active.value?.serverId ?: return
        viewModelScope.launch { projectPreferences.setSettingsProject(serverId, projectId) }
    }

    fun setThemePath(path: String) {
        _themeImportState.update {
            it.copy(path = path, isReading = false, preview = null, problem = null, details = emptyList())
        }
    }

    /** Reads only a JSON file under a `themes/` directory, through FileReader's bounded server API. */
    fun previewTheme() {
        val path = _themeImportState.value.path.trim()
        if (!isSafeThemePath(path)) {
            _themeImportState.update {
                it.copy(preview = null, problem = ThemeImportProblem.INVALID_PATH, details = emptyList())
            }
            return
        }
        val globalTheme = isGlobalThemePath(path)
        val directory = themeReadDirectory(path, state.value.selectedProject?.canonical)
        if (directory == null && !globalTheme) {
            _themeImportState.update {
                it.copy(preview = null, problem = ThemeImportProblem.PROJECT_REQUIRED, details = emptyList())
            }
            return
        }
        val dataSet = dataSets.active.value
        if (dataSet == null) {
            _themeImportState.update {
                it.copy(preview = null, problem = ThemeImportProblem.READ_FAILED, details = emptyList())
            }
            return
        }

        _themeImportState.update { it.copy(isReading = true, preview = null, problem = null, details = emptyList()) }
        viewModelScope.launch {
            val result = dataSet.files.read(directory, path)
            if (_themeImportState.value.path.trim() != path) return@launch
            val file = result.getOrNull()
            if (file == null) {
                _themeImportState.update { it.copy(isReading = false, problem = ThemeImportProblem.READ_FAILED) }
                return@launch
            }
            if (file.truncated || file.bytes.size > OpenCodeThemeImporter.MAX_SOURCE_BYTES) {
                _themeImportState.update { it.copy(isReading = false, problem = ThemeImportProblem.TOO_LARGE) }
                return@launch
            }
            val themeText = file.text
            if (file.kind != FileContentKind.TEXT || themeText == null) {
                _themeImportState.update { it.copy(isReading = false, problem = ThemeImportProblem.NOT_TEXT) }
                return@launch
            }
            try {
                val themeName = path.substringAfterLast('/').removeSuffix(".json").ifBlank { "OpenCode theme" }
                val preview = OpenCodeThemeImporter.parse(themeText, themeName)
                _themeImportState.update {
                    it.copy(isReading = false, preview = preview, problem = null, details = emptyList())
                }
            } catch (failure: ThemeImportException) {
                _themeImportState.update {
                    it.copy(
                        isReading = false,
                        preview = null,
                        problem = failure.toProblem(),
                        details = failure.details,
                    )
                }
            } catch (_: Exception) {
                _themeImportState.update {
                    it.copy(isReading = false, preview = null, problem = ThemeImportProblem.MALFORMED_JSON)
                }
            }
        }
    }

    fun applyThemePreview() {
        val preview = _themeImportState.value.preview ?: return
        viewModelScope.launch {
            runCatching { themePreferences.setImportedTheme(preview.toStoredJson()) }
                .onFailure { _themeImportState.update { state -> state.copy(problem = ThemeImportProblem.SAVE_FAILED) } }
        }
    }

    fun discardThemePreview() {
        _themeImportState.update { it.copy(preview = null, problem = null, details = emptyList()) }
    }

    fun resetImportedTheme() {
        viewModelScope.launch {
            runCatching { themePreferences.clearImportedTheme() }
                .onSuccess {
                    _themeImportState.update { it.copy(preview = null, problem = null, details = emptyList()) }
                }
                .onFailure { _themeImportState.update { state -> state.copy(problem = ThemeImportProblem.SAVE_FAILED) } }
        }
    }

    private fun ThemeImportException.toProblem(): ThemeImportProblem = when (failure) {
        ThemeImportFailure.TOO_LARGE -> ThemeImportProblem.TOO_LARGE
        ThemeImportFailure.MALFORMED_JSON -> ThemeImportProblem.MALFORMED_JSON
        ThemeImportFailure.MISSING_BASE -> ThemeImportProblem.MISSING_BASE
        ThemeImportFailure.MISSING_MODE -> ThemeImportProblem.MISSING_MODE
        ThemeImportFailure.LOW_CONTRAST -> ThemeImportProblem.LOW_CONTRAST
    }
}

enum class ThemeImportProblem {
    PROJECT_REQUIRED,
    INVALID_PATH,
    READ_FAILED,
    NOT_TEXT,
    TOO_LARGE,
    MALFORMED_JSON,
    MISSING_BASE,
    MISSING_MODE,
    LOW_CONTRAST,
    SAVE_FAILED,
}

data class ThemeImportUiState(
    val path: String = "",
    val isReading: Boolean = false,
    val preview: ImportedThemePalette? = null,
    val appliedThemeName: String? = null,
    val problem: ThemeImportProblem? = null,
    val details: List<String> = emptyList(),
)

/**
 * The projects Settings offers as a scope: the added ones in their order, or, before any is added, every project
 * the server lists, so a fresh server's accounts and providers are still reachable.
 */
fun settingsProjects(projects: List<Project>, added: List<String>): List<Project> {
    val byId = projects.associateBy { it.id }
    val mine = added.mapNotNull { byId[it] }
    return mine.ifEmpty { projects.sortedByDescending { it.time.updated } }
}

private const val STOP_TIMEOUT_MILLIS = 5_000L
private const val MAX_THEME_PATH_LENGTH = 512
private const val PROJECT_THEME_PATH_PREFIX = ".opencode/themes/"
private const val GLOBAL_THEME_PATH_PREFIX = "~/.config/opencode/themes/"

/** Only a direct JSON child of OpenCode's project or global theme directory is readable. */
internal fun isSafeThemePath(path: String): Boolean {
    if (path.length > MAX_THEME_PATH_LENGTH || path.contains('\\') || path.contains('\u0000')) return false
    return (
        path.startsWith(PROJECT_THEME_PATH_PREFIX) &&
            path.removePrefix(PROJECT_THEME_PATH_PREFIX).isThemeJsonFile()
        ) ||
        (
            path.startsWith(GLOBAL_THEME_PATH_PREFIX) &&
                path.removePrefix(GLOBAL_THEME_PATH_PREFIX).isThemeJsonFile()
            )
}

/** The global API read has no project-directory query parameter. */
internal fun isGlobalThemePath(path: String): Boolean =
    isSafeThemePath(path) && path.startsWith(GLOBAL_THEME_PATH_PREFIX)

internal fun themeReadDirectory(path: String, projectDirectory: String?): String? =
    if (isGlobalThemePath(path)) null else projectDirectory

private fun String.isThemeJsonFile(): Boolean =
    '/' !in this && isNotBlank() && endsWith(".json", ignoreCase = true) && substringBeforeLast('.').isNotBlank()
