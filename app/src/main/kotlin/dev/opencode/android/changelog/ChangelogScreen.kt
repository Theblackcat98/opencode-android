package dev.opencode.android.changelog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.opencode.android.R
import dev.opencode.android.core.designsystem.component.OpenCodeEmptyState

/**
 * What the changelog screen shows. Loading is kept apart from a missing file, so neither is mistaken for the other.
 */
sealed interface ChangelogUiState {
    data object Loading : ChangelogUiState

    /** The bundled changelog could not be read, which is a build without the asset rather than a failure to report. */
    data object Unavailable : ChangelogUiState

    data class Ready(val blocks: List<ChangelogBlock>) : ChangelogUiState
}

/**
 * The in-app changelog: this release's notes, read from the file the repository keeps.
 *
 * Stateless, so the host decides what [state] is. The blocks arrive already parsed, which keeps this composable to
 * layout and leaves no markdown to read on the main thread.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChangelogScreen(
    state: ChangelogUiState,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.changelog_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.changelog_navigate_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        when (state) {
            ChangelogUiState.Loading -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }

            ChangelogUiState.Unavailable -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.changelog_unavailable),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
            }

            is ChangelogUiState.Ready -> if (state.blocks.isEmpty()) {
                // An empty changelog is a state, not a missing screen: without this branch the
                // release notes render as a blank page, which reads as a load that never finished.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    OpenCodeEmptyState(
                        title = stringResource(R.string.changelog_empty_title),
                        subtitle = stringResource(R.string.changelog_empty_body),
                        icon = Icons.AutoMirrored.Filled.Article,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(state.blocks) { block -> ChangelogBlockView(block) }
                }
            }
        }
    }
}

@Composable
private fun ChangelogBlockView(block: ChangelogBlock) {
    when (block) {
        is ChangelogBlock.Heading -> {
            val style = when (block.level) {
                1 -> MaterialTheme.typography.headlineSmall
                2 -> MaterialTheme.typography.titleLarge
                3 -> MaterialTheme.typography.titleMedium
                else -> MaterialTheme.typography.titleSmall
            }
            Text(
                text = block.text,
                style = style,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .padding(top = if (block.level <= 2) 16.dp else 8.dp)
                    .semantics { heading() },
            )
        }

        is ChangelogBlock.Bullet -> Row(
            modifier = Modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) {},
        ) {
            Text(text = stringResource(R.string.changelog_bullet), style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = block.text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
        }

        is ChangelogBlock.Paragraph -> Text(
            text = block.text,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
