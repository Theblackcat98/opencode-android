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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.opencode.android.core.data.composer.LineRange
import dev.opencode.android.core.data.review.ReviewComment
import dev.opencode.android.feature.composer.R

/**
 * The staged-revert banner: what the undo will restore and how to take it back (plan §6, "Undo,
 * redo and revert").
 *
 * **The files are the server's, and they are named.** A revert that restores four files is a
 * different action from one that restores none, and a user who cannot see which is which cannot
 * decide whether to confirm it. The empty case has its own sentence — "the working copy was not
 * snapshotted" — because `snapshots: false` is a configuration the server allows and the honest
 * thing is to say the files did not change rather than to show an empty list.
 */
@Composable
fun StagedRevertBanner(
    state: ComposerUiState,
    onRedo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!state.isStaged) return
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                text = stringResource(R.string.composer_staged_banner),
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                text = if (state.restoredFiles.isEmpty()) {
                    stringResource(R.string.composer_staged_none)
                } else {
                    stringResource(R.string.composer_staged_files, state.restoredFiles.size)
                },
                style = MaterialTheme.typography.bodySmall,
            )
            if (state.restoredFiles.isNotEmpty()) {
                LazyRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.restoredFiles, key = { it.file }) { file ->
                        Text(
                            text = file.file,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.composer_revert_commit_first),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onRedo) { Text(stringResource(R.string.composer_redo)) }
            }
        }
    }
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
