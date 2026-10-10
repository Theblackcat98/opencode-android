package dev.opencode.android.navigation

import android.content.res.AssetManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import dev.opencode.android.changelog.ChangelogMarkdown
import dev.opencode.android.changelog.ChangelogScreen
import dev.opencode.android.changelog.ChangelogUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.IOException

/** The name `docs/CHANGELOG.md` has in the app's assets, where `:app`'s build copies it. */
private const val CHANGELOG_ASSET = "changelog.md"

/** The in-app changelog. It is server-independent, so it takes no server id and no directory. */
@Serializable
data object ChangelogRoute

/**
 * The changelog screen. The bundled file is read and parsed on the IO dispatcher, and the screen is handed the result,
 * so the main thread never waits on the asset.
 */
@Composable
fun ChangelogHost(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val assets = LocalContext.current.assets
    val state by produceState<ChangelogUiState>(ChangelogUiState.Loading, assets) {
        value = withContext(Dispatchers.IO) { loadChangelog(assets) }
    }
    ChangelogScreen(state = state, onNavigateBack = onNavigateBack, modifier = modifier)
}

/**
 * The bundled changelog as screen state. A build whose asset is missing shows [ChangelogUiState.Unavailable] rather
 * than failing, and only an I/O failure is caught, so a cancellation still propagates.
 */
internal fun loadChangelog(assets: AssetManager): ChangelogUiState = try {
    val markdown = assets.open(CHANGELOG_ASSET).bufferedReader().use { it.readText() }
    ChangelogUiState.Ready(ChangelogMarkdown.parse(markdown))
} catch (missing: IOException) {
    ChangelogUiState.Unavailable
}
