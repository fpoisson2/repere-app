package ca.repere.mobile

import android.content.Context
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
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
            if (title.isNotBlank()) PageHeaderLite(eyebrow, title)
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

/* ---------- charts ---------- */

@Composable
private fun ChartFrame(topLabel: String, bottomStart: String, bottomEnd: String, content: @Composable () -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth()) {
            Text(topLabel, style = MaterialTheme.typography.labelSmall, color = Pine.copy(alpha = .6f))
        }
        content()
        Row(Modifier.fillMaxWidth()) {
            Text(bottomStart, style = MaterialTheme.typography.labelSmall, color = Pine.copy(alpha = .5f))
            Spacer(Modifier.weight(1f))
            Text(bottomEnd, style = MaterialTheme.typography.labelSmall, color = Pine.copy(alpha = .5f))
        }
    }
}

@Composable
private fun BarChart(values: List<Double>, colors: List<Color>, threshold: Double? = null, modifier: Modifier = Modifier) {
    val max = (values.maxOrNull() ?: 0.0).coerceAtLeast(threshold ?: 0.0).coerceAtLeast(0.01)
    Canvas(modifier.fillMaxWidth().height(140.dp)) {
        if (values.isEmpty()) return@Canvas
        // horizontal gridlines at 0 / 50 / 100 %
        listOf(0f, .5f, 1f).forEach { f ->
            val y = size.height * (1 - f)
            drawLine(Pine.copy(alpha = .12f), Offset(0f, y), Offset(size.width, y), 1f)
        }
        val gap = 1.5f
        val bw = (size.width - gap * (values.size - 1)) / values.size
        values.forEachIndexed { i, v ->
            val h = (v / max * size.height).toFloat()
            drawRect(colors.getOrElse(i) { Pine }, Offset(i * (bw + gap), size.height - h),
                androidx.compose.ui.geometry.Size(bw.coerceAtLeast(1f), h))
        }
        threshold?.let { t ->
            val y = (size.height - (t / max * size.height)).toFloat()
            drawLine(Color(0xFFD9534F), Offset(0f, y), Offset(size.width, y), 2f)
        }
    }
}

/** Daily bars behind one or more overlay lines, shared Y scale. */
@Composable
private fun ComboChart(bars: List<Double>, lines: List<Pair<Color, List<Double?>>>, threshold: Double? = null, modifier: Modifier = Modifier) {
    val allLine = lines.flatMap { it.second }.filterNotNull()
    val max = (bars.maxOrNull() ?: 0.0)
        .coerceAtLeast(allLine.maxOrNull() ?: 0.0)
        .coerceAtLeast(threshold ?: 0.0)
        .coerceAtLeast(0.01)
    Canvas(modifier.fillMaxWidth().height(150.dp)) {
        listOf(0f, .5f, 1f).forEach { f ->
            val y = size.height * (1 - f)
            drawLine(Pine.copy(alpha = .12f), Offset(0f, y), Offset(size.width, y), 1f)
        }
        if (bars.isNotEmpty()) {
            val bw = size.width / bars.size
            bars.forEachIndexed { i, v ->
                val h = (v / max * size.height).toFloat()
                drawRect(Pine.copy(alpha = .18f), Offset(i * bw, size.height - h),
                    androidx.compose.ui.geometry.Size((bw - 1f).coerceAtLeast(1f), h))
            }
        }
        threshold?.let { t ->
            val y = (size.height - (t / max * size.height)).toFloat()
            drawLine(Color(0xFFD9534F), Offset(0f, y), Offset(size.width, y), 2f)
        }
        lines.forEach { (color, series) ->
            if (series.count { it != null } < 2) return@forEach
            val step = size.width / (series.size - 1).coerceAtLeast(1)
            val path = Path(); var started = false
            series.forEachIndexed { i, v ->
                if (v == null) return@forEachIndexed
                val x = i * step
                val y = (size.height - (v / max * size.height)).toFloat()
                if (!started) { path.moveTo(x, y); started = true } else path.lineTo(x, y)
            }
            drawPath(path, color, style = Stroke(width = 3f, cap = StrokeCap.Round))
        }
    }
}

@Composable
private fun LineChart(values: List<Double?>, modifier: Modifier = Modifier, color: Color = Pine) {
    val present = values.filterNotNull()
    val min = present.minOrNull() ?: 0.0
    val max = (present.maxOrNull() ?: 1.0)
    val span = (max - min).takeIf { it > 0 } ?: 1.0
    Canvas(modifier.fillMaxWidth().height(130.dp)) {
        listOf(0f, .5f, 1f).forEach { f ->
            val y = size.height * (1 - f)
            drawLine(Pine.copy(alpha = .12f), Offset(0f, y), Offset(size.width, y), 1f)
        }
        if (present.size < 2) return@Canvas
        val step = size.width / (values.size - 1).coerceAtLeast(1)
        val path = Path(); var started = false
        values.forEachIndexed { i, v ->
            if (v == null) return@forEachIndexed
            val x = i * step
            val y = (size.height - ((v - min) / span * size.height)).toFloat()
            if (!started) { path.moveTo(x, y); started = true } else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(width = 3f, cap = StrokeCap.Round))
    }
}

private fun fmt(value: Double?, digits: Int = 1): String =
    if (value == null) "—" else String.format(Locale.CANADA_FRENCH, "%.${digits}f", value)

/* ---------- Stats ---------- */

private val HEALTH_LABELS = mapOf(
    "sleep" to "Sommeil", "steps" to "Pas", "exercise" to "Exercice",
    "resting_heart_rate" to "FC repos", "heart_rate" to "FC moyenne", "hrv_rmssd" to "VFC",
)

@Composable
fun StatsScreen(context: Context) {
    var start by remember { mutableStateOf(LocalDate.now().minusDays(89)) }
    var end by remember { mutableStateOf(LocalDate.now()) }
    var custom by remember { mutableStateOf(false) }
    val query = "start=$start&end=$end"
    Column(Modifier.fillMaxSize()) {
        PageHeaderLite("Analyse", "Stats")
        StatsPeriodSelector(start, end, custom, onPreset = { days -> start=LocalDate.now().minusDays(days-1L);end=LocalDate.now();custom=false }, onCustom = { a,b -> start=a;end=b;custom=true })
        key(query) { RemoteScreen(context, "", "", { ctx ->
        val stats = Net.json(ctx, "/api/stats?$query")
        val trends = runCatching { Net.json(ctx, "/api/stats/trends") }.getOrNull()
        val health = runCatching { Net.json(ctx, "/api/stats/health?$query") }.getOrNull()
        Triple(stats, trends, health)
    }) { (stats, trends, health) ->
        val period = stats.optJSONObject("period") ?: JSONObject()
        val grams = period.optJSONObject("grams") ?: JSONObject()
        val standards = period.optJSONObject("standards") ?: JSONObject()
        SectionCard("Période observée", "Du ${start.format(STAT_DATE)} au ${end.format(STAT_DATE)}") {
            StatRow("Jours observés", period.optInt("days_observed").toString())
            StatRow("Jours sans alcool", "${period.optInt("alcohol_free_days")} (${fmt(period.numOrNull("alcohol_free_percent"), 0)} %)")
            StatRow("Total standards", fmt(period.numOrNull("total_standards")))
            StatRow("Moyenne / jour", "${fmt(standards.numOrNull("mean"), 2)} std")
            StatRow("Médiane / jour", "${fmt(standards.numOrNull("median"), 2)} std")
            StatRow("Maximum", "${fmt(standards.numOrNull("max"), 2)} std · ${fmt(grams.numOrNull("max"), 0)} g")
        }
        if (trends != null) TrendSection(trends, start, end)
        if (health != null) HealthSection(health)
    } }
    }
}

private val STAT_DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.CANADA_FRENCH)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StatsPeriodSelector(start:LocalDate,end:LocalDate,custom:Boolean,onPreset:(Int)->Unit,onCustom:(LocalDate,LocalDate)->Unit) {
    var dialog by remember { mutableStateOf<String?>(null) }
    var draftStart by remember(start) { mutableStateOf(start) };var draftEnd by remember(end) { mutableStateOf(end) }
    Column(Modifier.padding(horizontal=20.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            listOf(30 to "30 j",90 to "90 j",180 to "180 j",365 to "1 an").forEach { (days,label) ->
                FilterChip(selected=!custom && start==LocalDate.now().minusDays(days-1L) && end==LocalDate.now(),onClick={onPreset(days)},label={Text(label)},modifier=Modifier.padding(end=6.dp))
            }
            FilterChip(selected=custom,onClick={draftStart=start;draftEnd=end;dialog="start"},label={Text("Personnalisée")})
        }
        Text("${start.format(STAT_DATE)} — ${end.format(STAT_DATE)}",style=MaterialTheme.typography.bodySmall,color=Pine.copy(alpha=.65f),modifier=Modifier.padding(top=4.dp,bottom=4.dp))
    }
    dialog?.let { target ->
        val initial=if(target=="start")draftStart else draftEnd
        val state=rememberDatePickerState(initialSelectedDateMillis=initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),selectableDates=object:SelectableDates{override fun isSelectableDate(utcTimeMillis:Long)=utcTimeMillis<=System.currentTimeMillis()})
        DatePickerDialog(onDismissRequest={dialog=null},confirmButton={TextButton(onClick={
            val picked=state.selectedDateMillis?.let{Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()}?:initial
            if(target=="start"){draftStart=picked;dialog="end"}else{draftEnd=picked;if(draftStart<=picked)onCustom(draftStart,picked);dialog=null}
        }){Text(if(target=="start")"Suivant":"Appliquer")}},dismissButton={TextButton(onClick={dialog=null}){Text("Annuler")}}){DatePicker(state)}
    }
}

@Composable
private fun TrendSection(trends: JSONObject, start:LocalDate, end:LocalDate) {
    var metric by remember { mutableStateOf("standards") }
    val moving = trends.optJSONObject("moving_averages") ?: JSONObject()
    fun series(window: String, field: String): List<Double?> {
        val arr = moving.optJSONArray(window) ?: JSONArray()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.filter { it.optString("date") in start.toString()..end.toString() }.map { r -> if (r.isNull(field)) null else r.optDouble(field) }
    }
    val daily = series("7", "daily_$metric").map { it ?: 0.0 }
    val ma7 = series("7", metric);val ma30 = series("30", metric);val ma90 = series("90", metric)
    val unit=if(metric=="grams")"g / jour" else "standards / jour"
    val weekly = trends.optJSONArray("weekly") ?: JSONArray()
    SectionCard("Moyennes mobiles", "Barres = jour · vert = 7 j · ambre = 30 j · gris = 90 j") {
        Row { listOf("standards" to "Standards","grams" to "Grammes").forEach { (value,label) -> FilterChip(selected=metric==value,onClick={metric=value},label={Text(label)},modifier=Modifier.padding(end=6.dp)) } }
        ChartFrame(unit, start.toString().takeLast(5), end.toString().takeLast(5)) {
            ComboChart(daily, listOf(Pine to ma7, Amber to ma30, Pine.copy(alpha=.4f) to ma90), threshold = if(metric=="standards")3.0 else null)
        }
        Spacer(Modifier.height(12.dp))
        val lastWeeks = (maxOf(0, weekly.length() - 6) until weekly.length()).map { weekly.optJSONObject(it) }
        lastWeeks.forEach { w ->
            if (w != null) StatRow(
                w.optString("period_start"),
                "${fmt(w.numOrNull("total_standards"), 1)} std · ${w.optInt("alcohol_free_days")} j sans alcool",
            )
        }
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
    val series = ArrayList<Double?>()
    val values = ArrayList<Double>()
    for (i in 0 until days.length()) {
        val h = days.optJSONObject(i)?.optJSONObject("health")
        val v = if (h != null && h.has(metric) && !h.isNull(metric)) h.optDouble(metric) else null
        series.add(v)
        if (v != null) values.add(v)
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
        LineChart(series, color = Amber)
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

private val GOAL_KINDS = listOf(
    "max_moving_7_grams" to "Moyenne mobile 7 j en grammes (max)",
    "max_grams_week" to "Grammes / semaine (max)",
    "max_standards" to "Standards / semaine (max)",
    "min_alcohol_free_days" to "Jours sans alcool / semaine (min)",
    "max_drinking_days" to "Jours avec alcool / semaine (max)",
    "max_grams_session" to "Grammes / occasion (max)",
    "monthly_reduction" to "Réduction mensuelle (%)",
)
private val GOAL_LABELS = GOAL_KINDS.toMap()

private fun durationText(g: JSONObject): String = when (g.optString("temporal_mode")) {
    "deadline" -> {
        val due = g.optString("due_date").takeIf { it.isNotBlank() && it != "null" }
        val left = if (g.isNull("days_remaining")) null else g.optInt("days_remaining")
        "À tenir jusqu’au ${due ?: "?"}" + (left?.let { " (dans $it j)" } ?: "")
    }
    else -> {
        val weeks = if (g.isNull("consecutive_weeks")) null else g.optInt("consecutive_weeks")
        val done = if (g.isNull("consecutive_weeks_achieved")) null else g.optInt("consecutive_weeks_achieved")
        "À maintenir ${weeks ?: "?"} semaines consécutives" + (done?.let { " · ${it}/${weeks ?: "?"} atteintes" } ?: "")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GoalsScreen(context: Context) {
    var goals by remember { mutableStateOf<JSONArray?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }
    var adding by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(tick) {
        refreshing = true; error = null
        runCatching { Net.array(context, "/api/goals") }.onSuccess { goals = it }.onFailure { error = it.message }
        refreshing = false
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    PullToRefreshBox(isRefreshing = refreshing, onRefresh = { tick++ }, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            PageHeaderLite("Suivi", "Objectifs")
            Button(onClick = { adding = true }, modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth()) { Text("Ajouter un objectif") }
            error?.let { Text(it, Modifier.padding(20.dp), color = Pine.copy(alpha = .7f)) }
            val list = goals
            if (list != null && list.length() == 0) SectionCard("Aucun objectif") {
                Text("Ajoute un objectif pour suivre ta progression semaine après semaine.", color = Pine.copy(alpha = .7f))
            }
            for (i in 0 until (list?.length() ?: 0)) {
                val g = list!!.optJSONObject(i) ?: continue
                SectionCard(GOAL_LABELS[g.optString("kind")] ?: g.optString("kind")) {
                    StatRow("Cible", fmt(g.numOrNull("target"), 1))
                    StatRow("Actuel", fmt(g.numOrNull("current"), 1))
                    val onTrack = if (g.isNull("on_track")) null else g.optBoolean("on_track")
                    StatRow("Statut", when (onTrack) { true -> "Sur la bonne voie"; false -> "À ajuster"; else -> "—" })
                    Text(durationText(g), style = MaterialTheme.typography.bodySmall, color = Pine.copy(alpha = .7f))
                    g.numOrNull("progress_percent")?.let { p ->
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(progress = { (p / 100.0).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    }
                    if (!g.optBoolean("active")) Text("En pause", style = MaterialTheme.typography.labelMedium, color = Amber)
                    TextButton(onClick = {
                        scope.launch {
                            runCatching { Net.send(context, "/api/goals/${g.optInt("id")}", JSONObject(), "DELETE") }
                                .onSuccess { tick++ }.onFailure { error = it.message }
                        }
                    }) { Text("Retirer", color = Color(0xFFD9534F)) }
                }
            }
            Spacer(Modifier.height(28.dp))
        }
    }
    if (adding) GoalDialog(onDismiss = { adding = false }) { payload ->
        scope.launch {
            runCatching { Net.send(context, "/api/goals", payload) }.onSuccess { adding = false; tick++ }.onFailure { error = it.message }
        }
    }
}

@Composable
private fun GoalDialog(onDismiss: () -> Unit, onCreate: (JSONObject) -> Unit) {
    var kind by remember { mutableStateOf(GOAL_KINDS.first().first) }
    var kindOpen by remember { mutableStateOf(false) }
    var target by remember { mutableStateOf("") }
    var deadlineMode by remember { mutableStateOf(false) }
    var weeks by remember { mutableStateOf("4") }
    var dueDate by remember { mutableStateOf(java.time.LocalDate.now().plusMonths(1).toString()) }
    val numeric = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nouvel objectif") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box {
                    OutlinedTextField(GOAL_LABELS[kind] ?: kind, {}, readOnly = true, label = { Text("Type") }, modifier = Modifier.fillMaxWidth(),
                        trailingIcon = { TextButton(onClick = { kindOpen = true }) { Text("Changer") } })
                    DropdownMenu(expanded = kindOpen, onDismissRequest = { kindOpen = false }) {
                        GOAL_KINDS.forEach { (k, label) -> DropdownMenuItem(text = { Text(label) }, onClick = { kind = k; kindOpen = false }) }
                    }
                }
                OutlinedTextField(target, { target = it.filter { c -> c.isDigit() || c == '.' || c == ',' } }, label = { Text("Cible") }, singleLine = true, keyboardOptions = numeric, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Objectif avec échéance", Modifier.weight(1f))
                    Switch(checked = deadlineMode, onCheckedChange = { deadlineMode = it })
                }
                if (deadlineMode) OutlinedTextField(dueDate, { dueDate = it }, label = { Text("Échéance (AAAA-MM-JJ)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                else OutlinedTextField(weeks, { weeks = it.filter(Char::isDigit) }, label = { Text("Semaines consécutives à tenir") }, singleLine = true, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number), modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val payload = JSONObject().put("kind", kind).put("target", target.replace(',', '.').toDoubleOrNull() ?: 0.0)
                if (deadlineMode) payload.put("temporal_mode", "deadline").put("due_date", dueDate.trim())
                else payload.put("temporal_mode", "consecutive_weeks").put("consecutive_weeks", weeks.toIntOrNull()?.coerceAtLeast(1) ?: 1)
                onCreate(payload)
            }) { Text("Créer") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}
