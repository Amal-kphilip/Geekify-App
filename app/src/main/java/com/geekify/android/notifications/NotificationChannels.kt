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
    /** New ID required for HIGH importance because Android locks a channel's importance after creation. */
    const val UPDATES = "geekify_app_updates"
    private const val LEGACY_UPDATES = "updates"
    const val TIPS = "geekify_tips_and_reminders"
    private const val LEGACY_TIPS = "tips"

    fun ensureAll(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(PLAYBACK, context.getString(R.string.channel_playback_name), NotificationManager.IMPORTANCE_LOW).apply {
                description = "Playback controls and the current song"
                setShowBadge(false)
            }
        )
        // Existing installs used "updates" at DEFAULT importance. A new channel ID is required
        // for heads-up behavior because Android locks importance after channel creation. Preserve
        // an existing explicit block/low importance choice instead of bypassing the user's choice.
        val legacyUpdates = nm.getNotificationChannel(LEGACY_UPDATES)
        val updatesImportance = when (legacyUpdates?.importance) {
            NotificationManager.IMPORTANCE_NONE -> NotificationManager.IMPORTANCE_NONE
            NotificationManager.IMPORTANCE_MIN -> NotificationManager.IMPORTANCE_MIN
            NotificationManager.IMPORTANCE_LOW -> NotificationManager.IMPORTANCE_LOW
            else -> NotificationManager.IMPORTANCE_HIGH
        }
        nm.createNotificationChannel(
            NotificationChannel(UPDATES, context.getString(R.string.channel_updates_name), updatesImportance).apply {
                description = "Tells you when a new version of Geekify is available"
                enableVibration(legacyUpdates?.shouldVibrate() ?: true)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                TIPS,
                context.getString(R.string.channel_tips_name),
                when (nm.getNotificationChannel(LEGACY_TIPS)?.importance) {
                    NotificationManager.IMPORTANCE_NONE -> NotificationManager.IMPORTANCE_NONE
                    NotificationManager.IMPORTANCE_MIN -> NotificationManager.IMPORTANCE_MIN
                    else -> NotificationManager.IMPORTANCE_DEFAULT
                }
            ).apply {
                description = "Occasional, useful feature and library reminders"
                setShowBadge(false)
            }
        )
    }
}
