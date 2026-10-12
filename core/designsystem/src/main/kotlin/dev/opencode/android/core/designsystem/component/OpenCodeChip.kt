package dev.opencode.android.core.designsystem.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.opencode.android.core.designsystem.theme.OpenCodeThemeExtras

/**
 * A compact chip: optional leading icon and a one-line label, filled when [selected].
 *
 * Wraps M3's [FilterChip] for the interactive case instead of hand-rolling selection:
 * selection exposes checkbox semantics (TalkBack announces the checked state and a checkmark
 * replaces the icon when selected), disabled colors come from the scheme, and the chip is at
 * least 48dp in both dimensions. Chips without [onClick] are display-only and render the same
 * visuals on a [Surface], since this BOM's M3 chip family is clickable-only.
 */
@Composable
fun OpenCodeChip(
    label: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    icon: ImageVector? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
    mono: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    val shapes = OpenCodeThemeExtras.shapes
    val labelContent: @Composable () -> Unit = {
        Text(
            text = label,
            style = if (mono) OpenCodeThemeExtras.code.small else MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    val leadingIcon: (@Composable () -> Unit)? = icon?.let { image ->
        { Icon(image, contentDescription = null, modifier = Modifier.size(18.dp)) }
    }
    if (onClick != null) {
        FilterChip(
            selected = selected,
            onClick = onClick,
            label = labelContent,
            modifier = modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
            enabled = enabled,
            leadingIcon = leadingIcon,
            shape = shapes.chip,
            colors = FilterChipDefaults.filterChipColors(
                containerColor = scheme.surfaceContainerHigh,
                labelColor = scheme.onSurfaceVariant,
                iconColor = scheme.onSurfaceVariant,
                selectedContainerColor = scheme.primaryContainer,
                selectedLabelColor = scheme.onPrimaryContainer,
                selectedLeadingIconColor = scheme.onPrimaryContainer,
            ),
            border = if (selected) {
                null
            } else {
                // filterChipBorder's signature requires enabled/disabled variants this BOM
                // doesn't expose as optional; the conditional stroke is the same visual.
                BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.5f))
            },
        )
    } else {
        // Display-only: this BOM's M3 chip family is clickable-only (no non-clickable
        // AssistChip overload exists), so the static chip is a Surface carrying the same
        // shape, colors and border language as the FilterChip above.
        Surface(
            modifier = modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
            shape = shapes.chip,
            color = if (selected) scheme.primaryContainer else scheme.surfaceContainerHigh,
            contentColor = if (selected) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
            border = if (selected) {
                null
            } else {
                BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.5f))
            },
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                leadingIcon?.invoke()
                labelContent()
            }
        }
    }
}
