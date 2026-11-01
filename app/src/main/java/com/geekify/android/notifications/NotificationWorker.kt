package com.geekify.android.notifications

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.android.EntryPointAccessors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Runs twice a day (every 12 hours). Network is only used inside [UpdateChecker], which has its own 12 h cache. */
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
        const val UNIQUE_NAME = "geekify_notification_check"
        const val KICK_NAME = "geekify_notification_kick"
        const val ENGAGEMENT_FOLLOWUP_NAME = "geekify_engagement_followup"
        const val SCHEDULE_INTERVAL_HOURS = 12L

        private val periodicEnqueued = AtomicBoolean(false)

        internal val networkConstraints: Constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        fun schedule(context: Context) {
            if (!periodicEnqueued.compareAndSet(false, true)) return
            val request = PeriodicWorkRequestBuilder<NotificationWorker>(SCHEDULE_INTERVAL_HOURS, TimeUnit.HOURS)
                .setConstraints(networkConstraints)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        /**
         * Starts a single coalesced notification check shortly after the app leaves the foreground,
         * plus a scheduled follow-up after the quiet window. Uses unique work policies so repeated
         * background transitions never stack duplicate workers.
         */
        fun kick(context: Context) {
            val wm = WorkManager.getInstance(context)
            val immediateRequest = OneTimeWorkRequestBuilder<NotificationWorker>()
                .setInitialDelay(3, TimeUnit.SECONDS)
                .build()
            wm.enqueueUniqueWork(
                KICK_NAME,
                ExistingWorkPolicy.KEEP,
                immediateRequest
            )

            val followupDelayMs = NotificationPolicy.ENGAGEMENT_QUIET_AFTER_OPEN_MS + 60_000L
            val followupRequest = OneTimeWorkRequestBuilder<NotificationWorker>()
                .setInitialDelay(followupDelayMs, TimeUnit.MILLISECONDS)
                .setConstraints(networkConstraints)
                .build()
            wm.enqueueUniqueWork(
                ENGAGEMENT_FOLLOWUP_NAME,
                ExistingWorkPolicy.REPLACE,
                followupRequest
            )
        }
    }
}
