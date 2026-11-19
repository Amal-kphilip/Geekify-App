package com.geekify.android.di

import android.app.Application
import kotlinx.coroutines.CoroutineDispatcher
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.koinApplication
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KoinGraphTest {

    @After
    fun tearDown() {
        stopKoin()
    }

    @Test
    fun `dispatchers module provides all three qualified dispatchers`() {
        val koinApp = koinApplication {
            modules(dispatchersModule)
        }
        val koin = koinApp.koin
        assertNotNull(koin.get<CoroutineDispatcher>(IoDispatcherQualifier))
        assertNotNull(koin.get<CoroutineDispatcher>(DefaultDispatcherQualifier))
        assertNotNull(koin.get<CoroutineDispatcher>(MainDispatcherQualifier))
        koinApp.close()
    }

    @Test
    fun `allAppModules start cleanly with Android application context`() {
        val app: Application = RuntimeEnvironment.getApplication()
        stopKoin()
        val koinApp = startKoin {
            androidContext(app)
            modules(allAppModules)
        }
        val koin = koinApp.koin
        assertNotNull(koin.get<okhttp3.OkHttpClient>())
        assertNotNull(koin.get<com.geekify.android.data.cache.TtlLruCache>())
        assertNotNull(koin.get<com.geekify.android.settings.FeatureFlagManager>())
        assertNotNull(koin.get<com.geekify.android.settings.AppSettingsRepository>())
        assertNotNull(koin.get<com.geekify.android.player.QueueManager>())
        assertNotNull(koin.get<com.geekify.android.player.InnerTubeXPlayer>())
        assertNotNull(koin.get<com.geekify.android.data.source.MusicSource>())
        assertNotNull(koin.get<com.geekify.android.audio.AudioEffectsController>())
        assertNotNull(koin.get<com.geekify.android.audio.CrossfadeAudioTransitionController>())
        assertNotNull(koin.get<com.geekify.android.player.PlaybackDiagnosticsTracker>())
        assertNotNull(koin.get<com.geekify.android.player.extraction.PlaybackNetworkMonitor>())
        assertNotNull(koin.get<com.geekify.android.downloads.AudioCacheManager>())
        assertNotNull(koin.get<com.geekify.android.downloads.SongDownloadManager>())
        assertNotNull(koin.get<com.geekify.android.recognition.ShazamRecognitionClient>())
        assertNotNull(koin.get<com.geekify.android.recognition.MusicRecognitionService>())
        assertNotNull(koin.get<com.geekify.android.lyrics.LyricsRepository>())
        assertNotNull(koin.get<com.geekify.android.scrobble.ScrobbleManager>())
    }
}
