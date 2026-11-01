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
import com.geekify.android.data.local.LocalPlaylist
import com.geekify.android.data.local.SavedCollection
import com.geekify.android.data.model.ArtistRef
import com.geekify.android.data.model.Thumbnail
import com.geekify.android.data.model.Track
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
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
                updatedAt = cloud.updatedAt
            )
            return SyncResolution(
                dataToApply = applied,
                shouldPushToCloud = false
            )
        }
    }

    data class CloudData(
        val liked: List<Track>,
        val playlists: List<LocalPlaylist>,
        val history: List<Pair<Track, Long>>,
        val saved: List<SavedCollection>?,
        val updatedAt: Long = 0L
    )

    data class SyncResolution(
        val dataToApply: CloudData?,
        val shouldPushToCloud: Boolean
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val db by lazy { Firebase.firestore }
    private val syncMutex = Mutex()
    private val jobLock = Any()
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val _syncState = MutableStateFlow(SyncState.IDLE)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    @Volatile private var activeUid: String? = null
    @Volatile private var pushJob: Job? = null
    @Volatile private var lastPull = 0L
    @Volatile private var applying = false

    init {
        runCatching {
            connectivityManager?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    onConnectivityRestored()
                }

                override fun onLost(network: Network) {
                    val uid = activeUid ?: auth.user.value?.uid
                    if (uid != null && _syncState.value != SyncState.SYNCING && _syncState.value != SyncState.SAVED) {
                        _syncState.value = SyncState.OFFLINE
                    }
                }
            })
        }
    }

    private fun isNetworkConnected(): Boolean {
        val cm = connectivityManager ?: return true
        val active = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(active) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun onConnectivityRestored() {
        val uid = activeUid ?: auth.user.value?.uid ?: return
        if (_syncState.value == SyncState.OFFLINE || _syncState.value == SyncState.ERROR) {
            scope.launch {
                flushPendingSync()
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
        updatedAt = System.currentTimeMillis()
    )

    private suspend fun markLocalDirty(now: Long = System.currentTimeMillis()) {
        dataStore.edit { prefs ->
            prefs[KEY_SYNC_DIRTY] = true
            prefs[KEY_LOCAL_MODIFIED_AT] = now
        }
    }

    private suspend fun markSyncClean(uid: String, syncedAt: Long) {
        dataStore.edit { prefs ->
            prefs[KEY_LAST_UID] = uid
            prefs[KEY_SYNC_DIRTY] = false
            prefs[KEY_LAST_SYNCED_AT] = syncedAt
        }
        SyncWorker.cancel(context)
    }

    // ---- Core operations ----

    suspend fun startSync(uid: String) {
        stopSync()
        activeUid = uid
        if (!isNetworkConnected()) {
            _syncState.value = SyncState.OFFLINE
            markLocalDirty()
            SyncWorker.enqueue(context)
            return
        }
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
                    writeNowLocked(uid)
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
                } else {
                    _syncState.value = SyncState.ERROR
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
        if (applying) return
        val uid = activeUid ?: auth.user.value?.uid ?: return
        activeUid = uid
        synchronized(jobLock) {
            pushJob?.cancel()
            pushJob = scope.launch {
                markLocalDirty()
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
        if (applying) return
        val uid = activeUid ?: auth.user.value?.uid ?: return
        activeUid = uid
        synchronized(jobLock) {
            pushJob?.cancel()
            pushJob = scope.launch {
                markLocalDirty()
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
    suspend fun flushPendingSync(): Boolean = syncMutex.withLock {
        // Only attempt cloud sync when an authenticated user session is active
        val uid = activeUid ?: auth.user.value?.uid ?: return@withLock true
        activeUid = uid
        val prefs = dataStore.data.first()
        val isDirty = prefs[KEY_SYNC_DIRTY] == true
        val localModifiedAt = prefs[KEY_LOCAL_MODIFIED_AT] ?: 0L
        val lastSyncedAt = prefs[KEY_LAST_SYNCED_AT] ?: 0L

        if (!isNetworkConnected()) {
            _syncState.value = SyncState.OFFLINE
            return@withLock false
        }

        if (!isDirty && localModifiedAt <= lastSyncedAt) {
            if (_syncState.value == SyncState.ERROR || _syncState.value == SyncState.OFFLINE) {
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
            val writeSuccess = writeNowLocked(uid)
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
            } else {
                _syncState.value = SyncState.ERROR
            }
            false
        }
    }

    /** Call on app foreground. */
    fun onForeground() {
        val uid = activeUid ?: auth.user.value?.uid ?: return
        activeUid = uid
        if (!isNetworkConnected()) {
            if (_syncState.value != SyncState.SAVED) {
                _syncState.value = SyncState.OFFLINE
            }
            return
        }
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
                        writeNowLocked(uid)
                    } else if (cloud != null) {
                        applyCloud(cloud)
                        markSyncClean(uid, maxOf(System.currentTimeMillis(), cloud.updatedAt))
                        _syncState.value = SyncState.SAVED
                    } else {
                        _syncState.value = SyncState.SAVED
                    }
                    lastPull = System.currentTimeMillis()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w(e, "SyncRepository: onForeground sync failed for uid: $uid")
                    if (!isNetworkConnected()) {
                        _syncState.value = SyncState.OFFLINE
                    }
                }
            }
        }
    }

    /** Stops syncing and removes this account's backed-up library from the cloud. */
    suspend fun deleteCloudData(uid: String) {
        stopSync()
        SyncWorker.cancel(context)
        syncMutex.withLock {
            val userDoc = db.collection("users").document(uid)
            runCatching {
                val playlistDocs = userDoc.collection("playlists").get().await()
                for (chunk in playlistDocs.documents.chunked(FIRESTORE_BATCH_LIMIT)) {
                    val batch = db.batch()
                    chunk.forEach { batch.delete(it.reference) }
                    batch.commit().await()
                }
            }
            runCatching {
                val likedDocs = userDoc.collection("liked_chunks").get().await()
                for (chunk in likedDocs.documents.chunked(FIRESTORE_BATCH_LIMIT)) {
                    val batch = db.batch()
                    chunk.forEach { batch.delete(it.reference) }
                    batch.commit().await()
                }
            }
            userDoc.delete().await()
        }
    }

    /** Resumes syncing for [uid], e.g. when an account deletion failed part-way. */
    suspend fun resumeSync(uid: String) = startSync(uid)

    suspend fun signOutAndClear() {
        val uid = activeUid
        synchronized(jobLock) {
            pushJob?.cancel()
            pushJob = null
        }
        if (uid != null) {
            syncMutex.withLock {
                runCatching { writeNowLocked(uid) }
            }
        }
        stopSync()
        SyncWorker.cancel(context)
        auth.signOut()
        applyCloud(CloudData(emptyList(), emptyList(), emptyList(), emptyList()), wipeHistory = true)
        history.clearStats()
        dataStore.edit {
            it.remove(KEY_LAST_UID)
            it.remove(KEY_SYNC_DIRTY)
            it.remove(KEY_LOCAL_MODIFIED_AT)
            it.remove(KEY_LAST_SYNCED_AT)
        }
    }

    /** Manually triggers synchronization for the currently active user session. */
    fun retrySync() {
        val uid = activeUid ?: auth.user.value?.uid ?: return
        scope.launch {
            startSync(uid)
        }
    }

    private suspend fun writeNowLocked(uid: String): Boolean {
        if (!isNetworkConnected()) {
            markLocalDirty()
            _syncState.value = SyncState.OFFLINE
            SyncWorker.enqueue(context)
            return false
        }
        _syncState.value = SyncState.SYNCING
        return try {
            val now = System.currentTimeMillis()
            val likedTracks = library.likedOnce()
            val playlists = library.playlistsOnce()
            val hist = history.allTimed().take(MAX_HISTORY).map { (track, at) -> track.toMap() + ("playedAt" to at) }
            val saved = library.savedCollectionsOnce().map { it.toMap() }

            val userRef = db.collection("users").document(uid)

            // 1. Sync per-playlist documents in users/{uid}/playlists/{playlistId}
            val playlistsCol = userRef.collection("playlists")
            val existingPlaylistDocs = runCatching { playlistsCol.get().await().documents }.getOrDefault(emptyList())
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
            val existingLikedDocs = runCatching { likedCol.get().await().documents }.getOrDefault(emptyList())
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
            } else {
                _syncState.value = SyncState.ERROR
            }
            SyncWorker.enqueue(context)
            false
        }
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun pull(uid: String): CloudData? {
        val userRef = db.collection("users").document(uid)
        val snap = userRef.get().await()
        val subPlaylistsSnap = runCatching { userRef.collection("playlists").get().await() }.getOrNull()
        val subLikedSnap = runCatching { userRef.collection("liked_chunks").get().await() }.getOrNull()

        if (!snap.exists() && (subPlaylistsSnap == null || subPlaylistsSnap.isEmpty) && (subLikedSnap == null || subLikedSnap.isEmpty)) {
            return null
        }

        val data = snap.data.orEmpty()
        val updatedAt = (data["updatedAt"] as? Long) ?: 0L

        val legacyLiked = (data["liked"] as? List<Map<String, Any?>>)?.mapNotNull { it.toTrack() }.orEmpty()
        val chunkedLiked = subLikedSnap?.documents
            ?.sortedBy { (it.getLong("index") ?: 0L).toInt() }
            ?.flatMap { doc ->
                val rawList = doc.get("tracks") as? List<Map<String, Any?>>
                rawList?.mapNotNull { it.toTrack() }.orEmpty()
            }
            .orEmpty()
        val liked = if (chunkedLiked.isNotEmpty()) mergeTracks(chunkedLiked, legacyLiked) else legacyLiked

        val legacyPlaylists = (data["playlists"] as? List<Map<String, Any?>>)?.mapNotNull { it.toLocalPlaylist() }.orEmpty()
        val subPlaylists = subPlaylistsSnap?.documents
            ?.mapNotNull { doc -> doc.data?.toLocalPlaylist() }
            ?.sortedByDescending { it.createdAt }
            .orEmpty()
        val pls = if (subPlaylists.isNotEmpty()) mergePlaylists(subPlaylists, legacyPlaylists) else legacyPlaylists

        val hist = (data["history"] as? List<Map<String, Any?>>)?.mapNotNull { row ->
            row.toTrack()?.let { it to ((row["playedAt"] as? Long) ?: 0L) }
        } ?: emptyList()

        // null = this account's cloud copy predates the feature: keep whatever is saved on the phone.
        val saved = (data["savedCollections"] as? List<Map<String, Any?>>)?.mapNotNull { it.toSavedCollection() }
        return CloudData(liked, pls, hist, saved, updatedAt)
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
        } finally {
            applying = false
        }
    }
}
