package dev.opencode.android.feature.sessions.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.opencode.android.feature.sessions.R

/**
 * The two actions a *user* message offers: "fork from here" and "undo to here" (plan §6).
 *
 * **They are on user messages and only user messages.** A fork cuts the history *before* a message
 * and a revert rolls back *to* a user message, so a message of any other type is not a boundary the
 * server accepts. Offering the rows everywhere and letting the call fail would be a control that
 * sometimes works, which is worse than a row that is only there when it can.
 *
 * **Both are the app's, not the timeline's.** The actions cross a feature boundary — the fork
 * navigates to another session and the revert is a Phase 6 operation — so the composable takes two
 * callbacks and holds nothing.
 */
@Composable
fun UserMessageActions(
    messageId: String,
    text: String,
    onFork: (messageId: String) -> Unit,
    onRevert: (messageId: String, text: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        TextButton(onClick = { onFork(messageId) }) {
            Text(stringResource(R.string.timeline_fork_here))
        }
        TextButton(onClick = { onRevert(messageId, text) }) {
            Text(stringResource(R.string.timeline_revert_here))
        }
    }
}
