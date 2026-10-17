package dev.opencode.android.core.designsystem.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenCodeThemeImporterTest {

    @Test
    fun mapsV2BaseTokensAndResolvesModeHueAliases() {
        val palette = OpenCodeThemeImporter.parse(
            """
            {
              "base": {
                "background": {
                  "base": "${'$'}hue.neutral.900",
                  "raised": { "base": "#181818", "high": "#222222", "max": "#333333" }
                },
                "text": { "base": "${'$'}hue.neutral.100", "muted": "#dddddd" },
                "border": { "base": "#666666" }
              },
              "dark": {
                "hue": {
                  "neutral": { "100": "#ffffff", "900": "#000000" },
                  "purple": { "500": "#7755aa" },
                  "accent": "${'$'}hue.purple"
                }
              }
            }
            """.trimIndent(),
            name = "Night",
        )

        assertEquals("Night", palette.name)
        assertEquals(Color.Black.toArgb(), palette.dark["background"])
        assertEquals(Color.White.toArgb(), palette.dark["onSurface"])
        assertEquals(Color(0xFF181818).toArgb(), palette.dark["surfaceVariant"])
        assertEquals(palette.dark, palette.light)
        assertTrue(palette.unsupportedTokens.isEmpty())
        assertTrue(OpenCodeThemeImporter.contrastFailures(palette, darkTheme = true).isEmpty())
    }

    @Test
    fun lightAndDarkModesCanProvideDifferentSchemes() {
        val palette = OpenCodeThemeImporter.parse(
            """
            {
              "base": {},
              "light": {
                "background": { "base": "#ffffff" },
                "text": { "base": "#595959", "muted": "#595959" }
              },
              "dark": {
                "background": { "base": "#000000" },
                "text": { "base": "#ffffff" }
              }
            }
            """.trimIndent(),
            name = "Contrast edge",
        )

        assertEquals(
            Color.White.toArgb(),
            palette.colorScheme(false, openCodeBaseColorScheme(false)).background.toArgb(),
        )
        assertEquals(
            Color(0xFF595959).toArgb(),
            palette.colorScheme(false, openCodeBaseColorScheme(false)).onSurface.toArgb(),
        )
        assertEquals(
            Color.Black.toArgb(),
            palette.colorScheme(true, openCodeBaseColorScheme(true)).background.toArgb(),
        )
        assertEquals(
            Color.White.toArgb(),
            palette.colorScheme(true, openCodeBaseColorScheme(true)).onSurface.toArgb(),
        )
        assertTrue(OpenCodeThemeImporter.contrastFailures(palette, darkTheme = false).isEmpty())
        assertTrue(OpenCodeThemeImporter.contrastFailures(palette, darkTheme = true).isEmpty())
    }

    @Test
    fun partialTokensUseDefaultsAndUnknownValuesAreReported() {
        val palette = OpenCodeThemeImporter.parse(
            """
            {
              "base": {
                    "background": {
                      "base": "#ffffff",
                      "raised": { "base": "#ffffff", "high": "#ffffff", "max": "#ffffff" }
                    },
                    "text": { "base": "#000000", "muted": "#000000" },
                "syntax": { "string": "#ff00ff" }
              },
              "light": {}
            }
            """.trimIndent(),
            name = "Partial",
        )

        assertEquals(Color.White.toArgb(), palette.dark["surfaceContainerHigh"])
        assertTrue(palette.unsupportedTokenCount >= 1)
        assertTrue(palette.unsupportedTokens.any { it == "base.syntax.string" })
        assertFalse("secondary" in palette.dark)
        assertTrue(OpenCodeThemeImporter.contrastFailures(palette, darkTheme = false).isEmpty())
    }

    @Test
    fun unresolvedSupportedReferenceIsReportedAndKeepsTheDefault() {
        val palette = OpenCodeThemeImporter.parse(
            """
            { "base": { "background": { "raised": { "high": "${'$'}hue.neutral.500" } } }, "dark": { "hue": {} } }
            """.trimIndent(),
            name = "Unresolved",
        )

        assertFalse("surfaceContainerHigh" in palette.dark)
        assertTrue(palette.unsupportedTokens.any { it.contains("background.raised.high") })
    }

    @Test
    fun lowContrastIsRejectedBeforePreviewOrApply() {
        val failure = runCatching {
            OpenCodeThemeImporter.parse(
                """
                {
                  "base": {
                    "background": { "base": "#777777" },
                    "text": { "base": "#777777" }
                  },
                  "light": {}
                }
                """.trimIndent(),
                name = "Unreadable",
            )
        }.exceptionOrNull() as? ThemeImportException

        assertNotNull(failure)
        assertEquals(ThemeImportFailure.LOW_CONTRAST, failure?.failure)
        assertTrue(failure?.details?.any { it.contains("onBackground/background") } == true)
    }

    @Test
    fun lowContrastOnRaisedSurfacesIsRejectedToo() {
        val failure = runCatching {
            OpenCodeThemeImporter.parse(
                """
                {
                  "base": {
                    "background": { "base": "#ffffff", "raised": { "high": "#f8f8f8" } },
                    "text": { "base": "#767676", "muted": "#767676" }
                  },
                  "light": {}
                }
                """.trimIndent(),
                name = "Raised contrast edge",
            )
        }.exceptionOrNull() as? ThemeImportException

        assertNotNull(failure)
        assertEquals(ThemeImportFailure.LOW_CONTRAST, failure?.failure)
        assertTrue(failure?.details?.any { it.contains("onSurface/surfaceContainerHigh") } == true)
    }

    @Test
    fun lowContrastImportedOutlineIsRejectedAtThreeToOne() {
        val failure = runCatching {
            OpenCodeThemeImporter.parse(
                """
                {
                  "base": {
                    "background": { "base": "#ffffff" },
                    "text": { "base": "#000000" },
                    "border": { "base": "#eeeeee" }
                  },
                  "light": {}
                }
                """.trimIndent(),
                name = "Invisible border",
            )
        }.exceptionOrNull() as? ThemeImportException

        assertNotNull(failure)
        assertEquals(ThemeImportFailure.LOW_CONTRAST, failure?.failure)
        assertTrue(failure?.details?.any { it.contains("outline/surface") } == true)
    }

    @Test
    fun excessiveJsonNestingReturnsMalformedFailure() {
        val depth = OpenCodeThemeImporter.MAX_JSON_NESTING + 1
        val nested = "[".repeat(depth) + "0" + "]".repeat(depth)
        val failure = runCatching {
            OpenCodeThemeImporter.parse("{\"base\":{\"extra\":$nested},\"light\":{}}", "Deep")
        }.exceptionOrNull() as? ThemeImportException

        assertNotNull(failure)
        assertEquals(ThemeImportFailure.MALFORMED_JSON, failure?.failure)
    }

    @Test
    fun deepHueAliasChainFallsBackAndIsReported() {
        val last = OpenCodeThemeImporter.MAX_COLOR_REFERENCE_DEPTH + 1
        val aliases = (0..last).joinToString(",") { index ->
            if (index == last) {
                "\"color$index\":{\"0\":\"#000000\"}"
            } else {
                "\"color$index\":\"${'$'}hue.color${index + 1}\""
            }
        }
        val source = """
            {
              "base": { "background": { "base": "${'$'}hue.color0.0" }, "text": { "base": "#000000" } },
              "light": { "hue": { $aliases } },
              "dark": { "hue": { $aliases }, "text": { "base": "#ffffff" } }
            }
        """.trimIndent()
        val palette = OpenCodeThemeImporter.parse(source, "Deep aliases")

        assertTrue(palette.unsupportedTokens.any { it.contains("background.base") })
        assertTrue(OpenCodeThemeImporter.contrastFailures(palette, darkTheme = false).isEmpty())
        assertTrue(OpenCodeThemeImporter.contrastFailures(palette, darkTheme = true).isEmpty())
    }

    @Test
    fun invalidDocumentShapesAndOversizedFilesHaveNormalFailures() {
        assertEquals(
            ThemeImportFailure.MALFORMED_JSON,
            runCatching {
                OpenCodeThemeImporter.parse("{", "bad")
            }.exceptionOrNull()?.let { (it as ThemeImportException).failure },
        )
        assertEquals(
            ThemeImportFailure.MISSING_BASE,
            runCatching {
                OpenCodeThemeImporter.parse("{\"dark\":{}}", "bad")
            }.exceptionOrNull()?.let { (it as ThemeImportException).failure },
        )
        assertEquals(
            ThemeImportFailure.MISSING_MODE,
            runCatching {
                OpenCodeThemeImporter.parse("{\"base\":{}}", "bad")
            }.exceptionOrNull()?.let { (it as ThemeImportException).failure },
        )
        assertEquals(
            ThemeImportFailure.TOO_LARGE,
            runCatching {
                OpenCodeThemeImporter.parse(" ".repeat(OpenCodeThemeImporter.MAX_SOURCE_BYTES + 1), "large")
            }.exceptionOrNull()?.let { (it as ThemeImportException).failure },
        )
    }

    @Test
    fun normalizedPaletteRoundTripsAndFallsBackForCorruptStorage() {
        val palette = OpenCodeThemeImporter.parse(
            """
            {
              "base": {
                    "background": {
                      "base": "#000000",
                      "raised": { "base": "#111111", "high": "#222222", "max": "#333333" },
                      "action": { "primary": { "base": "#222222" } }
                    },
                    "text": { "base": "#ffffff", "muted": "#ffffff", "action": { "primary": { "base": "#ffffff" } } }
              },
              "dark": {}
            }
            """.trimIndent(),
            name = "Persisted",
        )

        val restored = ImportedThemePalette.fromStoredJson(palette.toStoredJson())
        assertNotNull(restored)
        assertEquals(palette.name, restored?.name)
        assertEquals(palette.dark, restored?.dark)
        assertEquals(
            Color(0xFF222222).toArgb(),
            restored?.colorScheme(true, openCodeBaseColorScheme(true))?.primary?.toArgb(),
        )
        assertEquals(null, ImportedThemePalette.fromStoredJson("not-json"))
    }
}
