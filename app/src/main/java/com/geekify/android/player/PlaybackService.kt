package com.geekify.android.player

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CacheBitmapLoader
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.geekify.android.MainActivity
import com.geekify.android.R
import com.geekify.android.audio.AudioEffectsController
import com.geekify.android.audio.CrossfadeAudioTransitionController
import com.geekify.android.data.local.ListeningSessionEntity
import com.geekify.android.data.local.ListeningStatsRepository
import com.geekify.android.data.model.Track
import com.geekify.android.data.source.MusicSource
import com.geekify.android.data.source.innertube.InnerTubeClient
import com.geekify.android.domain.listening.ListeningBehaviorManager
import com.geekify.android.domain.listening.SmartQueueRanker
import com.geekify.android.notifications.NotificationChannels
import com.geekify.android.player.artwork.ArtworkUrls
import com.geekify.android.player.artwork.CoilBitmapLoader
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import dagger.hilt.android.AndroidEntryPoint
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

@OptIn(UnstableApi::class)
@AndroidEntryPoint
class PlaybackService : MediaLibraryService() {

    companion object {
        @Volatile private var activeInstance: PlaybackService? = null

        /** Stops the live playback session before an account transition. A non-running service is a no-op. */
        suspend fun stopForAccountTransition() {
            activeInstance?.pauseAndStopForAccountTransition()
        }

        /** Clears the old account's media state after the data handoff commits. The service and
         * MediaSession remain alive so the next account can play without requiring an activity restart. */
        suspend fun completeAccountTransition() {
            activeInstance?.completeAccountTransition()
        }

        /** Restores the old service to a safe paused state when backup or account validation fails. */
        suspend fun abortAccountTransition() {
            activeInstance?.abortAccountTransition()
        }

        private const val PLAYBACK_CHANNEL_ID = NotificationChannels.PLAYBACK
        private const val PLAYBACK_NOTIFICATION_ID = 1001
        /** Fresh-link attempts for one track before the error is shown. */
        private const val MAX_STREAM_RETRIES = 3
        /** Continuous playback after a retry that proves the new link works and refills the retry budget. */
        private const val RETRY_RESET_AFTER_MS = 15_000L
        /** A broken/stalled googlevideo stream must not leave the player buffering forever. */
        private const val BUFFERING_TIMEOUT_MS = 12_000L
        private const val LISTENING_CHECKPOINT_MS = 15_000L
        private const val ACTION_MEDIA_LIKE = "com.geekify.android.media.LIKE"
        private const val ACTION_MEDIA_SHUFFLE = "com.geekify.android.media.SHUFFLE"
    }

    @Inject lateinit var queueManager: QueueManager
    @Inject lateinit var streamResolver: InnerTubeXPlayer
    @Inject lateinit var audioEffects: AudioEffectsController
    @Inject lateinit var innerTube: InnerTubeClient
    @Inject lateinit var musicSource: MusicSource
    @Inject lateinit var historyRepository: com.geekify.android.data.local.HistoryRepository
    @Inject lateinit var listeningStatsRepository: ListeningStatsRepository
    @Inject lateinit var recommendationLogRepository: com.geekify.android.data.local.RecommendationLogRepository
    @Inject lateinit var libraryRepository: com.geekify.android.data.local.LibraryRepository
    @Inject lateinit var http: OkHttpClient
    @Inject lateinit var audioCache: SimpleCache
    @Inject lateinit var syncRepository: dagger.Lazy<com.geekify.android.data.sync.SyncRepository>
    @Inject lateinit var appSettings: com.geekify.android.settings.AppSettingsRepository
    @Inject lateinit var listeningBehaviorManager: ListeningBehaviorManager
    @Inject lateinit var crossfadeController: CrossfadeAudioTransitionController
    @Inject lateinit var diagnosticsTracker: PlaybackDiagnosticsTracker

    private lateinit var player: ExoPlayer
    private lateinit var mediaSession: MediaLibraryService.MediaLibrarySession
    private var mediaNotificationController: MediaSession.ControllerInfo? = null
    private var likedVideoIds: Set<String> = emptySet()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    // Persistence survives service teardown long enough to flush the final listening checkpoint.
    private val listeningPersistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** Serializes periodic and final session checkpoints so an old checkpoint cannot land after account cleanup. */
    private var listeningPersistenceJob: Job? = null
    private var prefetchJob: Job? = null
    private var autoplayJob: Job? = null
    private var progressJob: Job? = null
    /** Mirrors ExoPlayer's isPlaying; the progress ticker suspends on this while playback is paused/stopped. */
    private val playerActivelyPlaying = MutableStateFlow(false)
    private var bufferingWatchdogJob: Job? = null
    private var lastPlayingVideoId: String? = null
    private val retryCoordinator = PlaybackRetryCoordinator(MAX_STREAM_RETRIES, RETRY_RESET_AFTER_MS)
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var placeholderShown = false
    private lateinit var artworkLoader: CoilBitmapLoader
    private var listenedMs = 0L
    private var playCounted = false
    private var activeListeningSessionId: String? = null
    private var activeListeningTrack: Track? = null
    private var activeListeningStartedAt = 0L
    private var lastListeningUpdatedAt = 0L
    private var activeListeningMs = 0L
    private var lastListeningCheckpointMs = 0L
    private var logged30sThreshold = false
    private var logged50PercentThreshold = false
    private var restoredPositionUsed = false
    @Volatile private var accountTransitionInProgress = false
    @Volatile private var transitionWasPlaying = false
    private var transitionOriginalVolume = 1f
    /** Invalidates resolver, retry, autoplay and watchdog work across account boundaries. */
    private val playbackGeneration = AtomicLong(0L)
    @Volatile private var networkWarmedUp = false
    @Volatile private var cachedQueueRankingSignals: com.geekify.android.domain.listening.QueueRankingSignals? = null
    private var lastPrefetchedVideoIds: List<String> = emptyList()

    private fun ensureWarmUpTriggered() {
        if (networkWarmedUp) return
        networkWarmedUp = true
        scope.launch(Dispatchers.IO) {
            runCatching { innerTube.warmUp() }
            runCatching { streamResolver.warmUp() }
        }
    }

    private fun invalidateQueueRankingCache() {
        cachedQueueRankingSignals = null
    }

    override fun onCreate() {
        super.onCreate()
        activeInstance = this

        createPlaybackNotificationChannel()
        registerConnectivityMonitor()

        // Media3 owns the media notification (same id/channel as the early placeholder below). Artwork comes from
        // the session's BitmapLoader (Coil-backed, see CoilBitmapLoader), so the cover is cached, downsampled and
        // applied to the lock screen / notification as soon as it is ready.
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setNotificationId(PLAYBACK_NOTIFICATION_ID)
                .setChannelId(PLAYBACK_CHANNEL_ID)
                .setChannelName(R.string.channel_playback_name)
                .build()
                .apply { setSmallIcon(R.drawable.ic_stat_geekify) }
        )

        // Keep the upstream factory neutral: InnerTubeX supplies the User-Agent and other required
        // headers for the exact client that produced each signed stream URL.
        val upstreamFactory = OkHttpDataSource.Factory(http)
        // Apply the selected InnerTubeX stream's client headers and range constraints only when
        // Media3 actually opens the upstream URL. Keep the stable per-track/itag disk-cache key,
        // so expired URLs can be refreshed without discarding already cached audio bytes.
        val resolvingUpstreamFactory = ResolvingDataSource.Factory(upstreamFactory) { dataSpec ->
            streamResolver.resolveDataSpec(dataSpec)
        }
        val dataSourceFactory = CacheDataSource.Factory()
            .setCache(audioCache)
            .setUpstreamDataSourceFactory(resolvingUpstreamFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        // The audio sink runs our equalizer and normaliser before ExoPlayer's own tempo / silence processors.
        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink? = DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(false)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .setAudioProcessors(audioEffects.processors)
                .build()
        }

        player = ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(
                        /* minBufferMs = */ 15_000,
                        /* maxBufferMs = */ 50_000,
                        /* bufferForPlaybackMs = */ 1_000,
                        /* bufferForPlaybackAfterRebufferMs = */ 3_000
                    )
                    .build()
            )
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                true
            )
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setHandleAudioBecomingNoisy(true)
            .build()
            .also { exo ->
                exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                    .build()
            }

        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        artworkLoader = CoilBitmapLoader(this)
        mediaSession = MediaLibraryService.MediaLibrarySession.Builder(this, QueuePlayer(player), PlaybackSessionCallback())
            .setSessionActivity(pendingIntent)
            .setBitmapLoader(CacheBitmapLoader(artworkLoader))
            .setMediaButtonPreferences(mediaButtonPreferences(liked = false, shuffle = false))
            .build()
        // Media3 only manages the notification (and the foreground state) for sessions that were added to the
        // service. The app never creates a MediaController, so onGetSession() is not called by anything and the
        // session was never added: no media notification was ever posted. Adding it here makes Media3 show it.
        addSession(mediaSession)

        // Keep media-notification actions synchronized with the authoritative local queue and liked-song state.
        queueManager.state
            .map { it.current?.videoId to it.shuffle }
            .distinctUntilChanged()
            .onEach { (_, shuffle) -> updateMediaNotificationButtons(shuffle = shuffle) }
            .launchIn(scope)
        libraryRepository.likedVideoIds
            .distinctUntilChanged()
            .onEach { ids ->
                likedVideoIds = ids
                invalidateQueueRankingCache()
                updateMediaNotificationButtons()
            }
            .launchIn(scope)

        // Tempo, pitch and silence skipping change on the running player (no re-initialisation).
        // Equalizer and normalization transitions are smoothed directly inside their audio processors
        // without seeking or flushing the audio sink.
        observeRuntimeAppSettings()
        audioEffects.settings.onEach { s ->
            val current = player.playbackParameters
            if (current.speed != s.speed || current.pitch != s.pitch) {
                player.playbackParameters = PlaybackParameters(s.speed, s.pitch)
            }
            if (player.skipSilenceEnabled != s.skipSilence) player.skipSilenceEnabled = s.skipSilence
        }.launchIn(scope)

        player.addListener(object : Player.Listener {
            // The queue's `isPlaying` is the user's *intent* to play, so it follows playWhenReady.
            // It must not follow ExoPlayer's momentary isPlaying: when a song ends, STATE_ENDED
            // (which calls queueManager.next() and sets isPlaying = true) is delivered BEFORE
            // onIsPlayingChanged(false). Mirroring that false here used to flip the intent back
            // off, so the next song was loaded paused and auto-advance never started playback.
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                // A media-button or external-controller command must never restart the previous
                // account's player while an account handoff is being committed.
                val queueState = queueManager.state.value
                if (accountTransitionInProgress || (playWhenReady && queueState.current == null)) {
                    if (playWhenReady) {
                        player.playWhenReady = false
                        player.pause()
                    }
                    if (queueState.current == null && queueState.isPlaying) queueManager.setPlaying(false)
                    return
                }
                // A false playWhenReady can be the deliberate tonearm gate during a track swap,
                // not a user pause. Preserve the queue's play intent until the stylus lands.
                if (!playWhenReady && queueState.tonearmTransitionPending && queueState.isPlaying) return
                queueManager.setPlaying(playWhenReady)
                // Remember where a pause happened so reopening the app can offer to continue from here.
                if (!playWhenReady) {
                    queueManager.persistProgress()
                    // Close the active segment on pause. If the user resumes hours or days later,
                    // the new listening time must belong to the actual resume date, not this one.
                    finishListeningSession()
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playerActivelyPlaying.value = isPlaying
                if ((accountTransitionInProgress || queueManager.state.value.current == null) && isPlaying) {
                    player.volume = 0f
                    player.pause()
                    queueManager.setPlaying(false)
                    return
                }
                if (isPlaying) {
                    queueManager.setBuffering(false)
                    queueManager.state.value.current?.let(::recordTrackStarted)
                } else {
                    // The ticker suspends while not playing: publish the exact stop position once.
                    publishPlayerPosition()
                }
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (accountTransitionInProgress) return
                // A track can change while ExoPlayer remains continuously in the playing state.
                // Pair this callback with onIsPlayingChanged so session accounting isn't missed.
                if (player.isPlaying) queueManager.state.value.current?.let(::recordTrackStarted)
            }
            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                if (accountTransitionInProgress) return
                // Seeks from the notification, Bluetooth or Android Auto while paused would otherwise
                // not reach the UI until playback resumes, because the ticker is suspended.
                if (!player.isPlaying) publishPlayerPosition()
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (accountTransitionInProgress) {
                    bufferingWatchdogJob?.cancel()
                    bufferingWatchdogJob = null
                    queueManager.setBuffering(false)
                    return
                }
                when (playbackState) {
                    Player.STATE_BUFFERING -> {
                        queueManager.setBuffering(true)
                        startBufferingWatchdog()
                    }
                    Player.STATE_READY -> {
                        bufferingWatchdogJob?.cancel()
                        bufferingWatchdogJob = null
                        queueManager.setBuffering(false)
                        // Duration becomes known here; publish it even if playback stays paused.
                        publishPlayerPosition()
                    }
                    Player.STATE_ENDED -> {
                        bufferingWatchdogJob?.cancel()
                        bufferingWatchdogJob = null
                        finishListeningSession(completed = true)
                        // Forget the finished play so a replay (repeat, or play again) is recorded again.
                        lastPlayingVideoId = null
                        handleTrackEnded()
                    }
                    else -> Unit
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                if (accountTransitionInProgress) return
                bufferingWatchdogJob?.cancel()
                bufferingWatchdogJob = null
                queueManager.setBuffering(false)
                handlePlaybackError(error)
            }
        })

        // Periodically update progress in QueueManager for UI seekers. The loop suspends entirely while
        // the player is not playing (no polling while paused) and publishes only to the dedicated
        // position flow, so queue observers below are not re-run on every tick.
        progressJob = scope.launch {
            var tick = 0
            while (isActive) {
                if (!playerActivelyPlaying.value) playerActivelyPlaying.first { it }
                if (player.isPlaying) {
                    val pos = player.currentPosition
                    val dur = player.duration.coerceAtLeast(0L)
                    queueManager.setProgress(pos, dur)

                    // A link that has played for a while is good: allow fresh-link retries again for later failures.
                    retryCoordinator.onPlaybackProgressTick(500L)

                    // Accumulate actual playing time independently of play-count qualification. A qualifying
                    // play is still counted only once, after 30 seconds or half of a short track.
                    listenedMs += 500
                    if (activeListeningSessionId != null && activeListeningTrack?.videoId == queueManager.state.value.current?.videoId) {
                        activeListeningMs += 500
                        if (activeListeningMs - lastListeningCheckpointMs >= LISTENING_CHECKPOINT_MS) {
                            checkpointListeningSession()
                            lastListeningCheckpointMs = activeListeningMs
                        }
                        if (!logged30sThreshold && activeListeningMs >= 30_000L && !appSettings.state.value.pauseListenHistory) {
                            logged30sThreshold = true
                            val vid = activeListeningTrack?.videoId
                            if (vid != null) {
                                listeningPersistenceScope.launch {
                                    runCatching {
                                        recommendationLogRepository.recordOutcome(
                                            videoId = vid,
                                            event = com.geekify.android.data.local.RecommendationOutcomeEvent.PLAYED_30S,
                                            playDurationMs = activeListeningMs
                                        )
                                    }
                                }
                            }
                        }
                        if (!logged50PercentThreshold && dur > 0L && activeListeningMs >= dur / 2L && !appSettings.state.value.pauseListenHistory) {
                            logged50PercentThreshold = true
                            val vid = activeListeningTrack?.videoId
                            if (vid != null) {
                                listeningPersistenceScope.launch {
                                    runCatching {
                                        recommendationLogRepository.recordOutcome(
                                            videoId = vid,
                                            event = com.geekify.android.data.local.RecommendationOutcomeEvent.PLAYED_50_PERCENT,
                                            playDurationMs = activeListeningMs
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (!playCounted) {
                        val threshold = if (dur > 0L) minOf(30_000L, dur / 2) else 30_000L
                        val current = queueManager.state.value.current
                        if (current != null && current.videoId == lastPlayingVideoId && listenedMs >= threshold) {
                            playCounted = true
                            enqueueHistoryWrite { historyRepository.countPlay(current) }
                        }
                    }
                    if (++tick % 10 == 0) queueManager.persistProgress()
                }
                delay(500)
            }
        }

        // Listen for seek events from UI
        queueManager.seekEvents.onEach { posMs ->
            if (!accountTransitionInProgress && queueManager.state.value.current != null) {
                player.seekTo(posMs)
            }
        }.launchIn(scope)

        // Observe queue changes and drive ExoPlayer
        queueManager.state.onEach { state ->
            // A user/account transition deliberately tears down the active item. Do not let this
            // observer resolve and prepare the old account's current track again during teardown.
            if (accountTransitionInProgress) return@onEach
            if (player.volume != state.volume) player.volume = state.volume
            val track = state.current ?: return@onEach
            if (activeListeningTrack != null && activeListeningTrack?.videoId != track.videoId && activeListeningSessionId != null) {
                // A current-item change before STATE_ENDED is a manual skip or replacement, not a completion.
                finishListeningSession(skipped = true)
            }
            // Before the very first stream is prepared Media3 has no item, so it posts no notification and Android
            // could keep the service background-only. Promote the service with a one-off placeholder in that gap only.
            // Once the player has a media item Media3 owns notification id 1001 (it carries the artwork and the
            // controls); re-posting our own, artwork-less notification over it is what used to wipe the cover.
            if ((state.isPlaying || state.isBuffering) && !placeholderShown && player.currentMediaItem == null) {
                placeholderShown = true
                showForegroundPlaybackNotification(track)
            }
            val shouldPlay = state.isPlaying && !state.tonearmTransitionPending
            if (state.tonearmTransitionPending && player.playWhenReady) {
                // Stop the previous track as soon as the skip is observed; do not let it continue
                // underneath the record-swap animation while the next stream is being resolved.
                player.pause()
            }
            if (player.currentMediaItem?.mediaId != track.videoId) {
                // During a visible vinyl transition, the new item is prepared but held until the stylus lands.
                loadAndPlay(track, autoPlay = shouldPlay)
            } else {
                if (shouldPlay && !player.isPlaying && player.playbackState != Player.STATE_BUFFERING) {
                    // Repeat-one / replay after the end of the queue: an ended player must be rewound first.
                    if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
                    player.play()
                } else if (!shouldPlay && player.playWhenReady) {
                    player.pause()
                }
                if (state.isPlaying) {
                    startPrefetch(track.videoId)
                }
            }
        }.launchIn(scope)
    }

    /**
     * Publishes the player's current position/duration to the dedicated position flow (main thread only).
     * Skipped while the player still holds the previous item (e.g. right after STATE_ENDED advanced the
     * queue), so the new track's reset position is never overwritten by the old track's end position.
     */
    private fun publishPlayerPosition() {
        if (!::player.isInitialized) return
        val currentId = queueManager.state.value.current?.videoId ?: return
        if (player.currentMediaItem?.mediaId != currentId) return
        queueManager.setProgress(
            player.currentPosition.coerceAtLeast(0L),
            player.duration.coerceAtLeast(0L)
        )
    }

    private fun isCurrentlyConnected(): Boolean {
        val cm = connectivityManager
            ?: getSystemService(ConnectivityManager::class.java).also { connectivityManager = it }
            ?: return true
        val active = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(active) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun registerConnectivityMonitor() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        connectivityManager = cm
        retryCoordinator.onConnectivityChanged(isCurrentlyConnected(), null) {}

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                notifyConnectivityChanged(true)
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                val hasInternet = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                notifyConnectivityChanged(hasInternet)
            }

            override fun onLost(network: Network) {
                notifyConnectivityChanged(isCurrentlyConnected())
            }
        }
        networkCallback = callback
        runCatching {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.registerNetworkCallback(request, callback)
        }
    }

    private fun unregisterConnectivityMonitor() {
        val cb = networkCallback ?: return
        networkCallback = null
        runCatching { connectivityManager?.unregisterNetworkCallback(cb) }
    }

    private fun notifyConnectivityChanged(online: Boolean) {
        scope.launch {
            val currentTrack = queueManager.state.value.current
            retryCoordinator.onConnectivityChanged(
                online = online,
                currentTrackId = currentTrack?.videoId
            ) { recovery ->
                queueManager.setError(null)
                if (recovery.wasPlaying) {
                    queueManager.setPlaying(true)
                }
                retryCurrentStream(
                    track = recovery.track,
                    resumeAt = recovery.resumeAtMs,
                    wasPlaying = recovery.wasPlaying,
                    markFailed = recovery.markFailed
                )
            }
            if (online && currentTrack != null && appSettings.state.value.autoplayRelated) {
                val s = queueManager.state.value
                val upcoming = s.queue.size - s.index - 1
                if (upcoming < 2 && s.repeat == RepeatMode.OFF && !s.shuffle && autoplayJob?.isActive != true) {
                    startAutoplay(currentTrack)
                }
            }
        }
    }

    /**
     * ExoPlayer only ever holds the current song; the real queue lives in [QueueManager]. Without
     * this wrapper the notification / lock screen / headset see a one-item playlist: no "next", and
     * "previous" just restarts the song. This routes them to the real queue instead.
     */
    private val likeCommand = SessionCommand(ACTION_MEDIA_LIKE, Bundle.EMPTY)
    private val shuffleCommand = SessionCommand(ACTION_MEDIA_SHUFFLE, Bundle.EMPTY)

    private fun mediaButtonPreferences(liked: Boolean, shuffle: Boolean): List<CommandButton> = listOf(
        CommandButton.Builder(
            if (shuffle) CommandButton.ICON_SHUFFLE_ON else CommandButton.ICON_SHUFFLE_OFF
        )
            .setDisplayName("Shuffle")
            .setSessionCommand(shuffleCommand)
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build(),
        CommandButton.Builder(
            if (liked) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED
        )
            .setDisplayName(if (liked) "Unlike" else "Like")
            .setSessionCommand(likeCommand)
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build()
    )

    private fun updateMediaNotificationButtons(
        liked: Boolean = queueManager.state.value.current?.videoId?.let(likedVideoIds::contains) == true,
        shuffle: Boolean = queueManager.state.value.shuffle
    ) {
        val controller = mediaNotificationController ?: return
        mediaSession.setMediaButtonPreferences(controller, mediaButtonPreferences(liked, shuffle))
    }

    private fun buildBrowsableMediaItem(id: String, title: String): androidx.media3.common.MediaItem {
        return androidx.media3.common.MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                androidx.media3.common.MediaMetadata.Builder()
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setTitle(title)
                    .build()
            )
            .build()
    }

    private fun buildTrackMediaItem(track: Track): androidx.media3.common.MediaItem {
        return androidx.media3.common.MediaItem.Builder()
            .setMediaId(track.videoId)
            .setMediaMetadata(
                androidx.media3.common.MediaMetadata.Builder()
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setTitle(track.title)
                    .setArtist(track.artist)
                    .setArtworkUri(track.thumbnails.firstOrNull()?.url?.let { android.net.Uri.parse(it) })
                    .build()
            )
            .build()
    }

    private inner class PlaybackSessionCallback : MediaLibraryService.MediaLibrarySession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val accepted = MediaSession.ConnectionResult.AcceptedResultBuilder(session)
            if (session.isMediaNotificationController(controller)) {
                mediaNotificationController = controller
                val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                    .add(likeCommand)
                    .add(shuffleCommand)
                    .build()
                accepted
                    .setAvailableSessionCommands(commands)
                    .setMediaButtonPreferences(
                        mediaButtonPreferences(
                            liked = queueManager.state.value.current?.videoId?.let(likedVideoIds::contains) == true,
                            shuffle = queueManager.state.value.shuffle
                        )
                    )
            }
            return accepted.build()
        }

        override fun onGetLibraryRoot(
            session: MediaLibraryService.MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: MediaLibraryService.LibraryParams?
        ): ListenableFuture<androidx.media3.session.LibraryResult<androidx.media3.common.MediaItem>> {
            val rootItem = androidx.media3.common.MediaItem.Builder()
                .setMediaId("ROOT")
                .setMediaMetadata(
                    androidx.media3.common.MediaMetadata.Builder()
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .setTitle("Geekify")
                        .build()
                )
                .build()
            val res = SettableFuture.create<androidx.media3.session.LibraryResult<androidx.media3.common.MediaItem>>()
            res.set(androidx.media3.session.LibraryResult.ofItem(rootItem, params))
            return res
        }

        override fun onGetChildren(
            session: MediaLibraryService.MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: MediaLibraryService.LibraryParams?
        ): ListenableFuture<androidx.media3.session.LibraryResult<com.google.common.collect.ImmutableList<androidx.media3.common.MediaItem>>> {
            val res = SettableFuture.create<androidx.media3.session.LibraryResult<com.google.common.collect.ImmutableList<androidx.media3.common.MediaItem>>>()
            scope.launch(Dispatchers.IO) {
                try {
                    val items = when (parentId) {
                        "ROOT" -> listOf(
                            buildBrowsableMediaItem("NODE_LIKED", "Liked Songs"),
                            buildBrowsableMediaItem("NODE_RECENTS", "Recently Played"),
                            buildBrowsableMediaItem("NODE_PLAYLISTS", "Playlists")
                        )
                        "NODE_LIKED" -> {
                            val liked = libraryRepository.likedOnce()
                            liked.map { buildTrackMediaItem(it) }
                        }
                        "NODE_RECENTS" -> {
                            val recents = historyRepository.allOnce()
                            recents.map { buildTrackMediaItem(it) }
                        }
                        "NODE_PLAYLISTS" -> {
                            val pls = libraryRepository.playlistsOnce()
                            pls.map { buildBrowsableMediaItem("PLAYLIST_${it.id}", it.name) }
                        }
                        else -> {
                            if (parentId.startsWith("PLAYLIST_")) {
                                val plId = parentId.removePrefix("PLAYLIST_")
                                val plTracks = libraryRepository.playlistTracks(plId).first()
                                plTracks.map { buildTrackMediaItem(it) }
                            } else {
                                emptyList()
                            }
                        }
                    }
                    res.set(androidx.media3.session.LibraryResult.ofItemList(items, params))
                } catch (_: Exception) {
                    res.set(androidx.media3.session.LibraryResult.ofError(androidx.media3.session.SessionError.ERROR_UNKNOWN))
                }
            }
            return res
        }

        override fun onGetItem(
            session: MediaLibraryService.MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String
        ): ListenableFuture<androidx.media3.session.LibraryResult<androidx.media3.common.MediaItem>> {
            val res = SettableFuture.create<androidx.media3.session.LibraryResult<androidx.media3.common.MediaItem>>()
            scope.launch(Dispatchers.IO) {
                try {
                    val track = libraryRepository.likedOnce().find { it.videoId == mediaId }
                        ?: historyRepository.allOnce().find { it.videoId == mediaId }
                    if (track != null) {
                        res.set(androidx.media3.session.LibraryResult.ofItem(buildTrackMediaItem(track), null))
                    } else {
                        res.set(androidx.media3.session.LibraryResult.ofError(androidx.media3.session.SessionError.ERROR_BAD_VALUE))
                    }
                } catch (_: Exception) {
                    res.set(androidx.media3.session.LibraryResult.ofError(androidx.media3.session.SessionError.ERROR_UNKNOWN))
                }
            }
            return res
        }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<androidx.media3.common.MediaItem>,
            startIndex: Int,
            startPositionMs: Long
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val res = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            scope.launch(Dispatchers.IO) {
                val allTracks = libraryRepository.likedOnce() + historyRepository.allOnce()
                val tracks = mediaItems.mapNotNull { item ->
                    val videoId = item.mediaId
                    allTracks.find { it.videoId == videoId }
                }
                if (tracks.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        val target = tracks.getOrElse(startIndex) { tracks.first() }
                        queueManager.play(target, tracks)
                    }
                }
                res.set(MediaSession.MediaItemsWithStartPosition(mediaItems, startIndex, startPositionMs))
            }
            return res
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            if (customCommand != likeCommand && customCommand != shuffleCommand) {
                return super.onCustomCommand(session, controller, customCommand, args)
            }

            val result = SettableFuture.create<SessionResult>()
            if (accountTransitionInProgress || queueManager.state.value.current == null) {
                result.set(SessionResult(SessionResult.RESULT_SUCCESS))
                return result
            }
            scope.launch {
                try {
                    when (customCommand) {
                        likeCommand -> queueManager.state.value.current?.let { track ->
                            withContext(Dispatchers.IO) {
                                libraryRepository.toggleLike(track)
                                syncRepository.get().pushNow()
                            }
                        }
                        shuffleCommand -> queueManager.toggleShuffle()
                    }
                    updateMediaNotificationButtons()
                    result.set(SessionResult(SessionResult.RESULT_SUCCESS))
                } catch (_: Exception) {
                    result.set(SessionResult(androidx.media3.session.SessionError.ERROR_UNKNOWN))
                }
            }
            return result
        }

        override fun onPostConnect(session: MediaSession, controller: MediaSession.ControllerInfo) {
            if (session.isMediaNotificationController(controller)) {
                mediaNotificationController = controller
                updateMediaNotificationButtons()
            }
        }

        override fun onDisconnected(session: MediaSession, controller: MediaSession.ControllerInfo) {
            if (mediaNotificationController == controller) mediaNotificationController = null
        }
    }

    private inner class QueuePlayer(wrapped: Player) : ForwardingPlayer(wrapped) {
        override fun getAvailableCommands(): Player.Commands =
            super.getAvailableCommands().buildUpon()
                .add(Player.COMMAND_SEEK_TO_NEXT)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                .build()

        override fun isCommandAvailable(command: Int): Boolean = availableCommands.contains(command)

        override fun hasNextMediaItem(): Boolean = !accountTransitionInProgress && queueManager.state.value.hasNext
        override fun hasPreviousMediaItem(): Boolean = !accountTransitionInProgress && queueManager.state.value.queue.isNotEmpty()

        override fun seekToNext() { if (!accountTransitionInProgress) queueManager.next() }
        override fun seekToNextMediaItem() { if (!accountTransitionInProgress) queueManager.next() }

        // Same rule as the in-app button: past 3 s restarts the song, otherwise go to the previous one.
        override fun seekToPrevious() { if (!accountTransitionInProgress) queueManager.previous(currentPosition.coerceAtLeast(0L) / 1000) }
        override fun seekToPreviousMediaItem() { if (!accountTransitionInProgress) queueManager.previous(currentPosition.coerceAtLeast(0L) / 1000) }
    }

    private fun observeRuntimeAppSettings() {
        appSettings.state
            .map { it.audioQuality }
            .distinctUntilChanged()
            .drop(1)
            .onEach {
                lastPrefetchedVideoIds = emptyList()
                streamResolver.clearCache()
                val currentTrack = queueManager.state.value.current
                if (currentTrack != null && player.currentMediaItem != null) {
                    retryCoordinator.resetRetryBudget()
                    retryCurrentStream(
                        track = currentTrack,
                        resumeAt = player.currentPosition.coerceAtLeast(0L),
                        wasPlaying = queueManager.state.value.isPlaying,
                        markFailed = false
                    )
                    startPrefetch(currentTrack.videoId)
                }
            }
            .launchIn(scope)

        appSettings.state
            .map { it.autoplayRelated }
            .distinctUntilChanged()
            .drop(1)
            .onEach { enabled ->
                if (enabled) {
                    queueManager.state.value.current?.let(::startAutoplay)
                } else {
                    autoplayJob?.cancel()
                    queueManager.removeAutoplayTracks()
                }
            }
            .launchIn(scope)

        appSettings.state
            .map { it.hideExplicit }
            .distinctUntilChanged()
            .drop(1)
            .onEach { hideExplicit ->
                if (hideExplicit) {
                    queueManager.removeExplicitUpcomingTracks()
                }
            }
            .launchIn(scope)
    }

    private fun createPlaybackNotificationChannel() = NotificationChannels.ensureAll(this)

    private fun showForegroundPlaybackNotification(track: Track) {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = Notification.Builder(this, PLAYBACK_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_geekify)
            .setContentTitle(track.title)
            .setContentText(track.artist)
            .setContentIntent(openAppIntent)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setStyle(Notification.MediaStyle().setMediaSession(mediaSession.platformToken))
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                PLAYBACK_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(PLAYBACK_NOTIFICATION_ID, notification)
        }
    }

    /** One place builds every MediaItem so all song sources expose title, artist, album and a loadable artwork URI. */
    private fun buildMediaItem(track: Track, url: String, mimeType: String?, itag: Int? = null): MediaItem =
        MediaItem.Builder()
            .setMediaId(track.videoId)
            .setUri(url)
            // Stable cache key: the same song + audio format hits the disk cache even though the URL differs.
            .setCustomCacheKey(if (itag != null) "${track.videoId}:$itag" else null)
            .setMimeType(mimeType)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(track.title)
                    .setDisplayTitle(track.title)
                    .setArtist(track.artist)
                    .setAlbumTitle(track.album)
                    .setArtworkUri(ArtworkUrls.forTrack(track)?.let(Uri::parse))
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .setIsPlayable(true)
                    .build()
            )
            .build()

    private var loadJob: Job? = null
    private var loadingId: String? = null
    /** Prevents a cancelled old-account resolver from clearing a new account's same-track load marker. */
    private var loadingGeneration: Long = -1L
    private var historyWriteJob: Job? = null

    private fun loadAndPlay(track: Track, autoPlay: Boolean = true) {
        // Never resolve or attach a stream after account teardown has begun. This also prevents
        // a slow in-flight resolver from installing the previous account's track after clearMediaItems().
        if (accountTransitionInProgress) return
        val generation = playbackGeneration.get()
        // State emissions (progress, volume...) must not restart a lookup that is already running.
        if (loadingId == track.videoId && loadJob?.isActive == true) return
        if (player.currentMediaItem?.mediaId == track.videoId && !retryCoordinator.isRetryActive()) {
            if (autoPlay && !player.isPlaying) player.play()
            return
        }
        loadJob?.cancel()
        bufferingWatchdogJob?.cancel()
        bufferingWatchdogJob = null
        retryCoordinator.onNewTrackLoading(track.videoId)
        loadingId = track.videoId
        loadingGeneration = generation
        lastPlayingVideoId = null
        // Only the very first load after the app starts is a restored queue; it resumes at the saved position.
        val pendingStart = queueManager.takePendingStart()
        val startMs = when {
            pendingStart > 0L -> pendingStart
            !restoredPositionUsed -> queueManager.position.value.positionMs.coerceAtLeast(0L)
            else -> 0L
        }
        restoredPositionUsed = true
        if (autoPlay) queueManager.setBuffering(true)
        ensureWarmUpTriggered()
        loadJob = scope.launch {
            val resolveStartTime = System.currentTimeMillis()
            try {
                val stream = withContext(Dispatchers.IO) {
                    streamResolver.resolve(track.videoId)
                }
                diagnosticsTracker.recordResolution(System.currentTimeMillis() - resolveStartTime, success = true)
                diagnosticsTracker.recordAudioFormat(stream.mimeType)
                if (!isActive || accountTransitionInProgress || generation != playbackGeneration.get() ||
                    loadingId != track.videoId || loadingGeneration != generation ||
                    queueManager.state.value.current?.videoId != track.videoId
                ) return@launch
                audioEffects.setTrackLoudness(stream.loudnessDb)
                val item = buildMediaItem(track, stream.url, stream.mimeType, stream.itag)
                player.setMediaItem(item, startMs)
                // The user may have pressed play/pause while the stream was being resolved.
                val latestState = queueManager.state.value
                player.playWhenReady = latestState.isPlaying && !latestState.tonearmTransitionPending
                listeningBehaviorManager.recordPlaybackStart(track)
                crossfadeController.applyTrackStartFadeIn(player)
                player.prepare()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                diagnosticsTracker.recordResolution(System.currentTimeMillis() - resolveStartTime, success = false)
                diagnosticsTracker.recordError("RESOLUTION", e.message)
                if (accountTransitionInProgress || generation != playbackGeneration.get()) return@launch
                // Show the reason instead of failing silently. Only move on to the next song when the
                // person was actually listening: a restored, paused queue must never start by itself.
                val wasPlaying = queueManager.state.value.isPlaying
                val online = isCurrentlyConnected()
                retryCoordinator.onConnectivityChanged(online, track.videoId) {}
                if (retryCoordinator.deferIfOffline(track, startMs, wasPlaying, markFailed = false)) {
                    queueManager.setError("No internet connection. Playback will resume when back online.")
                } else {
                    queueManager.setError(PlaybackErrors.message(e))
                    if (wasPlaying) skipAfterError(track)
                }
            } finally {
                if (loadingId == track.videoId && loadingGeneration == generation) {
                    loadingId = null
                    loadingGeneration = -1L
                }
            }
        }
    }

    private fun skipAfterError(failed: Track) {
        if (accountTransitionInProgress || !appSettings.state.value.autoSkipOnError) return
        val generation = playbackGeneration.get()
        val online = isCurrentlyConnected()
        retryCoordinator.onConnectivityChanged(online, failed.videoId) {}
        if (!retryCoordinator.canSkipAfterError()) return
        val job = scope.launch {
            delay(3000)
            if (accountTransitionInProgress || generation != playbackGeneration.get()) return@launch
            if (!isCurrentlyConnected() || !retryCoordinator.canSkipAfterError()) return@launch
            val s = queueManager.state.value
            // Only skip if the user has not already moved to another track.
            if (s.current?.videoId == failed.videoId && s.queue.size > 1 && s.hasNext) queueManager.next()
        }
        retryCoordinator.attachSkipJob(job)
    }

    private fun startBufferingWatchdog() {
        bufferingWatchdogJob?.cancel()
        val track = queueManager.state.value.current ?: return
        if (!queueManager.state.value.isPlaying) return
        val trackId = track.videoId
        val generation = playbackGeneration.get()
        bufferingWatchdogJob = scope.launch {
            delay(BUFFERING_TIMEOUT_MS)
            if (accountTransitionInProgress || generation != playbackGeneration.get()) return@launch
            val state = queueManager.state.value
            if (state.current?.videoId != trackId || !state.isPlaying || player.playbackState != Player.STATE_BUFFERING) return@launch

            val online = isCurrentlyConnected()
            retryCoordinator.onConnectivityChanged(online, trackId) {}
            if (!online) {
                retryCoordinator.deferIfOffline(
                    track = track,
                    resumeAtMs = player.currentPosition.coerceAtLeast(0L),
                    wasPlaying = true,
                    markFailed = false
                )
                queueManager.setBuffering(false)
                queueManager.setError("No internet connection. Playback will resume when back online.")
                return@launch
            }
            if (!retryCoordinator.canWatchdogTriggerRetry(loadJob?.isActive == true)) return@launch

            // A stream that never becomes READY is normally a stale/rejected googlevideo URL. Refresh it instead
            // of leaving the user on an endless loading animation. The normal retry/error path is reused.
            if (retryCoordinator.resolveRetries < MAX_STREAM_RETRIES) {
                retryCurrentStream(
                    track = track,
                    resumeAt = player.currentPosition.coerceAtLeast(0L),
                    wasPlaying = true,
                    markFailed = false
                )
            } else {
                queueManager.setBuffering(false)
                queueManager.setError("This track took too long to start.")
                skipAfterError(track)
            }
        }
    }

    private fun retryCurrentStream(track: Track, resumeAt: Long, wasPlaying: Boolean, markFailed: Boolean) {
        if (accountTransitionInProgress) return
        val generation = playbackGeneration.get()
        val online = isCurrentlyConnected()
        retryCoordinator.onConnectivityChanged(online, track.videoId) {}
        if (retryCoordinator.deferIfOffline(track, resumeAt, wasPlaying, markFailed)) {
            bufferingWatchdogJob?.cancel()
            bufferingWatchdogJob = null
            queueManager.setBuffering(false)
            queueManager.setError("No internet connection. Playback will resume when back online.")
            return
        }

        // Prevent simultaneous retry operations
        if (retryCoordinator.isRetryActive()) return

        val attempt = retryCoordinator.tryBeginRetry()
        if (attempt == null) {
            queueManager.setBuffering(false)
            queueManager.setError("Could not start this track.")
            if (wasPlaying) skipAfterError(track)
            return
        }

        if (markFailed) streamResolver.markFailed(track.videoId) else streamResolver.invalidate(track.videoId)
        bufferingWatchdogJob?.cancel()
        bufferingWatchdogJob = null
        loadJob?.cancel()
        loadingId = null
        loadingGeneration = -1L
        player.stop()
        queueManager.setBuffering(true)
        ensureWarmUpTriggered()

        var launchedJob: Job? = null
        launchedJob = scope.launch {
            try {
                delay(400L * attempt)
                if (!isActive || accountTransitionInProgress || generation != playbackGeneration.get()) return@launch
                val stillOnline = isCurrentlyConnected()
                retryCoordinator.onConnectivityChanged(stillOnline, track.videoId) {}
                if (retryCoordinator.deferIfOffline(track, resumeAt, wasPlaying, markFailed)) {
                    queueManager.setBuffering(false)
                    queueManager.setError("No internet connection. Playback will resume when back online.")
                    return@launch
                }
                val stream = withContext(Dispatchers.IO) {
                    streamResolver.resolve(track.videoId, forceRefresh = true)
                }
                if (!isActive || accountTransitionInProgress || generation != playbackGeneration.get() ||
                    queueManager.state.value.current?.videoId != track.videoId
                ) return@launch
                audioEffects.setTrackLoudness(stream.loudnessDb)
                val item = buildMediaItem(track, stream.url, stream.mimeType, stream.itag)
                player.setMediaItem(item, resumeAt)
                val latestState = queueManager.state.value
                player.playWhenReady = latestState.isPlaying && !latestState.tonearmTransitionPending
                player.prepare()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (accountTransitionInProgress || generation != playbackGeneration.get()) return@launch
                queueManager.setBuffering(false)
                val stillOnline = isCurrentlyConnected()
                retryCoordinator.onConnectivityChanged(stillOnline, track.videoId) {}
                if (retryCoordinator.deferIfOffline(track, resumeAt, wasPlaying, markFailed)) {
                    queueManager.setError("No internet connection. Playback will resume when back online.")
                } else {
                    queueManager.setError(PlaybackErrors.message(e))
                    if (wasPlaying) skipAfterError(track)
                }
            } finally {
                retryCoordinator.clearRetryJob(launchedJob)
            }
        }
        retryCoordinator.attachRetryJob(launchedJob)
    }

    private fun handlePlaybackError(error: PlaybackException) {
        if (accountTransitionInProgress) return
        val track = queueManager.state.value.current ?: return
        val wasPlaying = queueManager.state.value.isPlaying
        val resumeAt = player.currentPosition.coerceAtLeast(0L)
        val online = isCurrentlyConnected()
        retryCoordinator.onConnectivityChanged(online, track.videoId) {}
        if (retryCoordinator.deferIfOffline(track, resumeAt, wasPlaying, markFailed = false)) {
            queueManager.setError("No internet connection. Playback will resume when back online.")
            return
        }

        val httpCode = generateSequence<Throwable>(error) { it.cause }
            .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
            .firstOrNull()?.responseCode
        // 403 / 404 / 410 (and 5xx) mean this particular link is no good; network hiccups are worth a fresh try too.
        // 429 is left alone: hammering YouTube again right away only makes the rate limit last longer.
        val urlProblem = when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
                httpCode == null || httpCode == 403 || httpCode == 404 || httpCode == 410 || httpCode >= 500
            PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> true
            else -> false
        }
        if (urlProblem) {
            // Rejected or expired URL: get a fresh one and try again from the same position.
            retryCurrentStream(
                track = track,
                resumeAt = resumeAt,
                wasPlaying = wasPlaying,
                markFailed = httpCode == 403
            )
        } else {
            retryCoordinator.resetRetryBudget()
            queueManager.setError(PlaybackErrors.message(error))
            if (wasPlaying) skipAfterError(track)
        }
    }

    /**
     * Recent-history and play-count writes share one ordered queue. Account handoff waits for its
     * tail before clearing Room, so no queued old-account write can land in the next account's DB.
     */
    private fun enqueueHistoryWrite(write: suspend () -> Unit) {
        val generation = playbackGeneration.get()
        val previous = historyWriteJob
        historyWriteJob = scope.launch {
            previous?.join()
            try {
                // A write queued before a handoff is allowed to finish for the old identity. The
                // handoff joins this job before switching Auth/clearing local tables.
                if (!appSettings.state.value.pauseListenHistory) {
                    write()
                    if (!accountTransitionInProgress && generation == playbackGeneration.get()) {
                        syncRepository.get().schedulePush()
                    }
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                timber.log.Timber.w(error, "Could not persist local listening history")
            }
        }
    }

    private fun recordTrackStarted(current: Track) {
        if (activeListeningTrack?.videoId != current.videoId) {
            if (activeListeningSessionId != null) finishListeningSession(skipped = true)
            startListeningSession(current)
        }
        if (current.videoId != lastPlayingVideoId) {
            lastPlayingVideoId = current.videoId
            listenedMs = 0L
            playCounted = false
            val generation = playbackGeneration.get()
            enqueueHistoryWrite { historyRepository.add(current) }
            startPrefetch(current.videoId)
            startAutoplay(current)
        }
    }

    private fun startPrefetch(currentId: String) {
        if (accountTransitionInProgress) return
        val generation = playbackGeneration.get()
        val s = queueManager.state.value
        val nextIds = buildList {
            s.queue.getOrNull(s.index + 1)?.videoId?.let { add(it) }
            s.queue.getOrNull(s.index + 2)?.videoId?.let { add(it) }
        }.filter { it != currentId }.distinct()
        if (nextIds == lastPrefetchedVideoIds) return
        lastPrefetchedVideoIds = nextIds
        prefetchJob?.cancel()
        if (nextIds.isEmpty()) {
            streamResolver.prefetch(emptyList())
            return
        }
        ensureWarmUpTriggered()
        prefetchJob = scope.launch(Dispatchers.IO) {
            if (accountTransitionInProgress || generation != playbackGeneration.get()) return@launch
            streamResolver.prefetch(nextIds)
        }
    }

    private suspend fun resolveQueueRankingSignals(): com.geekify.android.domain.listening.QueueRankingSignals {
        val now = System.currentTimeMillis()
        cachedQueueRankingSignals?.let { cached ->
            if (now - cached.builtAtMs <= 60_000L) {
                return cached
            }
        }
        val (sessions, plays, likedPair) = withContext(Dispatchers.IO) {
            val s = listeningStatsRepository.latest(500)
            val p = historyRepository.topPlayed(250)
            val ids = likedVideoIds.ifEmpty { libraryRepository.likedVideoIdsOnce() }
            val artists = libraryRepository.distinctLikedArtistsOnce(150)
            Triple(s, p, ids to artists)
        }
        return withContext(Dispatchers.Default) {
            SmartQueueRanker.buildSignals(
                sessions = sessions,
                playCounts = plays,
                likedIds = likedPair.first,
                likedArtists = likedPair.second,
                now = now
            )
        }.also { cachedQueueRankingSignals = it }
    }

    private fun handleTrackEnded() {
        val s = queueManager.state.value
        if (s.hasNext || s.repeat != RepeatMode.OFF) {
            queueManager.next()
            return
        }
        val currentTrack = s.current
        if (currentTrack != null && appSettings.state.value.autoplayRelated && !s.shuffle) {
            queueManager.setBuffering(true)
            val generation = playbackGeneration.get()
            scope.launch {
                val activeAutoplay = autoplayJob
                if (activeAutoplay != null && activeAutoplay.isActive) {
                    activeAutoplay.join()
                } else {
                    fetchAndAppendAutoplay(currentTrack, generation)
                }
                if (accountTransitionInProgress || generation != playbackGeneration.get()) return@launch
                queueManager.setBuffering(false)
                queueManager.next()
            }
        } else {
            queueManager.next()
        }
    }

    private suspend fun fetchAndAppendAutoplay(track: Track, generation: Long): Boolean {
        val result = withContext(Dispatchers.IO) {
            musicSource.related(track.videoId)
        }
        if (accountTransitionInProgress || generation != playbackGeneration.get()) return false
        if (result !is com.geekify.android.data.source.MusicResult.Success) return false
        val latestQueue = queueManager.state.value
        val latestUpcoming = latestQueue.queue.size - latestQueue.index - 1
        if (latestQueue.current?.videoId != track.videoId ||
            latestQueue.repeat != RepeatMode.OFF ||
            latestQueue.shuffle ||
            latestUpcoming >= 2
        ) {
            return latestUpcoming > 0
        }
        val queuedIds = latestQueue.queue.map { it.videoId }.toSet()
        val candidates = result.value.filter { it.videoId !in queuedIds }
        if (candidates.isEmpty()) return false
        val signals = resolveQueueRankingSignals()
        if (accountTransitionInProgress || generation != playbackGeneration.get()) return false
        val ranked = SmartQueueRanker.rankAsync(
            candidates = candidates,
            current = latestQueue.current ?: track,
            signals = signals,
            upcoming = latestQueue.queue.drop(latestQueue.index + 1),
            dispatcher = Dispatchers.Default
        )
        if (accountTransitionInProgress || generation != playbackGeneration.get()) return false
        if (ranked.isEmpty()) return false
        queueManager.appendRelated(
            ranked.map { it.track },
            ranked.associate { it.track.videoId to it.reason }
        )
        startPrefetch(track.videoId)
        return true
    }

    /**
     * Endless "Up next": whenever fewer than two songs remain after the current one, ask YouTube Music's
     * `next` (radio) endpoint for related songs and append them. Skipped for repeat and shuffle, where the
     * queue already loops by itself.
     */
    private fun startAutoplay(track: Track) {
        if (accountTransitionInProgress || !appSettings.state.value.autoplayRelated) return
        val generation = playbackGeneration.get()
        val s = queueManager.state.value
        val upcoming = s.queue.size - s.index - 1
        if (upcoming >= 2 || s.repeat != RepeatMode.OFF || s.shuffle) return
        autoplayJob?.cancel()
        autoplayJob = scope.launch {
            fetchAndAppendAutoplay(track, generation)
        }
    }

    private fun startListeningSession(track: Track) {
        activeListeningSessionId = UUID.randomUUID().toString()
        activeListeningTrack = track
        activeListeningStartedAt = System.currentTimeMillis()
        lastListeningUpdatedAt = activeListeningStartedAt
        activeListeningMs = 0L
        lastListeningCheckpointMs = 0L
        logged30sThreshold = false
        logged50PercentThreshold = false

        if (!appSettings.state.value.pauseListenHistory) {
            listeningPersistenceScope.launch {
                try {
                    recommendationLogRepository.recordOutcome(
                        track.videoId,
                        com.geekify.android.data.local.RecommendationOutcomeEvent.PLAYED
                    )
                } catch (_: Exception) {}
            }
        }
    }

    private fun checkpointListeningSession() {
        if (accountTransitionInProgress) return
        val sessionId = activeListeningSessionId ?: return
        val track = activeListeningTrack ?: return
        val listened = activeListeningMs
        if (listened <= 0L) return
        val duration = player.duration.takeIf { it > 0L } ?: (track.durationSeconds?.toLong()?.times(1_000L) ?: 0L)
        val record = ListeningSessionEntity(
            sessionId = sessionId,
            videoId = track.videoId,
            title = track.title,
            artist = track.artist,
            artists = track.artists,
            listenedMs = listened,
            durationMs = duration.coerceAtLeast(0L),
            startedAt = activeListeningStartedAt,
            updatedAt = nextListeningUpdatedAt(),
            completed = false,
            skipped = false
        )
        if (!appSettings.state.value.pauseListenHistory) {
            enqueueListeningPersistence(record, pushToCloud = false)
        }
    }

    /** Serial queue for session writes. The final checkpoint is appended after every earlier periodic one. */
    private fun enqueueListeningPersistence(
        record: ListeningSessionEntity,
        pushToCloud: Boolean
    ): Job {
        val previous = listeningPersistenceJob
        return listeningPersistenceScope.launch {
            previous?.join()
            try {
                listeningStatsRepository.save(record)
                if (pushToCloud) syncRepository.get().pushSession(record)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                timber.log.Timber.w(error, "Could not persist listening session ${record.sessionId}")
            }
        }.also { listeningPersistenceJob = it }
    }

    private fun finishListeningSession(completed: Boolean = false, skipped: Boolean = false): Job? {
        val sessionId = activeListeningSessionId ?: return null
        val track = activeListeningTrack
        var persistenceJob: Job? = null
        if (track != null && activeListeningMs > 0L) {
            val duration = player.duration.takeIf { it > 0L } ?: (track.durationSeconds?.toLong()?.times(1_000L) ?: 0L)
            val playThreshold = if (duration > 0L) minOf(30_000L, duration / 2L) else 30_000L
            val meaningfulCompletion = completed && activeListeningMs >= playThreshold
            val record = ListeningSessionEntity(
                sessionId = sessionId,
                videoId = track.videoId,
                title = track.title,
                artist = track.artist,
                artists = track.artists,
                listenedMs = activeListeningMs,
                durationMs = duration.coerceAtLeast(0L),
                startedAt = activeListeningStartedAt,
                updatedAt = nextListeningUpdatedAt(),
                completed = meaningfulCompletion,
                skipped = skipped && !meaningfulCompletion
            )
            if (!appSettings.state.value.pauseListenHistory) {
                listeningBehaviorManager.recordPlaybackEnd(
                    track = track,
                    listenedMs = activeListeningMs,
                    durationMs = duration.coerceAtLeast(0L),
                    isCompleted = meaningfulCompletion,
                    isSkipped = skipped && !meaningfulCompletion
                )
                invalidateQueueRankingCache()
                // This job waits for all previous periodic checkpoints, then writes and uploads the
                // final cumulative record. Account handoff joins it before clearing Room or changing UID.
                persistenceJob = enqueueListeningPersistence(record, pushToCloud = true)

                listeningPersistenceScope.launch {
                    try {
                        val event = when {
                            meaningfulCompletion -> com.geekify.android.data.local.RecommendationOutcomeEvent.COMPLETED
                            skipped && activeListeningMs < 10_000L -> com.geekify.android.data.local.RecommendationOutcomeEvent.SKIPPED_UNDER_10S
                            skipped && activeListeningMs < playThreshold -> com.geekify.android.data.local.RecommendationOutcomeEvent.SKIPPED_EARLY
                            else -> null
                        }
                        if (event != null) {
                            recommendationLogRepository.recordOutcome(
                                videoId = track.videoId,
                                event = event,
                                playDurationMs = activeListeningMs
                            )
                        }
                    } catch (_: Exception) {}
                }
            }
        }
        activeListeningSessionId = null
        activeListeningTrack = null
        activeListeningStartedAt = 0L
        lastListeningUpdatedAt = 0L
        activeListeningMs = 0L
        lastListeningCheckpointMs = 0L
        logged30sThreshold = false
        logged50PercentThreshold = false
        return persistenceJob
    }

    /**
     * Pauses and tears down the active Media3 session before the account's final sync. The queue
     * remains intact until SyncRepository confirms the backup; this way a failed backup does not
     * destroy the user's current queue.
     */
    private suspend fun pauseAndStopForAccountTransition() {
        withContext(Dispatchers.Main.immediate) {
            if (accountTransitionInProgress || !::player.isInitialized) return@withContext
            transitionWasPlaying = player.playWhenReady || player.isPlaying
            transitionOriginalVolume = player.volume
            accountTransitionInProgress = true
            playbackGeneration.incrementAndGet()
            // Mute before pausing/stopping so even a delayed renderer callback cannot leak audio
            // from the previous identity during the sync handoff.
            player.volume = 0f
            prefetchJob?.cancel()
            autoplayJob?.cancel()
            bufferingWatchdogJob?.cancel()
            loadJob?.cancel()
            retryCoordinator.cancelAll()
            // Do not cancel these writes: they belong to the old account and must finish before
            // its final backup snapshot. Their tail is joined below, before the UID changes.
            historyWriteJob?.join()
            historyWriteJob = null

            // Close the listening segment before player.pause() emits its own pause callback.
            val checkpointJob = finishListeningSession(skipped = true)
            // A normal pause may already have closed the active session while its final upload is
            // still running. Join the tail of the serialized persistence queue even in that case.
            val sessionPersistenceTail = listeningPersistenceJob
            player.playWhenReady = false
            player.pause()
            player.stop()
            player.clearMediaItems()
            queueManager.setPlaying(false)
            queueManager.setBuffering(false)
            queueManager.persistProgress()

            // Do not allow the next account to become active until this account's last session
            // checkpoint has reached Room and the existing cloud sync path. Keep the service
            // recoverable until SyncRepository confirms the account handoff.
            checkpointJob?.join()
            sessionPersistenceTail?.join()
        }
    }

    private suspend fun completeAccountTransition() {
        withContext(Dispatchers.Main.immediate) {
            if (!::player.isInitialized) return@withContext

            // Keep the service and its MediaSession alive. MainActivity starts PlaybackService once;
            // stopping the service here would leave the next account without a playback observer
            // until the process/activity is recreated. The queue has already been cleared by
            // SyncRepository, so this only releases the previous account's player state.
            accountTransitionInProgress = true
            playbackGeneration.incrementAndGet()
            transitionWasPlaying = false
            prefetchJob?.cancel(); prefetchJob = null
            autoplayJob?.cancel(); autoplayJob = null
            bufferingWatchdogJob?.cancel(); bufferingWatchdogJob = null
            loadJob?.cancel(); loadJob = null
            loadingId = null
            loadingGeneration = -1L
            retryCoordinator.cancelAll()

            player.volume = 0f
            player.playWhenReady = false
            player.pause()
            player.stop()
            player.clearMediaItems()
            queueManager.setPlaying(false)
            queueManager.setBuffering(false)
            playerActivelyPlaying.value = false
            placeholderShown = false
            lastPlayingVideoId = null
            lastPrefetchedVideoIds = emptyList()
            invalidateQueueRankingCache()
            listenedMs = 0L
            playCounted = false
            activeListeningSessionId = null
            activeListeningTrack = null
            activeListeningStartedAt = 0L
            lastListeningUpdatedAt = 0L
            activeListeningMs = 0L
            lastListeningCheckpointMs = 0L

            // Remove the stale playback notification, but keep the registered session available
            // for the next account's queue. Media3 will publish a fresh notification on playback.
            stopForeground(STOP_FOREGROUND_REMOVE)
            player.volume = queueManager.state.value.volume.coerceIn(0f, 1f)
            accountTransitionInProgress = false
            updateMediaNotificationButtons(
                liked = false,
                shuffle = queueManager.state.value.shuffle
            )
        }
    }

    private suspend fun abortAccountTransition() {
        withContext(Dispatchers.Main.immediate) {
            if (!::player.isInitialized) return@withContext
            playbackGeneration.incrementAndGet()
            accountTransitionInProgress = false
            player.volume = transitionOriginalVolume.coerceIn(0f, 1f)
            val shouldResume = transitionWasPlaying
            transitionWasPlaying = false
            val current = queueManager.state.value.current ?: return@withContext
            // The queue was intentionally retained until cloud backup succeeded. Restore the
            // previous account's item on abort; successful handoffs finalize and release the service.
            if (shouldResume) queueManager.setPlaying(true)
            if (player.currentMediaItem?.mediaId != current.videoId) {
                loadAndPlay(current, autoPlay = shouldResume)
            }
        }
    }

    private fun nextListeningUpdatedAt(): Long {
        val next = maxOf(System.currentTimeMillis(), lastListeningUpdatedAt + 1L)
        lastListeningUpdatedAt = next
        return next
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibraryService.MediaLibrarySession = mediaSession

    @OptIn(UnstableApi::class)
    override fun onTaskRemoved(rootIntent: Intent?) {
        queueManager.persistProgress()
        // Configurable task-removal behavior (defaults to true so dismissing Geekify from Recents stops playback).
        if (!appSettings.state.value.stopPlaybackOnTaskRemoved && player.isPlaying) {
            return
        }
        player.pause()
        queueManager.setPlaying(false)
        pauseAllPlayersAndStopSelf()
    }

    override fun onDestroy() {
        if (activeInstance === this) activeInstance = null
        unregisterConnectivityMonitor()
        retryCoordinator.cancelAll()
        finishListeningSession()
        // The final checkpoint runs in its own IO scope so it can outlive the service just long
        // enough to finish the durable Room/Firestore write. Cancel that scope immediately after
        // the final queued write completes to avoid leaking a service-owned coroutine scope.
        listeningPersistenceJob?.invokeOnCompletion { listeningPersistenceScope.cancel() }
            ?: listeningPersistenceScope.cancel()
        if (!accountTransitionInProgress) queueManager.persistProgress()
        progressJob?.cancel()
        bufferingWatchdogJob?.cancel()
        mediaSession.release()
        artworkLoader.close()
        player.release()
        scope.cancel()
        super.onDestroy()
    }
}
