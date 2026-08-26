package ca.repere.wear

import android.content.ComponentName
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.*
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { QuickDrink(intent.getBooleanExtra("quick_toggle", false)) } }
    }

    @Composable private fun QuickDrink(quickToggle: Boolean) {
        val prefs = remember { getSharedPreferences("repere", MODE_PRIVATE) }
        var active by remember { mutableStateOf(prefs.getBoolean("active", false)) }
        var volume by remember { mutableIntStateOf(prefs.getInt("volume", 473)) }
        var abv by remember { mutableFloatStateOf(prefs.getFloat("abv", 5f)) }
        var quantity by remember { mutableIntStateOf(prefs.getInt("quantity", 1)) }
        var message by remember { mutableStateOf("") }; var busy by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        fun toggle() = scope.launch {
            if (busy) return@launch; busy = true
            runCatching { if (active) Api.finish(this@MainActivity) else Api.start(this@MainActivity, volume, abv, quantity) }
                .onSuccess { active = !active; prefs.edit().putBoolean("active", active).putInt("volume", volume).putFloat("abv", abv).putInt("quantity", quantity).apply(); ComplicationDataSourceUpdateRequester.create(this@MainActivity, ComponentName(this@MainActivity, QuickDrinkComplicationService::class.java)).requestUpdateAll(); message = if (active) "Début enregistré" else "Fin enregistrée" }
                .onFailure { message = it.message ?: "Synchronisation impossible" }
            busy = false
        }
        LaunchedEffect(Unit) {
            runCatching { Api.state(this@MainActivity) }.onSuccess { active = it.optJSONObject("active") != null; prefs.edit().putBoolean("active", active).apply() }
            if (quickToggle) toggle()
        }
        Column(Modifier.fillMaxSize().padding(horizontal = 18.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(if (active) "En cours" else "Prêt", style = MaterialTheme.typography.title2)
            if (!active) {
                Text("${volume} ml · ${"%.1f".format(abv)} % · ×$quantity")
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Button(onClick = { volume = when(volume) { 341 -> 473; 473 -> 150; else -> 341 } }) { Text("ml") }
                    Button(onClick = { abv = if (abv >= 40) 5f else abv + .5f }) { Text("%") }
                    Button(onClick = { quantity = quantity % 4 + 1 }) { Text("×") }
                }
            }
            Button(onClick = { toggle() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "…" else if (active) "Terminer" else "Démarrer") }
            if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.body2)
        }
    }
}
