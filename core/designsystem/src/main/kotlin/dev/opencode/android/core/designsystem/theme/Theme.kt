package dev.opencode.android.core.designsystem.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalContext

/**
 * The app theme. Uses Material You dynamic color on Android 12+ unless [dynamicColor] is false,
 * and the [OpenCodePalette] otherwise.
 */
@Composable
fun OpenCodeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
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
    CompositionLocalProvider(LocalCodeTypography provides CodeTypography()) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = OpenCodeTypography,
            content = content,
        )
    }
}

/** Accessors for the design system's additions to [MaterialTheme]. */
object OpenCodeThemeExtras {
    val code: CodeTypography
        @Composable
        @ReadOnlyComposable
        get() = LocalCodeTypography.current
}
