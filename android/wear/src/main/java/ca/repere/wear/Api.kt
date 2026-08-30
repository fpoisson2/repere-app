package ca.repere.wear

import android.content.Context
import ca.repere.core.CredentialStore
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.time.OffsetDateTime
import java.time.ZoneId

object Api {
    private val queueMutex = Mutex()

    suspend fun start(context: Context, volume: Int, abv: Float): JSONObject =
        sendOrQueue(context, operation("/api/wear/start", "POST", JSONObject().put("volume_ml", volume).put("abv_percent", abv).put("quantity", 1).put("started_at", OffsetDateTime.now().toString()).put("timezone_id", ZoneId.systemDefault().id).put("utc_offset_minutes", OffsetDateTime.now().offset.totalSeconds / 60)))

    suspend fun finish(context: Context): JSONObject =
        sendOrQueue(context, operation("/api/wear/finish", "POST", JSONObject().put("ended_at", OffsetDateTime.now().toString())))

    private suspend fun sendOrQueue(context: Context, current: JSONObject): JSONObject {
        return runCatching {
            flushPending(context)
            networkCall(context, current)
        }.getOrElse {
            enqueue(context, current)
            WearSyncWorker.schedule(context)
            JSONObject().put("queued", true)
        }
    }

    suspend fun flushPending(context: Context) = queueMutex.withLock {
        val credentials = CredentialStore(context)
        val queue = readQueue(credentials)
        while (queue.length() > 0) {
            networkCall(context, queue.getJSONObject(0))
            queue.remove(0)
            credentials.setPendingWearOperations(queue.toString())
        }
    }

    private suspend fun enqueue(context: Context, operation: JSONObject) = queueMutex.withLock {
        val credentials = CredentialStore(context)
        val queue = readQueue(credentials).put(operation)
        credentials.setPendingWearOperations(queue.toString())
    }

    private fun readQueue(credentials: CredentialStore): JSONArray =
        runCatching { JSONArray(credentials.pendingWearOperations()) }.getOrDefault(JSONArray())

    private fun operation(path: String, method: String, body: JSONObject?) = JSONObject()
        .put("id", UUID.randomUUID().toString())
        .put("path", path)
        .put("method", method)
        .put("body", body?.toString().orEmpty())

    private suspend fun networkCall(context: Context, request: JSONObject): JSONObject = relayThroughPhone(context,request)

    private suspend fun relayThroughPhone(context: Context, request: JSONObject): JSONObject {
        val nodes = Wearable.getNodeClient(context).connectedNodes.await()
        if (nodes.isEmpty()) error(context.getString(R.string.wear_phone_not_connected))
        val client = Wearable.getMessageClient(context)
        val responsePath = "$RESPONSE_PREFIX/${request.getString("id")}" 
        val response = CompletableDeferred<JSONObject>()
        val listener = MessageClient.OnMessageReceivedListener { event: MessageEvent ->
            if (event.path == responsePath && !response.isCompleted) {
                runCatching { JSONObject(event.data.decodeToString()) }.onSuccess { response.complete(it) }
            }
        }
        client.addListener(listener).await()
        try {
            client.sendMessage(nodes.first().id, REQUEST_PATH, request.toString().toByteArray()).await()
            val envelope = withTimeout(12_000) { response.await() }
            if (!envelope.optBoolean("ok")) error(envelope.optString("error", context.getString(R.string.wear_sync_failed)))
            return JSONObject(envelope.optString("body", "{}"))
        } finally {
            client.removeListener(listener).await()
        }
    }

    private const val REQUEST_PATH = "/repere/proxy/request"
    private const val RESPONSE_PREFIX = "/repere/proxy/response"
}
