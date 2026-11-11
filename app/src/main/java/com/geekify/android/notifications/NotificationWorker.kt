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
            val coordinator = entry.coordinator()
            coordinator.syncTopicSubscription { updatesEnabled ->
                UpdatePushSubscription.sync(
                    context = applicationContext,
                    enabled = updatesEnabled,
                    scheduleFallbackOnFailure = false
                )
            }
            coordinator.runOnce()
            coordinator.nextFollowupDelayMs()?.let { delayMs ->
                scheduleFollowup(applicationContext, delayMs)
            }
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
        const val TOPIC_SYNC_FALLBACK_NAME = "geekify_fcm_topic_sync"
        const val SCHEDULE_INTERVAL_HOURS = 12L

        private val periodicEnqueued = AtomicBoolean(false)

        internal val networkConstraints: Constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        fun schedule(context: Context) {
            if (!periodicEnqueued.compareAndSet(false, true)) return
            val request = PeriodicWorkRequestBuilder<NotificationWorker>(SCHEDULE_INTERVAL_HOURS, TimeUnit.HOURS)
                .setInitialDelay(1, TimeUnit.HOURS)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }

        fun cancelKick(context: Context) {
            runCatching {
                WorkManager.getInstance(context).cancelUniqueWork(KICK_NAME)
            }
        }

        fun scheduleFollowup(context: Context, delayMs: Long) {
            val safeDelayMs = delayMs.coerceAtLeast(60_000L)
            val followupRequest = OneTimeWorkRequestBuilder<NotificationWorker>()
                .setInitialDelay(safeDelayMs, TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                ENGAGEMENT_FOLLOWUP_NAME,
                ExistingWorkPolicy.REPLACE,
                followupRequest
            )
        }

        fun scheduleTopicSyncFallback(context: Context) {
            runCatching {
                val request = OneTimeWorkRequestBuilder<NotificationWorker>()
                    .setConstraints(networkConstraints)
                    .setInitialDelay(15, TimeUnit.SECONDS)
                    .build()
                WorkManager.getInstance(context).enqueueUniqueWork(
                    TOPIC_SYNC_FALLBACK_NAME,
                    ExistingWorkPolicy.KEEP,
                    request
                )
            }
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
                ExistingWorkPolicy.REPLACE,
                immediateRequest
            )

            val followupDelayMs = NotificationPolicy.ENGAGEMENT_QUIET_AFTER_OPEN_MS + 60_000L
            scheduleFollowup(context, followupDelayMs)
        }
    }
}
