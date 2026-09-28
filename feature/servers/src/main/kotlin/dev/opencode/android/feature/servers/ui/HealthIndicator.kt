package dev.opencode.android.feature.servers.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.opencode.android.core.data.repository.ServerHealth
import dev.opencode.android.feature.servers.R

/**
 * The health dot.
 *
 * Colour alone cannot be the only signal, so the dot carries a content description with the state
 * in words and clears its own semantics so a screen reader reads one label instead of two.
 */
@Composable
fun HealthStatusDot(
    health: ServerHealth,
    modifier: Modifier = Modifier,
) {
    val color = health.dotColor()
    val description = stringResource(R.string.health_dot_description, stringResource(health.labelRes()))
    Box(
        modifier = modifier
            .size(12.dp)
            .background(color, CircleShape)
            .clearAndSetSemantics { contentDescription = description },
    )
}

@Composable
private fun ServerHealth.dotColor(): Color = when (this) {
    ServerHealth.CONNECTED -> MaterialTheme.colorScheme.primary
    ServerHealth.CONNECTING -> MaterialTheme.colorScheme.tertiary
    ServerHealth.OFFLINE -> MaterialTheme.colorScheme.secondary
    ServerHealth.REAUTH_REQUIRED -> MaterialTheme.colorScheme.error
    ServerHealth.DISCONNECTED -> MaterialTheme.colorScheme.outline
}

/** Marks a server the app opens first. */
@Composable
fun DefaultServerBadge(modifier: Modifier = Modifier) {
    Badge(
        text = stringResource(R.string.servers_default_badge),
        container = MaterialTheme.colorScheme.primaryContainer,
        content = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = modifier,
    )
}

/**
 * Marks a server reached over plain HTTP on a non-loopback address (plan §2.4, §5.2).
 *
 * It is deliberately loud: the credential travels in the clear on every request on such a network.
 */
@Composable
fun UnencryptedBadge(modifier: Modifier = Modifier) {
    val label = stringResource(R.string.servers_unencrypted_badge)
    val description = stringResource(R.string.servers_unencrypted_description)
    Badge(
        text = label,
        container = MaterialTheme.colorScheme.errorContainer,
        content = MaterialTheme.colorScheme.onErrorContainer,
        contentDescription = description,
        modifier = modifier,
    )
}

@Composable
private fun Badge(
    text: String,
    container: Color,
    content: Color,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    val semanticsModifier = if (contentDescription != null) {
        Modifier.semantics { this.contentDescription = contentDescription }
    } else {
        Modifier
    }
    Box(
        modifier = modifier
            .background(container, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .then(semanticsModifier),
    ) {
        Text(text = text, style = MaterialTheme.typography.labelSmall, color = content)
    }
}
