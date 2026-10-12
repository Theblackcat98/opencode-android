package dev.opencode.android.core.designsystem.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * The OpenCode palette: Developer Terminal Canvas.
 * Engineered for nocturnal developer tooling: deep #131313 surface, warm terminal peach primary,
 * electric blue secondary, and violet syntax highlights with hairline borders.
 */
object OpenCodePalette {
    // Primary: Warm Terminal Peach
    val Primary: Color = Color(0xFFFFD6BD)
    val OnPrimary: Color = Color(0xFF502402)
    val PrimaryContainer: Color = Color(0xFFFAB283)
    val OnPrimaryContainer: Color = Color(0xFF76421D)
    val InversePrimary: Color = Color(0xFF88512A)
    val Peach: Color = Color(0xFFFAB283)
    val PeachDeep: Color = Color(0xFF76421D)

    // Secondary: Electric Dev Blue
    val Secondary: Color = Color(0xFFA7C8FF)
    val OnSecondary: Color = Color(0xFF003060)
    val SecondaryContainer: Color = Color(0xFF0361B6)
    val OnSecondaryContainer: Color = Color(0xFFCBDDFF)
    val Blue: Color = Color(0xFFA7C8FF)
    val BlueDeep: Color = Color(0xFF0361B6)

    // Tertiary: Electric Violet
    val Tertiary: Color = Color(0xFFE7D7FF)
    val OnTertiary: Color = Color(0xFF3D1A73)
    val TertiaryContainer: Color = Color(0xFFD0B5FF)
    val OnTertiaryContainer: Color = Color(0xFF5D3D95)
    val Violet: Color = Color(0xFFE7D7FF)
    val VioletDeep: Color = Color(0xFF5D3D95)

    // Error & Destructive
    val Error: Color = Color(0xFFFFB4AB)
    val OnError: Color = Color(0xFF690005)
    val ErrorContainer: Color = Color(0xFF93000A)
    val OnErrorContainer: Color = Color(0xFFFFDAD6)
    val Red: Color = Color(0xFFE06C75)
    val RedDeep: Color = Color(0xFFBA1A1A)

    // Semantic Accents
    val Green: Color = Color(0xFF7FD88F)
    val GreenDeep: Color = Color(0xFF2E7D32)
    val Amber: Color = Color(0xFFF5A742)
    val AmberDeep: Color = Color(0xFF8A5100)

    // Neutral Surface Ramp (Developer Terminal Canvas)
    val Surface: Color = Color(0xFF131313)
    val SurfaceDim: Color = Color(0xFF131313)
    val SurfaceBright: Color = Color(0xFF3A3939)
    val SurfaceContainerLowest: Color = Color(0xFF0E0E0E)
    val SurfaceContainerLow: Color = Color(0xFF1C1B1B)
    val SurfaceContainer: Color = Color(0xFF201F1F)
    val SurfaceContainerHigh: Color = Color(0xFF2A2A2A)
    val SurfaceContainerHighest: Color = Color(0xFF353534)
    val SurfaceVariant: Color = Color(0xFF353534)
    val OnSurface: Color = Color(0xFFE5E2E1)
    val OnSurfaceVariant: Color = Color(0xFFD7C3B7)
    val Outline: Color = Color(0xFF9F8D83)
    val OutlineVariant: Color = Color(0xFF52443C)
    val SurfaceTint: Color = Color(0xFFFFB787)
    val InverseSurface: Color = Color(0xFFE5E2E1)
    val InverseOnSurface: Color = Color(0xFF313030)
    val Background: Color = Color(0xFF131313)
    val OnBackground: Color = Color(0xFFE5E2E1)

    // Fixed Tokens
    val PrimaryFixed: Color = Color(0xFFFFDCC7)
    val PrimaryFixedDim: Color = Color(0xFFFAB283)
    val OnPrimaryFixed: Color = Color(0xFF311300)
    val OnPrimaryFixedVariant: Color = Color(0xFF6B3A15)
    val SecondaryFixed: Color = Color(0xFFD5E3FF)
    val SecondaryFixedDim: Color = Color(0xFFA7C8FF)
    val OnSecondaryFixed: Color = Color(0xFF001B3C)
    val OnSecondaryFixedVariant: Color = Color(0xFF004788)
    val TertiaryFixed: Color = Color(0xFFEBDCFF)
    val TertiaryFixedDim: Color = Color(0xFFD4BBFF)
    val OnTertiaryFixed: Color = Color(0xFF260058)
    val OnTertiaryFixedVariant: Color = Color(0xFF54348B)

    // Legacy Neutrals (for compatibility)
    val Ink0: Color = SurfaceContainerLowest
    val Ink1: Color = SurfaceContainerLow
    val Ink2: Color = SurfaceContainer
    val Ink3: Color = SurfaceContainerHigh
    val Ink4: Color = OutlineVariant
    val Ink5: Color = Outline
    val Ink6: Color = OnSurfaceVariant
    val Ink7: Color = OnSurface

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
    primary = OpenCodePalette.Primary,
    onPrimary = OpenCodePalette.OnPrimary,
    primaryContainer = OpenCodePalette.PrimaryContainer,
    onPrimaryContainer = OpenCodePalette.OnPrimaryContainer,
    secondary = OpenCodePalette.Secondary,
    onSecondary = OpenCodePalette.OnSecondary,
    secondaryContainer = OpenCodePalette.SecondaryContainer,
    onSecondaryContainer = OpenCodePalette.OnSecondaryContainer,
    tertiary = OpenCodePalette.Tertiary,
    onTertiary = OpenCodePalette.OnTertiary,
    tertiaryContainer = OpenCodePalette.TertiaryContainer,
    onTertiaryContainer = OpenCodePalette.OnTertiaryContainer,
    error = OpenCodePalette.Error,
    onError = OpenCodePalette.OnError,
    errorContainer = OpenCodePalette.ErrorContainer,
    onErrorContainer = OpenCodePalette.OnErrorContainer,
    background = OpenCodePalette.Background,
    onBackground = OpenCodePalette.OnBackground,
    surface = OpenCodePalette.Surface,
    onSurface = OpenCodePalette.OnSurface,
    surfaceVariant = OpenCodePalette.SurfaceVariant,
    onSurfaceVariant = OpenCodePalette.OnSurfaceVariant,
    surfaceContainerLowest = OpenCodePalette.SurfaceContainerLowest,
    surfaceContainerLow = OpenCodePalette.SurfaceContainerLow,
    surfaceContainer = OpenCodePalette.SurfaceContainer,
    surfaceContainerHigh = OpenCodePalette.SurfaceContainerHigh,
    surfaceContainerHighest = OpenCodePalette.SurfaceContainerHighest,
    surfaceDim = OpenCodePalette.SurfaceDim,
    surfaceBright = OpenCodePalette.SurfaceBright,
    surfaceTint = OpenCodePalette.SurfaceTint,
    scrim = Color.Black,
    outline = OpenCodePalette.Outline,
    outlineVariant = OpenCodePalette.OutlineVariant,
    inverseSurface = OpenCodePalette.InverseSurface,
    inverseOnSurface = OpenCodePalette.InverseOnSurface,
    inversePrimary = OpenCodePalette.InversePrimary,
)

internal val OpenCodeLightColors: ColorScheme = lightColorScheme(
    primary = OpenCodePalette.PeachDeep,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDBC8),
    onPrimaryContainer = Color(0xFF331200),
    secondary = Color(0xFF0361B6),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD5E3FF),
    onSecondaryContainer = Color(0xFF001B3C),
    tertiary = Color(0xFF5D3D95),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFEBDCFF),
    onTertiaryContainer = Color(0xFF260058),
    error = Color(0xFFBA1A1A),
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
    surfaceDim = OpenCodePalette.Paper3,
    surfaceBright = OpenCodePalette.Paper0,
    surfaceTint = OpenCodePalette.SurfaceTint,
    scrim = Color.Black,
    outline = OpenCodePalette.Paper5,
    outlineVariant = OpenCodePalette.Paper4,
    inverseSurface = OpenCodePalette.Paper7,
    inverseOnSurface = OpenCodePalette.Paper2,
    inversePrimary = OpenCodePalette.Primary,
)
