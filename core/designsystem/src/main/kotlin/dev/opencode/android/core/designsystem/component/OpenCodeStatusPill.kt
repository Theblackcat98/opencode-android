package dev.opencode.android.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.opencode.android.core.designsystem.theme.OpenCodeThemeExtras

/** What a [OpenCodeStatusPill] reports. */
enum class StatusKind { Running, Retrying, Idle, Success, Failure }
/**
 * A small status badge: a dot (or a spinner while running) and a label, on the state's container color.
 *
 * The label is required. Color alone does not tell a color-blind reader, or a screen reader, which
 * state a row is in.
 */
@Composable
fun OpenCodeStatusPill(
    kind: StatusKind,
    label: String,
    modifier: Modifier = Modifier,
) {
    val colors = OpenCodeThemeExtras.status
    val (container, content) = when (kind) {
        StatusKind.Running -> colors.running to colors.onRunning
        StatusKind.Retrying -> colors.warning to colors.onWarning
        StatusKind.Idle -> colors.neutral to colors.onNeutral
        StatusKind.Success -> colors.success to colors.onSuccess
        StatusKind.Failure -> colors.failure to colors.onFailure
    }
    Row(
        modifier = modifier
            .clip(OpenCodeThemeExtras.shapes.pill)
            .background(container)
            .padding(horizontal = OpenCodeThemeExtras.spacing.sm, vertical = OpenCodeThemeExtras.spacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        OpenCodeStatusDot(kind = kind, color = content)
        Text(
            text = label,
            style = OpenCodeThemeExtras.code.small,
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The leading mark of a [OpenCodeStatusPill], usable on its own in a dense row. */
@Composable
fun OpenCodeStatusDot(
    kind: StatusKind,
    modifier: Modifier = Modifier,
    color: Color = openCodeStatusContentColor(kind),
) {
    if (kind == StatusKind.Running || kind == StatusKind.Retrying) {
        CircularProgressIndicator(modifier = modifier.size(8.dp), strokeWidth = 1.5.dp, color = color)
    } else {
        Box(
            modifier = modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(color),
        )
    }
}

/** The foreground color for [kind], for marks drawn outside a pill. */
@Composable
fun openCodeStatusContentColor(kind: StatusKind): Color {
    val colors = OpenCodeThemeExtras.status
    return when (kind) {
        StatusKind.Running -> colors.onRunning
        StatusKind.Retrying -> colors.onWarning
        StatusKind.Idle -> colors.onNeutral
        StatusKind.Success -> colors.onSuccess
        StatusKind.Failure -> colors.onFailure
    }
}
