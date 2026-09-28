package dev.opencode.android.core.database.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.opencode.android.core.database.OpenCodeDatabase
import dev.opencode.android.core.database.dao.ServerDao
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideOpenCodeDatabase(
        @ApplicationContext context: Context,
    ): OpenCodeDatabase {
        return Room.databaseBuilder(
            context,
            OpenCodeDatabase::class.java,
            "opencode.db",
        )
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
    }

    @Provides
    fun provideServerDao(database: OpenCodeDatabase): ServerDao {
        return database.serverDao()
    }
}
