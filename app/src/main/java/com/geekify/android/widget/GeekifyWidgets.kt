package com.geekify.android.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.size.Size
import coil3.toBitmap
import com.geekify.android.MainActivity
import com.geekify.android.R
import com.geekify.android.notifications.GeekifyNotifier
import com.geekify.android.player.PlaybackService
import com.geekify.android.player.QueueManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.context.GlobalContext

/**
 * Adaptive Now Playing home-screen AppWidget showing current track artwork, title, artist,
 * and play/pause, previous, and next controls. Survives process death via [QueueManager.awaitLoaded].
 */
class GeekifyMusicWidgetProvider : AppWidgetProvider(), KoinComponent {
    private val queueManager: QueueManager by inject()

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        if (GlobalContext.getOrNull() == null) return
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        widgetScope.launch {
            try {
                runCatching { queueManager.awaitLoaded() }
                val state = runCatching { queueManager.state.value }.getOrNull()
                val current = state?.current
                val title = current?.title ?: "Geekify"
                val artist = current?.artist ?: "Tap to start listening"
                val isPlaying = state?.isPlaying == true
                val thumbUrl = current?.thumbnails?.lastOrNull()?.url
                val bitmap = loadArtworkBitmap(appContext, thumbUrl)
                val views = buildRemoteViews(appContext, title, artist, isPlaying, bitmap)
                for (id in appWidgetIds) {
                    appWidgetManager.updateAppWidget(id, views)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private val widgetScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        @Volatile private var cachedArtUrl: String? = null
        @Volatile private var cachedArtBitmap: Bitmap? = null

        fun refreshAll(context: Context) {
            val appContext = context.applicationContext
            val mgr = AppWidgetManager.getInstance(appContext)
            val ids = mgr.getAppWidgetIds(ComponentName(appContext, GeekifyMusicWidgetProvider::class.java))
            if (ids.isEmpty()) return
            val intent = Intent(appContext, GeekifyMusicWidgetProvider::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            }
            appContext.sendBroadcast(intent)
        }

        fun updateAll(
            context: Context,
            title: String,
            artist: String,
            isPlaying: Boolean,
            thumbnailUrl: String? = null
        ) {
            val appContext = context.applicationContext
            val mgr = AppWidgetManager.getInstance(appContext)
            val ids = mgr.getAppWidgetIds(ComponentName(appContext, GeekifyMusicWidgetProvider::class.java))
            if (ids.isEmpty()) return

            // Push immediate text/state update first
            val currentBitmap = if (thumbnailUrl != null && thumbnailUrl == cachedArtUrl) cachedArtBitmap else null
            val immediateViews = buildRemoteViews(appContext, title, artist, isPlaying, currentBitmap)
            for (id in ids) {
                mgr.updateAppWidget(id, immediateViews)
            }

            // Load artwork asynchronously if URL changed
            if (!thumbnailUrl.isNullOrBlank() && thumbnailUrl != cachedArtUrl) {
                widgetScope.launch {
                    val bmp = loadArtworkBitmap(appContext, thumbnailUrl)
                    if (bmp != null) {
                        cachedArtUrl = thumbnailUrl
                        cachedArtBitmap = bmp
                        val artViews = buildRemoteViews(appContext, title, artist, isPlaying, bmp)
                        for (id in ids) {
                            mgr.updateAppWidget(id, artViews)
                        }
                    }
                }
            }
        }

        private suspend fun loadArtworkBitmap(context: Context, url: String?): Bitmap? {
            if (url.isNullOrBlank()) return null
            if (url == cachedArtUrl && cachedArtBitmap != null) return cachedArtBitmap
            return runCatching {
                val request = ImageRequest.Builder(context)
                    .data(url)
                    .size(Size(192, 192))
                    .allowHardware(false)
                    .build()
                SingletonImageLoader.get(context).execute(request).image?.toBitmap()
            }.getOrNull()
        }

        private fun buildRemoteViews(
            context: Context,
            title: String,
            artist: String,
            isPlaying: Boolean,
            artwork: Bitmap?
        ): RemoteViews {
            val widgetCust = runCatching {
                GlobalContext.getOrNull()?.get<com.geekify.android.settings.AppSettingsRepository>()
                    ?.state?.value?.customization?.widgets
            }.getOrNull() ?: com.geekify.android.customization.WidgetCustomizationConfig()

            val views = RemoteViews(context.packageName, R.layout.widget_music_player)
            views.setInt(R.id.widget_root, "setBackgroundColor", widgetCust.backgroundStyle.argbHex)
            views.setTextViewText(R.id.widget_title, title)
            views.setTextViewText(R.id.widget_artist, artist)
            views.setViewVisibility(
                R.id.widget_artist,
                if (widgetCust.showArtist) android.view.View.VISIBLE else android.view.View.GONE
            )
            views.setViewVisibility(
                R.id.widget_artwork,
                if (widgetCust.showArtwork) android.view.View.VISIBLE else android.view.View.GONE
            )
            val prevNextVis = if (widgetCust.showPrevNext) android.view.View.VISIBLE else android.view.View.GONE
            views.setViewVisibility(R.id.widget_prev, prevNextVis)
            views.setViewVisibility(R.id.widget_next, prevNextVis)

            views.setTextViewText(R.id.widget_play_pause, if (isPlaying) "❚❚" else "▶")
            if (artwork != null) {
                views.setImageViewBitmap(R.id.widget_artwork, artwork)
            } else {
                views.setImageViewResource(R.id.widget_artwork, R.mipmap.ic_launcher_round)
            }

            val openIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            views.setOnClickPendingIntent(
                R.id.widget_root,
                PendingIntent.getActivity(context, 7001, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            )
            views.setOnClickPendingIntent(R.id.widget_prev, actionPendingIntent(context, WidgetActionReceiver.ACTION_PREV, 7002))
            views.setOnClickPendingIntent(R.id.widget_play_pause, actionPendingIntent(context, WidgetActionReceiver.ACTION_TOGGLE_PLAY, 7003))
            views.setOnClickPendingIntent(R.id.widget_next, actionPendingIntent(context, WidgetActionReceiver.ACTION_NEXT, 7004))
            return views
        }

        private fun actionPendingIntent(context: Context, action: String, reqCode: Int): PendingIntent {
            val intent = Intent(context, WidgetActionReceiver::class.java).apply { this.action = action }
            return PendingIntent.getBroadcast(
                context,
                reqCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }
}

/**
 * 1-tap home-screen AppWidget that launches Geekify directly to Shazam Music Recognition.
 */
class GeekifyRecognizerWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(GeekifyNotifier.EXTRA_ROUTE, "recognition")
        }
        val pi = PendingIntent.getActivity(
            context,
            7010,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        for (id in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.widget_recognizer)
            views.setOnClickPendingIntent(R.id.widget_recognizer_root, pi)
            appWidgetManager.updateAppWidget(id, views)
        }
    }
}

/**
 * Routes widget transport actions (Play/Pause, Next, Previous) into [QueueManager] and wakes
 * [PlaybackService] even after app process death.
 */
class WidgetActionReceiver : BroadcastReceiver(), KoinComponent {
    private val queueManager: QueueManager by inject()
    private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onReceive(context: Context, intent: Intent) {
        if (GlobalContext.getOrNull() == null) return
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        receiverScope.launch {
            try {
                // Ensure persisted queue is restored from DataStore if the app process just cold-started
                queueManager.awaitLoaded()
                when (intent.action) {
                    ACTION_TOGGLE_PLAY -> queueManager.togglePlay()
                    ACTION_NEXT -> queueManager.next()
                    ACTION_PREV -> queueManager.previous(queueManager.position.value.positionMs / 1000L)
                }
                val state = queueManager.state.value
                // If playback should now be active and PlaybackService is not running, start it as a foreground service
                if (state.current != null && (state.isPlaying || intent.action == ACTION_NEXT || intent.action == ACTION_PREV)) {
                    runCatching {
                        ContextCompat.startForegroundService(appContext, Intent(appContext, PlaybackService::class.java))
                    }
                }
                GeekifyMusicWidgetProvider.updateAll(
                    context = appContext,
                    title = state.current?.title ?: "Geekify",
                    artist = state.current?.artist ?: "Tap to start listening",
                    isPlaying = state.isPlaying,
                    thumbnailUrl = state.current?.thumbnails?.lastOrNull()?.url
                )
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_TOGGLE_PLAY = "com.geekify.android.widget.TOGGLE_PLAY"
        const val ACTION_NEXT = "com.geekify.android.widget.NEXT"
        const val ACTION_PREV = "com.geekify.android.widget.PREV"
    }
}
