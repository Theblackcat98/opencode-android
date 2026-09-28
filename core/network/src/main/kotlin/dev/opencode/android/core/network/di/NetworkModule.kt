package dev.opencode.android.core.network.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.opencode.android.core.network.AndroidNetworkConnectivityMonitor
import dev.opencode.android.core.network.NetworkConnectivityMonitor
import dev.opencode.android.core.network.ServerValidator
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    @Provides
    @Singleton
    fun provideNetworkConnectivityMonitor(
        @ApplicationContext context: Context,
    ): NetworkConnectivityMonitor {
        return AndroidNetworkConnectivityMonitor(context)
    }

    @Provides
    @Singleton
    fun provideServerValidator(
        okHttpClient: OkHttpClient,
    ): ServerValidator {
        return ServerValidator(okHttpClient = okHttpClient)
    }
}
