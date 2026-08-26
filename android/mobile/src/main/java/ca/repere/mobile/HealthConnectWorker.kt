package ca.repere.mobile

import android.content.Context
import androidx.health.connect.client.permission.HealthPermission
import androidx.work.*
import ca.repere.core.CredentialStore
import ca.repere.data.HealthLocalRepository
import org.json.JSONArray
import java.time.LocalDate
import java.util.concurrent.TimeUnit

class HealthConnectWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params) {
    override suspend fun doWork():Result {
        val bridge=HealthConnectBridge(applicationContext)
        if(!bridge.backgroundAvailable())return Result.success()
        if(HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND !in bridge.granted())return Result.success()
        return runCatching {
            val all=JSONArray();repeat(3){offset ->
                val rows=bridge.aggregateDay(LocalDate.now().minusDays(offset.toLong()))
                for(index in 0 until rows.length())all.put(rows.getJSONObject(index))
            }
            val local=HealthLocalRepository(applicationContext);local.store(all)
            val credentials=CredentialStore(applicationContext);val server=credentials.server();val token=credentials.token()
            if(credentials.syncEnabled()&&server.isNotBlank()&&token.isNotBlank()){
                val (pending,ids)=local.pending();if(pending.length()>0){Api.postArray(server,token,"/api/health-connect/aggregates",pending);local.markSynced(ids)}
            }
            Result.success()
        }.getOrElse{Result.retry()}
    }

    companion object {
        private const val NAME="repere-health-connect-sync"
        fun enable(context:Context){
            val constraints=Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build()
            val request=PeriodicWorkRequestBuilder<HealthConnectWorker>(12,TimeUnit.HOURS).setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME,ExistingPeriodicWorkPolicy.KEEP,request)
        }
        fun disable(context:Context)=WorkManager.getInstance(context).cancelUniqueWork(NAME)
    }
}
