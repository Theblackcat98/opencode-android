package dev.opencode.android.core.designsystem.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * Shape tokens for Developer Terminal Canvas components.
 */
@Immutable
data class OpenCodeShapes(
    val badge: Shape = RoundedCornerShape(6.dp),
    val button: Shape = RoundedCornerShape(8.dp),
    val chip: Shape = RoundedCornerShape(8.dp),
    val codeBlock: Shape = RoundedCornerShape(8.dp),
    val card: Shape = RoundedCornerShape(12.dp),
    val sheet: Shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
    val pill: Shape = RoundedCornerShape(9999.dp),
)

val LocalOpenCodeShapes = staticCompositionLocalOf { OpenCodeShapes() }
