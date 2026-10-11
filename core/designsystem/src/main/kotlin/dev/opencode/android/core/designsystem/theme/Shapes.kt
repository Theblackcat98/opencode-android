package dev.opencode.android.core.designsystem.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * Shape tokens for OpenCode components.
 */
@Immutable
data class OpenCodeShapes(
    val card: Shape = RoundedCornerShape(12.dp),
    val button: Shape = RoundedCornerShape(8.dp),
    val badge: Shape = RoundedCornerShape(6.dp),
    val chip: Shape = RoundedCornerShape(8.dp),
    val sheet: Shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
    val codeBlock: Shape = RoundedCornerShape(8.dp),
)

val LocalOpenCodeShapes = staticCompositionLocalOf { OpenCodeShapes() }
