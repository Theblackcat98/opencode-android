package dev.opencode.android

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import dev.opencode.android.core.data.attention.AttentionCoordinator
import dev.opencode.android.core.data.server.DebugFlags
import dev.opencode.android.feature.requests.notifications.ConnectionServiceLauncher
import dev.opencode.android.feature.requests.notifications.installAttentionChannels
import javax.inject.Inject

/**
 * The application, and where the debug-only switches and the Phase 4 background machinery are set.
 *
 * **[DebugFlags.selfCheckLogs] is read lazily by the Hilt provider, so it has to be set before the
 * first injection happens.** This constructor runs before any component, and there is no earlier hook.
 * A release build leaves it off, which is what keeps a transcript out of logcat in a shipped app.
 *
 * **The attention layer starts here rather than in an activity.** A notification has to be posted
 * whether or not a screen is open, and the coordinator has to be running for a receiver to be able to
 * cancel what it just answered. Starting it in the application also means the connection-service
 * launcher is watching before the first foreground pass, which is what lets the service start at the
 * moment a session becomes busy rather than the moment the user next opens the app.
 */
@HiltAndroidApp
class OpenCodeApplication : Application() {

    @Inject
    lateinit var attention: AttentionCoordinator

    @Inject
    lateinit var serviceLauncher: ConnectionServiceLauncher

    override fun onCreate() {
        super.onCreate()
        DebugFlags.selfCheckLogs = BuildConfig.DEBUG
        installAttentionChannels(this)
        attention.start()
        serviceLauncher.start()
    }
}
