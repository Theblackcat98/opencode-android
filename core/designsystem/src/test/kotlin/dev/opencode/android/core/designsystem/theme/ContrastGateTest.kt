package dev.opencode.android.core.designsystem.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * WCAG contrast gate for the theme's text/background pairs (issue #27).
 *
 * Every `onX` color must hit 4.5:1 against its `X` background in both schemes — WCAG AA for
 * normal text. If someone edits the palette without checking contrast, this fails instead of
 * shipping unreadable text. The audit found pairs at 4.52–4.58:1 with no headroom; this test
 * locks the floor so they cannot silently slip below it.
 */
class ContrastGateTest {

    /** sRGB channel to linear light. */
    private fun linear(c: Double): Double =
        if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)

    /** Relative luminance per WCAG 2.1. */
    private fun luminance(color: Color): Double {
        val argb = color.toArgb()
        val r = linear(((argb shr 16) and 0xFF) / 255.0)
        val g = linear(((argb shr 8) and 0xFF) / 255.0)
        val b = linear((argb and 0xFF) / 255.0)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    /** WCAG contrast ratio, always >= 1. */
    private fun ratio(a: Color, b: Color): Double {
        val l1 = luminance(a)
        val l2 = luminance(b)
        val (hi, lo) = if (l1 > l2) l1 to l2 else l2 to l1
        return (hi + 0.05) / (lo + 0.05)
    }

    private fun assertContrast(
        schemeName: String,
        pairName: String,
        foreground: Color,
        background: Color,
        minRatio: Double = 4.5,
    ) {
        val actual = ratio(foreground, background)
        assertTrue(
            "$schemeName: $pairName contrast %.2f:1 below floor %.1f:1".format(actual, minRatio),
            actual >= minRatio,
        )
    }

    private fun gateScheme(name: String, s: ColorScheme) {
        assertContrast(name, "onPrimary/primary", s.onPrimary, s.primary)
        assertContrast(name, "onSecondary/secondary", s.onSecondary, s.secondary)
        assertContrast(name, "onTertiary/tertiary", s.onTertiary, s.tertiary)
        assertContrast(name, "onError/error", s.onError, s.error)
        assertContrast(name, "onBackground/background", s.onBackground, s.background)
        assertContrast(name, "onSurface/surface", s.onSurface, s.surface)
        assertContrast(name, "onSurfaceVariant/surfaceVariant", s.onSurfaceVariant, s.surfaceVariant)
        assertContrast(name, "onPrimaryContainer/primaryContainer", s.onPrimaryContainer, s.primaryContainer)
        assertContrast(name, "onSecondaryContainer/secondaryContainer", s.onSecondaryContainer, s.secondaryContainer)
        assertContrast(name, "onTertiaryContainer/tertiaryContainer", s.onTertiaryContainer, s.tertiaryContainer)
        assertContrast(name, "onErrorContainer/errorContainer", s.onErrorContainer, s.errorContainer)
        assertContrast(name, "inverseOnSurface/inverseSurface", s.inverseOnSurface, s.inverseSurface)
    }

    @Test
    fun darkSchemeMeetsContrastFloor() {
        gateScheme("dark", OpenCodeDarkColors)
    }

    @Test
    fun lightSchemeMeetsContrastFloor() {
        gateScheme("light", OpenCodeLightColors)
    }
}
