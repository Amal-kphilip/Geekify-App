package com.geekify.android

import android.app.Application
import android.util.Log
import com.geekify.android.appicon.AppIconManager
import com.geekify.android.notifications.NotificationChannels
import com.geekify.android.notifications.NotificationWorker
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber

@HiltAndroidApp
class GeekifyApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        } else {
            Timber.plant(ReleaseLoggingTree())
        }

        AppIconManager.ensureValidSelection(this)
        NotificationChannels.ensureAll(this)
        // KEEP policy: scheduling on every launch never duplicates or resets the existing periodic job.
        NotificationWorker.schedule(this)
        com.geekify.android.domain.recommend.RecommendationPruneWorker.schedule(this)
    }

    private class ReleaseLoggingTree : Timber.Tree() {
        override fun isLoggable(tag: String?, priority: Int): Boolean {
            // Strip VERBOSE and DEBUG logs in production to safeguard user privacy and reduce overhead
            return priority >= Log.INFO
        }

        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            if (!isLoggable(tag, priority)) return
            val effectiveTag = tag ?: "Geekify"
            if (priority >= Log.WARN) {
                Log.println(priority, effectiveTag, message)
                if (t != null) {
                    Log.println(priority, effectiveTag, Log.getStackTraceString(t))
                }
            }
        }
    }
}
