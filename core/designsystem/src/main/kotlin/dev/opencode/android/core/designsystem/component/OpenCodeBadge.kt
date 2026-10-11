package dev.opencode.android.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import dev.opencode.android.core.designsystem.theme.OpenCodeThemeExtras

/**
 * Reusable micro badge indicator for status, counts, tags, and commit hashes:
 * 6dp rounded corners, 8dp horizontal / 2dp vertical padding, and monospace text.
 */
@Composable
fun OpenCodeBadge(
    text: String,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    shape: Shape = OpenCodeThemeExtras.shapes.badge,
    style: TextStyle = OpenCodeThemeExtras.code.small,
) {
    Box(
        modifier = modifier
            .background(color = containerColor, shape = shape)
            .padding(
                horizontal = OpenCodeThemeExtras.spacing.sm,
                vertical = OpenCodeThemeExtras.spacing.xxs,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = contentColor,
            style = style,
        )
    }
}
