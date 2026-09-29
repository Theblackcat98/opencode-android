package dev.opencode.android.feature.servers.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.opencode.android.feature.servers.R

/**
 * The onboarding help: the exact commands, then the two ways to reach a server without opening the
 * network to it (plan §2.3, §6 Phase 1).
 *
 * The commands are shown as selectable monospace text, because a user has to type them into a
 * terminal on another machine; making them copyable is the difference between this being useful
 * and being a description of a fix.
 */
@Composable
fun OnboardingGuideCard(
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        ),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.onboarding_guide_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )

            GuideStep(
                title = stringResource(R.string.onboarding_step_lan),
                command = stringResource(R.string.onboarding_step_lan_commands),
                description = stringResource(R.string.onboarding_step_lan_desc),
            )

            GuideStep(
                title = stringResource(R.string.onboarding_step_pair),
                command = stringResource(R.string.onboarding_step_pair_command),
                description = stringResource(R.string.onboarding_step_pair_desc),
            )

            GuideStep(
                title = stringResource(R.string.onboarding_step_serve),
                command = stringResource(R.string.onboarding_step_serve_command),
                description = stringResource(R.string.onboarding_step_serve_desc),
            )

            GuideStep(
                title = stringResource(R.string.onboarding_step_ssh),
                command = stringResource(R.string.onboarding_step_ssh_command),
                description = stringResource(R.string.onboarding_step_ssh_desc),
            )

            GuideSection(
                title = stringResource(R.string.onboarding_step_tailscale),
                description = stringResource(R.string.onboarding_step_tailscale_desc),
            )

            Text(
                text = stringResource(R.string.onboarding_https_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Phase 8's half of the help. The guide is where a user learns what the app can do with
            // their server, and an account they have to walk to the computer to create is the one
            // thing about this app that was still true when P7 finished.
            GuideSection(
                title = stringResource(R.string.onboarding_manage_title),
                description = stringResource(R.string.onboarding_manage_desc),
            )
        }
    }
}

@Composable
private fun GuideStep(
    title: String,
    command: String,
    description: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        SelectionContainer {
            Text(
                text = command,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun GuideSection(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
