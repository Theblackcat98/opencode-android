package dev.opencode.android.feature.requests.notifications

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The Quick Settings tile that starts and stops the connection service (matrix J9).
 *
 * **It shows what the launcher says and asks the launcher to act.** The tile is lit while
 * [ConnectionServiceLauncher.requested] is true, which is from the moment a start is asked for until the
 * service reports that it has stopped. A tap goes through the launcher's own entry points, so the policy
 * still decides whether a start is allowed, and the tile never starts the service by a second route.
 *
 * **It is updated only while the platform is listening.** [onStartListening] subscribes and
 * [onStopListening] lets go, so a tile that is not on screen does no work.
 *
 * The label and icon are declared in the manifest, which is where the platform reads them, and the label is
 * the string resource there rather than a literal here.
 */
@AndroidEntryPoint
class ConnectionTileService : TileService() {

    @Inject
    lateinit var launcher: ConnectionServiceLauncher

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var listening: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        listening?.cancel()
        listening = scope.launch {
            launcher.requested.collect { render(ConnectionTilePolicy.decide(it)) }
        }
    }

    override fun onStopListening() {
        listening?.cancel()
        listening = null
        super.onStopListening()
    }

    override fun onClick() {
        when (ConnectionTilePolicy.decide(launcher.requested.value).tap) {
            ConnectionTileTap.START -> launcher.startFromTile()
            ConnectionTileTap.STOP -> launcher.stopFromTile()
        }
    }

    override fun onDestroy() {
        listening?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun render(decision: ConnectionTileDecision) {
        val tile = qsTile ?: return
        tile.state = if (decision.active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
    }
}
