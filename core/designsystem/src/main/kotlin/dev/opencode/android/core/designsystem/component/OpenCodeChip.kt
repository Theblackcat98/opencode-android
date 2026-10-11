package dev.opencode.android.core.designsystem.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.opencode.android.core.designsystem.theme.OpenCodeThemeExtras

/**
 * A compact chip: optional leading icon and a one-line label, filled when [selected].
 *
 * It is 32dp tall to sit in a dense row, and pads its touch target to 48dp through
 * Material's minimum-interactive-size enforcement when it is clickable.
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
    val container = if (selected) scheme.primaryContainer else scheme.surfaceContainerHigh
    val content = if (selected) scheme.onPrimaryContainer else scheme.onSurfaceVariant
    Surface(
        modifier = modifier,
        shape = OpenCodeThemeExtras.shapes.chip,
        color = container,
        contentColor = content,
        border = if (selected) null else BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.5f)),
    ) {
        val click = if (onClick != null) Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick) else Modifier
        Row(
            modifier = click
                .defaultMinSize(minHeight = 32.dp)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (icon != null) Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
            Text(
                text = label,
                style = if (mono) OpenCodeThemeExtras.code.small else MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
