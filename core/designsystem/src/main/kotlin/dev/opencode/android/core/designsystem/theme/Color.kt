package dev.opencode.android.core.designsystem.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * The OpenCode palette, taken from the terminal client's default `opencode` theme: a warm peach
 * primary, blue secondary and violet accent on near-black (dark) or near-white (light) surfaces.
 * Used when dynamic color is unavailable (Android 11 and lower) or switched off.
 */
object OpenCodePalette {
    val Peach: Color = Color(0xFFFAB283)
    val PeachDeep: Color = Color(0xFF9A4F1C)
    val Blue: Color = Color(0xFF5C9CF5)
    val BlueDeep: Color = Color(0xFF3B7DD8)
    val Violet: Color = Color(0xFF9D7CD8)
    val VioletDeep: Color = Color(0xFF7B5BB6)
    val Red: Color = Color(0xFFE06C75)
    val RedDeep: Color = Color(0xFFBA1A1A)
    val Green: Color = Color(0xFF7FD88F)
    val GreenDeep: Color = Color(0xFF2E7D32)
    val Amber: Color = Color(0xFFF5A742)
    val AmberDeep: Color = Color(0xFF8A5100)

    val Ink0: Color = Color(0xFF0A0A0A)
    val Ink1: Color = Color(0xFF141414)
    val Ink2: Color = Color(0xFF1E1E1E)
    val Ink3: Color = Color(0xFF282828)
    val Ink4: Color = Color(0xFF3C3C3C)
    val Ink5: Color = Color(0xFF808080)
    val Ink6: Color = Color(0xFFB8B8B8)
    val Ink7: Color = Color(0xFFEEEEEE)

    val Paper0: Color = Color(0xFFFFFFFF)
    val Paper1: Color = Color(0xFFFAFAFA)
    val Paper2: Color = Color(0xFFF3F3F3)
    val Paper3: Color = Color(0xFFEBEBEB)
    val Paper4: Color = Color(0xFFD4D4D4)
    val Paper5: Color = Color(0xFF8A8A8A)
    val Paper6: Color = Color(0xFF4A4A4A)
    val Paper7: Color = Color(0xFF1A1A1A)
}

internal val OpenCodeDarkColors: ColorScheme = darkColorScheme(
    primary = OpenCodePalette.Peach,
    onPrimary = Color(0xFF3A1A04),
    primaryContainer = Color(0xFF5A3217),
    onPrimaryContainer = Color(0xFFFFDBC8),
    secondary = OpenCodePalette.Blue,
    onSecondary = Color(0xFF00315F),
    secondaryContainer = Color(0xFF1B3F6B),
    onSecondaryContainer = Color(0xFFD4E3FF),
    tertiary = OpenCodePalette.Violet,
    onTertiary = Color(0xFF2E1260),
    tertiaryContainer = Color(0xFF45307A),
    onTertiaryContainer = Color(0xFFEADDFF),
    error = OpenCodePalette.Red,
    onError = Color(0xFF3F0A10),
    errorContainer = Color(0xFF6B1E26),
    onErrorContainer = Color(0xFFFFDADB),
    background = OpenCodePalette.Ink0,
    onBackground = OpenCodePalette.Ink7,
    surface = OpenCodePalette.Ink0,
    onSurface = OpenCodePalette.Ink7,
    surfaceVariant = OpenCodePalette.Ink3,
    onSurfaceVariant = OpenCodePalette.Ink6,
    surfaceContainerLowest = OpenCodePalette.Ink0,
    surfaceContainerLow = OpenCodePalette.Ink1,
    surfaceContainer = OpenCodePalette.Ink1,
    surfaceContainerHigh = OpenCodePalette.Ink2,
    surfaceContainerHighest = OpenCodePalette.Ink3,
    outline = OpenCodePalette.Ink5,
    outlineVariant = OpenCodePalette.Ink4,
    inverseSurface = OpenCodePalette.Ink7,
    inverseOnSurface = OpenCodePalette.Ink1,
    inversePrimary = OpenCodePalette.PeachDeep,
)

internal val OpenCodeLightColors: ColorScheme = lightColorScheme(
    primary = OpenCodePalette.PeachDeep,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDBC8),
    onPrimaryContainer = Color(0xFF331200),
    secondary = OpenCodePalette.BlueDeep,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD4E3FF),
    onSecondaryContainer = Color(0xFF001C3A),
    tertiary = OpenCodePalette.VioletDeep,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFEADDFF),
    onTertiaryContainer = Color(0xFF21005D),
    error = OpenCodePalette.RedDeep,
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = OpenCodePalette.Paper1,
    onBackground = OpenCodePalette.Paper7,
    surface = OpenCodePalette.Paper1,
    onSurface = OpenCodePalette.Paper7,
    surfaceVariant = OpenCodePalette.Paper3,
    onSurfaceVariant = OpenCodePalette.Paper6,
    surfaceContainerLowest = OpenCodePalette.Paper0,
    surfaceContainerLow = OpenCodePalette.Paper2,
    surfaceContainer = OpenCodePalette.Paper2,
    surfaceContainerHigh = OpenCodePalette.Paper3,
    surfaceContainerHighest = OpenCodePalette.Paper4,
    outline = OpenCodePalette.Paper5,
    outlineVariant = OpenCodePalette.Paper4,
    inverseSurface = OpenCodePalette.Paper7,
    inverseOnSurface = OpenCodePalette.Paper2,
    inversePrimary = OpenCodePalette.Peach,
)
