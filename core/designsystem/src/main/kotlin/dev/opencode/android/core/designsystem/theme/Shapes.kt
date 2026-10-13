package dev.opencode.android.core.designsystem.theme

import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp

/**
 * Shape tokens for Developer Terminal Canvas components.
 *
 * The slots are [CornerBasedShape] (not the wider [Shape]) because M3's [Shapes] slots
 * require it, and every token here is a rounded rectangle.
 */
@Immutable
data class OpenCodeShapes(
    val badge: CornerBasedShape = RoundedCornerShape(6.dp),
    val button: CornerBasedShape = RoundedCornerShape(8.dp),
    val chip: CornerBasedShape = RoundedCornerShape(8.dp),
    val codeBlock: CornerBasedShape = RoundedCornerShape(8.dp),
    val card: CornerBasedShape = RoundedCornerShape(12.dp),
    val sheet: CornerBasedShape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
    val pill: CornerBasedShape = RoundedCornerShape(9999.dp),
)

/**
 * The M3 shape slots, derived from the token set so the two systems cannot drift.
 * This is what [OpenCodeTheme] passes to [MaterialTheme], so every M3 component that reads
 * `MaterialTheme.shapes` (cards, dialogs, sheets, menus) draws the brand radii.
 */
fun OpenCodeShapes.toMaterialShapes(): Shapes = Shapes(
    extraSmall = badge,
    small = button,
    medium = card,
    large = sheet,
    extraLarge = RoundedCornerShape(28.dp),
)

val LocalOpenCodeShapes = staticCompositionLocalOf { OpenCodeShapes() }
