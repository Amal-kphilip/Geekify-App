package com.geekify.android.notifications

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

/**
 * Immediate update-notification path, modeled on Morphe Manager.
 *
 * The FCM payload is a data-only hint published by Geekify's release workflow. The payload is
 * checked against the same numeric version-code scheme as ReleaseParser; the notification tap
 * performs a fresh GitHub release check before showing Geekify's existing update dialog.
 */
@AndroidEntryPoint
class GeekifyUpdateMessagingService : FirebaseMessagingService() {

    @Inject lateinit var coordinator: NotificationCoordinator

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        if (data[KEY_TYPE] != TYPE_APP_UPDATE) return

        val versionName = data[KEY_VERSION]
            ?.trim()
            ?.removePrefix("v")
            ?.takeIf { it.isNotEmpty() && '-' !in it }
            ?: return
        val versionCode = ReleaseParser.versionCodeFor(versionName) ?: return
        val announcedCode = data[KEY_VERSION_CODE]?.toIntOrNull()
        if (announcedCode != null && announcedCode != versionCode) {
            Log.w(TAG, "Ignoring update push with mismatched version metadata")
            return
        }

        val release = ReleaseInfo(
            versionName = versionName,
            versionCode = versionCode,
            pageUrl = "$RELEASES_URL/tag/v$versionName"
        )

        // FirebaseMessagingService invokes this callback on a background thread. Unlike a
        // BroadcastReceiver, it does not expose goAsync(); bridge the short local suspend work
        // here so notification processing finishes before the callback returns.
        try {
            runBlocking(Dispatchers.IO) {
                coordinator.handlePushedRelease(release)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.e(TAG, "Failed to handle release update push", error)
        }
    }

    private companion object {
        const val TAG = "GeekifyUpdatePush"
        const val KEY_TYPE = "type"
        const val KEY_VERSION = "version"
        const val KEY_VERSION_CODE = "version_code"
        const val TYPE_APP_UPDATE = "app_update"
        const val RELEASES_URL = "https://github.com/Amal-kphilip/Geekify-App/releases"
    }
}
