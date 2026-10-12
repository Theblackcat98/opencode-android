package dev.opencode.android.core.designsystem.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.expressiveMotionScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalContext

/**
 * The app theme: the [OpenCodePalette] by default.
 *
 * Material You dynamic color is opt-in through [dynamicColor]. It used to be the default, which meant
 * every device on Android 12+ drew the wallpaper's palette and never showed the one the repo-root
 * `design.md` specifies.
 */
@Composable
fun OpenCodeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme: ColorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> OpenCodeDarkColors

        else -> OpenCodeLightColors
    }
    // One shape object feeds both the design-system local and the M3 slots, so a component that
    // reads MaterialTheme.shapes and one that reads OpenCodeThemeExtras.shapes cannot disagree.
    CompositionLocalProvider(
        LocalCodeTypography provides SharedCodeTypography,
        LocalOpenCodeSpacing provides SharedOpenCodeSpacing,
        LocalOpenCodeShapes provides SharedOpenCodeShapes,
        LocalOpenCodeStatusColors provides if (darkTheme) DarkStatusColors else LightStatusColors,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            shapes = SharedOpenCodeShapes.toMaterialShapes(),
            typography = OpenCodeTypography,
            // The motion scheme is a deliberate choice, not the silent default: expressive, per the
            // M3 Expressive bar the app's design inspiration sets. The choice is recorded in MOTION.md,
            // with the T-004 caveat that the expressive spatial spring stays off elements read as data.
            motionScheme = expressiveMotionScheme(),
            content = content,
        )
    }
}

/**
 * The theme's static allocations, hoisted out of the [OpenCodeTheme] composition so a
 * recomposition never rebuilds the token objects. The dynamic-color branch still allocates per
 * composition, but that path is opt-in and rare.
 */
private val SharedCodeTypography = CodeTypography()
private val SharedOpenCodeSpacing = OpenCodeSpacing()
private val SharedOpenCodeShapes = OpenCodeShapes()

/** Accessors for the design system's additions to [MaterialTheme]. */
object OpenCodeThemeExtras {
    val code: CodeTypography
        @Composable
        @ReadOnlyComposable
        get() = LocalCodeTypography.current

    val spacing: OpenCodeSpacing
        @Composable
        @ReadOnlyComposable
        get() = LocalOpenCodeSpacing.current

    val shapes: OpenCodeShapes
        @Composable
        @ReadOnlyComposable
        get() = LocalOpenCodeShapes.current

    val status: OpenCodeStatusColors
        @Composable
        @ReadOnlyComposable
        get() = LocalOpenCodeStatusColors.current
}
