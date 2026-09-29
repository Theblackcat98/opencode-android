package dev.opencode.android.feature.sessions.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.opencode.android.core.model.FinishReason
import dev.opencode.android.core.model.StructuredError
import dev.opencode.android.feature.sessions.R
import java.util.concurrent.TimeUnit

/**
 * The busy and retry indicators the session header carries (plan §6, Status and errors).
 *
 * **A retry shows what failed, when it will be tried again and what the user can do about it.** A
 * spinner hides all three, and a provider that says "usage exceeded, upgrade" is the difference
 * between a turn that retries in four seconds and one that will never succeed until the user does
 * something. The countdown is computed from the server's `next` timestamp against a value the caller
 * passes in, so the row does not hold a ticking clock of its own.
 */
@Composable
fun RetryBanner(
    retry: RetryUi,
    now: Long,
    onOpenLink: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val remaining = (retry.next - now).coerceAtLeast(0L)
    val countdown = formatCountdown(remaining)
    val headline = stringResource(R.string.sessions_retry_in, countdown)
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = headline,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(R.string.sessions_retry_attempt, retry.attempt),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            Text(retry.message, style = MaterialTheme.typography.bodyMedium)
            retry.action?.let { action ->
                Text(action.title, style = MaterialTheme.typography.labelLarge)
                Text(action.message, style = MaterialTheme.typography.bodySmall)
                action.link?.let { link ->
                    TextButton(onClick = { onOpenLink(link) }) { Text(action.label) }
                } ?: Text(action.label, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/**
 * A structured error card.
 *
 * The error's `type` and HTTP `status` are shown next to its message: a provider error is a code in a
 * JSON blob, and hiding the code is how a bug report becomes unactionable.
 */
@Composable
fun ErrorCard(
    error: StructuredError,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(error.message, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = stringResource(R.string.step_error_type, error.type, error.status ?: 0),
                style = MaterialTheme.typography.labelMedium,
            )
            onRetry?.let { TextButton(onClick = it) { Text(stringResource(R.string.sessions_retry_badge)) } }
        }
    }
}

/** A running indicator with a progress bar, for a turn the user is watching. */
@Composable
fun BusyBanner(modifier: Modifier = Modifier) {
    val label = stringResource(R.string.sessions_running_description)
    Column(modifier.fillMaxWidth().padding(4.dp)) {
        LinearProgressIndicator(Modifier.fillMaxWidth().semantics { contentDescription = label })
    }
}

/** The finish reason of a step, which is the last thing on a failed turn. */
@androidx.annotation.StringRes
fun FinishReason.labelRes(): Int = when (this) {
    FinishReason.Stop -> R.string.divider_turn_succeeded
    FinishReason.Length -> R.string.sessions_outcome_interrupted
    FinishReason.ToolCalls -> R.string.divider_turn_succeeded
    FinishReason.ContentFilter -> R.string.step_error
    FinishReason.Error -> R.string.divider_turn_failed
    else -> R.string.sessions_outcome_succeeded
}

/** A countdown, coarse on purpose: "in 4s" and "in 1m" are what a header has room for. */
internal fun formatCountdown(millis: Long): String {
    val seconds = TimeUnit.MILLISECONDS.toSeconds(millis)
    return when {
        seconds < 60 -> "${seconds}s"
        seconds < 3600 -> "${seconds / 60}m"
        else -> "${seconds / 3600}h"
    }
}
