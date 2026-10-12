package dev.opencode.android.core.designsystem.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.opencode.android.core.designsystem.theme.OpenCodeThemeExtras

/**
 * A compact chip: optional leading icon and a one-line label, filled when [selected].
 *
 * Wraps M3's chip family instead of hand-rolling selection: chips with an [onClick] are
 * [FilterChip]s, so selection exposes checkbox semantics (TalkBack announces the checked
 * state and a checkmark replaces the icon when selected), disabled colors come from the
 * scheme, and the chip is at least 48dp in both dimensions.
 * Chips without [onClick] render the same visuals statically.
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
            border = FilterChipDefaults.filterChipBorder(
                borderColor = scheme.outlineVariant.copy(alpha = 0.5f),
                selectedBorderColor = Color.Transparent,
                borderWidth = 1.dp,
                selectedBorderWidth = 0.dp,
            ),
        )
    } else {
        // The static overload has no onClick/enabled: it is display-only, so there is no
        // disabled state to render.
        AssistChip(
            label = labelContent,
            modifier = modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
            leadingIcon = leadingIcon,
            shape = shapes.chip,
            colors = AssistChipDefaults.assistChipColors(
                containerColor = if (selected) scheme.primaryContainer else scheme.surfaceContainerHigh,
                labelColor = if (selected) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
                leadingIconContentColor = if (selected) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
            ),
            border = if (selected) {
                null
            } else {
                BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.5f))
            },
        )
    }
}
