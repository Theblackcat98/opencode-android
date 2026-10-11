package dev.opencode.android.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A tinted circle with one letter, for projects and agents.
 *
 * The tint is picked from [key] so the same project keeps its color across screens and launches.
 * It is decoration: the name it stands for is always printed next to it, so it is hidden from
 * accessibility services rather than read out as a lone letter.
 */
@Composable
fun OpenCodeAvatar(
    key: String,
    modifier: Modifier = Modifier,
    label: String = key,
    size: Dp = 32.dp,
) {
    val tints = avatarTints()
    val (container, content) = tints[Math.floorMod(key.hashCode(), tints.size)]
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(container)
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?",
            color = content,
            fontWeight = FontWeight.SemiBold,
            fontSize = (size.value * 0.42f).sp,
        )
    }
}

@Composable
private fun avatarTints(): List<Pair<Color, Color>> {
    val scheme = MaterialTheme.colorScheme
    return listOf(
        scheme.primaryContainer to scheme.onPrimaryContainer,
        scheme.secondaryContainer to scheme.onSecondaryContainer,
        scheme.tertiaryContainer to scheme.onTertiaryContainer,
        scheme.surfaceContainerHighest to scheme.onSurface,
    )
}
