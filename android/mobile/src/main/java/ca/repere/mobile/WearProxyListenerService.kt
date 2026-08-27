package ca.repere.mobile

import ca.repere.core.CredentialStore
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONObject

/** Relays authenticated Wear requests through the paired phone's network connection. */
class WearProxyListenerService : WearableListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != REQUEST_PATH) return
        val sourceNodeId = event.sourceNodeId
        scope.launch {
            val request = runCatching { JSONObject(event.data.decodeToString()) }.getOrElse { return@launch }
            val requestId = request.optString("id")
            val response = runCatching { forward(request) }.fold(
                onSuccess = { JSONObject().put("ok", true).put("body", it) },
                onFailure = { JSONObject().put("ok", false).put("error", it.message ?: "Connexion impossible") }
            )
            runCatching {
                Wearable.getMessageClient(this@WearProxyListenerService)
                    .sendMessage(sourceNodeId, "$RESPONSE_PREFIX/$requestId", response.toString().toByteArray()).await()
            }
        }
    }

    private fun forward(request: JSONObject): String {
        val credentials = CredentialStore(this)
        val server = credentials.server(BuildConfig.DEFAULT_SERVER_URL).trimEnd('/')
        val token = credentials.token()
        if (token.isBlank()) error("Associez d'abord l'application mobile à Répère")
        val connection = URL(server + request.getString("path")).openConnection() as HttpURLConnection
        connection.requestMethod = request.getString("method")
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
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
        return text.ifBlank { "{}" }
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
