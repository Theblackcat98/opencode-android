package dev.opencode.android.feature.requests.notifications

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.opencode.android.core.data.connection.ServerConnectionManager
import dev.opencode.android.core.data.presence.PresenceController
import dev.opencode.android.core.data.presence.PresenceSignalsSource
import javax.inject.Singleton

/**
 * Keeps the active server's event stream open while the app is in the background.
 *
 * The connection manager stops the stream when the app leaves the foreground, because on its own it
 * only knows about the app. The connection service is the thing that knows better, and this is the one
 * call it makes to say so.
 */
fun interface StreamHold {
    /** `true` keeps the stream up although the app is away; `false` hands it back to the app's lifecycle. */
    fun hold(held: Boolean)
}

/**
 * What the connection service and its launcher read from, and act on, in the rest of the app.
 *
 * **Narrow seams rather than the classes behind them.** The service is the one component in the app
 * whose contract with the platform (`startForeground()` before anything else) has to be checked with
 * real Hilt injection, and it can only be checked if the signals it reads can be driven from a test and
 * the stream it holds can be observed. Binding the interfaces here, in a module of their own, is what
 * lets a test uninstall this module and supply both without also having to replace the notification
 * builders `AttentionModule` binds.
 */
@Module
@InstallIn(SingletonComponent::class)
object ConnectionServiceModule {

    /** The launcher and the service need the presence signals and nothing else; see `PresenceSignalsSource`. */
    @Provides
    @Singleton
    fun providePresenceSignals(presence: PresenceController): PresenceSignalsSource = presence

    @Provides
    @Singleton
    fun provideStreamHold(connections: ServerConnectionManager): StreamHold =
        StreamHold { held -> connections.activeConnection.value?.setForeground(held) }
}
