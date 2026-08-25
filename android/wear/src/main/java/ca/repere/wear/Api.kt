package ca.repere.wear

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

object Api {
    suspend fun state(context: Context): JSONObject = call(context, "/api/wear/state", "GET", null)
    suspend fun start(context: Context, volume: Int, abv: Float, quantity: Int): JSONObject = call(context, "/api/wear/start", "POST", JSONObject().put("volume_ml", volume).put("abv_percent", abv).put("quantity", quantity))
    suspend fun finish(context: Context): JSONObject = call(context, "/api/wear/finish", "POST", JSONObject())
    private suspend fun call(context: Context, path: String, method: String, body: JSONObject?): JSONObject = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences("repere", Context.MODE_PRIVATE)
        val server = prefs.getString("server", "")!!.trimEnd('/'); val token = prefs.getString("token", "")!!
        if (server.isBlank() || token.isBlank()) error("Configurez la montre depuis le téléphone")
        val connection = URL(server + path).openConnection() as HttpURLConnection
        connection.requestMethod = method; connection.connectTimeout = 8000; connection.readTimeout = 8000
        connection.setRequestProperty("Content-Type", "application/json"); connection.setRequestProperty("Authorization", "Bearer $token")
        connection.setRequestProperty("Idempotency-Key", UUID.randomUUID().toString())
        if (body != null) { connection.doOutput = true; connection.outputStream.use { it.write(body.toString().toByteArray()) } }
        val text = (if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream).bufferedReader().readText()
        if (connection.responseCode !in 200..299) error(runCatching { JSONObject(text).optString("detail") }.getOrDefault("Erreur ${connection.responseCode}"))
        JSONObject(text.ifBlank { "{}" })
    }
}
