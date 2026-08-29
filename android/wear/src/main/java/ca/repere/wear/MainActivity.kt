package ca.repere.wear

import android.content.ComponentName
import android.content.SharedPreferences
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
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { QuickDrink() } }
    }

    private fun requestComplicationUpdate() =
        ComplicationDataSourceUpdateRequester
            .create(this, ComponentName(this, QuickDrinkComplicationService::class.java))
            .requestUpdateAll()

    /** Persist the bits the complication renders from, then ask it to redraw. */
    private fun cacheForComplication(prefs: SharedPreferences, active: Boolean, startedAtMillis: Long, todayStandard: Float) {
        prefs.edit()
            .putBoolean("active", active)
            .putLong("active_started_at", if (active) startedAtMillis else 0L)
            .putFloat("today_standard", todayStandard)
            .apply()
        requestComplicationUpdate()
    }

    @Composable private fun QuickDrink() {
        val prefs = remember { getSharedPreferences("repere", MODE_PRIVATE) }
        var active by remember { mutableStateOf(prefs.getBoolean("active", false)) }
        var volume by remember { mutableIntStateOf(prefs.getInt("volume", 473)) }
        var abv by remember { mutableFloatStateOf(prefs.getFloat("abv", 5f)) }
        var todayStandard by remember { mutableFloatStateOf(prefs.getFloat("today_standard", 0f)) }
        var message by remember { mutableStateOf("") }; var busy by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        fun toggle() = scope.launch {
            if (busy) return@launch; busy = true
            runCatching { if (active) Api.finish(this@MainActivity) else Api.start(this@MainActivity, volume, abv) }
                .onSuccess { result ->
                    active = !active
                    prefs.edit().putInt("volume", volume).putFloat("abv", abv).apply()
                    val queued = result.optBoolean("queued")
                    message = if (queued) "Enregistré hors ligne" else if (active) "Début enregistré" else "Fin enregistrée"
                    if (queued) {
                        val startedAt = if (active) parseInstantMillis(result.optString("started_at_utc").ifBlank { result.optString("started_at") }) else 0L
                        cacheForComplication(prefs, active, startedAt, todayStandard)
                    } else {
                        refreshState(prefs) { a, _, t -> active = a; todayStandard = t }
                    }
                }
                .onFailure { message = it.message ?: "Synchronisation impossible" }
            busy = false
        }
        LaunchedEffect(Unit) {
            refreshState(prefs) { a, _, t -> active = a; todayStandard = t }
        }
        Column(Modifier.fillMaxSize().padding(horizontal = 18.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(if (active) "En cours" else "Prêt", style = MaterialTheme.typography.title2)
            if (!active) {
                Text("${volume} ml · ${"%.1f".format(abv)} %")
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Button(onClick = { volume = when(volume) { 333 -> 473; 473 -> 150; else -> 333 } }) { Text("ml") }
                    Button(onClick = { abv = (abv - .5f).coerceAtLeast(.5f) }) { Text("−%") }
                    Button(onClick = { abv = (abv + .5f).coerceAtMost(70f) }) { Text("+%") }
                }
            }
            Button(onClick = { toggle() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "…" else if (active) "Terminer" else "Démarrer") }
            if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.body2)
        }
    }

    private suspend fun refreshState(prefs: SharedPreferences, apply: (active: Boolean, startedAtMillis: Long, todayStandard: Float) -> Unit) {
        val isActive=prefs.getBoolean("active",false);val startedAt=prefs.getLong("active_started_at",0L);val today=prefs.getFloat("today_standard",0f)
        apply(isActive,startedAt,today)
    }

    private fun parseInstantMillis(value: String): Long = runCatching {
        OffsetDateTime.parse(value).toInstant().toEpochMilli()
    }.recoverCatching {
        // Naive timestamp with no offset: treat as UTC.
        OffsetDateTime.of(java.time.LocalDateTime.parse(value), ZoneOffset.UTC).toInstant().toEpochMilli()
    }.getOrDefault(Instant.now().toEpochMilli())
}
