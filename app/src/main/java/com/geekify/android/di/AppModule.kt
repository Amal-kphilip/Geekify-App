package com.geekify.android.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import com.geekify.android.data.local.GeekifyDatabase
import com.geekify.android.data.local.HistoryDao
import com.geekify.android.data.local.LikedDao
import com.geekify.android.data.local.CatalogDao
import com.geekify.android.data.local.MIGRATION_1_2
import com.geekify.android.data.local.MIGRATION_2_3
import com.geekify.android.data.local.ShelfCacheDao
import com.geekify.android.data.local.PlayStatDao
import com.geekify.android.data.local.PlaylistDao
import com.geekify.android.data.source.MusicSource
import com.geekify.android.data.source.YouTubeMusicSource
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "geekify_prefs")

@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {
    @Binds abstract fun bindMusicSource(impl: YouTubeMusicSource): MusicSource

    companion object {
        @Provides @Singleton
        fun provideOkHttp(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

        @Provides @Singleton
        fun provideDataStore(@ApplicationContext ctx: Context): DataStore<Preferences> = ctx.dataStore

        @Provides @Singleton
        fun provideDatabase(@ApplicationContext ctx: Context): GeekifyDatabase =
            Room.databaseBuilder(ctx, GeekifyDatabase::class.java, "geekify.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()

        @Provides fun provideLikedDao(db: GeekifyDatabase): LikedDao = db.likedDao()
        @Provides fun provideHistoryDao(db: GeekifyDatabase): HistoryDao = db.historyDao()
        @Provides fun providePlaylistDao(db: GeekifyDatabase): PlaylistDao = db.playlistDao()
        @Provides fun providePlayStatDao(db: GeekifyDatabase): PlayStatDao = db.playStatDao()
        @Provides fun provideCatalogDao(db: GeekifyDatabase): CatalogDao = db.catalogDao()
        @Provides fun provideShelfCacheDao(db: GeekifyDatabase): ShelfCacheDao = db.shelfCacheDao()
    }
}
