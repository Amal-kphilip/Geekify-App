@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.geekify.android.di

import android.content.Context
import androidx.annotation.OptIn
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.room.Room
import com.geekify.android.audio.AudioEffectsController
import com.geekify.android.audio.AudioSettingsRepository
import com.geekify.android.audio.AudioSettingsViewModel
import com.geekify.android.audio.CrossfadeAudioTransitionController
import com.geekify.android.data.auth.AuthRepository
import com.geekify.android.data.cache.TtlLruCache
import com.geekify.android.data.local.CachedAudioDao
import com.geekify.android.data.local.CatalogDao
import com.geekify.android.data.local.CatalogRepository
import com.geekify.android.data.local.DownloadDao
import com.geekify.android.data.local.GeekifyDatabase
import com.geekify.android.data.local.HistoryDao
import com.geekify.android.data.local.HistoryRepository
import com.geekify.android.data.local.LibraryRepository
import com.geekify.android.data.local.LikedDao
import com.geekify.android.data.local.ListeningSessionDao
import com.geekify.android.data.local.ListeningStatsRepository
import com.geekify.android.data.local.LyricsDao
import com.geekify.android.data.local.MIGRATION_10_11
import com.geekify.android.data.local.MIGRATION_1_2
import com.geekify.android.data.local.MIGRATION_2_3
import com.geekify.android.data.local.MIGRATION_3_4
import com.geekify.android.data.local.MIGRATION_4_5
import com.geekify.android.data.local.MIGRATION_5_6
import com.geekify.android.data.local.MIGRATION_6_7
import com.geekify.android.data.local.MIGRATION_7_8
import com.geekify.android.data.local.MIGRATION_8_9
import com.geekify.android.data.local.MIGRATION_9_10
import com.geekify.android.data.local.PlayStatDao
import com.geekify.android.data.local.PlaylistDao
import com.geekify.android.data.local.RecentSearchDao
import com.geekify.android.data.local.RecognitionHistoryDao
import com.geekify.android.data.local.RecommendationEngineDao
import com.geekify.android.data.local.RecommendationFeedbackDao
import com.geekify.android.data.local.RecommendationLogDao
import com.geekify.android.data.local.RecommendationLogRepository
import com.geekify.android.data.local.ShelfCacheDao
import com.geekify.android.data.local.ShelfCacheRepository
import com.geekify.android.downloads.AudioCacheManager
import com.geekify.android.downloads.SongDownloadManager
import com.geekify.android.lyrics.LyricsClient
import com.geekify.android.lyrics.LyricsRepository
import com.geekify.android.recognition.MusicRecognitionService
import com.geekify.android.recognition.ShazamRecognitionClient
import com.geekify.android.scrobble.ScrobbleManager
import com.geekify.android.customization.CustomizationViewModel
import com.geekify.android.ui.downloads.DownloadsViewModel
import com.geekify.android.ui.recognition.RecognitionViewModel
import com.geekify.android.data.source.MusicSource
import com.geekify.android.data.source.YouTubeMusicSource
import com.geekify.android.data.source.innertube.GeekifyInnerTubeBackend
import com.geekify.android.data.source.innertube.InnerTubeClient
import com.geekify.android.data.sync.SyncRepository
import com.geekify.android.domain.listening.ListeningBehaviorManager
import com.geekify.android.domain.listening.ListeningLanguageMetadataResolver
import com.geekify.android.domain.offline.OfflineIntelligenceManager
import com.geekify.android.domain.recommend.RecommendationFeedbackRepository
import com.geekify.android.domain.recommend.RecommendationMemory
import com.geekify.android.domain.recommend.SmartMixManager
import com.geekify.android.domain.recommend.engine.AudioEmbeddingService
import com.geekify.android.domain.recommend.engine.CandidateDiscoveryEngine
import com.geekify.android.domain.recommend.engine.CollaborativeRecommendationEngine
import com.geekify.android.domain.recommend.engine.HybridRecommendationPipeline
import com.geekify.android.domain.recommend.engine.LyricsSemanticService
import com.geekify.android.domain.recommend.engine.MusicBrainzCandidateProvider
import com.geekify.android.domain.recommend.engine.MusicClassificationService
import com.geekify.android.domain.recommend.engine.NeuralRecommendationEngine
import com.geekify.android.domain.recommend.engine.RecommendationContextEngine
import com.geekify.android.domain.recommend.engine.RecommendationExperimentManager
import com.geekify.android.domain.recommend.engine.YouTubeMusicCandidateProvider
import com.geekify.android.listentogether.ListenTogetherManager
import com.geekify.android.listentogether.ListenTogetherViewModel
import com.geekify.android.notifications.GeekifyNotifier
import com.geekify.android.notifications.NotificationCoordinator
import com.geekify.android.notifications.NotificationSettingsViewModel
import com.geekify.android.notifications.NotificationStore
import com.geekify.android.notifications.UpdateChecker
import com.geekify.android.player.InnerTubeXPlayer
import com.geekify.android.player.PlaybackDiagnosticsTracker
import com.geekify.android.player.PlayerController
import com.geekify.android.player.QueueManager
import com.geekify.android.player.StreamResolver
import com.geekify.android.player.extraction.InnerTubePlaybackExtractor
import com.geekify.android.player.extraction.PlaybackNetworkMonitor
import com.geekify.android.settings.AppSettingsRepository
import com.geekify.android.settings.FeatureFlagManager
import com.geekify.android.settings.SettingsViewModel
import com.geekify.android.ui.DebugSearchViewModel
import com.geekify.android.ui.account.AccountViewModel
import com.geekify.android.ui.details.DetailsViewModel
import com.geekify.android.ui.health.HealthViewModel
import com.geekify.android.ui.home.HomeViewModel
import com.geekify.android.ui.library.LibraryViewModel
import com.geekify.android.ui.library.ListeningStatsViewModel
import com.geekify.android.ui.library.RecentsViewModel
import com.geekify.android.ui.player.PlayerViewModel
import com.geekify.android.ui.search.SearchViewModel
import com.geekify.android.update.AppUpdateManager
import com.geekify.android.update.UpdateSessionCoordinator
import com.geekify.android.update.UpdateViewModel
import java.io.File
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.bind
import org.koin.dsl.module

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "geekify_prefs")

/** Upper bound for the legacy ExoPlayer on-disk cache. */
private const val AUDIO_CACHE_BYTES = 300L * 1024 * 1024

val networkModule = module {
    single<OkHttpClient> {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }
    single { TtlLruCache() }
    single { GeekifyInnerTubeBackend(get()) }
    single { InnerTubeClient(get(), get()) }
}

val databaseModule = module {
    single<DataStore<Preferences>> { androidContext().dataStore }

    single<GeekifyDatabase> {
        Room.databaseBuilder(androidContext(), GeekifyDatabase::class.java, "geekify.db")
            .addMigrations(
                MIGRATION_1_2,
                MIGRATION_2_3,
                MIGRATION_3_4,
                MIGRATION_4_5,
                MIGRATION_5_6,
                MIGRATION_6_7,
                MIGRATION_7_8,
                MIGRATION_8_9,
                MIGRATION_9_10,
                MIGRATION_10_11
            )
            .build()
    }

    single<LikedDao> { get<GeekifyDatabase>().likedDao() }
    single<HistoryDao> { get<GeekifyDatabase>().historyDao() }
    single<PlaylistDao> { get<GeekifyDatabase>().playlistDao() }
    single<PlayStatDao> { get<GeekifyDatabase>().playStatDao() }
    single<ListeningSessionDao> { get<GeekifyDatabase>().listeningSessionDao() }
    single<CatalogDao> { get<GeekifyDatabase>().catalogDao() }
    single<ShelfCacheDao> { get<GeekifyDatabase>().shelfCacheDao() }
    single<RecentSearchDao> { get<GeekifyDatabase>().recentSearchDao() }
    single<RecommendationLogDao> { get<GeekifyDatabase>().recommendationLogDao() }
    single<RecommendationEngineDao> { get<GeekifyDatabase>().recommendationEngineDao() }
    single<RecommendationFeedbackDao> { get<GeekifyDatabase>().recommendationFeedbackDao() }
    single<CachedAudioDao> { get<GeekifyDatabase>().cachedAudioDao() }
    single<DownloadDao> { get<GeekifyDatabase>().downloadDao() }
    single<RecognitionHistoryDao> { get<GeekifyDatabase>().recognitionHistoryDao() }
    single<LyricsDao> { get<GeekifyDatabase>().lyricsDao() }
}

@OptIn(UnstableApi::class)
val playbackModule = module {
    single<SimpleCache> {
        SimpleCache(
            File(androidContext().cacheDir, "audio_cache"),
            LeastRecentlyUsedCacheEvictor(AUDIO_CACHE_BYTES),
            StandaloneDatabaseProvider(androidContext())
        )
    }
    single { AppSettingsRepository(get()) }
    single { FeatureFlagManager() }
    single { AudioSettingsRepository(get(), get(IoDispatcherQualifier)) }
    single { AudioEffectsController(get(), get(DefaultDispatcherQualifier)) }
    single { CrossfadeAudioTransitionController(get()) }
    single { PlaybackNetworkMonitor(androidContext()) }
    single { InnerTubePlaybackExtractor(androidContext(), get(), get()) }
    single { AudioCacheManager(androidContext(), get(), get(), get()) }
    single {
        val cacheManager = get<AudioCacheManager>()
        StreamResolver(get()) { videoId -> cacheManager.resolveLocalStream(videoId) }
    } bind InnerTubeXPlayer::class
    single { SongDownloadManager(androidContext(), get(), get(), get(), get(IoDispatcherQualifier)) }
    single { PlaybackDiagnosticsTracker(androidContext()) }
    single { QueueManager(get()) }
    single { PlayerController(androidContext(), get()) }
    single { ListenTogetherManager(get(), get(), get(), get(IoDispatcherQualifier)) }
}

val repositoryModule = module {
    single { AuthRepository(androidContext()) }
    single { CatalogRepository(get(), get(IoDispatcherQualifier)) }
    single { ShelfCacheRepository(get(), get(IoDispatcherQualifier)) }
    single { HistoryRepository(get(), get()) }
    single { ListeningStatsRepository(get()) }
    single { RecommendationLogRepository(get()) }
    single { LibraryRepository(get(), get(), get(), lazy { get<RecommendationLogRepository>() }) }
    single { YouTubeMusicSource(get(), get(), get(), get(), get(DefaultDispatcherQualifier), get(IoDispatcherQualifier)) } bind MusicSource::class
    single { SyncRepository(androidContext(), get(), get(), get(), get(), get(), get(), get(), get()) }

    // Listening & Offline
    single { ListeningBehaviorManager(get(), get()) }
    single { ListeningLanguageMetadataResolver(get()) }
    single { OfflineIntelligenceManager(androidContext(), get(), get(), get()) }

    // Recommendation Engine
    single { RecommendationFeedbackRepository(get()) }
    single { RecommendationMemory() }
    single { MusicClassificationService() }
    single { RecommendationContextEngine(get()) }
    single { AudioEmbeddingService(get()) }
    single { LyricsSemanticService() }
    single { CollaborativeRecommendationEngine() }
    single { NeuralRecommendationEngine() }
    single { RecommendationExperimentManager() }
    single { HybridRecommendationPipeline(get(), get(), get(), get(), get(), get(), get()) }
    single { SmartMixManager(get(), get(), get()) }
    single { YouTubeMusicCandidateProvider(get()) }
    single { MusicBrainzCandidateProvider(get(), get()) }
    single { CandidateDiscoveryEngine(get()) }

    // Notifications & Updates
    single { NotificationStore(get(), get(IoDispatcherQualifier)) }
    single { GeekifyNotifier(androidContext()) }
    single { UpdateChecker(get(), get(), get(IoDispatcherQualifier)) }
    single { AppUpdateManager(androidContext()) }
    single { NotificationCoordinator(get(), get(), get(), get(), get(), get(), get()) }
    single { UpdateSessionCoordinator(get(), get(), get()) }

    // Music Recognition, Lyrics, Scrobbling, Casting & Discord RPC
    single { ShazamRecognitionClient(get()) }
    single { MusicRecognitionService(androidContext(), get(), get(), get(), get(IoDispatcherQualifier)) }
    single { LyricsClient(get()) }
    single { com.geekify.android.lyrics.LyricsTranslationClient(get()) }
    single { LyricsRepository(get(), get()) }
    single { ScrobbleManager(get(), get()) }
    single { com.geekify.android.cast.GeekifyCastManager(androidContext(), get()) }
    single { com.geekify.android.discord.DiscordRpcManager(get(), get()) }
}

val viewModelModule = module {
    viewModel { PlayerViewModel(get(), get(), get(), get()) }
    viewModel { HomeViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    viewModel { SearchViewModel(get(), get(), get(), get(), get(), get(), get(), get(DefaultDispatcherQualifier), get(IoDispatcherQualifier)) }
    viewModel { LibraryViewModel(get(), get(), get()) }
    viewModel { DetailsViewModel(get(), get()) }
    viewModel { SettingsViewModel(androidContext(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    viewModel { AudioSettingsViewModel(get()) }
    viewModel { AccountViewModel(get(), get()) }
    viewModel { HealthViewModel(get(), get()) }
    viewModel { ListenTogetherViewModel(get(), get()) }
    viewModel { NotificationSettingsViewModel(get(), androidContext()) }
    viewModel { UpdateViewModel(get()) }
    viewModel { ListeningStatsViewModel(get(), get()) }
    viewModel { RecentsViewModel(get()) }
    viewModel { DebugSearchViewModel(get()) }
    viewModel { RecognitionViewModel(get()) }
    viewModel { DownloadsViewModel(get(), get()) }
    viewModel { CustomizationViewModel(androidContext(), get(), get()) }
}

val allAppModules = listOf(
    dispatchersModule,
    networkModule,
    databaseModule,
    playbackModule,
    repositoryModule,
    viewModelModule
)
