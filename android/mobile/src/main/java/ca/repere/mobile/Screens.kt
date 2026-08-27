package ca.repere.mobile

import android.content.Context
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/* ---------- shared building blocks ---------- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> RemoteScreen(
    context: Context,
    eyebrow: String,
    title: String,
    loader: suspend (Context) -> T,
    content: @Composable (T) -> Unit,
) {
    var data by remember { mutableStateOf<T?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(tick) {
        refreshing = true; error = null
        runCatching { loader(context) }
            .onSuccess { data = it }
            .onFailure { error = it.message ?: "Chargement impossible" }
        refreshing = false
    }
    // Refresh when the app returns to the foreground (but not on the initial composition).
    var armed by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { if (armed && !refreshing) tick++ else armed = true }
    PullToRefreshBox(isRefreshing = refreshing, onRefresh = { tick++ }, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            PageHeaderLite(eyebrow, title)
            when {
                data != null -> content(data!!)
                error != null -> Text(
                    error!!, Modifier.padding(20.dp), color = Pine.copy(alpha = .7f),
                )
                else -> Text("Chargement…", Modifier.padding(20.dp), color = Pine.copy(alpha = .6f))
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun PageHeaderLite(eyebrow: String, title: String) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
        Text(
            eyebrow.uppercase(Locale.CANADA_FRENCH),
            style = MaterialTheme.typography.labelSmall,
            color = Pine.copy(alpha = .65f), fontWeight = FontWeight.Bold,
        )
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
    }
}

@Composable
private fun SectionCard(title: String, subtitle: String? = null, body: @Composable ColumnScope.() -> Unit) {
    Card(
        Modifier.padding(horizontal = 20.dp, vertical = 8.dp).fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(22.dp),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Pine.copy(alpha = .65f))
            Spacer(Modifier.height(10.dp))
            body()
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(label, Modifier.weight(1f), color = Pine.copy(alpha = .8f))
        Text(value, fontWeight = FontWeight.Bold)
    }
}

private fun JSONObject.numOrNull(key: String): Double? =
    if (!has(key) || isNull(key)) null else optDouble(key)

private fun fmt(value: Double?, digits: Int = 1): String =
    if (value == null) "—" else String.format(Locale.CANADA_FRENCH, "%.${digits}f", value)

/* ---------- Stats ---------- */

private val HEALTH_LABELS = mapOf(
    "sleep" to "Sommeil", "steps" to "Pas", "exercise" to "Exercice",
    "resting_heart_rate" to "FC repos", "heart_rate" to "FC moyenne", "hrv_rmssd" to "VFC",
)

@Composable
fun StatsScreen(context: Context) {
    RemoteScreen(context, "Analyse", "Stats", { ctx ->
        val stats = Net.json(ctx, "/api/stats?days=30")
        val health = runCatching { Net.json(ctx, "/api/stats/health?days=90") }.getOrNull()
        stats to health
    }) { (stats, health) ->
        val period = stats.optJSONObject("period") ?: JSONObject()
        val grams = period.optJSONObject("grams") ?: JSONObject()
        val standards = period.optJSONObject("standards") ?: JSONObject()
        SectionCard("30 derniers jours", "Par journée observée") {
            StatRow("Jours observés", period.optInt("days_observed").toString())
            StatRow("Jours sans alcool", "${period.optInt("alcohol_free_days")} (${fmt(period.numOrNull("alcohol_free_percent"), 0)} %)")
            StatRow("Total standards", fmt(period.numOrNull("total_standards")))
            StatRow("Moyenne / jour", "${fmt(standards.numOrNull("mean"), 2)} std")
            StatRow("Médiane / jour", "${fmt(standards.numOrNull("median"), 2)} std")
            StatRow("Maximum", "${fmt(standards.numOrNull("max"), 2)} std · ${fmt(grams.numOrNull("max"), 0)} g")
        }
        if (health != null) HealthSection(health)
    }
}

@Composable
private fun HealthSection(health: JSONObject) {
    val types = health.optJSONArray("types") ?: JSONArray()
    if (types.length() == 0) {
        SectionCard("Santé", null) {
            Text(
                "Aucune donnée de santé importée. Active Health Connect dans Réglages → Application.",
                color = Pine.copy(alpha = .7f),
            )
        }
        return
    }
    var metric by remember { mutableStateOf(types.optString(0)) }
    val days = health.optJSONArray("days") ?: JSONArray()
    val correlations = health.optJSONObject("correlations") ?: JSONObject()
    val values = ArrayList<Double>()
    for (i in 0 until days.length()) {
        val h = days.optJSONObject(i)?.optJSONObject("health") ?: continue
        if (h.has(metric) && !h.isNull(metric)) values.add(h.optDouble(metric))
    }
    val avg = if (values.isEmpty()) null else values.sum() / values.size
    val corr = correlations.numOrNull(metric)
    SectionCard("Santé et consommation", "Corrélation de Pearson · n'implique pas de causalité") {
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            for (i in 0 until types.length()) {
                val t = types.optString(i)
                FilterChip(
                    selected = metric == t, onClick = { metric = t },
                    label = { Text(HEALTH_LABELS[t] ?: t) },
                    modifier = Modifier.padding(end = 6.dp),
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        val shown = if (metric == "sleep") avg?.div(60.0) else avg
        StatRow("${HEALTH_LABELS[metric] ?: metric} — moyenne", fmt(shown, if (metric == "steps") 0 else 1) + if (metric == "sleep") " h" else "")
        StatRow("Corrélation avec la consommation", if (corr == null) "—" else "r = ${fmt(corr, 2)} · ${strength(corr)}")
        StatRow("Jours avec donnée", values.size.toString())
    }
}

private fun strength(r: Double): String = when {
    abs(r) < 0.2 -> "faible"
    abs(r) < 0.4 -> "modérée"
    else -> "forte"
}

/* ---------- Repères (insights) ---------- */

@Composable
fun InsightsScreen(context: Context) {
    RemoteScreen(context, "Associations personnelles", "Repères", { ctx ->
        Net.json(ctx, "/api/analytics/personal")
    }) { data ->
        val ready = data.optJSONObject("model_readiness") ?: JSONObject()
        SectionCard("Disponibilité", "Ce que tes données permettent aujourd'hui") {
            StatRow("Jours disponibles", data.optInt("days_available").toString())
            StatRow("Journées de dépassement", data.optInt("events_available").toString())
            StatRow("Analyse descriptive", if (ready.optBoolean("descriptive")) "Prête" else "Pas encore")
            StatRow("Associations", if (ready.optBoolean("associations")) "Prêtes" else "Pas encore")
            StatRow("Modèle régularisé", if (ready.optBoolean("regularized_model")) "Prêt" else "Pas encore")
        }
        val associations = data.optJSONArray("associations") ?: JSONArray()
        for (i in 0 until associations.length()) {
            val a = associations.optJSONObject(i) ?: continue
            SectionCard(a.optString("factor").replaceFirstChar { it.uppercase() }) {
                val coef = a.numOrNull("coefficient")
                if (a.optString("status") == "insufficient_data" || coef == null) {
                    Text("Échantillon insuffisant (${a.optInt("sample_size")} observations).", color = Pine.copy(alpha = .7f))
                } else {
                    Text(a.optString("language"), color = Pine.copy(alpha = .8f))
                    Spacer(Modifier.height(6.dp))
                    StatRow("Coefficient", "${fmt(coef, 2)} · ${strength(coef)}")
                    StatRow("Observations", a.optInt("sample_size").toString())
                }
            }
        }
        Text(
            data.optString("disclaimer"),
            Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall, color = Pine.copy(alpha = .6f),
        )
    }
}

/* ---------- Succès ---------- */

@Composable
fun SuccessScreen(context: Context) {
    RemoteScreen(context, "Progrès orientés réduction", "Succès", { ctx ->
        Net.json(ctx, "/api/success")
    }) { data ->
        val badges = data.optJSONArray("badges") ?: JSONArray()
        val unlocked = (0 until badges.length()).count { badges.optJSONObject(it)?.optBoolean("unlocked") == true }
        SectionCard("Vue d'ensemble") {
            StatRow("Badges obtenus", "$unlocked / ${badges.length()}")
        }
        for (i in 0 until badges.length()) {
            val b = badges.optJSONObject(i) ?: continue
            val progress = (b.numOrNull("progress_percent") ?: 0.0).coerceIn(0.0, 100.0)
            SectionCard(b.optString("title")) {
                Text(b.optString("description"), color = Pine.copy(alpha = .78f))
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { (progress / 100.0).toFloat() },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    if (b.optBoolean("unlocked")) "Obtenu" else "${progress.roundToInt()} %",
                    style = MaterialTheme.typography.labelMedium, color = Pine,
                )
            }
        }
    }
}

/* ---------- Objectifs ---------- */

private val GOAL_LABELS = mapOf(
    "max_grams_week" to "Grammes / semaine (max)",
    "max_standards" to "Standards / semaine (max)",
    "min_alcohol_free_days" to "Jours sans alcool / semaine (min)",
    "max_drinking_days" to "Jours avec alcool / semaine (max)",
    "max_grams_session" to "Grammes / occasion (max)",
    "max_moving_7_grams" to "Moyenne mobile 7 j en grammes (max)",
    "monthly_reduction" to "Réduction mensuelle (%)",
)

@Composable
fun GoalsScreen(context: Context) {
    RemoteScreen(context, "Suivi", "Objectifs", { ctx ->
        Net.array(ctx, "/api/goals")
    }) { goals ->
        if (goals.length() == 0) {
            SectionCard("Aucun objectif") {
                Text("Crée un objectif depuis la version web pour le suivre ici.", color = Pine.copy(alpha = .7f))
            }
        }
        for (i in 0 until goals.length()) {
            val g = goals.optJSONObject(i) ?: continue
            SectionCard(GOAL_LABELS[g.optString("kind")] ?: g.optString("kind")) {
                StatRow("Cible", fmt(g.numOrNull("target"), 1))
                StatRow("Actuel", fmt(g.numOrNull("current"), 1))
                val onTrack = if (g.isNull("on_track")) null else g.optBoolean("on_track")
                StatRow("Statut", when (onTrack) { true -> "Sur la bonne voie"; false -> "À ajuster"; else -> "—" })
                g.numOrNull("progress_percent")?.let { p ->
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(progress = { (p / 100.0).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                }
                if (!g.optBoolean("active")) Text("En pause", style = MaterialTheme.typography.labelMedium, color = Amber)
            }
        }
    }
}
