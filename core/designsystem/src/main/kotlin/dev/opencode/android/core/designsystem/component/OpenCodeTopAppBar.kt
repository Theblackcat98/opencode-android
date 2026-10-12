package dev.opencode.android.core.designsystem.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.opencode.android.core.designsystem.R

/**
 * The one top bar: a single-line title, an optional single-line subtitle, and an optional thin
 * progress line drawn as the bar's bottom edge.
 *
 * Screens used to grow their own bars (a three-line title on the session, a bare title on the
 * execution screens, a column of rows on review), so the same back arrow sat at a different height on
 * every screen. [subtitle] is a slot rather than a string so a screen can put a status pill in it.
 *
 * [progress] is a fraction in 0..1; [progressDescription] is what a screen reader hears for it, since a
 * 2dp line carries no text of its own.
 *
 * [backDescription] labels the back button for screen readers. When a screen passes [onBack]
 * without one, the button falls back to the generic "Back" label rather than going unlabeled;
 * pass an explicit description when back means something more specific than leaving the screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpenCodeTopAppBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    backDescription: String? = null,
    subtitle: (@Composable () -> Unit)? = null,
    progress: Float? = null,
    progressDescription: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Column(modifier = modifier.fillMaxWidth()) {
        TopAppBar(
            title = {
                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (subtitle != null) {
                        CompositionLocalProvider(
                            LocalTextStyle provides MaterialTheme.typography.labelMedium,
                            LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant,
                        ) { subtitle() }
                    }
                }
            },
            navigationIcon = {
                if (onBack != null) {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = backDescription
                                ?: stringResource(R.string.top_app_bar_back),
                        )
                    }
                }
            },
            actions = actions,
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
        )
        if (progress != null) {
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .semantics { if (progressDescription != null) contentDescription = progressDescription },
                trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                drawStopIndicator = {},
                gapSize = 0.dp,
            )
        } else {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        }
    }
}
