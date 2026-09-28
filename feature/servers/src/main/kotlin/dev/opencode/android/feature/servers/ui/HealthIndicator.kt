package dev.opencode.android.feature.servers.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.opencode.android.core.data.repository.ServerHealth
import dev.opencode.android.feature.servers.R

@Composable
fun HealthStatusDot(
    health: ServerHealth,
    modifier: Modifier = Modifier,
) {
    val color = when (health) {
        ServerHealth.CONNECTED -> Color(0xFF2E7D32) // Forest green
        ServerHealth.CONNECTING -> Color(0xFFF57F17) // Amber
        ServerHealth.DISCONNECTED -> Color(0xFF9E9E9E) // Grey
        ServerHealth.ERROR -> Color(0xFFD32F2F) // Red
    }

    Box(
        modifier = modifier
            .size(10.dp)
            .background(color, CircleShape),
    )
}

@Composable
fun UnencryptedBadge(
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .background(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(4.dp),
            )
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = stringResource(R.string.servers_unencrypted_badge),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

@Composable
fun DefaultServerBadge(
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .background(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(4.dp),
            )
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = stringResource(R.string.servers_default_badge),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}
