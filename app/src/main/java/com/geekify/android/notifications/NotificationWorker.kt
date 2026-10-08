package com.geekify.android.notifications

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.NetworkType
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.android.EntryPointAccessors
import java.util.concurrent.TimeUnit

/** Runs about twice a day. Network is only used inside [UpdateChecker], which has its own 12 h cache. */
class NotificationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val entry = EntryPointAccessors.fromApplication(applicationContext, NotificationEntryPoint::class.java)
        return try {
            entry.coordinator().runOnce()
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            Result.success() // a failed pass just waits for the next one; no tight retry loop
        }
    }

    companion object {
        private const val UNIQUE_NAME = "geekify_notification_check"

        private const val KICK_NAME = "geekify_notification_kick"

        private val networkConstraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<NotificationWorker>(12, TimeUnit.HOURS)
                .setConstraints(networkConstraints)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }

        /** Starts a small, persisted notification check after the app leaves the foreground. */
        fun kick(context: Context) {
            val request = OneTimeWorkRequestBuilder<NotificationWorker>()
                .setConstraints(networkConstraints)
                .setInitialDelay(3, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                KICK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }
}
