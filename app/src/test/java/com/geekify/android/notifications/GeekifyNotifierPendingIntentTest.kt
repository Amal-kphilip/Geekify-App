package com.geekify.android.notifications

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Verifies that [GeekifyNotifier] builds explicit, immutable [PendingIntent]s for all
 * notification actions and intents.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class GeekifyNotifierPendingIntentTest {

    private val app: Application get() = RuntimeEnvironment.getApplication()

    private fun registerFakeBrowser(packageName: String = "com.example.browser") {
        val probe = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))
        val resolveInfo = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                this.packageName = packageName
                name = "$packageName.BrowserActivity"
            }
        }
        @Suppress("DEPRECATION")
        shadowOf(app.packageManager).addResolveInfoForIntent(probe, resolveInfo)
    }

    @Test
    fun `resolveDefaultBrowserPackage returns registered browser package`() {
        registerFakeBrowser("com.example.browser")
        val pkg = GeekifyNotifier.resolveDefaultBrowserPackage(app)
        assertEquals("com.example.browser", pkg)
    }

    @Test
    fun `postUpdate notification PendingIntents are explicit and immutable`() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        registerFakeBrowser("com.example.browser")

        val notifier = GeekifyNotifier(app)
        val posted = notifier.postUpdate(
            ReleaseInfo(
                versionName = "9.9.9",
                versionCode = 90909,
                pageUrl = "https://github.com/Amal-kphilip/Geekify-App/releases/tag/v9.9.9"
            )
        )
        assertTrue("Expected postUpdate to post the notification", posted)

        val nm = app.getSystemService(NotificationManager::class.java)
        val notification = shadowOf(nm).getNotification(GeekifyNotifier.UPDATE_NOTIFICATION_ID)
        assertNotNull("Expected notification to be present", notification)

        // 1. contentIntent (openApp -> MainActivity)
        val contentPi = shadowOf(notification.contentIntent)
        assertNotNull(contentPi.savedIntent.component)
        assertTrue((contentPi.flags and PendingIntent.FLAG_IMMUTABLE) != 0)

        // 2. deleteIntent (dismissIntent -> NotificationActionReceiver)
        val deletePi = shadowOf(notification.deleteIntent)
        assertNotNull(deletePi.savedIntent.component)
        assertTrue((deletePi.flags and PendingIntent.FLAG_IMMUTABLE) != 0)

        // 3. "What's new" action (openRelease -> browser package-scoped ACTION_VIEW)
        val whatsNewPi = shadowOf(notification.actions[0].actionIntent)
        assertEquals("com.example.browser", whatsNewPi.savedIntent.`package`)
        assertTrue((whatsNewPi.flags and PendingIntent.FLAG_IMMUTABLE) != 0)

        // 4. "Later" action (dismissIntent -> NotificationActionReceiver)
        val laterPi = shadowOf(notification.actions[1].actionIntent)
        assertNotNull(laterPi.savedIntent.component)
        assertTrue((laterPi.flags and PendingIntent.FLAG_IMMUTABLE) != 0)
    }
}
