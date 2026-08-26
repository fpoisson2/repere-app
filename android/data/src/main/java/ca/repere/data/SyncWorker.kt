package ca.repere.data

import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit

/** Entry point for durable synchronization. Network protocol wiring is kept in SyncRepository. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = runCatching {
        SyncRepository(applicationContext).synchronize()
        Result.success()
    }.getOrElse { Result.retry() }

    companion object {
        private const val UNIQUE_NAME = "repere-periodic-sync"
        fun schedule(context: Context) {
            val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            val request = PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request
            )
            WorkManager.getInstance(context).enqueueUniqueWork(
                "repere-immediate-sync", ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(constraints).build()
            )
        }
    }
}
