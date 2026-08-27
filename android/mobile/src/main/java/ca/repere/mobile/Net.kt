package ca.repere.mobile

import android.content.Context
import ca.repere.core.CredentialStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

internal fun JSONObject.doubleOrNull(key: String): Double? =
    if (!has(key) || isNull(key)) null else optDouble(key)

/** Authenticated JSON reads against the Repère server, with one transparent token refresh on 401. */
object Net {
    class HttpError(val code: Int, message: String) : Exception(message)
    private val background=CoroutineScope(SupervisorJob()+Dispatchers.IO)

    suspend fun json(context: Context, path: String): JSONObject =
        JSONObject(text(context, path).ifBlank { "{}" })

    suspend fun array(context: Context, path: String): JSONArray =
        JSONArray(text(context, path).ifBlank { "[]" })

    suspend fun text(context: Context, path: String): String = withContext(Dispatchers.IO) {
        val creds = CredentialStore(context)
        val cached=creds.cachedApi(path)
        if(cached.isNotBlank()){
            background.launch { runCatching { authenticatedGet(context,path) }.onSuccess { creds.cacheApi(path,it) };flush(context) }
            return@withContext cached
        }
        authenticatedGet(context,path).also{creds.cacheApi(path,it)}
    }

    private suspend fun authenticatedGet(context:Context,path:String):String {
        val creds = CredentialStore(context)
        return try {
            get(creds.server().trimEnd('/'), creds.token(), path)
        } catch (e: HttpError) {
            if (e.code == 401 && OAuthClient.refresh(context)) {
                get(CredentialStore(context).server().trimEnd('/'), CredentialStore(context).token(), path)
            } else throw e
        }
    }

    suspend fun send(context: Context, path: String, body: JSONObject, method: String = "POST"): JSONObject = withContext(Dispatchers.IO) {
        val creds = CredentialStore(context)
        val operation=JSONObject().put("id",java.util.UUID.randomUUID().toString()).put("path",path).put("method",method).put("body",body)
        val queue=runCatching{JSONArray(creds.pendingApiOperations())}.getOrDefault(JSONArray());queue.put(operation);creds.setPendingApiOperations(queue.toString())
        updateOptimisticCache(creds,path,method,body);background.launch{flush(context)}
        JSONObject(body.toString()).put("id",-System.currentTimeMillis())
    }

    private fun updateOptimisticCache(creds:CredentialStore,path:String,method:String,body:JSONObject){
        if(path=="/api/goals"&&method=="POST"){
            val key="/api/goals";val rows=runCatching{JSONArray(creds.cachedApi(key))}.getOrDefault(JSONArray());rows.put(JSONObject(body.toString()).put("id",-System.currentTimeMillis()).put("active",true));creds.cacheApi(key,rows.toString())
        } else if(path.startsWith("/api/goals/")&&method=="DELETE"){
            val id=path.substringAfterLast('/').toIntOrNull();val key="/api/goals";val old=runCatching{JSONArray(creds.cachedApi(key))}.getOrDefault(JSONArray());val rows=JSONArray();for(i in 0 until old.length())if(old.optJSONObject(i)?.optInt("id")!=id)rows.put(old.get(i));creds.cacheApi(key,rows.toString())
        } else if(path=="/api/check-ins"&&method=="POST"){
            val day=body.optString("local_date");if(day.isNotBlank())creds.cacheApi("/api/check-ins?start=$day&end=$day",JSONArray().put(body).toString())
        } else if(path=="/api/days/sober"&&method=="POST"){
            val day=body.optString("date");if(day.isNotBlank())creds.cacheApi("/api/days?start=$day&end=$day",JSONArray().put(JSONObject().put("date",day).put("status","sober").put("observed",true)).toString())
        } else if(path.startsWith("/api/days/sober/")&&method=="DELETE"){
            val day=path.substringAfterLast('/');creds.cacheApi("/api/days?start=$day&end=$day",JSONArray().put(JSONObject().put("date",day).put("status","no_data").put("observed",false)).toString())
        }
    }

    suspend fun flush(context:Context)=withContext(Dispatchers.IO){
        val creds=CredentialStore(context);val queue=runCatching{JSONArray(creds.pendingApiOperations())}.getOrDefault(JSONArray());if(queue.length()==0)return@withContext
        val remaining=JSONArray()
        for(i in 0 until queue.length()){
            val op=queue.getJSONObject(i);val sent=runCatching{write(creds.server().trimEnd('/'),creds.token(),op.getString("path"),op.getJSONObject("body"),op.getString("method"))}.isSuccess
            if(!sent)remaining.put(op)
        }
        creds.setPendingApiOperations(remaining.toString())
    }

    private fun write(server: String, token: String, path: String, body: JSONObject, method: String): String {
        if (server.isBlank() || token.isBlank()) throw HttpError(401, "Non connecté")
        val connection = URL(server + path).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 10_000
        connection.readTimeout = 20_000
        connection.doOutput = true
        connection.setRequestProperty("Authorization", "Bearer $token")
        connection.setRequestProperty("Content-Type", "application/json")
        connection.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = connection.responseCode
        val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) {
            val detail = runCatching { JSONObject(text).optString("detail") }.getOrNull()
            throw HttpError(code, detail?.takeIf { it.isNotBlank() } ?: "Erreur $code")
        }
        return text
    }

    private fun get(server: String, token: String, path: String): String {
        if (server.isBlank() || token.isBlank()) throw HttpError(401, "Non connecté")
        val connection = URL(server + path).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 10_000
        connection.readTimeout = 20_000
        connection.setRequestProperty("Authorization", "Bearer $token")
        connection.setRequestProperty("Accept", "application/json")
        val code = connection.responseCode
        val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) {
            val detail = runCatching { JSONObject(body).optString("detail") }.getOrNull()
            throw HttpError(code, detail?.takeIf { it.isNotBlank() } ?: "Erreur $code")
        }
        return body
    }
}
