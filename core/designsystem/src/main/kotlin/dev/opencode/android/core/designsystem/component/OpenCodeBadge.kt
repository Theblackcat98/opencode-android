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
 * Reusable badge indicator for counts, status, and tags.
 */
@Composable
fun OpenCodeBadge(
    text: String,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    shape: Shape = OpenCodeThemeExtras.shapes.badge,
    style: TextStyle = MaterialTheme.typography.labelSmall,
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
