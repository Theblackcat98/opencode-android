package dev.opencode.android.changelog

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import dev.opencode.android.R
import dev.opencode.android.core.designsystem.component.OpenCodeEmptyState
import dev.opencode.android.core.designsystem.theme.OpenCodeThemeExtras
import dev.opencode.android.core.model.SafeNavigationUrl

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
 *
 * The bar is M3's [MediumTopAppBar] rather than the design system's [OpenCodeTopAppBar]: the
 * system bar is a single-line title by design, and a 500-line reading document earns the
 * expressive large title that collapses as the reader scrolls.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChangelogScreen(
    state: ChangelogUiState,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            MediumTopAppBar(
                title = { Text(stringResource(R.string.changelog_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.changelog_navigate_back),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
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
                    .padding(OpenCodeThemeExtras.spacing.xl),
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
                // Links open in a Custom Tab, not the plain browser: the handler below is what
                // LinkAnnotation.Url clicks resolve through.
                val uriHandler = rememberCustomTabsUriHandler()
                CompositionLocalProvider(LocalUriHandler provides uriHandler) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding),
                        contentPadding = PaddingValues(OpenCodeThemeExtras.spacing.lg),
                        verticalArrangement = Arrangement.spacedBy(OpenCodeThemeExtras.spacing.sm),
                    ) {
                        items(state.blocks) { block -> ChangelogBlockView(block) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChangelogBlockView(block: ChangelogBlock) {
    val spacing = OpenCodeThemeExtras.spacing
    when (block) {
        is ChangelogBlock.Heading -> {
            val style = when (block.level) {
                1 -> MaterialTheme.typography.headlineSmall
                2 -> MaterialTheme.typography.titleLarge
                3 -> MaterialTheme.typography.titleMedium
                else -> MaterialTheme.typography.titleSmall
            }
            Text(
                text = annotatedInlines(block.inlines),
                style = style,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .padding(top = if (block.level <= 2) spacing.lg else spacing.sm)
                    .semantics { heading() },
            )
        }

        is ChangelogBlock.Bullet -> Row(
            modifier = Modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) {},
        ) {
            Text(text = stringResource(R.string.changelog_bullet), style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.width(spacing.sm))
            Text(
                text = annotatedInlines(block.inlines),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
        }

        is ChangelogBlock.Paragraph -> Text(
            text = annotatedInlines(block.inlines),
            style = MaterialTheme.typography.bodyMedium,
        )

        is ChangelogBlock.Code -> Surface(
            shape = OpenCodeThemeExtras.shapes.card,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Text(
                text = block.text,
                style = OpenCodeThemeExtras.code.body,
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(spacing.sm),
            )
        }
    }
}

/**
 * The block's inlines as an [AnnotatedString]: bold spans, mono code spans, and http(s) links.
 * Only links the parser validated get a [LinkAnnotation.Url]; anything else renders as its label.
 */
@Composable
private fun annotatedInlines(inlines: List<ChangelogInline>): AnnotatedString {
    val linkColor = MaterialTheme.colorScheme.primary
    val codeFont = OpenCodeThemeExtras.code.body.fontFamily
    return buildAnnotatedString {
        appendInlines(inlines, linkColor, codeFont)
    }
}

private fun AnnotatedString.Builder.appendInlines(
    inlines: List<ChangelogInline>,
    linkColor: Color,
    codeFont: FontFamily?,
) {
    for (inline in inlines) {
        when (inline) {
            is ChangelogInline.Text -> append(inline.text)
            is ChangelogInline.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                append(inline.text)
            }

            is ChangelogInline.Code -> withStyle(SpanStyle(fontFamily = codeFont)) {
                append(inline.text)
            }

            is ChangelogInline.Link -> {
                if (inline.url != null) {
                    withLink(
                        LinkAnnotation.Url(
                            url = inline.url,
                            styles = TextLinkStyles(
                                style = SpanStyle(
                                    color = linkColor,
                                    textDecoration = TextDecoration.Underline,
                                ),
                            ),
                        ),
                    ) {
                        appendInlines(inline.label, linkColor, codeFont)
                    }
                } else {
                    appendInlines(inline.label, linkColor, codeFont)
                }
            }
        }
    }
}

/**
 * A [UriHandler] that opens URLs in a Custom Tab.
 *
 * The intent carries the Custom Tabs extras directly, without `androidx.browser`, following the
 * pattern the navigation hosts use: `EXTRA_SESSION` is what makes a browser render a tab-stripped
 * window, and a browser that does not understand the extras simply opens the URL.
 */
@Composable
private fun rememberCustomTabsUriHandler(): UriHandler {
    val context = LocalContext.current
    return remember(context) {
        object : UriHandler {
            override fun openUri(uri: String) = openInCustomTab(context, uri)
        }
    }
}

private fun openInCustomTab(context: Context, url: String) {
    // Second gate: only validated http(s) URLs become link annotations, but the intent is built
    // here, so the scheme is checked again where it matters.
    if (!SafeNavigationUrl.isAllowed(url)) return
    val session = Bundle().apply {
        putString("android.support.customTabs.extra.SESSION", "opencode-changelog")
    }
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        putExtra("android.support.customTabs.extra.SESSION", session)
        putExtra("android.support.customTabs.extra.TOOLBAR_COLOR", 0)
        putExtra("android.support.customTabs.extra.EXTRA_ENABLE_URLBAR_HIDING", true)
    }
    try {
        context.startActivity(intent)
    } catch (missing: ActivityNotFoundException) {
        // No browser on the device; the changelog stays where it is.
    } catch (denied: SecurityException) {
        // A device policy blocked it; same outcome.
    }
}
