package dev.opencode.android.feature.sessions.ui

import dev.opencode.android.core.data.preferences.ThemePreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class FakeThemePreferences(initial: String? = null) : ThemePreferences {
    val state = MutableStateFlow<String?>(initial)
    override val importedTheme: Flow<String?> = state
    override suspend fun setImportedTheme(normalizedPalette: String) {
        state.value = normalizedPalette
    }
    override suspend fun clearImportedTheme() {
        state.value = null
    }
}
