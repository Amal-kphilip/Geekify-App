package com.geekify.android.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.geekify.android.R

/**
 * The only three notification channels Geekify has. Playback is never used for anything promotional;
 * Updates and Tips are separate so users can mute either from Android settings without touching playback.
 * Creating an existing channel is a no-op, so this is safe to call from anywhere, any number of times.
 */
object NotificationChannels {
    /** Kept as "playback" so existing installs keep the user's channel settings. */
    const val PLAYBACK = "playback"
    const val UPDATES = "updates"
    const val TIPS = "tips"

    fun ensureAll(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(PLAYBACK, context.getString(R.string.channel_playback_name), NotificationManager.IMPORTANCE_LOW).apply {
                description = "Playback controls and the current song"
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(UPDATES, context.getString(R.string.channel_updates_name), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Tells you when a new version of Geekify is available"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(TIPS, context.getString(R.string.channel_tips_name), NotificationManager.IMPORTANCE_LOW).apply {
                description = "Occasional, useful feature and library reminders"
                setShowBadge(false)
            }
        )
    }
}
