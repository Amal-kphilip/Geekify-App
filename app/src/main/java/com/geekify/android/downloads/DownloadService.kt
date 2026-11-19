package com.geekify.android.downloads

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.geekify.android.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber

/**
 * Foreground `DATA_SYNC` service that drains [SongDownloadManager]'s persistent Room queue
 * with live progress notifications and stops cleanly once idle.
 */
class DownloadService : Service(), KoinComponent {
    private val downloadManager: SongDownloadManager by inject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var drainJob: Job? = null
    @Volatile private var latestStartId: Int = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        ensureChannel()
        val notification = buildNotification("Preparing downloads...", 0, indeterminate = true)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (t: Throwable) {
            Timber.w(t, "DownloadService could not promote to foreground")
            stopSelfResult(startId)
            return START_NOT_STICKY
        }

        if (drainJob?.isActive != true) {
            drainJob = scope.launch {
                try {
                    downloadManager.requeueInterrupted()
                    val nm = getSystemService(NotificationManager::class.java)
                    while (downloadManager.processNext { title, pct ->
                            nm?.notify(
                                NOTIFICATION_ID,
                                buildNotification("Downloading $title", pct, indeterminate = pct <= 0)
                            )
                        }
                    ) {
                        // Continue draining queued items
                    }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    Timber.e(t, "DownloadService drain loop failed")
                } finally {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelfResult(latestStartId)
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        drainJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Offline Downloads",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    private fun buildNotification(text: String, progress: Int, indeterminate: Boolean) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_geekify)
            .setContentTitle("Geekify Downloads")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, progress.coerceIn(0, 100), indeterminate)
            .build()

    companion object {
        private const val CHANNEL_ID = "geekify_downloads"
        private const val NOTIFICATION_ID = 1042

        fun start(context: Context) {
            val intent = Intent(context, DownloadService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure { Timber.w(it, "Could not start DownloadService") }
        }
    }
}
