package com.geekify.android.settings

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.SimpleCache
import coil3.imageLoader
import com.geekify.android.data.cache.TtlLruCache
import com.geekify.android.data.local.CatalogRepository
import com.geekify.android.data.local.HistoryRepository
import com.geekify.android.data.local.LibraryRepository
import com.geekify.android.data.local.ListeningStatsRepository
import com.geekify.android.data.local.LocalPlaylist
import com.geekify.android.data.local.SavedCollection
import com.geekify.android.data.local.ShelfCacheRepository
import com.geekify.android.data.model.Track
import com.geekify.android.data.sync.SyncRepository
import com.geekify.android.innertube.YouTube
import com.geekify.android.innertube.models.YouTubeLocale
import com.geekify.android.player.QueueManager
import com.geekify.android.ui.navigation.FloatingTabBarInlineBehavior
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject

@Serializable
data class GeekifyBackupPayload(
    val version: Int = 1,
    val exportedAt: Long = System.currentTimeMillis(),
    val likedTracks: List<Track> = emptyList(),
    val playlists: List<LocalPlaylist> = emptyList(),
    val savedCollections: List<SavedCollection> = emptyList(),
    val historyTracks: List<Track> = emptyList()
)

data class StorageStats(
    val audioCacheBytes: Long = 0L,
    val imageCacheBytes: Long = 0L,
    val metadataBytes: Long = 0L,
    val updateCacheBytes: Long = 0L
) {
    val totalBytes: Long get() = audioCacheBytes + imageCacheBytes + metadataBytes + updateCacheBytes
}

@OptIn(UnstableApi::class)
@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: AppSettingsRepository,
    private val audioCache: SimpleCache,
    private val ttlCache: TtlLruCache,
    private val catalogRepository: CatalogRepository,
    private val shelfCacheRepository: ShelfCacheRepository,
    private val historyRepository: HistoryRepository,
    private val listeningStatsRepository: ListeningStatsRepository,
    private val libraryRepository: LibraryRepository,
    private val syncRepository: SyncRepository,
    private val queueManager: QueueManager
) : ViewModel() {

    val settings: StateFlow<AppSettingsState> = repository.state

    private val _storageStats = MutableStateFlow(StorageStats())
    val storageStats: StateFlow<StorageStats> = _storageStats.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private val backupJson = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    init {
        refreshStorageStats()
    }

    fun clearStatusMessage() {
        _statusMessage.value = null
    }

    fun refreshStorageStats() {
        viewModelScope.launch(Dispatchers.IO) {
            val audioBytes = runCatching {
                maxOf(audioCache.cacheSpace, folderSize(File(context.cacheDir, "audio_cache")))
            }.getOrDefault(0L)

            val imageBytes = runCatching {
                val coilDisk = context.imageLoader.diskCache?.size ?: 0L
                val fallbackDir = folderSize(File(context.cacheDir, "image_cache"))
                maxOf(coilDisk, fallbackDir)
            }.getOrDefault(0L)

            val dbFile = context.getDatabasePath("geekify.db")
            val dbBytes = runCatching {
                val base = if (dbFile.exists()) dbFile.length() else 0L
                val wal = File(dbFile.path + "-wal").takeIf { it.exists() }?.length() ?: 0L
                base + wal
            }.getOrDefault(0L)

            val updateBytes = runCatching {
                folderSize(File(context.cacheDir, "updates"))
            }.getOrDefault(0L)

            _storageStats.value = StorageStats(
                audioCacheBytes = audioBytes,
                imageCacheBytes = imageBytes,
                metadataBytes = dbBytes,
                updateCacheBytes = updateBytes
            )
        }
    }

    private fun folderSize(dir: File): Long {
        if (!dir.exists()) return 0L
        if (dir.isFile) return dir.length()
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    // ---- Playback ----
    fun setAudioQuality(quality: AudioQualityPreference) {
        viewModelScope.launch { repository.setAudioQuality(quality) }
    }

    fun setPersistentQueue(enabled: Boolean) {
        viewModelScope.launch { repository.setPersistentQueue(enabled) }
    }

    fun setAutoplayRelated(enabled: Boolean) {
        viewModelScope.launch { repository.setAutoplayRelated(enabled) }
    }

    fun setRememberShuffleRepeat(enabled: Boolean) {
        viewModelScope.launch { repository.setRememberShuffleRepeat(enabled) }
    }

    fun setAutoSkipOnError(enabled: Boolean) {
        viewModelScope.launch { repository.setAutoSkipOnError(enabled) }
    }

    fun setKeepScreenOn(enabled: Boolean) {
        viewModelScope.launch { repository.setKeepScreenOn(enabled) }
    }

    // ---- Liquid Glass & Appearance ----
    fun setGlassEnabled(enabled: Boolean) {
        viewModelScope.launch { repository.setGlassEnabled(enabled) }
    }

    fun setGlassBlurDp(value: Float) {
        viewModelScope.launch { repository.setGlassBlurDp(value) }
    }

    fun setGlassSaturation(value: Float) {
        viewModelScope.launch { repository.setGlassSaturation(value) }
    }

    fun setGlassLensAmountDp(value: Float) {
        viewModelScope.launch { repository.setGlassLensAmountDp(value) }
    }

    fun setGlassLensHeightDp(value: Float) {
        viewModelScope.launch { repository.setGlassLensHeightDp(value) }
    }

    fun setGlassChromaticAberration(enabled: Boolean) {
        viewModelScope.launch { repository.setGlassChromaticAberration(enabled) }
    }

    fun setGlassDepthEffect(enabled: Boolean) {
        viewModelScope.launch { repository.setGlassDepthEffect(enabled) }
    }

    fun setGlassTintAlpha(value: Float) {
        viewModelScope.launch { repository.setGlassTintAlpha(value) }
    }

    fun setGlassMiniPlayer(enabled: Boolean) {
        viewModelScope.launch { repository.setGlassMiniPlayer(enabled) }
    }

    fun setGlassNavBar(enabled: Boolean) {
        viewModelScope.launch { repository.setGlassNavBar(enabled) }
    }

    fun resetGlassDefaults() {
        viewModelScope.launch {
            repository.resetGlassDefaults()
            _statusMessage.value = "Liquid Glass restored to crystal-clear defaults"
        }
    }

    fun setNavInlineBehavior(behavior: FloatingTabBarInlineBehavior) {
        viewModelScope.launch { repository.setNavInlineBehavior(behavior) }
    }

    fun setDefaultTab(tab: String) {
        viewModelScope.launch { repository.setDefaultTab(tab) }
    }

    // ---- Content & Region ----
    fun setContentCountry(countryCode: String) {
        viewModelScope.launch {
            val lang = settings.value.contentLanguage
            YouTube.locale = YouTubeLocale(hl = lang, gl = countryCode)
            YouTube.visitorData = null
            runCatching { YouTube.extractionTransport().innerTube.regionOverrideActive = true }
            ttlCache.clear()
            shelfCacheRepository.clear()
            repository.setContentCountry(countryCode)
            _statusMessage.value = "Content country updated to $countryCode — Home feed refreshed"
        }
    }

    fun setContentLanguage(languageCode: String) {
        viewModelScope.launch {
            val country = settings.value.contentCountry
            YouTube.locale = YouTubeLocale(hl = languageCode, gl = country)
            YouTube.visitorData = null
            runCatching { YouTube.extractionTransport().innerTube.regionOverrideActive = true }
            ttlCache.clear()
            shelfCacheRepository.clear()
            repository.setContentLanguage(languageCode)
            _statusMessage.value = "Content language updated — Home feed refreshed"
        }
    }

    fun setHideExplicit(enabled: Boolean) {
        viewModelScope.launch {
            ttlCache.clear()
            shelfCacheRepository.clear()
            if (enabled) queueManager.removeExplicitUpcomingTracks()
            repository.setHideExplicit(enabled)
        }
    }

    // ---- Privacy & Storage ----
    fun setPauseListenHistory(enabled: Boolean) {
        viewModelScope.launch { repository.setPauseListenHistory(enabled) }
    }

    fun setDisableScreenshots(enabled: Boolean) {
        viewModelScope.launch { repository.setDisableScreenshots(enabled) }
    }

    fun clearListenHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            historyRepository.clear()
            historyRepository.clearStats()
            listeningStatsRepository.clear()
            _statusMessage.value = "Listening history and stats cleared"
            refreshStorageStats()
        }
    }

    fun clearAudioCache() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                audioCache.keys.toList().forEach { key ->
                    runCatching { audioCache.removeResource(key) }
                }
            }
            _statusMessage.value = "Audio stream cache cleared"
            refreshStorageStats()
        }
    }

    fun clearImageCache() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                context.imageLoader.memoryCache?.clear()
                context.imageLoader.diskCache?.clear()
                File(context.cacheDir, "image_cache").deleteRecursively()
            }
            _statusMessage.value = "Artwork & image cache cleared"
            refreshStorageStats()
        }
    }

    fun clearMetadataCache() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                catalogRepository.prune(0L)
                shelfCacheRepository.clear()
                ttlCache.clear()
            }
            _statusMessage.value = "Cached feed & catalog metadata cleared"
            refreshStorageStats()
        }
    }

    fun clearUpdateCache() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                File(context.cacheDir, "updates").deleteRecursively()
            }
            _statusMessage.value = "Downloaded update packages cleared"
            refreshStorageStats()
        }
    }

    // ---- Backup & Restore ----
    fun exportBackup(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val payload = GeekifyBackupPayload(
                    version = 1,
                    exportedAt = System.currentTimeMillis(),
                    likedTracks = libraryRepository.likedOnce(),
                    playlists = libraryRepository.playlistsOnce(),
                    savedCollections = libraryRepository.savedCollectionsOnce(),
                    historyTracks = historyRepository.allOnce()
                )
                val jsonBytes = backupJson.encodeToString(payload).toByteArray(Charsets.UTF_8)
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(jsonBytes)
                    out.flush()
                } ?: error("Could not open selected file for writing.")
                payload
            }.onSuccess { payload ->
                _statusMessage.value = "Backed up ${payload.likedTracks.size} liked songs & ${payload.playlists.size} playlists"
            }.onFailure { e ->
                _statusMessage.value = "Backup failed: ${e.message ?: "Unknown error"}"
            }
        }
    }

    fun importBackup(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val raw = context.contentResolver.openInputStream(uri)?.use { input ->
                    input.readBytes().toString(Charsets.UTF_8)
                } ?: error("Could not read backup file.")
                val payload = backupJson.decodeFromString<GeekifyBackupPayload>(raw)

                // Merge with existing library so nothing is lost
                val mergedLiked = (payload.likedTracks + libraryRepository.likedOnce()).distinctBy { it.videoId }
                val mergedPlaylists = (payload.playlists + libraryRepository.playlistsOnce()).distinctBy { it.id }
                val mergedCollections = (payload.savedCollections + libraryRepository.savedCollectionsOnce()).distinctBy { it.id }
                val mergedHistory = (payload.historyTracks + historyRepository.allOnce()).distinctBy { it.videoId }

                libraryRepository.replaceAllLiked(mergedLiked)
                libraryRepository.replaceAllPlaylists(mergedPlaylists)
                libraryRepository.replaceAllSavedCollections(mergedCollections)
                historyRepository.replaceAll(mergedHistory)
                syncRepository.schedulePush()
                payload
            }.onSuccess { payload ->
                _statusMessage.value = "Restored ${payload.likedTracks.size} liked songs & ${payload.playlists.size} playlists"
            }.onFailure { e ->
                _statusMessage.value = "Restore failed: invalid backup file (${e.message ?: "parse error"})"
            }
        }
    }
}
