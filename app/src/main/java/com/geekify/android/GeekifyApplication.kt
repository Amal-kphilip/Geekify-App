package com.geekify.android

import android.app.Application
import com.geekify.android.appicon.AppIconManager
import com.geekify.android.notifications.NotificationChannels
import com.geekify.android.notifications.NotificationWorker
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class GeekifyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppIconManager.ensureValidSelection(this)
        NotificationChannels.ensureAll(this)
        // KEEP policy: scheduling on every launch never duplicates or resets the existing periodic job.
        NotificationWorker.schedule(this)
    }
}
