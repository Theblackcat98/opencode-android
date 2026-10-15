package dev.opencode.android.core.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.themePreferences: DataStore<Preferences> by preferencesDataStore(name = "theme_preferences")

/** Client-owned appearance choice; imported theme JSON is never written back to the server. */
interface ThemePreferences {
    val importedTheme: Flow<String?>
    suspend fun setImportedTheme(normalizedPalette: String)
    suspend fun clearImportedTheme()
}

@Singleton
class DataStoreThemePreferences @Inject constructor(
    private val context: Context,
) : ThemePreferences {
    override val importedTheme: Flow<String?> =
        context.themePreferences.data.map { it[IMPORTED_THEME_KEY] }

    override suspend fun setImportedTheme(normalizedPalette: String) {
        context.themePreferences.edit { it[IMPORTED_THEME_KEY] = normalizedPalette }
    }

    override suspend fun clearImportedTheme() {
        context.themePreferences.edit { it.remove(IMPORTED_THEME_KEY) }
    }

    private companion object {
        val IMPORTED_THEME_KEY = stringPreferencesKey("imported_theme_v1")
    }
}
