package dev.opencode.android.core.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Semantic status colors, as container / content pairs (repo-root `design.md` § Chips & Status Badges).
 *
 * Material's scheme has `error` but no success, warning or running, and screens used to borrow
 * `tertiary` or `secondary` for those, which meant the same state was a different color on every screen.
 */
@Immutable
data class OpenCodeStatusColors(
    val running: Color,
    val onRunning: Color,
    val success: Color,
    val onSuccess: Color,
    val warning: Color,
    val onWarning: Color,
    val failure: Color,
    val onFailure: Color,
    val neutral: Color,
    val onNeutral: Color,
)

internal val DarkStatusColors = OpenCodeStatusColors(
    running = Color(0xFF1B3F6B),
    onRunning = Color(0xFF9CC2FF),
    success = Color(0xFF1E4A2A),
    onSuccess = Color(0xFF9BE3A8),
    warning = Color(0xFF5A3A08),
    onWarning = Color(0xFFFFC879),
    failure = Color(0xFF6B1E26),
    onFailure = Color(0xFFFFB3B8),
    neutral = Color(0xFF2A2A2A),
    onNeutral = Color(0xFFD0D0D0),
)

internal val LightStatusColors = OpenCodeStatusColors(
    running = Color(0xFFD8E6FF),
    onRunning = Color(0xFF0B3A73),
    success = Color(0xFFD3F2DA),
    onSuccess = Color(0xFF0A3A18),
    warning = Color(0xFFFFE6C2),
    onWarning = Color(0xFF5A3500),
    failure = Color(0xFFFFDAD6),
    onFailure = Color(0xFF6B0010),
    neutral = Color(0xFFEBEBEB),
    onNeutral = Color(0xFF3A3A3A),
)

val LocalOpenCodeStatusColors = staticCompositionLocalOf { DarkStatusColors }
