package com.geekify.android

import android.app.Application
import android.util.Log
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.geekify.android.appicon.AppIconManager
import com.geekify.android.notifications.NotificationChannels
import com.geekify.android.notifications.NotificationWorker
import dagger.hilt.android.HiltAndroidApp
import okhttp3.OkHttpClient
import timber.log.Timber
import javax.inject.Inject

@HiltAndroidApp
class GeekifyApplication : Application(), SingletonImageLoader.Factory {

    @Inject
    lateinit var okHttpClient: OkHttpClient

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        return ImageLoader.Builder(context)
            .components {
                add(
                    OkHttpNetworkFetcherFactory(
                        callFactory = { okHttpClient }
                    )
                )
            }
            .memoryCache {
                MemoryCache.Builder()
                    // Cap at 20% of max heap to prevent OOM when loading high-resolution album covers
                    .maxSizePercent(context, percent = 0.20)
                    .strongReferencesEnabled(true)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("image_cache"))
                    .maxSizeBytes(150L * 1024 * 1024) // 150 MB disk cache limit
                    .build()
            }
            .crossfade(true)
            .build()
    }

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
