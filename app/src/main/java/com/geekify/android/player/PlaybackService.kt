package com.geekify.android.player

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.net.Uri
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
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSessionService
import androidx.media3.session.CacheBitmapLoader
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.DefaultMediaNotificationProvider
import com.geekify.android.MainActivity
import com.geekify.android.audio.AudioEffectsController
import com.geekify.android.R
import com.geekify.android.data.model.Track
import com.geekify.android.notifications.NotificationChannels
import com.geekify.android.player.artwork.ArtworkUrls
import com.geekify.android.player.artwork.CoilBitmapLoader
import com.geekify.android.data.source.innertube.InnerTubeClient
import com.geekify.android.data.source.MusicSource
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import okhttp3.OkHttpClient
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import javax.inject.Inject

@OptIn(UnstableApi::class)
@AndroidEntryPoint
class PlaybackService : MediaSessionService() {

    companion object {
        private const val PLAYBACK_CHANNEL_ID = NotificationChannels.PLAYBACK
        private const val PLAYBACK_NOTIFICATION_ID = 1001
        /** Fresh-link attempts for one track before the error is shown. */
        private const val MAX_STREAM_RETRIES = 3
        /** Continuous playback after a retry that proves the new link works and refills the retry budget. */
        private const val RETRY_RESET_AFTER_MS = 15_000L
        /** A broken/stalled googlevideo stream must not leave the player buffering forever. */
        private const val BUFFERING_TIMEOUT_MS = 12_000L
        private const val ACTION_MEDIA_LIKE = "com.geekify.android.media.LIKE"
        private const val ACTION_MEDIA_SHUFFLE = "com.geekify.android.media.SHUFFLE"
    }

    @Inject lateinit var queueManager: QueueManager
    @Inject lateinit var streamResolver: InnerTubeXPlayer
    @Inject lateinit var audioEffects: AudioEffectsController
    @Inject lateinit var innerTube: InnerTubeClient
    @Inject lateinit var musicSource: MusicSource
    @Inject lateinit var historyRepository: com.geekify.android.data.local.HistoryRepository
    @Inject lateinit var libraryRepository: com.geekify.android.data.local.LibraryRepository
    @Inject lateinit var http: OkHttpClient
    @Inject lateinit var syncRepository: com.geekify.android.data.sync.SyncRepository

    private lateinit var player: ExoPlayer
    private lateinit var mediaSession: MediaSession
    private var mediaNotificationController: MediaSession.ControllerInfo? = null
    private var likedVideoIds: Set<String> = emptySet()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var prefetchJob: Job? = null
    private var autoplayJob: Job? = null
    private var progressJob: Job? = null
    private var bufferingWatchdogJob: Job? = null
    private var lastPlayingVideoId: String? = null
    private var resolveRetries = 0
    private var playedSinceRetryMs = 0L
    private var placeholderShown = false
    private lateinit var artworkLoader: CoilBitmapLoader
    private var listenedMs = 0L
    private var playCounted = false
    private var restoredPositionUsed = false

    override fun onCreate() {
        super.onCreate()

        createPlaybackNotificationChannel()

        // Load the YouTube config/visitor id in the background so the first song does not wait for it.
        scope.launch(Dispatchers.IO) { innerTube.warmUp() }

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

        val dataSourceFactory = OkHttpDataSource.Factory(http)
            // googlevideo checks that the URL is fetched with the same User-Agent as the client that issued it.
            .setUserAgent(InnerTubeClient.PLAYER_USER_AGENT)

        // The audio sink runs our equalizer and normaliser before ExoPlayer's own tempo / silence processors.
        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink? = DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .setAudioProcessors(audioEffects.processors)
                .build()
        }

        player = ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(1_500, 30_000, 750, 1_500)
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
        mediaSession = MediaSession.Builder(this, QueuePlayer(player))
            .setSessionActivity(pendingIntent)
            .setBitmapLoader(CacheBitmapLoader(artworkLoader))
            .setMediaButtonPreferences(mediaButtonPreferences(liked = false, shuffle = false))
            .setCallback(PlaybackSessionCallback())
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
        libraryRepository.liked
            .map { tracks -> tracks.asSequence().map { it.videoId }.toSet() }
            .distinctUntilChanged()
            .onEach { ids ->
                likedVideoIds = ids
                updateMediaNotificationButtons()
            }
            .launchIn(scope)

        // Tempo, pitch and silence skipping change on the running player (no re-initialisation).
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
                queueManager.setPlaying(playWhenReady)
                // Remember where a pause happened so reopening the app can offer to continue from here.
                if (!playWhenReady) queueManager.persistProgress()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    queueManager.setBuffering(false)
                    val current = queueManager.state.value.current ?: return
                    if (current.videoId != lastPlayingVideoId) {
                        lastPlayingVideoId = current.videoId
                        listenedMs = 0L
                        playCounted = false
                        scope.launch {
                            historyRepository.add(current)
                            // Plays are written locally first, so push them (debounced) to the cloud copy too.
                            syncRepository.schedulePush()
                        }
                        startPrefetch(current.videoId)
                        startAutoplay(current)
                    }
                }
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_BUFFERING -> {
                        queueManager.setBuffering(true)
                        startBufferingWatchdog()
                    }
                    Player.STATE_READY -> {
                        bufferingWatchdogJob?.cancel()
                        bufferingWatchdogJob = null
                        queueManager.setBuffering(false)
                    }
                    Player.STATE_ENDED -> {
                        bufferingWatchdogJob?.cancel()
                        bufferingWatchdogJob = null
                        // Forget the finished play so a replay (repeat, or play again) is recorded again.
                        lastPlayingVideoId = null
                        queueManager.next()
                    }
                    else -> Unit
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                bufferingWatchdogJob?.cancel()
                bufferingWatchdogJob = null
                queueManager.setBuffering(false)
                handlePlaybackError(error)
            }
        })

        // Periodically update progress in QueueManager for UI seekers
        progressJob = scope.launch {
            var tick = 0
            while (isActive) {
                if (player.isPlaying) {
                    val pos = player.currentPosition
                    val dur = player.duration.coerceAtLeast(0L)
                    queueManager.setProgress(pos, dur)

                    // A link that has played for a while is good: allow fresh-link retries again for later failures.
                    if (resolveRetries > 0) {
                        playedSinceRetryMs += 500
                        if (playedSinceRetryMs >= RETRY_RESET_AFTER_MS) { resolveRetries = 0; playedSinceRetryMs = 0L }
                    }

                    // Count a play only after a real listen (30 s, or half of a short song), not on a skip.
                    if (!playCounted) {
                        listenedMs += 500
                        val threshold = if (dur > 0L) minOf(30_000L, dur / 2) else 30_000L
                        val current = queueManager.state.value.current
                        if (current != null && current.videoId == lastPlayingVideoId && listenedMs >= threshold) {
                            playCounted = true
                            scope.launch { historyRepository.countPlay(current) }
                        }
                    }
                    if (++tick % 10 == 0) queueManager.persistProgress()
                }
                delay(500)
            }
        }

        // Listen for seek events from UI
        queueManager.seekEvents.onEach { posMs ->
            player.seekTo(posMs)
        }.launchIn(scope)

        // Observe queue changes and drive ExoPlayer
        queueManager.state.onEach { state ->
            if (player.volume != state.volume) player.volume = state.volume
            val track = state.current ?: return@onEach
            // Before the very first stream is prepared Media3 has no item, so it posts no notification and Android
            // could keep the service background-only. Promote the service with a one-off placeholder in that gap only.
            // Once the player has a media item Media3 owns notification id 1001 (it carries the artwork and the
            // controls); re-posting our own, artwork-less notification over it is what used to wipe the cover.
            if ((state.isPlaying || state.isBuffering) && !placeholderShown && player.currentMediaItem == null) {
                placeholderShown = true
                showForegroundPlaybackNotification(track)
            }
            if (player.currentMediaItem?.mediaId != track.videoId) {
                // After a restart the restored queue loads paused; only auto-play when the user asked to play.
                loadAndPlay(track, autoPlay = state.isPlaying)
            } else if (state.isPlaying && !player.isPlaying && player.playbackState != Player.STATE_BUFFERING) {
                // Repeat-one / replay after the end of the queue: an ended player must be rewound first.
                if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
                player.play()
            } else if (!state.isPlaying && player.playWhenReady) {
                player.pause()
            }
        }.launchIn(scope)
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

    private inner class PlaybackSessionCallback : MediaSession.Callback {
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
            scope.launch {
                try {
                    when (customCommand) {
                        likeCommand -> queueManager.state.value.current?.let { track ->
                            libraryRepository.toggleLike(track)
                        }
                        shuffleCommand -> queueManager.toggleShuffle()
                    }
                    updateMediaNotificationButtons()
                    result.set(SessionResult(SessionResult.RESULT_SUCCESS))
                } catch (_: Exception) {
                    result.set(SessionResult(SessionResult.RESULT_ERROR_UNKNOWN))
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

        override fun hasNextMediaItem(): Boolean = queueManager.state.value.hasNext
        override fun hasPreviousMediaItem(): Boolean = queueManager.state.value.queue.isNotEmpty()

        override fun seekToNext() { queueManager.next() }
        override fun seekToNextMediaItem() { queueManager.next() }

        // Same rule as the in-app button: past 3 s restarts the song, otherwise go to the previous one.
        override fun seekToPrevious() { queueManager.previous(currentPosition.coerceAtLeast(0L) / 1000) }
        override fun seekToPreviousMediaItem() { queueManager.previous(currentPosition.coerceAtLeast(0L) / 1000) }
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
    private fun buildMediaItem(track: Track, url: String, mimeType: String?): MediaItem =
        MediaItem.Builder()
            .setMediaId(track.videoId)
            .setUri(url)
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

    private fun loadAndPlay(track: Track, autoPlay: Boolean = true) {
        // State emissions (progress, volume...) must not restart a lookup that is already running.
        if (loadingId == track.videoId && loadJob?.isActive == true) return
        if (player.currentMediaItem?.mediaId == track.videoId) {
            if (autoPlay && !player.isPlaying) player.play()
            return
        }
        loadJob?.cancel()
        loadingId = track.videoId
        resolveRetries = 0
        lastPlayingVideoId = null
        // Only the very first load after the app starts is a restored queue; it resumes at the saved position.
        val pendingStart = queueManager.takePendingStart()
        val startMs = when {
            pendingStart > 0L -> pendingStart
            !restoredPositionUsed -> queueManager.state.value.progressMs.coerceAtLeast(0L)
            else -> 0L
        }
        restoredPositionUsed = true
        if (autoPlay) queueManager.setBuffering(true)
        loadJob = scope.launch {
            try {
                val stream = streamResolver.resolve(track.videoId)
                audioEffects.setTrackLoudness(stream.loudnessDb)
                val item = buildMediaItem(track, stream.url, stream.mimeType)
                player.setMediaItem(item, startMs)
                // The user may have pressed play/pause while the stream was being resolved.
                player.playWhenReady = queueManager.state.value.isPlaying
                player.prepare()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Show the reason instead of failing silently. Only move on to the next song when the
                // person was actually listening: a restored, paused queue must never start by itself.
                val wasPlaying = queueManager.state.value.isPlaying
                queueManager.setError(PlaybackErrors.message(e))
                if (wasPlaying) skipAfterError(track)
            } finally {
                if (loadingId == track.videoId) loadingId = null
            }
        }
    }

    private fun skipAfterError(failed: Track) {
        scope.launch {
            delay(3000)
            val s = queueManager.state.value
            // Only skip if the user has not already moved to another track.
            if (s.current?.videoId == failed.videoId && s.queue.size > 1 && s.hasNext) queueManager.next()
        }
    }

    private fun startBufferingWatchdog() {
        bufferingWatchdogJob?.cancel()
        val track = queueManager.state.value.current ?: return
        if (!queueManager.state.value.isPlaying) return
        val trackId = track.videoId
        bufferingWatchdogJob = scope.launch {
            delay(BUFFERING_TIMEOUT_MS)
            val state = queueManager.state.value
            if (state.current?.videoId != trackId || !state.isPlaying || player.playbackState != Player.STATE_BUFFERING) return@launch

            // A stream that never becomes READY is normally a stale/rejected googlevideo URL. Refresh it instead
            // of leaving the user on an endless loading animation. The normal retry/error path is reused.
            if (resolveRetries < MAX_STREAM_RETRIES) {
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
        if (resolveRetries >= MAX_STREAM_RETRIES) {
            queueManager.setBuffering(false)
            queueManager.setError("Could not start this track.")
            if (wasPlaying) skipAfterError(track)
            return
        }

        resolveRetries++
        playedSinceRetryMs = 0L
        val attempt = resolveRetries
        if (markFailed) streamResolver.markFailed(track.videoId) else streamResolver.invalidate(track.videoId)
        loadJob?.cancel()
        loadingId = null
        player.stop()
        queueManager.setBuffering(true)

        scope.launch {
            delay(400L * attempt)
            try {
                val stream = streamResolver.resolve(track.videoId, forceRefresh = true)
                if (queueManager.state.value.current?.videoId != track.videoId) return@launch
                audioEffects.setTrackLoudness(stream.loudnessDb)
                val item = buildMediaItem(track, stream.url, stream.mimeType)
                player.setMediaItem(item, resumeAt)
                player.playWhenReady = queueManager.state.value.isPlaying
                player.prepare()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                queueManager.setBuffering(false)
                queueManager.setError(PlaybackErrors.message(e))
                if (wasPlaying) skipAfterError(track)
            }
        }
    }

    private fun handlePlaybackError(error: PlaybackException) {
        val track = queueManager.state.value.current ?: return
        val wasPlaying = queueManager.state.value.isPlaying
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
                resumeAt = player.currentPosition.coerceAtLeast(0L),
                wasPlaying = wasPlaying,
                markFailed = httpCode == 403
            )
        } else {
            resolveRetries = 0
            playedSinceRetryMs = 0L
            queueManager.setError(PlaybackErrors.message(error))
            if (wasPlaying) skipAfterError(track)
        }
    }

    private fun startPrefetch(currentId: String) {
        prefetchJob?.cancel()
        prefetchJob = scope.launch {
            // No artificial delay: resolving a URL is a tiny request, and having the next songs
            // ready is what makes "next" and auto-advance start instantly.
            val s = queueManager.state.value
            val nextIds = buildList {
                s.queue.getOrNull(s.index + 1)?.videoId?.let { add(it) }
                s.queue.getOrNull(s.index + 2)?.videoId?.let { add(it) }
            }.filter { it != currentId }
            streamResolver.prefetch(nextIds)
        }
    }

    /**
     * Endless "Up next": whenever fewer than two songs remain after the current one, ask YouTube Music's
     * `next` (radio) endpoint for related songs and append them. Skipped for repeat and shuffle, where the
     * queue already loops by itself.
     */
    private fun startAutoplay(track: Track) {
        val s = queueManager.state.value
        val upcoming = s.queue.size - s.index - 1
        if (upcoming >= 2 || s.repeat != RepeatMode.OFF || s.shuffle) return
        autoplayJob?.cancel()
        autoplayJob = scope.launch {
            val result = musicSource.related(track.videoId)
            if (result is com.geekify.android.data.source.MusicResult.Success) {
                queueManager.appendRelated(result.value)
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = mediaSession

    override fun onDestroy() {
        queueManager.persistProgress()
        progressJob?.cancel()
        bufferingWatchdogJob?.cancel()
        mediaSession.release()
        artworkLoader.close()
        player.release()
        scope.cancel()
        super.onDestroy()
    }
}