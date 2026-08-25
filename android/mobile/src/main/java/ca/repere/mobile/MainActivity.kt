package ca.repere.mobile

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = lightColorScheme(primary = androidx.compose.ui.graphics.Color(0xFF176F55))) { RepereMobile(this) } }
    }
}

@Composable
private fun RepereMobile(context: Context) {
    val prefs = remember { context.getSharedPreferences("repere", Context.MODE_PRIVATE) }
    var server by remember { mutableStateOf(prefs.getString("server", "http://192.168.1.151") ?: "") }
    var code by remember { mutableStateOf("") }
    var token by remember { mutableStateOf(prefs.getString("token", "") ?: "") }
    var message by remember { mutableStateOf(if (token.isBlank()) "Non associé" else "Associé") }
    var active by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun syncToWatch() = scope.launch {
        val request = PutDataMapRequest.create("/repere/config").apply {
            dataMap.putString("server", server.trimEnd('/'))
            dataMap.putString("token", token)
            dataMap.putLong("updated", System.currentTimeMillis())
        }.asPutDataRequest().setUrgent()
        runCatching { Wearable.getDataClient(context).putDataItem(request).await() }
    }
    LaunchedEffect(token) {
        if (token.isNotBlank()) active = runCatching { Api.get(server, token, "/api/wear/state").optJSONObject("active") != null }.getOrDefault(false)
    }
    Scaffold { padding ->
        Column(Modifier.padding(padding).padding(20.dp).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Repère", style = MaterialTheme.typography.headlineLarge)
            Text("Téléphone et montre", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(server, { server = it }, label = { Text("Adresse du serveur") }, modifier = Modifier.fillMaxWidth())
            if (token.isBlank()) {
                OutlinedTextField(code, { code = it.filter(Char::isDigit).take(6) }, label = { Text("Code à 6 chiffres") }, modifier = Modifier.fillMaxWidth())
                Button(onClick = { scope.launch {
                    message = "Association…"
                    runCatching {
                        val result = Api.post(server, "", "/api/wear/pair", JSONObject().put("code", code).put("device_name", "Téléphone Android"))
                        token = result.getString("token")
                        prefs.edit().putString("server", server.trimEnd('/')).putString("token", token).apply()
                        syncToWatch(); message = "Associé et transmis à la montre"
                    }.onFailure { message = it.message ?: "Association impossible" }
                } }, enabled = code.length == 6) { Text("Associer") }
            } else {
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(18.dp)) {
                    Text(if (active) "Consommation en cours" else "Aucune consommation en cours", style = MaterialTheme.typography.titleLarge)
                    Button(onClick = { scope.launch {
                        val path = if (active) "/api/wear/finish" else "/api/wear/start"
                        runCatching { Api.post(server, token, path, JSONObject().apply { if (!active) { put("volume_ml", 473); put("abv_percent", 5); put("quantity", 1) } }) }
                            .onSuccess { active = !active; message = if (active) "Début enregistré" else "Fin enregistrée" }
                            .onFailure { message = it.message ?: "Erreur" }
                    } }, modifier = Modifier.fillMaxWidth()) { Text(if (active) "Terminer maintenant" else "Démarrer maintenant") }
                } }
                OutlinedButton(onClick = { syncToWatch(); message = "Configuration envoyée à la montre" }) { Text("Synchroniser la montre") }
                TextButton(onClick = { token = ""; prefs.edit().clear().apply(); message = "Association retirée" }) { Text("Dissocier ce téléphone") }
            }
            Text(message)
        }
    }
}

private object Api {
    suspend fun get(server: String, token: String, path: String) = request(server, token, path, "GET", null)
    suspend fun post(server: String, token: String, path: String, body: JSONObject) = request(server, token, path, "POST", body)
    private suspend fun request(server: String, token: String, path: String, method: String, body: JSONObject?): JSONObject = withContext(Dispatchers.IO) {
        val connection = URL(server.trimEnd('/') + path).openConnection() as HttpURLConnection
        connection.requestMethod = method; connection.connectTimeout = 8000; connection.readTimeout = 8000
        connection.setRequestProperty("Content-Type", "application/json")
        if (token.isNotBlank()) connection.setRequestProperty("Authorization", "Bearer $token")
        if (body != null) { connection.doOutput = true; connection.outputStream.use { it.write(body.toString().toByteArray()) } }
        val text = (if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream).bufferedReader().readText()
        if (connection.responseCode !in 200..299) error(JSONObject(text).optString("detail", "Erreur ${connection.responseCode}"))
        JSONObject(text.ifBlank { "{}" })
    }
}
