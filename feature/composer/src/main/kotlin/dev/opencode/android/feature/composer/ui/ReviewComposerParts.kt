package dev.opencode.android.feature.composer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.opencode.android.core.data.composer.LineRange
import dev.opencode.android.core.data.review.ReviewComment
import dev.opencode.android.feature.composer.R

/**
 * The staged-revert banner: what the undo restored and how to take it back (plan §6, "Undo, redo and
 * revert").
 *
 * **The files are the server's, and they are named.** A revert that restored four files is a
 * different action from one that restored none, and a user who cannot see which is which cannot
 * decide whether to redo it. The empty case has its own sentence — "the working copy was not
 * snapshotted" — because `snapshots: false` is a configuration the server allows and the honest
 * thing is to say the files did not change rather than to show an empty list.
 *
 * **[onRedo] asks; it does not redo.** The button opens [RedoConfirmationDialog] through the composer
 * (`askRedo`), because redo changes the working copy and plan §5.2 confirms that. The banner is a live
 * region so a screen reader says "Undo staged" when it appears, which is the only sign that the box above
 * the keyboard is now holding a rolled-back prompt.
 */
@Composable
fun StagedRevertBanner(
    state: ComposerUiState,
    onRedo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!state.isStaged) return
    Surface(
        modifier = modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        // The title and Redo share a row, so at a large font the action stays beside the words that explain it
        // instead of being squeezed into a column of one-word lines beside the hint.
        Column(modifier = Modifier.padding(start = 12.dp, end = 4.dp, bottom = 8.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.composer_staged_banner),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onRedo) { Text(stringResource(R.string.composer_redo)) }
            }
            Column(modifier = Modifier.padding(end = 8.dp)) {
                Text(
                    text = if (state.restoredFiles.isEmpty()) {
                        stringResource(R.string.composer_staged_none)
                    } else {
                        stringResource(R.string.composer_staged_files, state.restoredFiles.size)
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                if (state.restoredFiles.isNotEmpty()) {
                    // The file's own name, not its path: a path is longer than the banner is wide, and a row
                    // that scrolls sideways shows one of them. The count above says how many there are and the
                    // review screen has the paths.
                    LazyRow(
                        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(state.restoredFiles, key = { it.file }) { file ->
                            Text(
                                text = file.file.substringAfterLast('/'),
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                Text(
                    text = stringResource(R.string.composer_revert_commit_first),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/**
 * The question redo asks before it takes an undo back (plan §5.2: a change to the working copy is confirmed).
 *
 * It says what will happen in the two places the user can be surprised — the files, which return to how they
 * were before the undo, and the box, which keeps what is in it — because "Redo?" alone is a confirmation of
 * nothing. The yes is worded as the action, not "OK".
 */
@Composable
fun RedoConfirmationDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.composer_redo_confirm_title)) },
        text = { Text(stringResource(R.string.composer_redo_confirm_body)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.composer_redo)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.composer_redo_keep)) } },
    )
}

/**
 * The review comments waiting to go on the next prompt.
 *
 * **A chip says where it is and what it says.** `path:12-18` is the anchor the desktop reads back
 * and the range the attachment carries, and the comment's own words are what the user is about to
 * send — so both are on the row, and the remove button is a real 48 dp target with a name.
 */
@Composable
fun ReviewCommentChips(
    comments: List<ReviewComment>,
    onRemove: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (comments.isEmpty()) return
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(R.string.composer_comment_count, comments.size),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        comments.forEachIndexed { index, comment ->
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(
                            R.string.composer_comment_on,
                            comment.path.substringAfterLast('/'),
                            comment.selection,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { onRemove(index) }) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = stringResource(R.string.composer_comment_remove),
                        )
                    }
                }
            }
        }
    }
}

/** The `path:12-18` spelling of a comment's anchor, for a chip or a dialog title. */
internal fun ReviewComment.anchorText(): String =
    "${path.substringAfterLast('/')}:${LineRange(range.start, range.end).toSuffix().removePrefix("#")}"
