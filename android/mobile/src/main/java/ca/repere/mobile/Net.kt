package ca.repere.mobile

import android.content.Context
import ca.repere.core.CredentialStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Authenticated JSON reads against the Repère server, with one transparent token refresh on 401. */
object Net {
    class HttpError(val code: Int, message: String) : Exception(message)

    suspend fun json(context: Context, path: String): JSONObject =
        JSONObject(text(context, path).ifBlank { "{}" })

    suspend fun array(context: Context, path: String): JSONArray =
        JSONArray(text(context, path).ifBlank { "[]" })

    suspend fun text(context: Context, path: String): String = withContext(Dispatchers.IO) {
        val creds = CredentialStore(context)
        try {
            get(creds.server().trimEnd('/'), creds.token(), path)
        } catch (e: HttpError) {
            if (e.code == 401 && OAuthClient.refresh(context)) {
                get(CredentialStore(context).server().trimEnd('/'), CredentialStore(context).token(), path)
            } else throw e
        }
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
