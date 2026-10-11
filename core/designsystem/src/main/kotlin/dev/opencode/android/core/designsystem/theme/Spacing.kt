package dev.opencode.android.core.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Consistent spatial rhythm scale for Developer Terminal Canvas.
 * Built around a 4px (0.25rem) base rhythm.
 */
@Immutable
data class OpenCodeSpacing(
    val xxs: Dp = 2.dp,
    val xs: Dp = 4.dp,
    val sm: Dp = 8.dp,
    val md: Dp = 12.dp,
    val lg: Dp = 16.dp,
    val xl: Dp = 24.dp,
    val xxl: Dp = 32.dp,
    val gutter: Dp = 16.dp,
    val gutterTablet: Dp = 24.dp,
    val marginMobile: Dp = 16.dp,
    val marginTablet: Dp = 24.dp,
    val marginDesktop: Dp = 32.dp,
)

val LocalOpenCodeSpacing = staticCompositionLocalOf { OpenCodeSpacing() }
