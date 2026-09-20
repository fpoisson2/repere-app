package ca.repere.mobile

import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import ca.repere.core.CredentialStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import android.util.Base64

/**
 * OAuth 2.0 Authorization Code + PKCE against a self-hosted Repère server.
 * Public first-party client, no secret; the verifier lives only in the encrypted
 * credential store between launching the browser and handling the redirect.
 */
object OAuthClient {
    const val CLIENT_ID = "repere-android"
    const val REDIRECT_URI = "ca.repere.app://oauth2redirect"

    fun start(context: Context, server: String) {
        val base = server.trim().trimEnd('/')
        val verifier = randomToken(64)
        val state = randomToken(16)
        CredentialStore(context).setOauthTransient(
            JSONObject().put("v", verifier).put("s", state).put("server", base).toString(),
        )
        val url = Uri.parse("$base/api/oauth/authorize").buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", CLIENT_ID)
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("code_challenge", challenge(verifier))
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("state", state)
            .build()
        CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, url)
    }

    /** Handle the browser redirect: validate state, exchange the code, persist tokens. */
    suspend fun complete(context: Context, redirect: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val creds = CredentialStore(context)
            val pending = JSONObject(creds.oauthTransient().ifBlank { error(context.getString(R.string.oauth_no_pending)) })
            redirect.getQueryParameter("error")?.let { error(friendlyError(context, it)) }
            val code = redirect.getQueryParameter("code") ?: error(context.getString(R.string.oauth_missing_code))
            require(redirect.getQueryParameter("state") == pending.getString("s")) { context.getString(R.string.oauth_unexpected_response) }
            val server = pending.getString("server")
            val json = form(
                "$server/api/oauth/token",
                "grant_type=authorization_code" +
                    "&code=${enc(code)}" +
                    "&redirect_uri=${enc(REDIRECT_URI)}" +
                    "&client_id=$CLIENT_ID" +
                    "&code_verifier=${enc(pending.getString("v"))}",
            )
            creds.save(server, json.getString("access_token"))
            creds.setRefreshToken(json.optString("refresh_token"))
            creds.setOauthTransient("")
        }
    }

    /** Swap the refresh token for a fresh access token. Returns false if it cannot. */
    suspend fun refresh(context: Context): Boolean = withContext(Dispatchers.IO) {
        val creds = CredentialStore(context)
        val refresh = creds.refreshToken()
        val server = creds.server().trimEnd('/')
        if (refresh.isBlank() || server.isBlank()) return@withContext false
        runCatching {
            val json = form(
                "$server/api/oauth/token",
                "grant_type=refresh_token&refresh_token=${enc(refresh)}&client_id=$CLIENT_ID",
            )
            creds.save(server, json.getString("access_token"))
            creds.setRefreshToken(json.optString("refresh_token"))
        }.isSuccess
    }

    suspend fun signOut(context: Context) = withContext(Dispatchers.IO) {
        val creds = CredentialStore(context)
        val server = creds.server().trimEnd('/')
        val token = creds.refreshToken().ifBlank { creds.token() }
        if (server.isNotBlank() && token.isNotBlank()) {
            runCatching { form("$server/api/oauth/revoke", "token=${enc(token)}") }
        }
        creds.clear()
    }

    private fun friendlyError(context: Context, code: String) = when (code) {
        "access_denied" -> context.getString(R.string.oauth_access_denied)
        else -> context.getString(R.string.oauth_failed, code)
    }

    private fun randomToken(bytes: Int): String {
        val buffer = ByteArray(bytes)
        SecureRandom().nextBytes(buffer)
        return Base64.encodeToString(buffer, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private fun challenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray())
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun form(endpoint: String, body: String): JSONObject {
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        connection.setRequestProperty("Accept", "application/json")
        connection.outputStream.use { it.write(body.toByteArray()) }
        val ok = connection.responseCode in 200..299
        val text = (if (ok) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (!ok) {
            val detail = runCatching { JSONObject(text).optString("error_description") }.getOrNull()
            error(detail?.takeIf { it.isNotBlank() } ?: "HTTP ${connection.responseCode}")
        }
        return JSONObject(text.ifBlank { "{}" })
    }
}
