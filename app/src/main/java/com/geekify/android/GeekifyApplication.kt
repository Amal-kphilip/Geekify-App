package com.geekify.android

import android.app.Application
import com.geekify.android.notifications.NotificationChannels
import com.geekify.android.notifications.NotificationWorker
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class GeekifyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        NotificationChannels.ensureAll(this)
        // KEEP policy: scheduling on every launch never duplicates or resets the existing periodic job.
        NotificationWorker.schedule(this)
    }
}
