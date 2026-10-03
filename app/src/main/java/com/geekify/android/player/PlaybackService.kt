package com.geekify.android.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.DefaultMediaNotificationProvider
import com.geekify.android.MainActivity
import com.geekify.android.R
import com.geekify.android.data.model.Track
import com.geekify.android.ui.components.bestArtworkUrl
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import javax.inject.Inject

@OptIn(UnstableApi::class)
@AndroidEntryPoint
class PlaybackService : MediaSessionService() {

    companion object {
        private const val PLAYBACK_CHANNEL_ID = "playback"
        private const val PLAYBACK_NOTIFICATION_ID = 1001
    }

    @Inject lateinit var queueManager: QueueManager
    @Inject lateinit var streamResolver: StreamResolver
    @Inject lateinit var innerTube: InnerTubeClient
    @Inject lateinit var musicSource: MusicSource
    @Inject lateinit var historyRepository: com.geekify.android.data.local.HistoryRepository
    @Inject lateinit var http: OkHttpClient

    private lateinit var player: ExoPlayer
    private lateinit var mediaSession: MediaSession
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var prefetchJob: Job? = null
    private var autoplayJob: Job? = null
    private var progressJob: Job? = null
    private var lastPlayingVideoId: String? = null
    private var resolveRetries = 0
    private var foregroundShownFor: String? = null

    override fun onCreate() {
        super.onCreate()

        createPlaybackNotificationChannel()

        // Load the YouTube config/visitor id in the background so the first song does not wait for it.
        scope.launch(Dispatchers.IO) { innerTube.warmUp() }

        // On current Samsung/Android releases a concrete provider and app icon make
        // the foreground media notification reliable when playback leaves the app.
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider(this).apply {
                setSmallIcon(R.mipmap.ic_launcher)
            }
        )

        val dataSourceFactory = OkHttpDataSource.Factory(http)
            // googlevideo checks that the URL is fetched with the same User-Agent as the client that issued it.
            .setUserAgent(InnerTubeClient.PLAYER_USER_AGENT)

        player = ExoPlayer.Builder(this)
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
        mediaSession = MediaSession.Builder(this, QueuePlayer(player))
            .setSessionActivity(pendingIntent)
            .build()

        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                queueManager.setPlaying(isPlaying)
                if (isPlaying) {
                    queueManager.setBuffering(false)
                    val current = queueManager.state.value.current ?: return
                    if (current.videoId != lastPlayingVideoId) {
                        lastPlayingVideoId = current.videoId
                        scope.launch { historyRepository.add(current) }
                        startPrefetch(current.videoId)
                        startAutoplay(current)
                    }
                }
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_BUFFERING -> queueManager.setBuffering(true)
                    Player.STATE_READY -> queueManager.setBuffering(false)
                    Player.STATE_ENDED -> queueManager.next()
                    else -> Unit
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                queueManager.setBuffering(false)
                handlePlaybackError(error)
            }
        })

        // Periodically update progress in QueueManager for UI seekers
        progressJob = scope.launch {
            while (isActive) {
                if (player.isPlaying) {
                    val pos = player.currentPosition
                    val dur = player.duration.coerceAtLeast(0L)
                    queueManager.setProgress(pos, dur)
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
            // Promote immediately when the user starts a song. Resolving a stream can take a
            // few seconds, during which Media3's automatic notification has no prepared item
            // yet. Without this, Android can keep the service background-only after the app is
            // minimized and no media card reaches the notification drawer.
            // Once per song is enough to promote the service. Re-posting it on every progress update
            // (twice a second) kept overwriting Media3's own notification, which carries the controls.
            if (state.isPlaying || state.isBuffering) {
                if (foregroundShownFor != track.videoId) {
                    foregroundShownFor = track.videoId
                    showForegroundPlaybackNotification(track)
                }
            } else {
                foregroundShownFor = null
            }
            if (player.currentMediaItem?.mediaId != track.videoId) {
                // After a restart the restored queue loads paused; only auto-play when the user asked to play.
                loadAndPlay(track, autoPlay = state.isPlaying)
            } else if (state.isPlaying && !player.isPlaying && player.playbackState != Player.STATE_BUFFERING) {
                player.play()
            } else if (!state.isPlaying && player.isPlaying) {
                player.pause()
            }
        }.launchIn(scope)
    }

    /**
     * ExoPlayer only ever holds the current song; the real queue lives in [QueueManager]. Without
     * this wrapper the notification / lock screen / headset see a one-item playlist: no "next", and
     * "previous" just restarts the song. This routes them to the real queue instead.
     */
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

    private fun createPlaybackNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            PLAYBACK_CHANNEL_ID,
            "Music playback",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Playback controls and current song"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

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
            .setSmallIcon(R.mipmap.ic_launcher)
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
        if (autoPlay) queueManager.setBuffering(true)
        loadJob = scope.launch {
            try {
                val stream = streamResolver.resolve(track.videoId)
                val item = MediaItem.Builder()
                    .setMediaId(track.videoId)
                    .setUri(stream.url)
                    .setMimeType(stream.mimeType)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(track.title)
                            .setArtist(track.artist)
                            .setAlbumTitle(track.album)
                            .setArtworkUri(track.thumbnails.bestArtworkUrl()?.let(Uri::parse))
                            .build()
                    )
                    .build()
                player.setMediaItem(item)
                player.playWhenReady = autoPlay
                player.prepare()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Show the reason instead of failing silently, then move on after a moment.
                queueManager.setError(e.message ?: "This track could not be played.")
                skipAfterError(track)
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

    private fun handlePlaybackError(error: PlaybackException) {
        val track = queueManager.state.value.current ?: return
        val urlProblem = error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
        if (urlProblem && resolveRetries < 1) {
            // Expired or rejected URL: get a fresh one once and try again from the same position.
            resolveRetries++
            val resumeAt = player.currentPosition.coerceAtLeast(0L)
            streamResolver.invalidate(track.videoId)
            loadJob?.cancel()
            loadingId = null
            scope.launch {
                delay(400)
                try {
                    val stream = streamResolver.resolve(track.videoId, forceRefresh = true)
                    val item = MediaItem.Builder()
                        .setMediaId(track.videoId)
                        .setUri(stream.url)
                        .setMimeType(stream.mimeType)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(track.title)
                            .setArtist(track.artist)
                            .setAlbumTitle(track.album)
                            .setArtworkUri(track.thumbnails.bestArtworkUrl()?.let(Uri::parse))
                            .build()
                    )
                        .build()
                    player.setMediaItem(item, resumeAt)
                    player.playWhenReady = true
                    player.prepare()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    queueManager.setError(e.message ?: "This track could not be played.")
                    skipAfterError(track)
                }
            }
        } else {
            resolveRetries = 0
            queueManager.setError("Playback failed (${error.errorCodeName}). Check your connection and try again.")
            skipAfterError(track)
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

    private fun startAutoplay(track: Track) {
        if (queueManager.state.value.queue.size > 1) return
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
        progressJob?.cancel()
        mediaSession.release()
        player.release()
        super.onDestroy()
    }
}
