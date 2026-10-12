package com.geekify.android.notifications

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessaging

/**
 * Keeps the app's opt-in "Updates" preference aligned with the FCM topic subscription.
 *
 * FCM is the fast path on devices with Google Play services. Geekify's existing WorkManager
 * check remains the fallback if FCM is unavailable or subscription fails. Do not subscribe while
 * notifications are blocked by Android, since high-priority pushes must produce visible alerts.
 */
object UpdatePushSubscription {
    const val TOPIC = "geekify_updates"
    private const val TAG = "GeekifyUpdatePush"

    fun sync(context: Context, enabled: Boolean) {
        val shouldSubscribe = enabled && systemCanShowUpdates(context)
        runCatching {
            val messaging = FirebaseMessaging.getInstance()
            val task = if (shouldSubscribe) {
                messaging.subscribeToTopic(TOPIC)
            } else {
                messaging.unsubscribeFromTopic(TOPIC)
            }
            task.addOnFailureListener { error ->
                Log.w(TAG, "Could not ${if (shouldSubscribe) "subscribe to" else "unsubscribe from"} update topic", error)
            }
        }.onFailure { error ->
            // In particular, do not block app startup on non-GMS devices. WorkManager remains available.
            Log.w(TAG, "FCM update topic sync is unavailable", error)
        }
    }

    private fun systemCanShowUpdates(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        val notifications = NotificationManagerCompat.from(context)
        if (!notifications.areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannels.ensureAll(context)
            val channel = notifications.getNotificationChannel(NotificationChannels.UPDATES)
            if (channel?.importance == NotificationManager.IMPORTANCE_NONE) return false
        }
        return true
    }
}
