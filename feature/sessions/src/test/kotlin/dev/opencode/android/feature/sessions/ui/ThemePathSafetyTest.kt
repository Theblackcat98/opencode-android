package dev.opencode.android.feature.sessions.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemePathSafetyTest {

    @Test
    fun acceptsOnlyDocumentedProjectAndGlobalThemePaths() {
        assertTrue(isSafeThemePath("~/.config/opencode/themes/night.json"))
        assertTrue(isSafeThemePath(".opencode/themes/night.JSON"))
        assertTrue(isGlobalThemePath("~/.config/opencode/themes/night.json"))
        assertFalse(isGlobalThemePath(".opencode/themes/night.json"))
        assertNull(themeReadDirectory("~/.config/opencode/themes/night.json", "/projects/selected"))
        assertEquals("/projects/selected", themeReadDirectory(".opencode/themes/night.json", "/projects/selected"))
    }

    @Test
    fun rejectsTraversalNonThemeAndNonJsonPaths() {
        assertFalse(isSafeThemePath("../themes/night.json"))
        assertFalse(isSafeThemePath(".opencode/themes/../secrets.json"))
        assertFalse(isSafeThemePath("/home/user/.config/opencode/themes/night.json"))
        assertFalse(isSafeThemePath("/etc/themes/secrets.json"))
        assertFalse(isSafeThemePath("src/themes/night.json"))
        assertFalse(isSafeThemePath("themes/night.json"))
        assertFalse(isSafeThemePath(".opencode/themes/nested/night.json"))
        assertFalse(isSafeThemePath(".opencode/config.json"))
        assertFalse(isSafeThemePath(".opencode/themes/night.yaml"))
        assertFalse(isSafeThemePath(".opencode\\themes\\night.json"))
        assertFalse(isSafeThemePath(".opencode/themes/\u0000night.json"))
        assertFalse(isSafeThemePath("x".repeat(513) + "/themes/night.json"))
    }
}
