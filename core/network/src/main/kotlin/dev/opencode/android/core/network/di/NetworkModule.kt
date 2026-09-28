package dev.opencode.android.core.network.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.opencode.android.core.network.AndroidNetworkConnectivityMonitor
import dev.opencode.android.core.network.AndroidUserCertificateSource
import dev.opencode.android.core.network.NetworkConnectivityMonitor
import dev.opencode.android.core.network.PairingClient
import dev.opencode.android.core.network.ServerApiFactory
import dev.opencode.android.core.network.ServerTls
import dev.opencode.android.core.network.ServerValidator
import dev.opencode.android.core.network.UserCertificateSource
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    /**
     * The one HTTP client every server shares, so connections, threads and the auth interceptor
     * are pooled rather than duplicated per server.
     */
    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    @Provides
    @Singleton
    fun provideNetworkConnectivityMonitor(
        @ApplicationContext context: Context,
    ): NetworkConnectivityMonitor = AndroidNetworkConnectivityMonitor(context)

    @Provides
    @Singleton
    fun provideUserCertificateSource(): UserCertificateSource = AndroidUserCertificateSource()

    @Provides
    @Singleton
    fun provideServerTls(
        okHttpClient: OkHttpClient,
        userCertificateSource: UserCertificateSource,
    ): ServerTls = ServerTls(okHttpClient, userCertificateSource)

    @Provides
    @Singleton
    fun provideServerApiFactory(
        okHttpClient: OkHttpClient,
        serverTls: ServerTls,
    ): ServerApiFactory = ServerApiFactory(okHttpClient, serverTls)

    @Provides
    @Singleton
    fun provideServerValidator(serverApiFactory: ServerApiFactory): ServerValidator =
        ServerValidator(serverApiFactory)

    @Provides
    @Singleton
    fun providePairingClient(serverApiFactory: ServerApiFactory): PairingClient =
        PairingClient(serverApiFactory)
}
