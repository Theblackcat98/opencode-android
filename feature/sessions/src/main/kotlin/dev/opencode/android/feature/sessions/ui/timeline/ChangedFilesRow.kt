package dev.opencode.android.feature.sessions.ui.timeline

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.opencode.android.feature.sessions.R

/**
 * The files a turn changed, as a row of links (plan §6, "Changed files per message").
 *
 * **The paths come from the server and are shown as the server spelled them.** A row that shortened
 * `/home/dev/project/src/main/kotlin/A.kt` to `A.kt` would look tidier and would be wrong: two files
 * of the same name in two directories are two rows that a reviewer cannot tell apart. The label
 * keeps the tail, which is the part that identifies it inside one step, and the whole path is what
 * the link opens.
 *
 * **One row per file, and it is a 48 dp target.** These are links a user taps with a thumb while
 * reading, so the row is padded to the minimum touch target and every row carries its own text,
 * which is also what TalkBack reads.
 */
@Composable
fun ChangedFilesRow(
    files: List<String>,
    onOpenFile: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (files.isEmpty()) return
    Column(modifier = modifier.fillMaxWidth().padding(top = 4.dp)) {
        Text(
            text = pluralStringResource(R.plurals.timeline_changed_files, files.size, files.size),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        files.forEach { path ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenFile(path) }
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.Start,
            ) {
                Text(
                    text = path,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
