package dev.opencode.android.core.data.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.opencode.android.core.data.attention.AttentionPreferences
import dev.opencode.android.core.data.attention.DataStoreAttentionPreferences
import dev.opencode.android.core.data.composer.ComposerMemory
import dev.opencode.android.core.data.composer.DataStoreComposerMemory
import dev.opencode.android.core.data.repository.DefaultServerRepository
import dev.opencode.android.core.data.repository.ServerRepository
import dev.opencode.android.core.data.preferences.DataStoreModelPreferences
import dev.opencode.android.core.data.preferences.ModelPreferences
import dev.opencode.android.core.data.security.AndroidKeystoreCredentialStore
import dev.opencode.android.core.data.security.SecureCredentialStore
import dev.opencode.android.core.data.server.DebugFlags
import dev.opencode.android.core.data.server.LogTimelineSelfCheck
import dev.opencode.android.core.data.server.TimelineSelfCheck
import dev.opencode.android.core.database.cache.ReadCacheStore
import dev.opencode.android.core.database.dao.ServerDao
import dev.opencode.android.core.network.PairingClient
import dev.opencode.android.core.network.ServerCredentialCache
import dev.opencode.android.core.network.ServerValidator
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun provideSecureCredentialStore(
        @ApplicationContext context: Context,
    ): SecureCredentialStore = AndroidKeystoreCredentialStore(context)

    @Provides
    @Singleton
    fun provideServerRepository(
        serverDao: ServerDao,
        credentialStore: SecureCredentialStore,
        serverValidator: ServerValidator,
        pairingClient: PairingClient,
        credentialCache: ServerCredentialCache,
    ): ServerRepository = DefaultServerRepository(
        serverDao = serverDao,
        credentialStore = credentialStore,
        serverValidator = serverValidator,
        pairingClient = pairingClient,
        credentialCache = credentialCache,
    )

    /**
     * Where the timeline self-check's findings go.
     *
     * The comparison itself always runs; only the logging is behind a debug flag the app sets from
     * its own `BuildConfig`, so a release build never writes transcript contents to logcat.
     */
    @Provides
    @Singleton
    fun provideTimelineSelfCheck(): TimelineSelfCheck = LogTimelineSelfCheck(DebugFlags.selfCheckLogs)

    /**
     * The model picker's recents and favorites.
     *
     * The one piece of model state the client owns (features doc §8), stored per server in
     * DataStore rather than in Room: it is a handful of `provider/model#variant` strings, it is not
     * queried, and a preference store avoids a schema change in a database that is otherwise a
     * cache of the server's own projection.
     */
    @Provides
    @Singleton
    fun provideModelPreferences(
        @ApplicationContext context: Context,
    ): ModelPreferences = DataStoreModelPreferences(context)

    /**
     * Mute, quiet hours, the idle grace period and auto-approve (plan §6, Phase 4).
     *
     * All four are the client's, not the server's: a mute that travelled to the server would silence
     * the desktop too, and the server has no idea which sessions a user wants to be left alone in.
     */
    @Provides
    @Singleton
    fun provideAttentionPreferences(
        @ApplicationContext context: Context,
    ): AttentionPreferences = DataStoreAttentionPreferences(context)

    /**
     * The composer's client-side memory: per-session drafts, per-server prompt history and the
     * stash (plan §6, Phase 5, "Ergonomics").
     *
     * All three are the client's, for the same reason a mute is: a draft or a stashed prompt that
     * travelled to the server would be a state the TUI and the web app cannot see, and the server has
     * no concept of either.
     */
    @Provides
    @Singleton
    fun provideComposerMemory(
        @ApplicationContext context: Context,
    ): ComposerMemory = DataStoreComposerMemory(context)
}
