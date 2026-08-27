package ca.repere.wear

import android.content.Context
import ca.repere.core.CredentialStore
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.time.OffsetDateTime
import java.time.ZoneId

object Api {
    private val queueMutex = Mutex()

    suspend fun state(context: Context): JSONObject {
        runCatching { flushPending(context) }
        // Send the watch's local time so the server buckets "today" in the user's timezone.
        val now = URLEncoder.encode(OffsetDateTime.now().toString(), "UTF-8")
        return networkCall(context, operation("/api/wear/state?now=$now", "GET", null))
    }

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

    private suspend fun networkCall(context: Context, request: JSONObject): JSONObject =
        runCatching { relayThroughPhone(context, request) }.getOrElse { direct(context, request) }

    private suspend fun relayThroughPhone(context: Context, request: JSONObject): JSONObject {
        val nodes = Wearable.getNodeClient(context).connectedNodes.await()
        if (nodes.isEmpty()) error("Téléphone non connecté")
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
            if (!envelope.optBoolean("ok")) error(envelope.optString("error", "Synchronisation impossible"))
            return JSONObject(envelope.optString("body", "{}"))
        } finally {
            client.removeListener(listener).await()
        }
    }

    private suspend fun direct(context: Context, request: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val credentials = CredentialStore(context)
        val server = credentials.server().trimEnd('/')
        val token = credentials.token()

        if (server.isBlank() || token.isBlank()) error("Configurez la montre depuis le téléphone")
        val connection = URL(server + request.getString("path")).openConnection() as HttpURLConnection
        connection.requestMethod = request.getString("method")
        connection.connectTimeout = 8_000
        connection.readTimeout = 8_000
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Authorization", "Bearer $token")
        connection.setRequestProperty("Idempotency-Key", request.getString("id"))
        request.optString("body").takeIf { it.isNotBlank() }?.let { body ->
            connection.doOutput = true
            connection.outputStream.use { it.write(body.toByteArray()) }
        }
        val status = connection.responseCode
        val text = (if (status in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (status !in 200..299) error(runCatching { JSONObject(text).optString("detail") }.getOrDefault("Erreur $status"))
        JSONObject(text.ifBlank { "{}" })
    }

    private const val REQUEST_PATH = "/repere/proxy/request"
    private const val RESPONSE_PREFIX = "/repere/proxy/response"
}
