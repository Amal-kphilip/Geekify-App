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
import com.geekify.android.di.allAppModules
import com.geekify.android.notifications.NotificationChannels
import com.geekify.android.notifications.NotificationWorker
import okhttp3.OkHttpClient
import org.koin.android.ext.koin.androidContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import timber.log.Timber

class GeekifyApplication : Application(), SingletonImageLoader.Factory, KoinComponent {

    private val okHttpClient: OkHttpClient by inject()

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

        // Guard isolated subprocesses (e.g. :potoken WebView minter) from initializing main-process Koin/DB/WorkManager.
        val currentProcess = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            runCatching { Application.getProcessName() }.getOrNull().orEmpty()
        } else {
            ""
        }
        if (currentProcess.endsWith(":potoken")) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                runCatching { android.webkit.WebView.setDataDirectorySuffix("potoken") }
            }
            return
        }

        if (GlobalContext.getOrNull() == null) {
            startKoin {
                androidContext(this@GeekifyApplication)
                modules(allAppModules)
            }
        }

        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
            android.os.StrictMode.setThreadPolicy(
                android.os.StrictMode.ThreadPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .build()
            )
            android.os.StrictMode.setVmPolicy(
                android.os.StrictMode.VmPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .build()
            )
        } else {
            Timber.plant(ReleaseLoggingTree())
        }

        AppIconManager.ensureValidSelection(this)
        NotificationChannels.ensureAll(this)
        // KEEP policy: scheduling on every launch never duplicates or resets the existing periodic job.
        runCatching { NotificationWorker.schedule(this) }
        runCatching { com.geekify.android.domain.recommend.RecommendationPruneWorker.schedule(this) }
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
