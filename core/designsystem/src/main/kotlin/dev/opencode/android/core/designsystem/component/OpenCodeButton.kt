package dev.opencode.android.core.designsystem.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.opencode.android.core.designsystem.theme.OpenCodeThemeExtras

/**
 * Button content that keeps its size while [loading]: the label stays composed but invisible
 * and the spinner overlays it, so the button never shrinks and adjacent layout does not shift.
 */
@Composable
private fun LoadingButtonContent(
    loading: Boolean,
    spinnerColor: Color,
    content: @Composable RowScope.() -> Unit,
) {
    Box(contentAlignment = Alignment.Center) {
        Row(modifier = Modifier.alpha(if (loading) 0f else 1f)) {
            content()
        }
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = spinnerColor,
            )
        }
    }
}

/**
 * Standard button with built-in loading indicator, zero elevation, and minimum 48dp touch target.
 * Uses primaryContainer by default for tactile developer terminal styling.
 */
@Composable
fun OpenCodeButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    colors: ButtonColors = ButtonDefaults.buttonColors(
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ),
    content: @Composable RowScope.() -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier.defaultMinSize(minHeight = 48.dp, minWidth = 48.dp),
        enabled = enabled && !loading,
        shape = OpenCodeThemeExtras.shapes.button,
        colors = colors,
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp, pressedElevation = 0.dp),
    ) {
        LoadingButtonContent(loading = loading, spinnerColor = colors.contentColor, content = content)
    }
}

/**
 * Low-emphasis text button with the brand's 8dp corners, for actions in dialogs and rows
 * that previously bypassed the design system with raw M3 TextButtons.
 */
@Composable
fun OpenCodeTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    colors: ButtonColors = ButtonDefaults.textButtonColors(),
    content: @Composable RowScope.() -> Unit,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier.defaultMinSize(minHeight = 48.dp, minWidth = 48.dp),
        enabled = enabled && !loading,
        shape = OpenCodeThemeExtras.shapes.button,
        colors = colors,
    ) {
        LoadingButtonContent(loading = loading, spinnerColor = colors.contentColor, content = content)
    }
}

/**
 * Medium-emphasis outlined button with the brand's 8dp corners and zero elevation.
 */
@Composable
fun OpenCodeOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    colors: ButtonColors = ButtonDefaults.outlinedButtonColors(),
    content: @Composable RowScope.() -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.defaultMinSize(minHeight = 48.dp, minWidth = 48.dp),
        enabled = enabled && !loading,
        shape = OpenCodeThemeExtras.shapes.button,
        colors = colors,
    ) {
        LoadingButtonContent(loading = loading, spinnerColor = colors.contentColor, content = content)
    }
}

/**
 * Medium-emphasis tonal button with the brand's 8dp corners and zero elevation,
 * for actions that need more weight than a text button but less than the primary filled style.
 */
@Composable
fun OpenCodeTonalButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    colors: ButtonColors = ButtonDefaults.filledTonalButtonColors(),
    content: @Composable RowScope.() -> Unit,
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier.defaultMinSize(minHeight = 48.dp, minWidth = 48.dp),
        enabled = enabled && !loading,
        shape = OpenCodeThemeExtras.shapes.button,
        colors = colors,
        elevation = ButtonDefaults.filledTonalButtonElevation(defaultElevation = 0.dp, pressedElevation = 0.dp),
    ) {
        LoadingButtonContent(loading = loading, spinnerColor = colors.contentColor, content = content)
    }
}
