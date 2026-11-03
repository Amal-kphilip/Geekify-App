package com.geekify.android.domain.recommend

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.geekify.android.data.local.RecommendationLogRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import timber.log.Timber

@EntryPoint
@InstallIn(SingletonComponent::class)
interface RecommendationPruneEntryPoint {
    fun recommendationLogRepository(): RecommendationLogRepository
}

/**
 * Background worker to enforce the 30-day retention policy on recommendation impressions
 * and their cascading outcomes. Scheduled on app start.
 */
class RecommendationPruneWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val entry = EntryPointAccessors.fromApplication(applicationContext, RecommendationPruneEntryPoint::class.java)
            val repo = entry.recommendationLogRepository()
            val thirtyDaysAgo = System.currentTimeMillis() - THIRTY_DAYS_MS
            val pruned = repo.pruneOlderThan(thirtyDaysAgo)
            Timber.d("RecommendationPruneWorker: pruned $pruned stale recommendation impressions")
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "RecommendationPruneWorker failed")
            Result.failure()
        }
    }

    companion object {
        const val UNIQUE_NAME = "geekify_recommendation_prune"
        const val THIRTY_DAYS_MS = 30L * 24 * 60 * 60 * 1000L

        fun schedule(context: Context) {
            val request = OneTimeWorkRequestBuilder<RecommendationPruneWorker>().build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_NAME,
                ExistingWorkPolicy.KEEP,
                request
            )
        }
    }
}
