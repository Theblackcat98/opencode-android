package dev.opencode.android.feature.composer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.opencode.android.core.data.composer.PromptIntent
import dev.opencode.android.core.data.composer.StashEntry
import dev.opencode.android.core.model.SkillInfo
import dev.opencode.android.feature.composer.R

/**
 * The one line that says what the next send will carry.
 *
 * **This is the "context awareness" the phase is named for.** A prompt with a picture in it, a
 * mention in it, a delivery mode and a `resume` flag is four things the user has to remember; a line
 * that names them turns four remembered things into one visible fact, and it is the only place the
 * delivery mode and the attachments are described in words rather than by the state of two chips.
 *
 * It says what the *text* will mean — a message, a command, a shell line or an app action — and not
 * merely what is in the box, because that is the difference between typing `/compact` and pressing
 * send.
 */
@Composable
fun ComposerContextRow(state: ComposerUiState, modifier: Modifier = Modifier) {
    val summary = stringResource(
        R.string.composer_context_summary,
        describeIntent(state),
    )
    Text(
        text = summary,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
            .semantics { contentDescription = summary },
    )
}

@Composable
private fun describeIntent(state: ComposerUiState): String = when (val intent = state.intent) {
    is PromptIntent.Shell -> stringResource(R.string.composer_context_shell)

    is PromptIntent.Command -> stringResource(R.string.composer_context_command, intent.name)

    is PromptIntent.Client -> stringResource(R.string.composer_context_client)

    is PromptIntent.Prompt -> if (state.text.isBlank() && state.attachments.isNotEmpty()) {
        stringResource(R.string.composer_context_none)
    } else {
        stringResource(R.string.composer_context_text)
    }

    null -> stringResource(R.string.composer_context_none)
}

/**
 * What is stopping the send, and the one button that can clear it.
 *
 * **A confirmation, not a warning that scrolled away** (plan §5.2). "This model cannot see this
 * image" is a meaningful action — the attachment may be dropped by the provider — so it gets a
 * button that says what it does, next to the thing it is about, with the model's name in the sentence.
 */
@Composable
fun ComposerProblemRow(
    state: ComposerUiState,
    onSendAnyway: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val problem = state.problem ?: return
    val message = when (problem) {
        ComposerProblem.ATTACHMENT_BLOCKED -> stringResource(
            R.string.composer_attachment_blocked,
            state.problemDetail.orEmpty(),
        )

        ComposerProblem.ATTACHMENT_NEEDS_CONFIRMATION -> stringResource(
            R.string.composer_attachment_needs_confirm,
            state.problemDetail.orEmpty(),
            state.model?.id.orEmpty(),
        )

        ComposerProblem.ATTACHMENT_UNREADABLE -> stringResource(
            R.string.composer_attachment_unreadable,
            state.problemDetail.orEmpty(),
        )

        ComposerProblem.NO_SESSION -> stringResource(R.string.composer_no_session)

        ComposerProblem.REVERT_BLOCKED -> stringResource(R.string.composer_revert_blocked)

        ComposerProblem.SEARCH_FAILED -> stringResource(R.string.composer_search_failed)
    }
    Surface(
        modifier = modifier.fillMaxWidth().padding(bottom = 8.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            if (problem == ComposerProblem.ATTACHMENT_NEEDS_CONFIRMATION) {
                TextButton(onClick = onSendAnyway) {
                    Text(stringResource(R.string.composer_attachment_send_anyway))
                }
            }
        }
    }
}

/**
 * The skill picker.
 *
 * A skill is attached to the next prompt rather than run, which is the mechanism the API always has;
 * the experimental `session.skill` route is the one that activates it for the running session, and
 * the sheet offers that as a second action on the same row rather than as a separate screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillPicker(
    skills: List<SkillInfo>,
    attached: Set<String>,
    onToggle: (String) -> Unit,
    onActivate: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(
                text = stringResource(R.string.composer_add_skill),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            if (skills.isEmpty()) {
                Text(
                    text = stringResource(R.string.composer_skill_none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
                return@Column
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(skills, key = { it.id }) { skill ->
                    val isAttached = skill.id in attached
                    val description = skill.description ?: stringResource(R.string.composer_skill_none)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected = isAttached, onClick = { onToggle(skill.id) })
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .semantics(mergeDescendants = true) {
                                contentDescription = "${skill.name}. $description"
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = isAttached, onCheckedChange = null)
                        Column(
                            Modifier
                                .weight(1f)
                                .padding(start = 8.dp),
                        ) {
                            Text(skill.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { onActivate(skill.id) }) {
                            Text(stringResource(R.string.composer_skill_activate))
                        }
                    }
                }
            }
        }
    }
}

/**
 * The `/btw` answer, in a sheet.
 *
 * A side question does not enter the transcript — that is the point of it — so the answer needs
 * somewhere to live while it is read, and a copy button because the usual reason to ask is to move an
 * answer somewhere else.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SideQuestionSheet(state: SideQuestion, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.composer_btw_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(state.question, style = MaterialTheme.typography.bodyMedium)
            when {
                state.loading -> Text(
                    text = stringResource(R.string.composer_btw_waiting),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                state.failure != null -> Text(
                    text = stringResource(R.string.composer_btw_failed, state.failure),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )

                else -> {
                    Text(state.answer.orEmpty(), style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(
                            enabled = state.answered,
                            onClick = { clipboard.setText(AnnotatedString(state.answer.orEmpty())) },
                        ) {
                            Text(stringResource(R.string.composer_btw_copy))
                        }
                        TextButton(onClick = onDismiss) {
                            Text(stringResource(R.string.composer_close))
                        }
                    }
                }
            }
        }
    }
}

/**
 * The stash.
 *
 * **Stash, pop, list** (features doc §38, "Composer"). The list is newest first and each row puts
 * *its own* entry back — a row that popped the newest while claiming to restore itself would be the
 * most confusing control in the composer — and the header offers pop for the common case of "put back
 * what I just put away".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StashList(
    stash: List<StashEntry>,
    onPop: () -> Unit,
    onRestore: (StashEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.composer_stash_list),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (stash.isNotEmpty()) {
                    TextButton(onClick = onPop) { Text(stringResource(R.string.composer_stash_pop)) }
                }
            }
            if (stash.isEmpty()) {
                Text(
                    text = stringResource(R.string.composer_stash_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
                return@Column
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(stash, key = { it.id }) { entry ->
                    val restore = stringResource(R.string.composer_stash_restore, entry.text)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = entry.text,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = { onRestore(entry) },
                            modifier = Modifier.semantics { contentDescription = restore },
                        ) {
                            Text(stringResource(R.string.composer_stash_restore_short))
                        }
                    }
                }
            }
        }
    }
}
