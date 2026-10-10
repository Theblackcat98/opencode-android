package dev.opencode.android.feature.insights

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val QR_SIZE = 260.dp

/**
 * Pair another device (matrix B9; issue #21): one code, drawn as a QR code, for a second client to redeem.
 *
 * Stateless: [PairDeviceViewModel] mints the code and holds it, and this draws what it is given. An absent
 * route is said once and replaces the button, the same way Quick ask handles it, because a button that minted
 * nothing and then reported an error would look like a broken app rather than a server without the feature.
 */
@Composable
fun PairDeviceScreen(
    state: PairDeviceUiState,
    onGenerate: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!state.available) {
            Text(stringResource(R.string.pair_absent), style = MaterialTheme.typography.bodyMedium)
            return@Column
        }

        Text(
            stringResource(R.string.pair_help),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(R.string.pair_address),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        val offer = state.offer
        if (offer == null) {
            Button(onClick = onGenerate, enabled = state.canGenerate) {
                Text(stringResource(R.string.pair_show))
            }
        } else {
            Text(
                stringResource(R.string.pair_warning),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.SemiBold,
            )
            PairingQrCode(link = offer.link, modifier = Modifier.align(Alignment.CenterHorizontally))
            Text(stringResource(R.string.pair_link_label), style = MaterialTheme.typography.titleSmall)
            // Selectable so the link can be copied to a device that cannot scan. The app's paste field takes
            // this full link; it does not take the bare code.
            SelectionContainer {
                Text(
                    offer.link,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                )
            }
            OutlinedButton(onClick = onGenerate, enabled = state.canGenerate) {
                Text(stringResource(R.string.pair_new_code))
            }
        }

        if (state.generating) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator()
                Text(stringResource(R.string.pair_generating), style = MaterialTheme.typography.bodyMedium)
            }
        }

        state.error?.let { error -> InsightsErrorCard(error.message, onDismissError) }
    }
}

/**
 * The QR code for [link], drawn off the main thread.
 *
 * A link that cannot be encoded shows the text instead of a blank square, because the link under it is the
 * same thing and a scanner is not the only way to use it.
 */
@Composable
private fun PairingQrCode(link: String, modifier: Modifier = Modifier) {
    val picture by produceState<Result<ImageBitmap>?>(initialValue = null, key1 = link) {
        value = withContext(Dispatchers.Default) { runCatching { qrPicture(link) } }
    }
    val result = picture
    val bitmap = result?.getOrNull()
    when {
        result == null -> Box(modifier = modifier.size(QR_SIZE), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }

        bitmap != null -> Image(
            bitmap = bitmap,
            contentDescription = stringResource(R.string.pair_qr_description),
            contentScale = ContentScale.Fit,
            filterQuality = FilterQuality.None,
            modifier = modifier.size(QR_SIZE),
        )

        else -> Text(
            stringResource(R.string.pair_qr_failed),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = modifier,
        )
    }
}
