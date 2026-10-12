package dev.opencode.android.core.designsystem.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The M3 shape slots are derived from the token set: if someone edits [OpenCodeShapes] without
 * updating the mapping, this fails instead of letting the two systems drift apart silently.
 */
class ShapesTest {

    @Test
    fun materialShapesFollowTokens() {
        val tokens = OpenCodeShapes()
        val material = tokens.toMaterialShapes()
        assertEquals(tokens.badge, material.extraSmall)
        assertEquals(tokens.button, material.small)
        assertEquals(tokens.card, material.medium)
        assertEquals(tokens.sheet, material.large)
        assertEquals(RoundedCornerShape(28.dp), material.extraLarge)
    }

    @Test
    fun brandRadii() {
        val tokens = OpenCodeShapes()
        assertEquals(RoundedCornerShape(6.dp), tokens.badge)
        assertEquals(RoundedCornerShape(8.dp), tokens.button)
        assertEquals(RoundedCornerShape(8.dp), tokens.chip)
        assertEquals(RoundedCornerShape(12.dp), tokens.card)
    }
}
