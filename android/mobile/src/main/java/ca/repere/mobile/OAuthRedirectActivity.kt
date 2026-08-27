package ca.repere.mobile

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * Catches the `ca.repere.app://oauth2redirect` deep link from the Custom Tab,
 * finishes the token exchange, then hands control back to [MainActivity].
 */
class OAuthRedirectActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        val data = intent?.data
        lifecycleScope.launch {
            val result = if (data != null) OAuthClient.complete(this@OAuthRedirectActivity, data)
                else Result.failure(IllegalStateException("Redirection OAuth vide"))
            result.exceptionOrNull()?.let {
                Toast.makeText(this@OAuthRedirectActivity, it.message ?: "Connexion impossible", Toast.LENGTH_LONG).show()
            }
            startActivity(
                Intent(this@OAuthRedirectActivity, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
            finish()
        }
    }
}
