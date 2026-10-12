package dev.opencode.android.navigation

import androidx.compose.foundation.lazy.LazyListState
import androidx.navigation.NavHostController
import dev.opencode.android.core.data.server.ServerDataRegistry
import dev.opencode.android.core.model.SessionInfo
import kotlinx.serialization.Serializable

/**
 * The nested navigation graphs. `OpenCodeApp`'s `NavHost` used to declare all 36 destinations
 * inline; the four graphs below group them by phase so the graph reads as the plan's phases do.
 * The grouping is organizational only: routes, start destinations and back-stack behavior are
 * unchanged, and every graph's start destination is the destination the flat graph started from
 * in that phase.
 */
@Serializable
data object HomeGraph

@Serializable
data object SessionGraph

@Serializable
data object SettingsGraph

@Serializable
data object AdminGraph

/**
 * What the extracted graphs close over from `OpenCodeApp`.
 *
 * The graphs are `NavGraphBuilder` extensions, not composables with a dozen parameters, so the
 * state they need travels in one holder. Everything in it is either stable (the controller, the
 * registry, the list state) or a snapshot lambda/value read fresh each composition; nothing here
 * is remembered inside the graphs themselves.
 */
class NavGraphDeps(
    /** The controller every destination navigates and pops with. */
    val navController: NavHostController,
    /** The registry the catalog browsers read their lists from. */
    val serverDataSets: ServerDataRegistry,
    /** The server the top-level screens are bound to, if one is active. */
    val activeServerId: String?,
    /** Opens the command palette; the graphs never touch its open flag directly. */
    val onOpenCommandPalette: () -> Unit,
    /** This app run's session tabs, kept across rotation above the `NavHost`. */
    val sessionTabs: SessionTabs,
    /** Replaces the session tabs after an open or a close. */
    val onSessionTabsChange: (SessionTabs) -> Unit,
    /** The wide-window session list's scroll state, hoisted so a tap does not restart it at the top. */
    val sessionListState: LazyListState,
    /** Session titles by id, for the tab strip; the inbox reads the same store. */
    val sessionInfo: Map<String, SessionInfo>,
    /** The title shown for a session that has none yet. */
    val untitledSession: String,
    /** Shows a transient message in the app-level snackbar host. */
    val showSnackbar: (String) -> Unit,
)
