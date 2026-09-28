package dev.opencode.android.core.data.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.opencode.android.core.data.repository.DefaultServerRepository
import dev.opencode.android.core.data.repository.ServerRepository
import dev.opencode.android.core.data.security.AndroidKeystoreCredentialStore
import dev.opencode.android.core.data.security.SecureCredentialStore
import dev.opencode.android.core.database.dao.ServerDao
import dev.opencode.android.core.network.ServerValidator
import okhttp3.OkHttpClient
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun provideSecureCredentialStore(
        @ApplicationContext context: Context,
    ): SecureCredentialStore {
        return AndroidKeystoreCredentialStore(context)
    }

    @Provides
    @Singleton
    fun provideServerRepository(
        serverDao: ServerDao,
        credentialStore: SecureCredentialStore,
        serverValidator: ServerValidator,
        okHttpClient: OkHttpClient,
    ): ServerRepository {
        return DefaultServerRepository(
            serverDao = serverDao,
            credentialStore = credentialStore,
            serverValidator = serverValidator,
            okHttpClient = okHttpClient,
        )
    }
}
