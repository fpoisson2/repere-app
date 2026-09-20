package ca.repere.mobile

import android.content.Context
import ca.repere.core.CredentialStore
import ca.repere.data.PendingApiOperation
import ca.repere.data.ApiOperationRepository
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
            get(context, creds.server().trimEnd('/'), creds.token(), path)
        } catch (e: HttpError) {
            if (e.code == 401 && OAuthClient.refresh(context)) {
                get(context, CredentialStore(context).server().trimEnd('/'), CredentialStore(context).token(), path)
            } else throw e
        }
    }

    suspend fun send(context: Context, path: String, body: JSONObject, method: String = "POST"): JSONObject = withContext(Dispatchers.IO) {
        val creds = CredentialStore(context)
        val operation=PendingApiOperation(java.util.UUID.randomUUID().toString(),path,method,body.toString())
        ApiOperationRepository(context).enqueue(operation)
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
        val creds=CredentialStore(context);val operations=ApiOperationRepository(context)
        val legacy=runCatching{JSONArray(creds.pendingApiOperations())}.getOrDefault(JSONArray());for(i in 0 until legacy.length()){val op=legacy.getJSONObject(i);operations.enqueue(PendingApiOperation(op.getString("id"),op.getString("path"),op.getString("method"),op.getJSONObject("body").toString()))};if(legacy.length()>0)creds.setPendingApiOperations("[]")
        val queue=operations.pending()
        for(op in queue){
            val result=runCatching{write(context,creds.server().trimEnd('/'),creds.token(),op.path,JSONObject(op.body),op.method)}
            if(result.isSuccess)operations.complete(op.id) else {operations.failed(op,result.exceptionOrNull()?.message);throw result.exceptionOrNull()!!}
        }
    }

    private fun write(context: Context, server: String, token: String, path: String, body: JSONObject, method: String): String {
        if (server.isBlank() || token.isBlank()) throw HttpError(401, context.getString(R.string.error_not_signed_in))
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
            throw HttpError(code, detail?.takeIf { it.isNotBlank() } ?: context.getString(R.string.error_http, code))
        }
        return text
    }

    private fun get(context: Context, server: String, token: String, path: String): String {
        if (server.isBlank() || token.isBlank()) throw HttpError(401, context.getString(R.string.error_not_signed_in))
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
            throw HttpError(code, detail?.takeIf { it.isNotBlank() } ?: context.getString(R.string.error_http, code))
        }
        return body
    }
}
