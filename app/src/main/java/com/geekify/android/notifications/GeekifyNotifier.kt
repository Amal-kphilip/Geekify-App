package com.geekify.android.notifications

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.geekify.android.MainActivity
import com.geekify.android.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Builds and posts the update and tip notifications. Never touches the media playback notification. */
@Singleton
class GeekifyNotifier @Inject constructor(@ApplicationContext private val context: Context) {
    companion object {
        const val UPDATE_NOTIFICATION_ID = 2001
        const val TIP_NOTIFICATION_ID = 2002
        const val ACTION_DISMISS_UPDATE = "com.geekify.android.action.DISMISS_UPDATE"
        const val EXTRA_VERSION_CODE = "version_code"
        const val EXTRA_ROUTE = "geekify_route"
    }

    private val nm get() = NotificationManagerCompat.from(context)

    /** Respects the runtime permission, the app-level switch and the individual channel's block. */
    fun canPost(channelId: String): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        if (!nm.areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannels.ensureAll(context)
            val channel = nm.getNotificationChannel(channelId)
            if (channel != null && channel.importance == NotificationManager.IMPORTANCE_NONE) return false
        }
        return true
    }

    /** @return true if the notification was actually handed to the system. */
    fun postUpdate(info: ReleaseInfo): Boolean {
        if (!canPost(NotificationChannels.UPDATES)) return false
        val openRelease = PendingIntent.getActivity(
            context, 10,
            Intent(Intent.ACTION_VIEW, Uri.parse(info.pageUrl)),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val dismiss = dismissIntent(info.versionCode, requestCode = 11)
        val n = NotificationCompat.Builder(context, NotificationChannels.UPDATES)
            .setSmallIcon(R.drawable.ic_stat_geekify)
            .setContentTitle("Geekify v${info.versionName} is available")
            .setContentText("New playback improvements, fixes, and features are ready.")
            .setContentIntent(openApp(route = null, requestCode = 12)) // opens Geekify, whose update sheet downloads and installs
            .setDeleteIntent(dismiss) // swiping it away counts as "Later"
            .addAction(0, context.getString(R.string.notif_action_update), openRelease)
            .addAction(0, context.getString(R.string.notif_action_later), dismiss)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .build()
        return notifySafely(UPDATE_NOTIFICATION_ID, n)
    }

    fun postTip(title: String, text: String, route: String?): Boolean {
        if (!canPost(NotificationChannels.TIPS)) return false
        val n = NotificationCompat.Builder(context, NotificationChannels.TIPS)
            .setSmallIcon(R.drawable.ic_stat_geekify)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openApp(route, requestCode = 13))
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .build()
        return notifySafely(TIP_NOTIFICATION_ID, n)
    }

    fun cancelUpdate() = nm.cancel(UPDATE_NOTIFICATION_ID)

    private fun notifySafely(id: Int, n: android.app.Notification): Boolean = try {
        @Suppress("MissingPermission") // checked in canPost()
        nm.notify(id, n)
        true
    } catch (_: SecurityException) {
        false
    }

    private fun openApp(route: String?, requestCode: Int): PendingIntent = PendingIntent.getActivity(
        context, requestCode,
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            route?.let { putExtra(EXTRA_ROUTE, it) }
        },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun dismissIntent(versionCode: Int, requestCode: Int): PendingIntent = PendingIntent.getBroadcast(
        context, requestCode,
        Intent(context, NotificationActionReceiver::class.java).apply {
            action = ACTION_DISMISS_UPDATE
            putExtra(EXTRA_VERSION_CODE, versionCode)
        },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )
}
