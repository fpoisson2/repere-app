package ca.repere.wear

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

class WearSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = if (runCatching { Api.flushPending(applicationContext) }.isSuccess) {
        Result.success()
    } else {
        Result.retry()
    }

    companion object {
        fun schedule(context: Context) {
            // The relay only needs a Data Layer connection to the phone; Internet is not required.
            val request = OneTimeWorkRequestBuilder<WearSyncWorker>()
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build()
            WorkManager.getInstance(context).enqueueUniqueWork("repere-wear-sync", ExistingWorkPolicy.REPLACE, request)
        }
    }
}
