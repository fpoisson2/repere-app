package ca.repere.wear

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters

class WearSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = if (runCatching { Api.flushPending(applicationContext) }.isSuccess) {
        Result.success()
    } else {
        Result.retry()
    }

    companion object {
        fun schedule(context: Context) {
            val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            val request = OneTimeWorkRequestBuilder<WearSyncWorker>().setConstraints(constraints).build()
            WorkManager.getInstance(context).enqueueUniqueWork("repere-wear-sync", ExistingWorkPolicy.REPLACE, request)
        }
    }
}
