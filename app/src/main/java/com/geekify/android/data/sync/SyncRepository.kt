package com.geekify.android.data.sync

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.geekify.android.data.auth.AuthRepository
import com.geekify.android.data.auth.SyncState
import com.geekify.android.data.local.HistoryRepository
import com.geekify.android.data.local.LibraryRepository
import com.geekify.android.data.local.LocalPlaylist
import com.geekify.android.data.model.ArtistRef
import com.geekify.android.data.model.Thumbnail
import com.geekify.android.data.model.Track
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
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
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ports lib/cloudSync.ts exactly.
 *
 * - On sign-in: if last synced uid matches → cloud wins; otherwise merge local + cloud.
 * - While signed in: push after 2 s debounce; pull on foreground if >60 s stale and no push pending.
 * - On sign-out: flush pending, sign out, wipe local data.
 */
@Singleton
class SyncRepository @Inject constructor(
    private val auth: AuthRepository,
    private val library: LibraryRepository,
    private val history: HistoryRepository,
    private val dataStore: DataStore<Preferences>
) {
    companion object {
        private const val PUSH_DELAY_MS = 2_000L
        private const val STALE_PULL_MS = 60_000L
        private const val MAX_HISTORY = 40
        private val KEY_LAST_UID = stringPreferencesKey("sync_last_uid")
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val db = Firebase.firestore

    private val _syncState = MutableStateFlow(SyncState.IDLE)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    private var activeUid: String? = null
    private var pushJob: Job? = null
    private var lastPull = 0L
    private var applying = false

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

    private fun LocalPlaylist.toMap(): Map<String, Any?> = mapOf(
        "id" to id, "name" to name, "createdAt" to createdAt,
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

    private fun mergeTracks(first: List<Track>, second: List<Track>): List<Track> {
        val seen = mutableSetOf<String>()
        return (first + second).filter { it.videoId.isNotEmpty() && seen.add(it.videoId) }
    }

    private fun mergePlaylists(local: List<LocalPlaylist>, cloud: List<LocalPlaylist>): List<LocalPlaylist> {
        val byId = cloud.associateBy { it.id }.toMutableMap()
        for (p in local) {
            val existing = byId[p.id]
            byId[p.id] = if (existing != null) existing.copy(tracks = mergeTracks(p.tracks, existing.tracks)) else p
        }
        return byId.values.sortedByDescending { it.createdAt }
    }

    // ---- Core operations ----

    suspend fun startSync(uid: String) {
        stopSync()
        _syncState.value = SyncState.SYNCING
        try {
            val lastUid = dataStore.data.first()[KEY_LAST_UID]
            val firstTime = lastUid != uid
            val cloud = pull(uid)
            activeUid = uid
            if (cloud != null && !firstTime) {
                applyCloud(cloud)
            } else if (firstTime) {
                val localLiked = library.likedOnce()
                val localPlaylists = library.playlistsOnce()
                val localHistory = history.allOnce()
                applyCloud(CloudData(
                    mergeTracks(localLiked, cloud?.liked ?: emptyList()),
                    mergePlaylists(localPlaylists, cloud?.playlists ?: emptyList()),
                    mergeTracks(localHistory, cloud?.history ?: emptyList())
                ))
            }
            dataStore.edit { it[KEY_LAST_UID] = uid }
            lastPull = System.currentTimeMillis()
            writeNow(uid)
        } catch (_: Exception) {
            if (activeUid == uid) _syncState.value = SyncState.ERROR
        }
    }

    fun stopSync() {
        pushJob?.cancel(); pushJob = null
        activeUid = null
        _syncState.value = SyncState.IDLE
    }

    fun schedulePush() {
        val uid = activeUid ?: return
        if (applying) return
        pushJob?.cancel()
        pushJob = scope.launch {
            delay(PUSH_DELAY_MS)
            writeNow(uid)
        }
    }

    /** Call on app foreground. */
    fun onForeground() {
        val uid = activeUid ?: return
        if (pushJob != null) return
        if (System.currentTimeMillis() - lastPull < STALE_PULL_MS) return
        scope.launch {
            try { pull(uid)?.let { applyCloud(it) }; lastPull = System.currentTimeMillis() } catch (_: Exception) {}
        }
    }

    suspend fun signOutAndClear() {
        val uid = activeUid
        if (uid != null) {
            pushJob?.cancel(); pushJob = null
            runCatching { writeNow(uid) }
        }
        stopSync()
        auth.signOut()
        applyCloud(CloudData(emptyList(), emptyList(), emptyList()))
        dataStore.edit { it.remove(KEY_LAST_UID) }
    }

    private suspend fun writeNow(uid: String) {
        _syncState.value = SyncState.SYNCING
        try {
            val liked = library.likedOnce().map { it.toMap() }
            val playlists = library.playlistsOnce().map { it.toMap() }
            val hist = history.allOnce().take(MAX_HISTORY).map { it.toMap() }
            val payload = mapOf("liked" to liked, "playlists" to playlists, "history" to hist, "updatedAt" to System.currentTimeMillis())
            db.collection("users").document(uid).set(payload, SetOptions.merge()).await()
            _syncState.value = SyncState.SAVED
        } catch (_: Exception) {
            _syncState.value = SyncState.ERROR
        }
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun pull(uid: String): CloudData? {
        val snap = db.collection("users").document(uid).get().await()
        if (!snap.exists()) return null
        val data = snap.data ?: return null
        val liked = (data["liked"] as? List<Map<String, Any?>>)?.mapNotNull { it.toTrack() } ?: emptyList()
        val pls = (data["playlists"] as? List<Map<String, Any?>>)?.mapNotNull { it.toLocalPlaylist() } ?: emptyList()
        val hist = (data["history"] as? List<Map<String, Any?>>)?.mapNotNull { it.toTrack() } ?: emptyList()
        return CloudData(liked, pls, hist)
    }

    private suspend fun applyCloud(cloud: CloudData) {
        applying = true
        try {
            library.replaceAllLiked(cloud.liked)
            library.replaceAllPlaylists(cloud.playlists)
            history.replaceAll(cloud.history.take(MAX_HISTORY))
        } finally {
            applying = false
        }
    }

    private data class CloudData(val liked: List<Track>, val playlists: List<LocalPlaylist>, val history: List<Track>)
}
