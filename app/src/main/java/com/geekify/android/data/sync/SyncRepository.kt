package com.geekify.android.data.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.geekify.android.data.auth.AuthRepository
import com.geekify.android.data.auth.SyncState
import com.geekify.android.data.local.HistoryRepository
import com.geekify.android.data.local.LibraryRepository
import com.geekify.android.data.local.ListeningSessionEntity
import com.geekify.android.data.local.ListeningStatsRepository
import com.geekify.android.data.local.LocalPlaylist
import com.geekify.android.data.local.SavedCollection
import com.geekify.android.data.model.ArtistRef
import com.geekify.android.data.model.Thumbnail
import com.geekify.android.data.model.Track
import com.geekify.android.player.PlaybackService
import com.geekify.android.player.QueueManager
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Synchronizes the local library (liked songs, playlists, saved collections, and listening history)
 * with Firebase Firestore.
 *
 * - Uses scalable per-playlist (`users/{uid}/playlists/{id}`) and chunked liked-track
 *   (`users/{uid}/liked_chunks/chunk_{i}`) subcollections to avoid Firestore's 1 MiB single-document limit,
 *   while maintaining full backward compatibility with legacy top-level fields.
 * - Tracks local dirty state and modification timestamps (`sync_dirty`, `sync_local_modified_at`,
 *   `sync_last_synced_at`) so cloud pulls never overwrite unsynchronized offline local edits.
 * - Schedules [SyncWorker] with network constraints to automatically retry failed offline pushes.
 */
@Singleton
class SyncRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val auth: AuthRepository,
    private val library: LibraryRepository,
    private val history: HistoryRepository,
    private val listeningStats: ListeningStatsRepository,
    private val queueManager: QueueManager,
    private val dataStore: DataStore<Preferences>
) {
    companion object {
        private const val PUSH_DELAY_MS = 2_000L
        private const val STALE_PULL_MS = 60_000L
        private const val MAX_HISTORY = 40
        private const val LIKED_CHUNK_SIZE = 200
        private const val LEGACY_INLINE_TRACK_LIMIT = 250
        private const val FIRESTORE_BATCH_LIMIT = 400

        val KEY_LAST_UID = stringPreferencesKey("sync_last_uid")
        val KEY_SYNC_DIRTY = booleanPreferencesKey("sync_dirty")
        val KEY_LOCAL_MODIFIED_AT = longPreferencesKey("sync_local_modified_at")
        val KEY_LAST_SYNCED_AT = longPreferencesKey("sync_last_synced_at")

        fun mergeSaved(first: List<SavedCollection>, second: List<SavedCollection>): List<SavedCollection> {
            val seen = mutableSetOf<String>()
            return (first + second).filter { seen.add(it.id) }
        }

        fun mergeTracks(first: List<Track>, second: List<Track>): List<Track> {
            val seen = mutableSetOf<String>()
            return (first + second).filter { it.videoId.isNotEmpty() && seen.add(it.videoId) }
        }

        /** Union by song, keeping the most recent play time, newest first. */
        fun mergeHistory(first: List<Pair<Track, Long>>, second: List<Pair<Track, Long>>): List<Pair<Track, Long>> =
            (first + second)
                .filter { it.first.videoId.isNotEmpty() }
                .groupBy { it.first.videoId }
                .map { (_, entries) -> entries.maxByOrNull { it.second }!! }
                .sortedByDescending { it.second }

        /** Merge durable listening checkpoints by session ID, retaining the newest checkpoint. */
        fun mergeSessions(
            first: List<ListeningSessionEntity>,
            second: List<ListeningSessionEntity>
        ): List<ListeningSessionEntity> = (first + second)
            .filter { it.sessionId.isNotBlank() && it.videoId.isNotBlank() }
            .groupBy { it.sessionId }
            .map { (_, entries) ->
                val newest = entries.maxByOrNull { it.updatedAt }!!
                newest.copy(
                    listenedMs = entries.maxOf { it.listenedMs },
                    completed = entries.any { it.completed },
                    skipped = entries.any { it.skipped },
                    updatedAt = entries.maxOf { it.updatedAt }
                )
            }
            .sortedByDescending { it.startedAt }
            .take(5_000)

        fun mergePlaylists(local: List<LocalPlaylist>, cloud: List<LocalPlaylist>): List<LocalPlaylist> {
            val byId = cloud.associateBy { it.id }.toMutableMap()
            for (p in local) {
                val existing = byId[p.id]
                byId[p.id] = if (existing != null) {
                    val newerName = if (p.createdAt >= existing.createdAt) p.name else existing.name
                    existing.copy(
                        name = newerName,
                        tracks = mergeTracks(p.tracks, existing.tracks)
                    )
                } else {
                    p
                }
            }
            return byId.values.sortedByDescending { it.createdAt }
        }

        /**
         * Determines how local and cloud data should be reconciled:
         * - When [firstTime] is true or local has unsynchronized edits ([localDirty] or
         *   [localModifiedAt] > [lastSyncedAt]), local + cloud are merged so unsynchronized local
         *   changes are never lost.
         * - When local is clean and cloud is present, cloud wins for library items while history
         *   is always merged by timestamp.
         */
        fun resolveSyncData(
            local: CloudData,
            cloud: CloudData?,
            firstTime: Boolean,
            localDirty: Boolean,
            localModifiedAt: Long,
            lastSyncedAt: Long
        ): SyncResolution {
            val hasUnsyncedLocalChanges = localDirty || (localModifiedAt > 0L && localModifiedAt > lastSyncedAt)
            if (cloud == null) {
                return SyncResolution(
                    dataToApply = null,
                    shouldPushToCloud = true
                )
            }
            if (firstTime || hasUnsyncedLocalChanges) {
                val merged = CloudData(
                    liked = mergeTracks(local.liked, cloud.liked),
                    playlists = mergePlaylists(local.playlists, cloud.playlists),
                    history = mergeHistory(local.history, cloud.history).take(MAX_HISTORY),
                    saved = mergeSaved(local.saved.orEmpty(), cloud.saved.orEmpty()),
                    sessions = mergeSessions(local.sessions, cloud.sessions),
                    updatedAt = maxOf(localModifiedAt, cloud.updatedAt, System.currentTimeMillis())
                )
                return SyncResolution(
                    dataToApply = merged,
                    shouldPushToCloud = true
                )
            }
            val applied = CloudData(
                liked = cloud.liked,
                playlists = cloud.playlists,
                history = mergeHistory(local.history, cloud.history).take(MAX_HISTORY),
                saved = cloud.saved,
                sessions = mergeSessions(local.sessions, cloud.sessions),
                updatedAt = cloud.updatedAt
            )
            val cloudSessionsById = cloud.sessions.associateBy { it.sessionId }
            val localHasNewSessions = local.sessions.any { localSession ->
                val cloudSession = cloudSessionsById[localSession.sessionId]
                cloudSession == null || localSession.updatedAt > cloudSession.updatedAt
            }
            return SyncResolution(
                dataToApply = applied,
                shouldPushToCloud = localHasNewSessions
            )
        }
    }

    data class CloudData(
        val liked: List<Track>,
        val playlists: List<LocalPlaylist>,
        val history: List<Pair<Track, Long>>,
        val saved: List<SavedCollection>?,
        val sessions: List<ListeningSessionEntity> = emptyList(),
        val updatedAt: Long = 0L
    )

    data class SyncResolution(
        val dataToApply: CloudData?,
        val shouldPushToCloud: Boolean
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // Firestore must use the same named FirebaseApp as the currently active FirebaseAuth session.
    // This keeps each remembered account's cloud data scoped to its own authenticated identity.
    private val db: FirebaseFirestore
        get() = FirebaseFirestore.getInstance(auth.activeFirebaseApp())
    private val syncMutex = Mutex()
    private val startSyncMutex = Mutex()
    private val jobLock = Any()
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val _syncState = MutableStateFlow(SyncState.IDLE)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()
    private val _syncError = MutableStateFlow<String?>(null)
    val syncError: StateFlow<String?> = _syncError.asStateFlow()

    @Volatile private var activeUid: String? = null
    @Volatile private var pushJob: Job? = null
    @Volatile private var lastPull = 0L
    @Volatile private var applying = false
    @Volatile private var accountTransitionInProgress = false

    @Volatile private var wasOffline = false

    init {
        runCatching {
            connectivityManager?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (wasOffline) {
                        wasOffline = false
                        onConnectivityRestored()
                    }
                }

                override fun onLost(network: Network) {
                    wasOffline = true
                    val uid = activeUid ?: auth.user.value?.uid
                    if (uid != null && _syncState.value != SyncState.SYNCING && _syncState.value != SyncState.SAVED) {
                        _syncState.value = SyncState.OFFLINE
                        _syncError.value = null
                    }
                }
            })
        }
    }

    private fun isNetworkConnected(): Boolean {
        val cm = connectivityManager ?: return true
        val active = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(active) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun onConnectivityRestored() {
        val uid = auth.currentUid() ?: auth.user.value?.uid ?: activeUid ?: return
        activeUid = uid
        if (_syncState.value == SyncState.OFFLINE) {
            scope.launch {
                flushPendingSync(isBackgroundWorker = true)
            }
        }
    }

    // ---- Cloud document helpers ----

    private fun slimThumbs(thumbs: List<Thumbnail>): List<Map<String, Any?>> {
        if (thumbs.isEmpty()) return emptyList()
        val sorted = thumbs.sortedBy { it.width ?: 0 }
        val pick = if (sorted.size > 1) listOf(sorted.first(), sorted.last()) else sorted
        return pick.map { mapOf("url" to it.url, "width" to it.width, "height" to it.height) }
    }

    private fun Track.toMap(): Map<String, Any?> = mapOf(
        "videoId" to videoId, "title" to title, "artist" to artist,
        "artists" to artists.map { mapOf("name" to it.name, "id" to it.id) },
        "album" to album, "albumId" to albumId, "thumbnails" to slimThumbs(thumbnails),
        "duration" to duration, "durationSeconds" to durationSeconds,
        "explicit" to explicit, "type" to type
    )

    private fun LocalPlaylist.toMap(updatedAt: Long = System.currentTimeMillis()): Map<String, Any?> = mapOf(
        "id" to id, "name" to name, "createdAt" to createdAt, "updatedAt" to updatedAt,
        "tracks" to tracks.map { it.toMap() }
    )

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.toTrack(): Track? {
        val vid = get("videoId") as? String ?: return null
        return Track(
            videoId = vid,
            title = get("title") as? String ?: "",
            artist = get("artist") as? String ?: "",
            artists = (get("artists") as? List<Map<String, Any?>>)?.map { ArtistRef(it["name"] as? String ?: "", it["id"] as? String) } ?: emptyList(),
            album = get("album") as? String,
            albumId = get("albumId") as? String,
            thumbnails = (get("thumbnails") as? List<Map<String, Any?>>)?.map { Thumbnail(it["url"] as? String ?: "", (it["width"] as? Long)?.toInt(), (it["height"] as? Long)?.toInt()) } ?: emptyList(),
            duration = get("duration") as? String,
            durationSeconds = (get("durationSeconds") as? Long)?.toInt(),
            explicit = get("explicit") as? Boolean ?: false,
            type = get("type") as? String ?: "song"
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.toLocalPlaylist(): LocalPlaylist? {
        val id = get("id") as? String ?: return null
        val name = get("name") as? String ?: return null
        val createdAt = (get("createdAt") as? Long) ?: 0L
        val tracks = (get("tracks") as? List<Map<String, Any?>>)?.mapNotNull { it.toTrack() } ?: emptyList()
        return LocalPlaylist(id, name, createdAt, tracks)
    }

    private fun SavedCollection.toMap(): Map<String, Any?> = mapOf(
        "id" to id, "kind" to kind, "title" to title, "subtitle" to subtitle,
        "thumbnailUrl" to thumbnailUrl, "savedAt" to savedAt
    )

    private fun Map<String, Any?>.toSavedCollection(): SavedCollection? {
        val id = get("id") as? String ?: return null
        val kind = get("kind") as? String ?: return null
        val title = get("title") as? String ?: return null
        return SavedCollection(id, kind, title, get("subtitle") as? String, get("thumbnailUrl") as? String, (get("savedAt") as? Long) ?: 0L)
    }

    private suspend fun snapshotLocalData(): CloudData = CloudData(
        liked = library.likedOnce(),
        playlists = library.playlistsOnce(),
        history = history.allTimed(),
        saved = library.savedCollectionsOnce(),
        sessions = listeningStats.latest(5_000),
        updatedAt = System.currentTimeMillis()
    )

    private fun ListeningSessionEntity.toMap(): Map<String, Any?> = mapOf(
        "sessionId" to sessionId,
        "videoId" to videoId,
        "title" to title,
        "artist" to artist,
        "artists" to artists.map { mapOf("name" to it.name, "id" to it.id) },
        "listenedMs" to listenedMs,
        "durationMs" to durationMs,
        "startedAt" to startedAt,
        "updatedAt" to updatedAt,
        "completed" to completed,
        "skipped" to skipped
    )

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.toListeningSession(): ListeningSessionEntity? {
        val id = get("sessionId") as? String ?: return null
        val videoId = get("videoId") as? String ?: return null
        return ListeningSessionEntity(
            sessionId = id,
            videoId = videoId,
            title = get("title") as? String ?: "",
            artist = get("artist") as? String ?: "",
            artists = (get("artists") as? List<Map<String, Any?>>)?.map {
                ArtistRef(it["name"] as? String ?: "", it["id"] as? String)
            } ?: emptyList(),
            listenedMs = (get("listenedMs") as? Number)?.toLong() ?: 0L,
            durationMs = (get("durationMs") as? Number)?.toLong() ?: 0L,
            startedAt = (get("startedAt") as? Number)?.toLong() ?: 0L,
            updatedAt = (get("updatedAt") as? Number)?.toLong() ?: 0L,
            completed = get("completed") as? Boolean ?: false,
            skipped = get("skipped") as? Boolean ?: false
        )
    }

    private suspend fun markLocalDirty(now: Long = System.currentTimeMillis()) {
        dataStore.edit { prefs ->
            prefs[KEY_SYNC_DIRTY] = true
            prefs[KEY_LOCAL_MODIFIED_AT] = now
        }
    }

    private suspend fun markSyncClean(uid: String, syncedAt: Long) {
        _syncError.value = null
        dataStore.edit { prefs ->
            prefs[KEY_LAST_UID] = uid
            prefs[KEY_SYNC_DIRTY] = false
            prefs[KEY_LAST_SYNCED_AT] = syncedAt
        }
        SyncWorker.cancel(context)
    }

    private fun syncFailureMessage(error: Exception): String = when (error) {
        is FirebaseFirestoreException -> when (error.code) {
            FirebaseFirestoreException.Code.PERMISSION_DENIED ->
                "Firebase denied the sync request. Deploy the latest firestore.rules and verify the signed-in account."
            FirebaseFirestoreException.Code.UNAUTHENTICATED ->
                "Your Firebase session expired. Sign in again to resume cloud sync."
            FirebaseFirestoreException.Code.UNAVAILABLE,
            FirebaseFirestoreException.Code.DEADLINE_EXCEEDED ->
                "The sync service is temporarily unavailable. Check your connection and retry."
            else -> "Cloud sync failed (${error.code.name}). Tap to retry."
        }
        else -> "Cloud sync failed. Check your connection and Firebase configuration, then tap to retry."
    }

    // ---- Core operations ----

    suspend fun startSync(uid: String, force: Boolean = false) = startSyncMutex.withLock {
        if (accountTransitionInProgress) return@withLock
        if (!force && activeUid == uid && _syncState.value in setOf(
                SyncState.SYNCING, SyncState.SAVED, SyncState.OFFLINE, SyncState.ERROR
            )
        ) return@withLock
        stopSync()
        if (auth.currentUid() != uid) {
            _syncState.value = SyncState.IDLE
            return@withLock
        }
        activeUid = uid
        if (!isNetworkConnected()) {
            _syncState.value = SyncState.OFFLINE
            _syncError.value = null
            markLocalDirty()
            SyncWorker.enqueue(context)
            return@withLock
        }
        _syncError.value = null
        _syncState.value = SyncState.SYNCING
        syncMutex.withLock {
            try {
                val prefs = dataStore.data.first()
                val lastUid = prefs[KEY_LAST_UID]
                val firstTime = lastUid != uid
                val localDirty = prefs[KEY_SYNC_DIRTY] == true && !firstTime
                val localModifiedAt = if (firstTime) 0L else (prefs[KEY_LOCAL_MODIFIED_AT] ?: 0L)
                val lastSyncedAt = if (firstTime) 0L else (prefs[KEY_LAST_SYNCED_AT] ?: 0L)

                val cloud = pull(uid)
                activeUid = uid
                val local = snapshotLocalData()
                val resolution = resolveSyncData(
                    local = local,
                    cloud = cloud,
                    firstTime = firstTime,
                    localDirty = localDirty,
                    localModifiedAt = localModifiedAt,
                    lastSyncedAt = lastSyncedAt
                )
                resolution.dataToApply?.let { applyCloud(it) }
                dataStore.edit { it[KEY_LAST_UID] = uid }
                lastPull = System.currentTimeMillis()
                if (resolution.shouldPushToCloud) {
                    writeNowLocked(uid, cloudSessions = cloud?.sessions.orEmpty())
                } else {
                    markSyncClean(uid, maxOf(lastPull, cloud?.updatedAt ?: 0L))
                    _syncState.value = SyncState.SAVED
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "SyncRepository: startSync failed for uid: $uid")
                activeUid = uid
                if (!isNetworkConnected()) {
                    _syncState.value = SyncState.OFFLINE
                    _syncError.value = null
                } else {
                    _syncState.value = SyncState.ERROR
                    _syncError.value = syncFailureMessage(e)
                }
                markLocalDirty()
                SyncWorker.enqueue(context)
            }
        }
    }

    fun stopSync() {
        synchronized(jobLock) {
            pushJob?.cancel()
            pushJob = null
        }
        activeUid = null
        _syncState.value = SyncState.IDLE
    }

    fun schedulePush() {
        if (applying || accountTransitionInProgress) return
        val uid = auth.currentUid() ?: auth.user.value?.uid ?: activeUid ?: return
        activeUid = uid
        synchronized(jobLock) {
            pushJob?.cancel()
            pushJob = scope.launch {
                markLocalDirty()
                // If sync is already paused in ERROR or OFFLINE, keep changes locally and rely on background WorkManager.
                // Do not flap between SYNCING and ERROR while user is playing music or browsing.
                if (_syncState.value == SyncState.ERROR || _syncState.value == SyncState.OFFLINE) {
                    SyncWorker.enqueue(context)
                    return@launch
                }
                delay(PUSH_DELAY_MS)
                syncMutex.withLock {
                    writeNowLocked(uid)
                }
            }
        }
    }

    /**
     * Immediately persists the latest local library state using the application sync scope.
     * Used for user actions such as Like where the app may be minimized or closed immediately after the tap.
     */
    fun pushNow() {
        if (applying || accountTransitionInProgress) return
        val uid = auth.currentUid() ?: auth.user.value?.uid ?: activeUid ?: return
        activeUid = uid
        synchronized(jobLock) {
            pushJob?.cancel()
            pushJob = scope.launch {
                markLocalDirty()
                if (_syncState.value == SyncState.ERROR || _syncState.value == SyncState.OFFLINE) {
                    SyncWorker.enqueue(context)
                    return@launch
                }
                try {
                    syncMutex.withLock {
                        writeNowLocked(uid)
                    }
                } finally {
                    synchronized(jobLock) {
                        if (pushJob?.isActive == false) pushJob = null
                    }
                }
            }
        }
    }

    /** Called by [SyncWorker] or connectivity callback when network connectivity returns. */
    suspend fun flushPendingSync(isBackgroundWorker: Boolean = false): Boolean = syncMutex.withLock {
        if (accountTransitionInProgress) return@withLock false
        // Only attempt cloud sync when an authenticated user session is active
        val uid = auth.currentUid() ?: auth.user.value?.uid ?: return@withLock true
        activeUid = uid
        val prefs = dataStore.data.first()
        val isDirty = prefs[KEY_SYNC_DIRTY] == true
        val localModifiedAt = prefs[KEY_LOCAL_MODIFIED_AT] ?: 0L
        val lastSyncedAt = prefs[KEY_LAST_SYNCED_AT] ?: 0L

        if (!isNetworkConnected()) {
            _syncError.value = null
            if (!isBackgroundWorker) {
                _syncState.value = SyncState.OFFLINE
            }
            return@withLock false
        }

        if (!isDirty && localModifiedAt <= lastSyncedAt) {
            if (_syncState.value == SyncState.ERROR || _syncState.value == SyncState.OFFLINE) {
                // A failed session upload is also represented by the dirty flag; this branch
                // is only reached after the library and all pending session writes are clean.
                _syncState.value = SyncState.SAVED
            }
            return@withLock true
        }

        return@withLock try {
            val cloud = pull(uid)
            val local = snapshotLocalData()
            val resolution = resolveSyncData(
                local = local,
                cloud = cloud,
                firstTime = false,
                localDirty = true,
                localModifiedAt = localModifiedAt,
                lastSyncedAt = lastSyncedAt
            )
            resolution.dataToApply?.let { applyCloud(it) }
            val writeSuccess = writeNowLocked(
                uid,
                showSyncingState = !isBackgroundWorker && _syncState.value != SyncState.ERROR,
                cloudSessions = cloud?.sessions.orEmpty()
            )
            if (writeSuccess) {
                _syncState.value = SyncState.SAVED
            }
            writeSuccess
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "SyncRepository: flushPendingSync failed for uid: $uid")
            if (!isNetworkConnected()) {
                _syncState.value = SyncState.OFFLINE
                _syncError.value = null
            } else {
                _syncState.value = SyncState.ERROR
                _syncError.value = syncFailureMessage(e)
            }
            false
        }
    }

    /** Call on app foreground. */
    fun onForeground() {
        val uid = auth.currentUid() ?: auth.user.value?.uid ?: return
        activeUid = uid
        if (!isNetworkConnected()) {
            _syncError.value = null
            if (_syncState.value != SyncState.SAVED) {
                _syncState.value = SyncState.OFFLINE
            }
            return
        }
        // If sync has paused due to an error, do not flap on every foreground event; wait for manual retry or background sync
        if (_syncState.value == SyncState.ERROR) return
        if (pushJob?.isActive == true) return
        if (System.currentTimeMillis() - lastPull < STALE_PULL_MS) return
        scope.launch {
            syncMutex.withLock {
                try {
                    val prefs = dataStore.data.first()
                    val isDirty = prefs[KEY_SYNC_DIRTY] == true
                    val localModifiedAt = prefs[KEY_LOCAL_MODIFIED_AT] ?: 0L
                    val lastSyncedAt = prefs[KEY_LAST_SYNCED_AT] ?: 0L
                    val cloud = pull(uid)
                    if (isDirty || localModifiedAt > lastSyncedAt) {
                        val local = snapshotLocalData()
                        val resolution = resolveSyncData(
                            local = local,
                            cloud = cloud,
                            firstTime = false,
                            localDirty = true,
                            localModifiedAt = localModifiedAt,
                            lastSyncedAt = lastSyncedAt
                        )
                        resolution.dataToApply?.let { applyCloud(it) }
                        writeNowLocked(uid, cloudSessions = cloud?.sessions.orEmpty())
                    } else if (cloud != null) {
                        val local = snapshotLocalData()
                        val resolution = resolveSyncData(
                            local = local,
                            cloud = cloud,
                            firstTime = false,
                            localDirty = false,
                            localModifiedAt = localModifiedAt,
                            lastSyncedAt = lastSyncedAt
                        )
                        resolution.dataToApply?.let { applyCloud(it) }
                        if (resolution.shouldPushToCloud) {
                            writeNowLocked(uid, cloudSessions = cloud.sessions)
                        } else {
                            markSyncClean(uid, maxOf(System.currentTimeMillis(), cloud.updatedAt))
                            _syncState.value = SyncState.SAVED
                        }
                    } else {
                        // A missing cloud document is not proof that sync completed. Publish the
                        // current local snapshot so newly-created accounts and recovered cloud
                        // documents are backed up rather than reported as already saved.
                        writeNowLocked(uid, cloudSessions = emptyList())
                    }
                    lastPull = System.currentTimeMillis()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w(e, "SyncRepository: onForeground sync failed for uid: $uid")
                    _syncState.value = if (isNetworkConnected()) SyncState.ERROR else SyncState.OFFLINE
                    _syncError.value = if (isNetworkConnected()) syncFailureMessage(e) else null
                }
            }
        }
    }

    /** Stops syncing and removes this account's backed-up library from the cloud. */
    suspend fun deleteCloudData(uid: String) {
        accountTransitionInProgress = true
        try {
            PlaybackService.stopForAccountTransition()
            stopSync()
            SyncWorker.cancel(context)
            syncMutex.withLock {
                val userDoc = db.collection("users").document(uid)
                val playlistDocs = userDoc.collection("playlists").get().await()
                for (chunk in playlistDocs.documents.chunked(FIRESTORE_BATCH_LIMIT)) {
                    val batch = db.batch()
                    chunk.forEach { batch.delete(it.reference) }
                    batch.commit().await()
                }
                val likedDocs = userDoc.collection("liked_chunks").get().await()
                for (chunk in likedDocs.documents.chunked(FIRESTORE_BATCH_LIMIT)) {
                    val batch = db.batch()
                    chunk.forEach { batch.delete(it.reference) }
                    batch.commit().await()
                }
                val sessionDocs = userDoc.collection("listening_sessions").get().await()
                for (chunk in sessionDocs.documents.chunked(FIRESTORE_BATCH_LIMIT)) {
                    val batch = db.batch()
                    chunk.forEach { batch.delete(it.reference) }
                    batch.commit().await()
                }
                userDoc.delete().await()
            }
        } catch (error: Exception) {
            accountTransitionInProgress = false
            PlaybackService.abortAccountTransition()
            if (auth.currentUid() == uid) runCatching { startSync(uid, force = true) }
            throw error
        }
    }

    /** Resumes the current account after a failed destructive operation or account deletion. */
    suspend fun resumeSync(uid: String) {
        accountTransitionInProgress = false
        PlaybackService.abortAccountTransition()
        startSync(uid, force = true)
    }

    /**
     * Backs up and clears the active account's local data while retaining its FirebaseAuth session.
     * The caller can then activate another already-authenticated FirebaseApp without a password or
     * Google account picker. The previous account remains saved for a later quick switch.
     */
    suspend fun prepareForAccountSwitch() {
        backupAndClearLocalState(signOutFirebaseSession = false)
    }

    /** Releases the account-handoff gate after AuthRepository activates the new FirebaseApp. */
    suspend fun completeAccountSwitch(uid: String) {
        // The old queue is retained until the destination Firebase session has been activated.
        // Clear it now, before allowing any observer to see the new account's local state.
        queueManager.clearForAccountTransition()
        PlaybackService.completeAccountTransition()
        accountTransitionInProgress = false
        startSync(uid, force = true)
    }

    /** Rehydrates the previous account if a prepared handoff could not activate its destination. */
    suspend fun abortAccountSwitch(previousUid: String?) {
        // If the destination auth session was activated but its local handoff failed, roll back
        // to the original FirebaseApp before restarting sync. Both sessions remain available.
        if (!previousUid.isNullOrBlank() && auth.currentUid() != previousUid) {
            auth.activateCachedAccount(previousUid)
        }
        accountTransitionInProgress = false
        PlaybackService.abortAccountTransition()
        if (!previousUid.isNullOrBlank() && auth.currentUid() == previousUid) {
            startSync(previousUid, force = true)
        }
    }

    /**
     * Refuses to attach an unidentified local library to a different saved account. This is used
     * when the app has no active Firebase identity (for example, after a process restart during a
     * previously interrupted handoff). A mismatch must be resolved by restoring the owning account,
     * not by merging its private library into whichever account is selected next.
     */
    suspend fun ensureLocalAccountOwnership(uid: String) {
        require(uid.isNotBlank()) { "A valid account is required." }
        val prefs = dataStore.data.first()
        val persistedOwner = prefs[KEY_LAST_UID]
        val runtimeOwner = activeUid
        val owner = persistedOwner ?: runtimeOwner
        if (!owner.isNullOrBlank() && owner != uid) {
            throw IllegalStateException(
                "This device still has local data belonging to another account. Sign in to that account first so Geekify can finish its backup safely."
            )
        }
    }

    /**
     * Clears only detached playback state when there is no authenticated account to roll back to.
     * The library/history are deliberately retained; ownership checks prevent them from leaking to
     * another account, while clearing the queue ensures old audio cannot resume after sign-in.
     */
    suspend fun discardDetachedPlayback() {
        accountTransitionInProgress = true
        try {
            synchronized(jobLock) {
                pushJob?.cancel()
                pushJob = null
            }
            stopSync()
            SyncWorker.cancel(context)
            queueManager.clearForAccountTransition()
            PlaybackService.completeAccountTransition()
            _syncState.value = SyncState.IDLE
            _syncError.value = null
        } finally {
            accountTransitionInProgress = false
        }
    }

    /** Backs up, clears account-scoped local state, and fully signs out the active Firebase session. */
    suspend fun signOutAndClear() {
        backupAndClearLocalState(signOutFirebaseSession = true)
    }

    private suspend fun backupAndClearLocalState(signOutFirebaseSession: Boolean) {
        val uid = auth.currentUid() ?: auth.user.value?.uid ?: activeUid

        // Finish the live playback segment first. Otherwise ExoPlayer can continue producing
        // listening sessions for the previous account after another account becomes active.
        PlaybackService.stopForAccountTransition()
        accountTransitionInProgress = true

        try {
        synchronized(jobLock) {
            pushJob?.cancel()
            pushJob = null
        }
        stopSync()
        SyncWorker.cancel(context)

        if (uid != null) {
            val backupSucceeded = syncMutex.withLock {
                // Reconcile first so signing out from a device with an older local snapshot cannot
                // delete playlists or liked tracks that were added from another device.
                val prefs = dataStore.data.first()
                val cloud = pull(uid)
                val local = snapshotLocalData()
                val lastUid = prefs[KEY_LAST_UID]
                val firstTime = lastUid != uid
                val localDirty = prefs[KEY_SYNC_DIRTY] == true && !firstTime
                val localModifiedAt = if (firstTime) 0L else (prefs[KEY_LOCAL_MODIFIED_AT] ?: 0L)
                val lastSyncedAt = if (firstTime) 0L else (prefs[KEY_LAST_SYNCED_AT] ?: 0L)
                val resolution = resolveSyncData(
                    local = local,
                    cloud = cloud,
                    firstTime = firstTime,
                    localDirty = localDirty,
                    localModifiedAt = localModifiedAt,
                    lastSyncedAt = lastSyncedAt
                )
                resolution.dataToApply?.let { applyCloud(it) }
                if (resolution.shouldPushToCloud) {
                    writeNowLocked(uid, showSyncingState = true, cloudSessions = cloud?.sessions.orEmpty())
                } else {
                    markSyncClean(uid, maxOf(System.currentTimeMillis(), cloud?.updatedAt ?: 0L))
                    _syncState.value = SyncState.SAVED
                    true
                }
            }
            if (!backupSucceeded) {
                throw IllegalStateException(
                    "Geekify couldn't back up your latest library. You're still signed in and your data is safe on this device. Check your connection and retry."
                )
            }
        }

        // For a full sign-out, discard the old queue and stop the service now. For an account
        // switch, keep the queue snapshot and stopped service recoverable until the destination
        // Firebase session is activated by AccountViewModel.
        if (signOutFirebaseSession) {
            queueManager.clearForAccountTransition()
            PlaybackService.completeAccountTransition()
        }
        clearLocalAndSignOut(signOutFirebaseSession)
        if (signOutFirebaseSession) accountTransitionInProgress = false
        } catch (error: Exception) {
            accountTransitionInProgress = false
            PlaybackService.abortAccountTransition()
            if (!uid.isNullOrBlank() && auth.currentUid() == uid) {
                runCatching { startSync(uid, force = true) }
            }
            throw error
        }
    }

    /** Used after the account and its cloud data have already been successfully deleted. */
    suspend fun clearAfterAccountDeletion() {
        accountTransitionInProgress = true
        queueManager.clearForAccountTransition()
        PlaybackService.completeAccountTransition()
        clearLocalAndSignOut(signOutFirebaseSession = true)
        accountTransitionInProgress = false
    }

    private suspend fun clearLocalAndSignOut(signOutFirebaseSession: Boolean) {
        stopSync()
        SyncWorker.cancel(context)
        if (signOutFirebaseSession) auth.signOut()
        _syncError.value = null
        _syncState.value = SyncState.IDLE
        activeUid = null
        lastPull = 0L
        applying = false
        applyCloud(CloudData(emptyList(), emptyList(), emptyList(), emptyList()), wipeHistory = true)
        history.clearStats()
        listeningStats.clear()
        dataStore.edit {
            it.remove(KEY_LAST_UID)
            it.remove(KEY_SYNC_DIRTY)
            it.remove(KEY_LOCAL_MODIFIED_AT)
            it.remove(KEY_LAST_SYNCED_AT)
        }
    }

    /** Manually triggers synchronization for the currently active user session. */
    fun retrySync() {
        val uid = auth.currentUid() ?: auth.user.value?.uid ?: activeUid ?: return
        if (_syncState.value == SyncState.SYNCING) return
        scope.launch {
            startSync(uid, force = true)
        }
    }

    /** Persists a finished listening session without rewriting the entire library. */
    suspend fun pushSession(session: ListeningSessionEntity) = syncMutex.withLock {
        if (accountTransitionInProgress) return@withLock
        val uid = auth.currentUid() ?: auth.user.value?.uid ?: activeUid ?: return@withLock
        activeUid = uid
        if (!isNetworkConnected()) {
            markLocalDirty()
            _syncState.value = SyncState.OFFLINE
            _syncError.value = null
            SyncWorker.enqueue(context)
            return@withLock
        }
        try {
            db.collection("users").document(uid)
                .collection("listening_sessions").document(session.sessionId)
                .set(session.toMap())
                .await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "SyncRepository: failed to upload listening session ${session.sessionId}")
            markLocalDirty()
            _syncState.value = if (isNetworkConnected()) SyncState.ERROR else SyncState.OFFLINE
            _syncError.value = if (isNetworkConnected()) syncFailureMessage(e) else null
            SyncWorker.enqueue(context)
        }
    }

    private suspend fun writeNowLocked(
        uid: String,
        showSyncingState: Boolean = true,
        cloudSessions: List<ListeningSessionEntity>? = null
    ): Boolean {
        if (!isNetworkConnected()) {
            markLocalDirty()
            _syncState.value = SyncState.OFFLINE
            _syncError.value = null
            SyncWorker.enqueue(context)
            return false
        }
        if (showSyncingState) {
            _syncState.value = SyncState.SYNCING
        }
        return try {
            val now = System.currentTimeMillis()
            val likedTracks = library.likedOnce()
            val playlists = library.playlistsOnce()
            val hist = history.allTimed().take(MAX_HISTORY).map { (track, at) -> track.toMap() + ("playedAt" to at) }
            val saved = library.savedCollectionsOnce().map { it.toMap() }

            val userRef = db.collection("users").document(uid)

            // 1. Sync per-playlist documents in users/{uid}/playlists/{playlistId}
            val playlistsCol = userRef.collection("playlists")
            val existingPlaylistDocs = playlistsCol.get().await().documents
            val currentPlaylistIds = playlists.map { it.id }.toSet()
            val playlistOps = mutableListOf<(com.google.firebase.firestore.WriteBatch) -> Unit>()
            for (pl in playlists) {
                val docRef = playlistsCol.document(pl.id)
                val payload = pl.toMap(updatedAt = now)
                playlistOps += { batch -> batch.set(docRef, payload) }
            }
            for (staleDoc in existingPlaylistDocs) {
                if (staleDoc.id !in currentPlaylistIds) {
                    playlistOps += { batch -> batch.delete(staleDoc.reference) }
                }
            }
            for (chunk in playlistOps.chunked(FIRESTORE_BATCH_LIMIT)) {
                val batch = db.batch()
                chunk.forEach { it(batch) }
                batch.commit().await()
            }

            // 2. Sync liked tracks in chunked documents users/{uid}/liked_chunks/chunk_{i}
            val likedCol = userRef.collection("liked_chunks")
            val existingLikedDocs = likedCol.get().await().documents
            val likedChunks = likedTracks.chunked(LIKED_CHUNK_SIZE)
            val expectedChunkIds = likedChunks.indices.map { "chunk_$it" }.toSet()
            val likedOps = mutableListOf<(com.google.firebase.firestore.WriteBatch) -> Unit>()
            likedChunks.forEachIndexed { index, chunkTracks ->
                val docRef = likedCol.document("chunk_$index")
                val payload = mapOf(
                    "index" to index,
                    "updatedAt" to now,
                    "tracks" to chunkTracks.map { it.toMap() }
                )
                likedOps += { batch -> batch.set(docRef, payload) }
            }
            for (staleDoc in existingLikedDocs) {
                if (staleDoc.id !in expectedChunkIds) {
                    likedOps += { batch -> batch.delete(staleDoc.reference) }
                }
            }
            for (chunk in likedOps.chunked(FIRESTORE_BATCH_LIMIT)) {
                val batch = db.batch()
                chunk.forEach { it(batch) }
                batch.commit().await()
            }

            // During reconciliation, upload only local sessions that are absent from the cloud
            // or have a newer checkpoint. Routine library pushes avoid rewriting session history.
            if (cloudSessions != null) {
                val localSessions = listeningStats.latest(5_000)
                val cloudById = cloudSessions.associateBy { it.sessionId }
                val changedSessions = localSessions.filter { localSession ->
                    val remote = cloudById[localSession.sessionId]
                    remote == null || localSession.updatedAt > remote.updatedAt
                }
                for (chunk in changedSessions.chunked(FIRESTORE_BATCH_LIMIT)) {
                    val batch = db.batch()
                    chunk.forEach { session ->
                        batch.set(
                            userRef.collection("listening_sessions").document(session.sessionId),
                            session.toMap()
                        )
                    }
                    batch.commit().await()
                }
            }

            // 3. Write root user document (keeps inline liked/playlists when under safe size for legacy compatibility)
            val inlineCompatible = likedTracks.size <= LEGACY_INLINE_TRACK_LIMIT &&
                playlists.sumOf { it.tracks.size } <= LEGACY_INLINE_TRACK_LIMIT
            val rootPayload = buildMap<String, Any?> {
                put("history", hist)
                put("savedCollections", saved)
                put("updatedAt", now)
                put("schemaVersion", 2)
                put("likedCount", likedTracks.size)
                put("playlistCount", playlists.size)
                if (inlineCompatible) {
                    put("liked", likedTracks.map { it.toMap() })
                    put("playlists", playlists.map { it.toMap(updatedAt = now) })
                } else {
                    put("liked", emptyList<Map<String, Any?>>())
                    put("playlists", emptyList<Map<String, Any?>>())
                }
            }
            userRef.set(rootPayload, SetOptions.merge()).await()
            markSyncClean(uid, now)
            _syncState.value = SyncState.SAVED
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "SyncRepository: writeNowLocked failed for uid: $uid")
            markLocalDirty()
            if (!isNetworkConnected()) {
                _syncState.value = SyncState.OFFLINE
                _syncError.value = null
            } else {
                _syncState.value = SyncState.ERROR
                _syncError.value = syncFailureMessage(e)
            }
            if (showSyncingState) {
                SyncWorker.enqueue(context)
            }
            false
        }
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun pull(uid: String): CloudData? {
        val userRef = db.collection("users").document(uid)
        val snap = userRef.get().await()
        // A failed subcollection read must fail the sync. Treating permission/network errors as
        // empty data can otherwise make the device overwrite a valid cloud library with empties.
        val subPlaylistsSnap = userRef.collection("playlists").get().await()
        val subLikedSnap = userRef.collection("liked_chunks").get().await()
        val subSessionsSnap = userRef.collection("listening_sessions")
            .orderBy("startedAt", Query.Direction.DESCENDING)
            .limit(5_000)
            .get()
            .await()

        if (!snap.exists() && subPlaylistsSnap.isEmpty && subLikedSnap.isEmpty && subSessionsSnap.isEmpty) {
            return null
        }

        val data = snap.data.orEmpty()
        val updatedAt = (data["updatedAt"] as? Long) ?: 0L

        val legacyLiked = (data["liked"] as? List<Map<String, Any?>>)?.mapNotNull { it.toTrack() }.orEmpty()
        val chunkedLiked = subLikedSnap.documents
            .sortedBy { (it.getLong("index") ?: 0L).toInt() }
            .flatMap { doc ->
                val rawList = doc.get("tracks") as? List<Map<String, Any?>>
                rawList?.mapNotNull { it.toTrack() }.orEmpty()
            }
        val liked = if (chunkedLiked.isNotEmpty()) mergeTracks(chunkedLiked, legacyLiked) else legacyLiked

        val legacyPlaylists = (data["playlists"] as? List<Map<String, Any?>>)?.mapNotNull { it.toLocalPlaylist() }.orEmpty()
        val subPlaylists = subPlaylistsSnap.documents
            .mapNotNull { doc -> doc.data?.toLocalPlaylist() }
            .sortedByDescending { it.createdAt }
        val pls = if (subPlaylists.isNotEmpty()) mergePlaylists(subPlaylists, legacyPlaylists) else legacyPlaylists

        val hist = (data["history"] as? List<Map<String, Any?>>)?.mapNotNull { row ->
            row.toTrack()?.let { it to ((row["playedAt"] as? Long) ?: 0L) }
        } ?: emptyList()

        // null = this account's cloud copy predates the feature: keep whatever is saved on the phone.
        val saved = (data["savedCollections"] as? List<Map<String, Any?>>)?.mapNotNull { it.toSavedCollection() }
        val sessions = subSessionsSnap.documents.mapNotNull { it.data?.toListeningSession() }
        return CloudData(liked, pls, hist, saved, sessions, updatedAt)
    }

    /**
     * Applies reconciled cloud data atomically via Room transactions while [applying] is true so
     * local observers do not trigger a redundant push loop.
     */
    private suspend fun applyCloud(cloud: CloudData, wipeHistory: Boolean = false) {
        applying = true
        try {
            library.replaceAllLiked(cloud.liked)
            library.replaceAllPlaylists(cloud.playlists)
            val mergedHistory = if (wipeHistory) cloud.history else mergeHistory(history.allTimed(), cloud.history)
            history.replaceAllTimed(mergedHistory.take(MAX_HISTORY))
            cloud.saved?.let { library.replaceAllSavedCollections(it) }
            listeningStats.mergeSessions(cloud.sessions)
        } finally {
            applying = false
        }
    }
}
