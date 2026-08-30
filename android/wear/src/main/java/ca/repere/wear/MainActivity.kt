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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.*
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Locale
import kotlinx.coroutines.delay
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
            .putLong("local_action_at", System.currentTimeMillis())
            .putFloat("today_standard", todayStandard)
            .apply()
        requestComplicationUpdate()
    }

    @Composable private fun QuickDrink() {
        val prefs = remember { getSharedPreferences("repere", MODE_PRIVATE) }
        var active by remember { mutableStateOf(prefs.getBoolean("active", false)) }
        var activeStartedAt by remember { mutableLongStateOf(prefs.getLong("active_started_at", 0L)) }
        var volume by remember { mutableIntStateOf(prefs.getInt("volume", 473)) }
        var abv by remember { mutableFloatStateOf(prefs.getFloat("abv", 5f)) }
        var todayStandard by remember { mutableFloatStateOf(prefs.getFloat("today_standard", 0f)) }
        var message by remember { mutableStateOf("") }; var busy by remember { mutableStateOf(false) }
        var clock by remember { mutableLongStateOf(System.currentTimeMillis()) }
        val scope = rememberCoroutineScope()
        LaunchedEffect(active) { while(active){clock=System.currentTimeMillis();delay(1_000)} }
        fun toggle() = scope.launch {
            if (busy) return@launch; busy = true
            runCatching { if (active) Api.finish(this@MainActivity) else Api.start(this@MainActivity, volume, abv) }
                .onSuccess { result ->
                    active = !active
                    prefs.edit().putInt("volume", volume).putFloat("abv", abv).apply()
                    val queued = result.optBoolean("queued")
                    message = getString(if (queued) R.string.wear_saved_offline else if (active) R.string.wear_start_saved else R.string.wear_end_saved)
                    val startedAt = if (active) {
                        val raw = result.optString("started_at_utc").ifBlank { result.optString("started_at") }
                        if (raw.isNotBlank()) parseInstantMillis(raw) else Instant.now().toEpochMilli()
                    } else 0L
                    activeStartedAt = startedAt
                    // Always persist the toggle locally: the phone's own state push (via the Data
                    // Layer) can lag well behind this response, and until it arrives this is the
                    // only record that a consumption just started or ended.
                    cacheForComplication(prefs, active, startedAt, todayStandard)
                }
                .onFailure { message = it.message ?: getString(R.string.wear_sync_failed) }
            busy = false
        }
        // The phone may correct this state later (e.g. once its own sync catches up), so keep
        // listening for as long as the screen is open instead of only reading prefs once.
        DisposableEffect(prefs) {
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
                when (key) {
                    "active" -> active = p.getBoolean("active", false)
                    "active_started_at" -> activeStartedAt = p.getLong("active_started_at", 0L)
                    "today_standard" -> todayStandard = p.getFloat("today_standard", 0f)
                }
            }
            prefs.registerOnSharedPreferenceChangeListener(listener)
            onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
        }
        Column(Modifier.fillMaxSize().padding(horizontal = 18.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(stringResource(if (active) R.string.wear_in_progress else R.string.wear_ready), style = MaterialTheme.typography.title2)
            if(active && activeStartedAt>0L){
                val seconds=((clock-activeStartedAt).coerceAtLeast(0L)/1_000);Text("%02d:%02d".format(seconds/60,seconds%60),style=MaterialTheme.typography.title1)
            }
            if (!active) {
                Text(stringResource(R.string.wear_drink_summary, volume, String.format(Locale.getDefault(), "%.1f", abv)))
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Button(onClick = { volume = when(volume) { 333 -> 473; 473 -> 150; else -> 333 } }) { Text(stringResource(R.string.wear_unit_ml)) }
                    Button(onClick = { abv = (abv - .5f).coerceAtLeast(.5f) }) { Text(stringResource(R.string.wear_abv_down)) }
                    Button(onClick = { abv = (abv + .5f).coerceAtMost(70f) }) { Text(stringResource(R.string.wear_abv_up)) }
                }
            }
            Button(onClick = { toggle() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(if (busy) R.string.wear_busy else if (active) R.string.wear_finish else R.string.wear_start)) }
            if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.body2)
        }
    }

    private fun parseInstantMillis(value: String): Long = runCatching {
        OffsetDateTime.parse(value).toInstant().toEpochMilli()
    }.recoverCatching {
        // Naive timestamp with no offset: treat as UTC.
        OffsetDateTime.of(java.time.LocalDateTime.parse(value), ZoneOffset.UTC).toInstant().toEpochMilli()
    }.getOrDefault(Instant.now().toEpochMilli())
}
