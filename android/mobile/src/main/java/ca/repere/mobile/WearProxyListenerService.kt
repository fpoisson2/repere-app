package ca.repere.mobile

import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import ca.repere.data.SyncRepository
import ca.repere.data.SyncWorker
import ca.repere.data.WearStatePublisher

/** Relays authenticated Wear requests through the paired phone's network connection. */
class WearProxyListenerService : WearableListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != REQUEST_PATH) return
        val sourceNodeId = event.sourceNodeId
        scope.launch {
            val request = runCatching { JSONObject(event.data.decodeToString()) }.getOrElse { return@launch }
            val requestId = request.optString("id")
            val response = runCatching { handle(request) }.fold(
                onSuccess = { JSONObject().put("ok", true).put("body", it) },
                onFailure = { JSONObject().put("ok", false).put("error", it.message ?: "Connexion impossible") }
            )
            runCatching {
                Wearable.getMessageClient(this@WearProxyListenerService)
                    .sendMessage(sourceNodeId, "$RESPONSE_PREFIX/$requestId", response.toString().toByteArray()).await()
            }
        }
    }

    private suspend fun handle(request:JSONObject):String {
        val path=request.getString("path");if(path!="/api/wear/start"&&path!="/api/wear/finish")error("Commande Wear inconnue")
        val repository=SyncRepository(this);val body=JSONObject(request.optString("body").ifBlank{"{}"})
        if(path=="/api/wear/start")repository.startFromWear(request.getString("id"),body.optDouble("volume_ml",473.0),body.optDouble("abv_percent",5.0),body.getString("started_at"))
        else repository.finishFromWear(body.getString("ended_at"))
        SyncWorker.schedule(this);WearStatePublisher.publish(this,repository.localDrinks(),repository.localSettings())
        return JSONObject().put("stored_locally",true).toString()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val REQUEST_PATH = "/repere/proxy/request"
        const val RESPONSE_PREFIX = "/repere/proxy/response"
    }
}
