package com.geekify.android.notifications

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import com.geekify.android.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Verifies that [GeekifyNotifier] builds explicit, immutable [PendingIntent]s for all
 * notification actions and intents, and that [MainActivity.handleReleaseUrlExtra] only
 * launches `https` URLs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class GeekifyNotifierPendingIntentTest {

    private val app: Application get() = RuntimeEnvironment.getApplication()

    @Test
    fun `postUpdate notification PendingIntents target explicit components and are immutable`() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        val pageUrl = "https://github.com/Amal-kphilip/Geekify-App/releases/tag/v9.9.9"
        val notifier = GeekifyNotifier(app)
        val posted = notifier.postUpdate(
            ReleaseInfo(
                versionName = "9.9.9",
                versionCode = 90909,
                pageUrl = pageUrl
            )
        )
        assertTrue("Expected postUpdate to post the notification", posted)

        val nm = app.getSystemService(NotificationManager::class.java)
        val notification = shadowOf(nm).getNotification(GeekifyNotifier.UPDATE_NOTIFICATION_ID)
        assertNotNull("Expected notification to be present", notification)

        // 1. contentIntent (openApp -> MainActivity)
        val contentPi = shadowOf(notification.contentIntent)
        assertEquals(MainActivity::class.java.name, contentPi.savedIntent.component?.className)
        assertTrue((contentPi.flags and PendingIntent.FLAG_IMMUTABLE) != 0)
        assertTrue(contentPi.savedIntent.getBooleanExtra(GeekifyNotifier.EXTRA_TRIGGER_UPDATE_CHECK, false))

        // 2. deleteIntent (dismissIntent -> NotificationActionReceiver)
        val deletePi = shadowOf(notification.deleteIntent)
        assertEquals(NotificationActionReceiver::class.java.name, deletePi.savedIntent.component?.className)
        assertTrue((deletePi.flags and PendingIntent.FLAG_IMMUTABLE) != 0)

        // 3. "What's new" action (openRelease -> MainActivity with EXTRA_OPEN_RELEASE_URL)
        val whatsNewPi = shadowOf(notification.actions[0].actionIntent)
        assertEquals(MainActivity::class.java.name, whatsNewPi.savedIntent.component?.className)
        assertEquals(pageUrl, whatsNewPi.savedIntent.getStringExtra(GeekifyNotifier.EXTRA_OPEN_RELEASE_URL))
        assertTrue((whatsNewPi.flags and PendingIntent.FLAG_IMMUTABLE) != 0)

        // 4. "Later" action (dismissIntent -> NotificationActionReceiver)
        val laterPi = shadowOf(notification.actions[1].actionIntent)
        assertEquals(NotificationActionReceiver::class.java.name, laterPi.savedIntent.component?.className)
        assertTrue((laterPi.flags and PendingIntent.FLAG_IMMUTABLE) != 0)
    }

    @Test
    fun `MainActivity handleReleaseUrlExtra opens https URL once and removes extra`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        val pageUrl = "https://github.com/Amal-kphilip/Geekify-App/releases/tag/v9.9.9"
        val intent = Intent(app, MainActivity::class.java).apply {
            putExtra(GeekifyNotifier.EXTRA_OPEN_RELEASE_URL, pageUrl)
        }

        assertTrue(activity.handleReleaseUrlExtra(intent))
        val started = shadowOf(activity).nextStartedActivity
        assertNotNull("Expected ACTION_VIEW activity to be started", started)
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals(pageUrl, started.dataString)
        assertFalse("Extra should be removed after handling", intent.hasExtra(GeekifyNotifier.EXTRA_OPEN_RELEASE_URL))

        // Second call (e.g. rotation/relaunch) should not reopen
        assertFalse(activity.handleReleaseUrlExtra(intent))
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test
    fun `MainActivity handleReleaseUrlExtra ignores http, javascript, malformed, and null URLs`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()

        val disallowed = listOf(
            "http://github.com/Amal-kphilip/Geekify-App/releases",
            "javascript:alert(1)",
            "https://",
            "not a url",
            "",
            null
        )

        for (candidate in disallowed) {
            val intent = Intent(app, MainActivity::class.java).apply {
                if (candidate != null) {
                    putExtra(GeekifyNotifier.EXTRA_OPEN_RELEASE_URL, candidate)
                }
            }
            assertFalse("Expected '$candidate' to be ignored", activity.handleReleaseUrlExtra(intent))
            assertNull("Expected no activity started for '$candidate'", shadowOf(activity).nextStartedActivity)
            assertFalse(intent.hasExtra(GeekifyNotifier.EXTRA_OPEN_RELEASE_URL))
        }
    }
}
