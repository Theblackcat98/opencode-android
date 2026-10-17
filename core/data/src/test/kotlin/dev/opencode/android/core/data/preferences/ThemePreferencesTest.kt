package dev.opencode.android.core.data.preferences

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ThemePreferencesTest {

    private lateinit var preferences: DataStoreThemePreferences

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        preferences = DataStoreThemePreferences(context)
    }

    @Test
    fun persistsAndClearsImportedTheme() = runTest {
        preferences.clearImportedTheme()
        assertNull(preferences.importedTheme.first())

        val sampleTheme = "{\"version\":1,\"name\":\"Midnight\"}"
        preferences.setImportedTheme(sampleTheme)
        assertEquals(sampleTheme, preferences.importedTheme.first())

        preferences.clearImportedTheme()
        assertNull(preferences.importedTheme.first())
    }
}
