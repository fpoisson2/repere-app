package ca.repere.mobile

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import ca.repere.data.DrinkEntity
import ca.repere.data.TrackedDayEntity
import ca.repere.data.HealthAggregateEntity
import ca.repere.data.CheckInEntity
import ca.repere.data.GoalEntity
import ca.repere.data.LocalSettings
import ca.repere.data.SyncRepository
import ca.repere.core.alcoholGrams
import ca.repere.core.CANADIAN_STANDARD_GRAMS
import ca.repere.core.canadianStandards
import ca.repere.core.parseDrinkTime
import ca.repere.core.trackedDay

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
            Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Pine.copy(alpha = .7f), modifier = Modifier.padding(top = 2.dp))
            Spacer(Modifier.height(14.dp))
            body()
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f).padding(end = 10.dp), style = MaterialTheme.typography.bodyLarge, color = Pine.copy(alpha = .8f))
        Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.End)
    }
}

private fun JSONObject.numOrNull(key: String): Double? =
    if (!has(key) || isNull(key)) null else optDouble(key)

/* ---------- charts ---------- */

private val SHORT_DATE = DateTimeFormatter.ofPattern("d MMM", Locale.CANADA_FRENCH)
private val DAY_DATE = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.CANADA_FRENCH)

private fun axisText(value: Double): String = when {
    value == 0.0 -> "0"
    abs(value) >= 10 -> String.format(Locale.CANADA_FRENCH, "%.0f", value)
    abs(value) >= 1 -> String.format(Locale.CANADA_FRENCH, "%.1f", value)
    else -> String.format(Locale.CANADA_FRENCH, "%.2f", value)
}

/** Left-hand scale shared by every chart: haut / milieu / bas, aligned with the canvas gridlines. */
@Composable
private fun ChartYAxis(max: Double, height: Dp, min: Double = 0.0) {
    Column(Modifier.height(height).padding(end = 6.dp), verticalArrangement = Arrangement.SpaceBetween, horizontalAlignment = Alignment.End) {
        listOf(max, (max + min) / 2, min).forEach {
            Text(axisText(it), style = MaterialTheme.typography.labelMedium, color = Pine.copy(alpha = .55f), maxLines = 1)
        }
    }
}

@Composable
private fun ChartXAxis(start: String, end: String) {
    if (start.isBlank() && end.isBlank()) return
    Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Text(start, style = MaterialTheme.typography.labelMedium, color = Pine.copy(alpha = .55f))
        Spacer(Modifier.weight(1f))
        Text(end, style = MaterialTheme.typography.labelMedium, color = Pine.copy(alpha = .55f))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChartLegend(items: List<Pair<Color, String>>) {
    FlowRow(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items.forEach { (color, label) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(11.dp).background(color, RoundedCornerShape(3.dp)))
                Spacer(Modifier.width(6.dp))
                Text(label, style = MaterialTheme.typography.labelLarge, color = Pine.copy(alpha = .75f))
            }
        }
    }
}

@Composable
private fun ChartSelection(label: String, value: String, detail: String? = null) {
    Surface(color = Mint.copy(alpha = .6f), shape = RoundedCornerShape(14.dp), modifier = Modifier.padding(top = 10.dp).fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 11.dp)) {
            Text(label, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall, color = Pine)
            Text(value, style = MaterialTheme.typography.bodyLarge, color = Pine.copy(alpha = .9f))
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodyMedium, color = Pine.copy(alpha = .7f))
        }
    }
}

@Composable
private fun ChartHint(text: String = "Touche le graphique ou fais glisser ton doigt pour lire le détail d’un point.") {
    Text(text, style = MaterialTheme.typography.labelLarge, color = Pine.copy(alpha = .5f), modifier = Modifier.padding(top = 10.dp))
}

private fun DrawScope.gridlines() {
    listOf(0f, .25f, .5f, .75f, 1f).forEach { f ->
        val y = size.height * (1 - f)
        drawLine(Pine.copy(alpha = if (f == 0f || f == 1f) .24f else .10f), Offset(0f, y), Offset(size.width, y), 1.5f)
    }
}

private fun DrawScope.thresholdLine(threshold: Double?, max: Double) {
    threshold?.let { t ->
        val y = (size.height - (t / max * size.height)).toFloat()
        drawLine(Color(0xFFD9534F), Offset(0f, y), Offset(size.width, y), 3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 9f)))
    }
}

/** Metric tile mirroring the web `.metric` card: small label, large value, optional explanation on tap. */
@Composable
private fun MetricTile(label: String, value: String, hint: String? = null, modifier: Modifier = Modifier) {
    var open by remember(label) { mutableStateOf(false) }
    Surface(color = Mint.copy(alpha = .45f), shape = RoundedCornerShape(16.dp), modifier = modifier) {
        Column(
            Modifier.pointerInput(hint) { if (hint != null) detectTapGestures { open = !open } }
                .defaultMinSize(minHeight = 74.dp).padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(label, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.labelLarge, color = Pine.copy(alpha = .75f))
                if (hint != null) { Spacer(Modifier.width(5.dp)); Text("?", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Black, color = Pine.copy(alpha = .45f)) }
            }
            Spacer(Modifier.height(3.dp))
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Pine)
            if (open && hint != null) Text(hint, style = MaterialTheme.typography.bodyMedium, color = Pine.copy(alpha = .75f), modifier = Modifier.padding(top = 8.dp))
        }
    }
}

/** Two-column grid of [MetricTile]; each entry is label / value / optional explanation. */
@Composable
private fun MetricGrid(items: List<Triple<String, String, String?>>) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { (label, value, hint) -> MetricTile(label, value, hint, Modifier.weight(1f)) }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** Labelled horizontal bars — far more legible than 5-7 hairline vertical bars on a phone. */
@Composable
private fun CategoryBars(rows: List<Triple<String, Double, Color>>, unit: String, digits: Int = 1) {
    val max = rows.maxOfOrNull { it.second }?.coerceAtLeast(.01) ?: .01
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        rows.forEach { (label, value, color) ->
            Column {
                Row(Modifier.fillMaxWidth()) {
                    Text(label, Modifier.weight(1f).padding(end = 10.dp), style = MaterialTheme.typography.bodyLarge, color = Pine.copy(alpha = .85f))
                    Text("${fmt(value, digits)}$unit", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, color = Pine)
                }
                Spacer(Modifier.height(5.dp))
                Box(Modifier.fillMaxWidth().height(14.dp).background(Mint.copy(alpha = .55f), RoundedCornerShape(7.dp))) {
                    Box(Modifier.fillMaxWidth((value / max).toFloat().coerceIn(.001f, 1f)).height(14.dp).background(color, RoundedCornerShape(7.dp)))
                }
            }
        }
    }
}

@Composable
private fun MetricChips(metric: String, onSelect: (String) -> Unit) {
    Row(Modifier.padding(bottom = 6.dp)) {
        listOf("standards" to "Standards", "grams" to "Grammes").forEach { (value, label) ->
            FilterChip(metric == value, { onSelect(value) }, { Text(label) }, Modifier.padding(end = 8.dp))
        }
    }
}

@Composable
private fun BarChart(
    values: List<Double>, colors: List<Color>, threshold: Double? = null, modifier: Modifier = Modifier,
    labels: List<String> = emptyList(), unit: String = "", height: Dp = 180.dp, digits: Int = 2,
    xStart: String = "", xEnd: String = "",
) {
    val max = (values.maxOrNull() ?: 0.0).coerceAtLeast(threshold ?: 0.0).coerceAtLeast(0.01)
    var selected by remember(values) { mutableStateOf<Int?>(null) }
    Column {
        Row(Modifier.fillMaxWidth()) {
            ChartYAxis(max, height)
            Canvas(
                modifier.weight(1f).height(height)
                    .pointerInput(values) { detectTapGestures { tap -> if (values.isNotEmpty()) selected = ((tap.x / size.width) * values.size).toInt().coerceIn(0, values.lastIndex) } }
                    .pointerInput(values) { detectHorizontalDragGestures { change, _ -> if (values.isNotEmpty()) selected = ((change.position.x / size.width) * values.size).toInt().coerceIn(0, values.lastIndex) } },
            ) {
                gridlines()
                if (values.isEmpty()) return@Canvas
                val slot = size.width / values.size
                val gap = if (values.size > 40) 1f else 3f
                val bw = (slot - gap).coerceAtLeast(1.5f)
                values.forEachIndexed { i, v ->
                    val h = (v / max * size.height).toFloat()
                    if (h > 0f) drawRect(colors.getOrElse(i) { Pine }, Offset(i * slot + gap / 2, size.height - h), Size(bw, h.coerceAtLeast(2f)))
                }
                thresholdLine(threshold, max)
                selected?.let { i ->
                    drawRect(Amber.copy(alpha = .22f), Offset(i * slot, 0f), Size(slot, size.height))
                    val h = (values[i] / max * size.height).toFloat()
                    drawRect(Amber, Offset(i * slot + gap / 2, size.height - h.coerceAtLeast(2f)), Size(bw, h.coerceAtLeast(2f)))
                }
            }
        }
        ChartXAxis(xStart, xEnd)
        val i = selected
        if (i != null) ChartSelection(labels.getOrNull(i) ?: "Valeur ${i + 1}", "${fmt(values[i], digits)}$unit") else ChartHint()
    }
}

/** Daily bars behind one or more overlay lines, shared Y scale, scrubbable. */
@Composable
private fun ComboChart(
    bars: List<Double>, lines: List<Pair<Color, List<Double?>>>, threshold: Double? = null, modifier: Modifier = Modifier,
    labels: List<String> = emptyList(), unit: String = "", lineNames: List<String> = listOf("7 jours", "30 jours", "90 jours"),
    height: Dp = 230.dp, xStart: String = "", xEnd: String = "", thresholdName: String? = null, barName: String = "Par jour",
) {
    val allLine = lines.flatMap { it.second }.filterNotNull()
    val max = (bars.maxOrNull() ?: 0.0)
        .coerceAtLeast(allLine.maxOrNull() ?: 0.0)
        .coerceAtLeast(threshold ?: 0.0)
        .coerceAtLeast(0.01)
    var selected by remember(bars) { mutableStateOf<Int?>(null) }
    Column {
        Row(Modifier.fillMaxWidth()) {
            ChartYAxis(max, height)
            Canvas(
                modifier.weight(1f).height(height)
                    .pointerInput(bars) { detectTapGestures { tap -> if (bars.isNotEmpty()) selected = ((tap.x / size.width) * bars.size).toInt().coerceIn(0, bars.lastIndex) } }
                    .pointerInput(bars) { detectHorizontalDragGestures { change, _ -> if (bars.isNotEmpty()) selected = ((change.position.x / size.width) * bars.size).toInt().coerceIn(0, bars.lastIndex) } },
            ) {
                gridlines()
                if (bars.isEmpty()) return@Canvas
                val slot = size.width / bars.size
                fun center(i: Int) = (i + .5f) * slot
                fun y(v: Double) = (size.height - (v / max * size.height)).toFloat()
                bars.forEachIndexed { i, v ->
                    val h = (v / max * size.height).toFloat()
                    if (h > 0f) drawRect(Pine.copy(alpha = .22f), Offset(i * slot + .5f, size.height - h.coerceAtLeast(2f)), Size((slot - 1f).coerceAtLeast(1.5f), h.coerceAtLeast(2f)))
                }
                thresholdLine(threshold, max)
                lines.forEach { (color, series) ->
                    if (series.count { it != null } < 2) return@forEach
                    val path = Path(); var started = false
                    series.forEachIndexed { i, v ->
                        if (v == null) { started = false; return@forEachIndexed }
                        if (!started) { path.moveTo(center(i), y(v)); started = true } else path.lineTo(center(i), y(v))
                    }
                    drawPath(path, color, style = Stroke(width = 5f, cap = StrokeCap.Round))
                }
                selected?.let { i ->
                    val x = center(i)
                    drawLine(Amber, Offset(x, 0f), Offset(x, size.height), 3f)
                    drawCircle(Amber, 9f, Offset(x, y(bars[i])))
                    lines.forEach { (color, series) ->
                        series.getOrNull(i)?.let { v -> drawCircle(Color.White, 11f, Offset(x, y(v))); drawCircle(color, 8f, Offset(x, y(v))) }
                    }
                }
            }
        }
        ChartXAxis(xStart, xEnd)
        ChartLegend(
            buildList {
                add(Pine.copy(alpha = .22f) to barName)
                lines.forEachIndexed { n, pair -> add(pair.first to "Moyenne mobile ${lineNames.getOrNull(n) ?: ""}".trim()) }
                if (threshold != null && thresholdName != null) add(Color(0xFFD9534F) to thresholdName)
            },
        )
        val i = selected
        if (i != null) ChartSelection(
            labels.getOrNull(i) ?: "Point ${i + 1}",
            "Journée : ${fmt(bars[i], 2)}$unit",
            lines.mapIndexedNotNull { n, pair -> pair.second.getOrNull(i)?.let { "Moy. ${lineNames.getOrNull(n) ?: "mobile"} : ${fmt(it, 2)}$unit" } }
                .joinToString(" · ").takeIf { it.isNotBlank() },
        ) else ChartHint()
    }
}

@Composable
private fun LineChart(
    values: List<Double?>, modifier: Modifier = Modifier, color: Color = Pine, labels: List<String> = emptyList(),
    unit: String = "", height: Dp = 190.dp, xStart: String = "", xEnd: String = "",
) {
    val present = values.filterNotNull()
    val min = present.minOrNull() ?: 0.0
    val max = present.maxOrNull() ?: 1.0
    val span = (max - min).takeIf { it > 0 } ?: 1.0
    var selected by remember(values) { mutableStateOf<Int?>(null) }
    fun index(x: Float, width: Int): Int = if (values.size <= 1) 0 else ((x / width) * (values.size - 1) + .5f).toInt().coerceIn(0, values.lastIndex)
    Column {
        Row(Modifier.fillMaxWidth()) {
            ChartYAxis(max, height, min)
            Canvas(
                modifier.weight(1f).height(height)
                    .pointerInput(values) { detectTapGestures { tap -> if (values.isNotEmpty()) selected = index(tap.x, size.width) } }
                    .pointerInput(values) { detectHorizontalDragGestures { change, _ -> if (values.isNotEmpty()) selected = index(change.position.x, size.width) } },
            ) {
                gridlines()
                if (present.size < 2) return@Canvas
                val step = size.width / (values.size - 1).coerceAtLeast(1)
                fun y(v: Double) = (size.height - ((v - min) / span * size.height)).toFloat()
                val path = Path(); var started = false
                values.forEachIndexed { i, v ->
                    if (v == null) { started = false; return@forEachIndexed }
                    if (!started) { path.moveTo(i * step, y(v)); started = true } else path.lineTo(i * step, y(v))
                }
                drawPath(path, color, style = Stroke(width = 5f, cap = StrokeCap.Round))
                if (values.size <= 45) values.forEachIndexed { i, v -> if (v != null) drawCircle(color, 5f, Offset(i * step, y(v))) }
                selected?.let { i ->
                    val x = i * step
                    drawLine(Amber, Offset(x, 0f), Offset(x, size.height), 3f)
                    values[i]?.let { v -> drawCircle(Color.White, 11f, Offset(x, y(v))); drawCircle(Amber, 8f, Offset(x, y(v))) }
                }
            }
        }
        ChartXAxis(xStart, xEnd)
        val i = selected
        if (i != null) ChartSelection(labels.getOrNull(i) ?: "Point ${i + 1}", values[i]?.let { "${fmt(it, 2)}$unit" } ?: "Aucune donnée") else ChartHint()
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
fun StatsScreen(context: Context, drinks:List<DrinkEntity>, trackedDays:List<TrackedDayEntity>, healthRows:List<HealthAggregateEntity>,settings:LocalSettings) {
    var start by remember { mutableStateOf(LocalDate.now().minusDays(89)) }
    var end by remember { mutableStateOf(LocalDate.now()) }
    var custom by remember { mutableStateOf(false) }
    val days=remember(drinks,start,end){generateSequence(start){if(it<end)it.plusDays(1)else null}.map { day ->
        val rows=drinks.filter { runCatching{trackedDay(it.startedAt,settings.dayStartHour)==day}.getOrDefault(false) };val sober=trackedDays.any{it.day==day.toString()&&it.sober};LocalStatDay(day,rows.sumOf{alcoholGrams(it.volumeMl,it.abvPercent,it.quantity)},rows.sumOf{canadianStandards(it.volumeMl,it.abvPercent,it.quantity,settings.standardDrinkGrams)},rows,rows.isNotEmpty()||sober,sober)
    }.toList()};val observed=days.filter{it.observed};val drinking=days.filter{it.grams>0};val totalStd=observed.sumOf{it.standards};val totalGrams=observed.sumOf{it.grams}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        PageHeaderLite("Analyse", "Stats")
        StatsPeriodSelector(start, end, custom, onPreset = { days -> start=LocalDate.now().minusDays(days-1L);end=LocalDate.now();custom=false }, onCustom = { a,b -> start=a;end=b;custom=true })
        SectionCard("Période observée", "Du ${start.format(STAT_DATE)} au ${end.format(STAT_DATE)}") {
            MetricGrid(listOf(
                Triple("Jours observés", observed.size.toString(), null),
                Triple("Jours sans alcool", "${observed.count{it.sober}} · ${fmt(observed.count{it.sober}*100.0/observed.size.coerceAtLeast(1),0)} %", null),
                Triple("Jours sans donnée", days.count{!it.observed}.toString(), null),
                Triple("Total standards", fmt(totalStd,1), null),
                Triple("Moyenne / jour observé", "${fmt(totalStd/observed.size.coerceAtLeast(1),2)} std", null),
                Triple("Maximum", "${fmt(observed.maxOfOrNull{it.standards},2)} std", "Journée observée la plus élevée : ${fmt(observed.maxOfOrNull{it.grams},0)} g d’alcool pur."),
            ))
            Spacer(Modifier.height(10.dp))
            Text("Total : ${fmt(totalGrams,0)} g d’alcool pur", style = MaterialTheme.typography.bodyMedium, color = Pine.copy(alpha=.7f))
        }
        LocalTrendSection(days)
        DistributionSummary(observed,settings.standardDrinkGrams)
        HeatmapSection(days)
        DistributionChart(days)
        WeekdayChart(days)
        HourChart(drinking.flatMap{it.drinks})
        FirstDrinkChart(drinking)
        DayToDayChart(days)
        PeriodBars("Évolution par semaine",aggregateLocal(days,false))
        PeriodBars("Évolution par mois",aggregateLocal(days,true),monthly=true)
        LocalHealthSection(healthRows,start,end)
        SessionsSection(drinking.flatMap{it.drinks},settings.standardDrinkGrams)
        Spacer(Modifier.height(28.dp))
    }
}

private data class LocalStatDay(val date:LocalDate,val grams:Double,val standards:Double,val drinks:List<DrinkEntity>,val observed:Boolean,val sober:Boolean)
private data class LocalPeriod(val label:String,val standards:Double,val alcoholFree:Int)

@Composable private fun LocalTrendSection(days:List<LocalStatDay>){
    var metric by remember{mutableStateOf("standards")}
    // The chart opens on the first day that actually carries data: leading empty days say nothing.
    val rows=remember(days){days.dropWhile{!it.observed}}
    val daily=rows.map{if(metric=="grams")it.grams else it.standards}
    fun moving(n:Int)=rows.indices.map{i->rows.subList(maxOf(0,i-n+1),i+1).filter{it.observed}.map{if(metric=="grams")it.grams else it.standards}.takeIf{it.isNotEmpty()}?.average()}
    SectionCard("Tendance lissée","Consommation quotidienne et moyennes mobiles 7 / 30 / 90 jours"){
        MetricChips(metric){metric=it}
        if(rows.isEmpty()){Text("Aucune journée avec données dans cette période.",style=MaterialTheme.typography.bodyLarge,color=Pine.copy(alpha=.7f));return@SectionCard}
        ComboChart(daily,listOf(Pine to moving(7),Amber to moving(30),Pine.copy(alpha=.45f) to moving(90)),
            threshold=if(metric=="standards")3.0 else null,
            labels=rows.map{it.date.format(DAY_DATE)},unit=if(metric=="grams")" g" else " std",
            xStart=rows.first().date.format(SHORT_DATE),xEnd=rows.last().date.format(SHORT_DATE),
            thresholdName="Seuil 3 standards",barName=if(metric=="grams")"Grammes par jour" else "Standards par jour")
        Spacer(Modifier.height(8.dp))
        Text("Départ au ${rows.first().date.format(STAT_DATE)}, première journée avec données de la période.",style=MaterialTheme.typography.bodyMedium,color=Pine.copy(alpha=.65f))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun HeatmapSection(days:List<LocalStatDay>){val max=days.maxOfOrNull{it.grams}?.coerceAtLeast(.01)?:.01;var selected by remember(days){mutableStateOf<LocalStatDay?>(null)}
    SectionCard("Calendrier de consommation","Intensité quotidienne · touche une journée"){
        FlowRow(horizontalArrangement=Arrangement.spacedBy(4.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){days.forEach{d->Box(Modifier.size(19.dp).background(if(selected==d)Amber else if(!d.observed)Color(0xFFE2E5E3) else if(d.sober)Mint.copy(alpha=.85f)else Pine.copy(alpha=(.25+.75*d.grams/max).toFloat()),RoundedCornerShape(4.dp)).pointerInput(d){detectTapGestures{selected=d}})}}
        Spacer(Modifier.height(10.dp))
        ChartLegend(listOf(Color(0xFFE2E5E3) to "Sans donnée",Mint.copy(alpha=.85f) to "Journée sobre",Pine.copy(alpha=.45f) to "Faible",Pine to "Élevée"))
        Text("${days.firstOrNull()?.date?.format(STAT_DATE)?:"—"} — ${days.lastOrNull()?.date?.format(STAT_DATE)?:"—"}",style=MaterialTheme.typography.bodyMedium,color=Pine.copy(alpha=.6f),modifier=Modifier.padding(top=8.dp))
        val day=selected
        if(day!=null)ChartSelection(day.date.format(DAY_DATE),if(!day.observed)"Aucune donnée" else if(day.sober)"Journée sobre · 0 g" else "${fmt(day.grams,1)} g · ${fmt(day.standards,2)} std",if(day.observed&&!day.sober)"${day.drinks.sumOf{d->d.quantity}} consommation${if(day.drinks.sumOf{d->d.quantity}>1)"s"else""}" else null)
        else ChartHint("Touche une case pour voir le détail de la journée.")
    }
}

private fun aggregateLocal(days:List<LocalStatDay>,monthly:Boolean):List<LocalPeriod> = days.groupBy{if(monthly)it.date.withDayOfMonth(1) else it.date.minusDays(it.date.dayOfWeek.value-1L)}.toSortedMap().map{(date,rows)->LocalPeriod(date.toString(),rows.filter{it.observed}.sumOf{it.standards},rows.count{it.sober})}.takeLast(12)

private fun periodLabel(label:String,monthly:Boolean):String = runCatching{LocalDate.parse(label).format(if(monthly)DateTimeFormatter.ofPattern("MMMM yyyy",Locale.CANADA_FRENCH) else SHORT_DATE)}.getOrDefault(label)

@Composable private fun PeriodBars(title:String,rows:List<LocalPeriod>,monthly:Boolean=false){SectionCard(title,"Standards totaux · 12 dernières périodes"){
    BarChart(rows.map{it.standards},rows.map{Pine},labels=rows.map{if(monthly)periodLabel(it.label,true) else "Semaine du ${periodLabel(it.label,false)}"},unit=" std",digits=1,height=170.dp,xStart=rows.firstOrNull()?.let{periodLabel(it.label,monthly)}?:"",xEnd=rows.lastOrNull()?.let{periodLabel(it.label,monthly)}?:"")
    Spacer(Modifier.height(6.dp))
    rows.takeLast(if(monthly)12 else 6).reversed().forEach{StatRow(if(monthly)periodLabel(it.label,true) else periodLabel(it.label,false),"${fmt(it.standards,1)} std · ${it.alcoholFree} j sobres")}
}}

@Composable private fun SessionsSection(drinks:List<DrinkEntity>,standardGrams:Double=CANADIAN_STANDARD_GRAMS){val sorted=drinks.sortedBy{it.startedAt};val sessions=mutableListOf<MutableList<DrinkEntity>>();sorted.forEach{d->val at=runCatching{parseDrinkTime(d.startedAt)}.getOrNull();val last=sessions.lastOrNull()?.lastOrNull()?.let{runCatching{parseDrinkTime(it.startedAt).plusMinutes(it.durationMinutes.toLong())}.getOrNull()};if(last==null||at==null||java.time.Duration.between(last,at).toHours()>=8)sessions.add(mutableListOf(d))else sessions.last().add(d)}
    SectionCard("Sessions","Écart de 8 h · calcul local") { sessions.takeLast(8).reversed().forEach { rows -> val std=rows.sumOf{canadianStandards(it.volumeMl,it.abvPercent,it.quantity,standardGrams)};StatRow(runCatching{LocalDate.parse(rows.first().startedAt.take(10)).format(STAT_DATE)}.getOrDefault(rows.first().startedAt.take(10)),"${fmt(std,2)} std · ${rows.sumOf{it.quantity}} consommation${if(rows.sumOf{it.quantity}>1)"s"else""}") } }
}

@Composable private fun WeekdayChart(days:List<LocalStatDay>){val labels=listOf("Lundi","Mardi","Mercredi","Jeudi","Vendredi","Samedi","Dimanche");val values=(1..7).map{w->days.filter{it.date.dayOfWeek.value==w}.sumOf{it.grams}}
    SectionCard("Grammes par jour de semaine","Somme cumulée sur la période"){CategoryBars(labels.mapIndexed{i,l->Triple(l,values[i],if(values[i]>=(values.maxOrNull()?:0.0)&&values[i]>0)Amber else Pine)}," g",0)}}

@Composable private fun HourChart(drinks:List<DrinkEntity>){val values=(0..23).map{hour->drinks.filter{runCatching{parseDrinkTime(it.startedAt).hour==hour}.getOrDefault(false)}.sumOf{alcoholGrams(it.volumeMl,it.abvPercent,it.quantity)}};val groups=drinks.groupBy{it.startedAt.take(10)}
    fun decimal(t:OffsetDateTime)=t.hour+t.minute/60.0
    val first=groups.values.mapNotNull{rows->rows.mapNotNull{runCatching{parseDrinkTime(it.startedAt)}.getOrNull()}.minOrNull()?.let(::decimal)}.averageOrNull()
    val last=groups.values.mapNotNull{rows->rows.mapNotNull{d->runCatching{parseDrinkTime(d.startedAt).plusMinutes(d.durationMinutes.toLong())}.getOrNull()}.maxOrNull()?.let(::decimal)}.averageOrNull()
    fun clock(v:Double?)=v?.let{val mins=(it*60).roundToInt().mod(24*60);"${(mins/60).toString().padStart(2,'0')}:${(mins%60).toString().padStart(2,'0')}"}?:"—"
    SectionCard("Par heure de début","Hauteur = grammes d’alcool pur · axe horizontal = heure de la journée"){
        BarChart(values,values.map{if(it>0)Pine else Mint},labels=(0..23).map{"${it.toString().padStart(2,'0')} h — ${(it+1).mod(24).toString().padStart(2,'0')} h"},unit=" g",digits=1,height=170.dp,xStart="00 h",xEnd="23 h")
        Spacer(Modifier.height(8.dp))
        MetricGrid(listOf(Triple("Première consommation",clock(first),"Heure moyenne de la première consommation d’une journée."),Triple("Dernière consommation",clock(last),"Heure moyenne de fin de la dernière consommation d’une journée.")))
    }}

@Composable private fun DistributionChart(days:List<LocalStatDay>){val observed=days.filter{it.observed};val labels=listOf("0 standard","≤ 1 standard","1 à 2 standards","2 à 4 standards","Plus de 4 standards");val values=listOf(observed.count{it.sober},observed.count{it.standards in .000001..1.0},observed.count{it.standards>1&&it.standards<=2},observed.count{it.standards>2&&it.standards<=4},observed.count{it.standards>4}).map{it.toDouble()}
    val colors=listOf(Mint,Pine.copy(alpha=.4f),Pine.copy(alpha=.65f),Amber,Color(0xFFD9534F))
    SectionCard("Répartition des journées","Nombre de journées observées par intensité"){CategoryBars(labels.mapIndexed{i,l->Triple(l,values[i],colors[i])}," j",0)}}

@Composable private fun FirstDrinkChart(days:List<LocalStatDay>){val rows=days.mapNotNull{day->day.drinks.minByOrNull{it.startedAt}?.let{d->runCatching{parseDrinkTime(d.startedAt).let{day.date to (it.hour+it.minute/60.0)}}.getOrNull()}}
    SectionCard("Heure de première consommation","Journées avec consommation"){
        if(rows.isEmpty()){Text("Aucune consommation dans cette période.",style=MaterialTheme.typography.bodyLarge,color=Pine.copy(alpha=.7f));return@SectionCard}
        BarChart(rows.map{it.second},rows.map{Pine},labels=rows.map{it.first.format(DAY_DATE)},unit=" h",digits=1,height=170.dp,xStart=rows.first().first.format(SHORT_DATE),xEnd=rows.last().first.format(SHORT_DATE))
    }}

@Composable private fun DayToDayChart(days:List<LocalStatDay>){val rows=days.dropWhile{!it.observed};val changes=rows.mapIndexed{i,d->if(i==0||!d.observed||!rows[i-1].observed)null else d.standards-rows[i-1].standards}
    SectionCard("Consommation d’un jour à l’autre","Variation en standards par rapport à la veille"){
        if(changes.count{it!=null}<2){Text("Pas assez de journées consécutives observées.",style=MaterialTheme.typography.bodyLarge,color=Pine.copy(alpha=.7f));return@SectionCard}
        LineChart(changes,color=Pine,labels=rows.map{it.date.format(DAY_DATE)},unit=" std",xStart=rows.first().date.format(SHORT_DATE),xEnd=rows.last().date.format(SHORT_DATE))
    }}

private fun List<Double>.averageOrNull()=if(isEmpty())null else average()
private fun quantile(sorted:List<Double>,q:Double):Double?{if(sorted.isEmpty())return null;val p=(sorted.size-1)*q;val lo=p.toInt();val hi=kotlin.math.ceil(p).toInt();return if(lo==hi)sorted[lo]else sorted[lo]+(sorted[hi]-sorted[lo])*(p-lo)}

// Same wording as the web `distributionHelp` tooltips, so both clients explain a statistic identically.
private val DISTRIBUTION_HELP = mapOf(
    "Moyenne" to "Votre niveau moyen par journée observée. Elle sert à suivre l’évolution générale, mais peut être tirée vers le haut par quelques journées très élevées.",
    "Médiane" to "Votre journée la plus représentative : la moitié des journées est en dessous et l’autre moitié au-dessus. Utile lorsque quelques épisodes élevés déforment la moyenne.",
    "Quartile 1" to "25 % des journées observées sont à ce niveau ou moins. Peut servir de repère réaliste pour vos journées de plus faible consommation.",
    "Quartile 3" to "75 % des journées observées sont à ce niveau ou moins. Au-dessus, vous entrez dans le quart de vos journées les plus élevées.",
    "P90" to "90 % des journées observées sont à ce niveau ou moins. Les valeurs supérieures correspondent à vos 10 % de journées les plus élevées et peuvent aider à repérer les épisodes exceptionnels.",
    "Écart-type" to "Indique à quel point vos journées varient autour de la moyenne. Une valeur faible signifie un rythme stable; une valeur élevée indique des écarts importants d’une journée à l’autre.",
    "Coeff. variation" to "Rapporte la variabilité à votre moyenne. Pratique pour voir si votre rythme devient plus régulier même lorsque votre niveau moyen change.",
    "Minimum" to "Votre plus faible journée réellement observée. Les journées sobres explicitement consignées peuvent donc donner une valeur de zéro.",
    "Maximum" to "Votre journée observée la plus élevée. Sert à identifier l’ampleur de votre pic historique, sans en faire un record à battre.",
)

@Composable private fun DistributionSummary(days:List<LocalStatDay>,standardGrams:Double=CANADIAN_STANDARD_GRAMS){var metric by remember{mutableStateOf("standards")};val values=days.map{if(metric=="grams")it.grams else it.standards}.sorted();val mean=values.averageOrNull();val sd=mean?.let{m->kotlin.math.sqrt(values.sumOf{(it-m)*(it-m)}/values.size.coerceAtLeast(1))};val unit=if(metric=="grams")" g"else" std"
    SectionCard("Distribution complète","Par journée observée · 1 consommation standard = ${fmt(standardGrams,2)} g d’alcool pur."){
        MetricChips(metric){metric=it}
        MetricGrid(listOf(
            Triple("Moyenne",fmt(mean,2)+unit,DISTRIBUTION_HELP["Moyenne"]),
            Triple("Médiane",fmt(quantile(values,.5),2)+unit,DISTRIBUTION_HELP["Médiane"]),
            Triple("Quartile 1",fmt(quantile(values,.25),2)+unit,DISTRIBUTION_HELP["Quartile 1"]),
            Triple("Quartile 3",fmt(quantile(values,.75),2)+unit,DISTRIBUTION_HELP["Quartile 3"]),
            Triple("P90",fmt(quantile(values,.9),2)+unit,DISTRIBUTION_HELP["P90"]),
            Triple("Écart-type",fmt(sd,2)+unit,DISTRIBUTION_HELP["Écart-type"]),
            Triple("Coeff. variation",if(mean==null||mean==0.0)"—"else fmt(sd!!/mean,2),DISTRIBUTION_HELP["Coeff. variation"]),
            Triple("Minimum",fmt(values.minOrNull(),2)+unit,DISTRIBUTION_HELP["Minimum"]),
            Triple("Maximum",fmt(values.maxOrNull(),2)+unit,DISTRIBUTION_HELP["Maximum"]),
        ))
        Spacer(Modifier.height(10.dp))
        Text("Touche une tuile pour l’explication de la statistique.",style=MaterialTheme.typography.labelLarge,color=Pine.copy(alpha=.5f))
    }}

@Composable private fun LocalHealthSection(rows:List<HealthAggregateEntity>,start:LocalDate,end:LocalDate){val filtered=rows.filter{it.localDate>=start.toString()&&it.localDate<=end.toString()};val types=filtered.map{it.recordType}.distinct();if(types.isEmpty()){SectionCard("Données de santé"){Text("Aucune donnée Health Connect locale pour cette période.",style=MaterialTheme.typography.bodyLarge,color=Pine.copy(alpha=.7f))};return};var metric by remember(types){mutableStateOf(types.first())};val selected=filtered.filter{it.recordType==metric}.groupBy{it.localDate}.toSortedMap();val values=selected.mapValues{(_,rs)->rs.mapNotNull{runCatching{JSONObject(it.payload).optDouble("value")}.getOrNull()}.averageOrNull()};val unit=filtered.firstOrNull{it.recordType==metric}?.let{runCatching{JSONObject(it.payload).optString("unit")}.getOrDefault("")}?:""
    SectionCard("Données de santé","Données locales Health Connect"){
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom=6.dp)){types.forEach{t->FilterChip(metric==t,{metric=t},{Text(HEALTH_LABELS[t]?:t)},Modifier.padding(end=8.dp))}}
        val suffix=if(unit.isBlank())""else" $unit"
        LineChart(values.values.toList(),color=Amber,labels=values.keys.map{runCatching{LocalDate.parse(it).format(DAY_DATE)}.getOrDefault(it)},unit=suffix,xStart=values.keys.firstOrNull()?.let{runCatching{LocalDate.parse(it).format(SHORT_DATE)}.getOrDefault(it)}?:"",xEnd=values.keys.lastOrNull()?.let{runCatching{LocalDate.parse(it).format(SHORT_DATE)}.getOrDefault(it)}?:"")
        Spacer(Modifier.height(10.dp))
        MetricGrid(listOf(Triple("Moyenne",fmt(values.values.filterNotNull().averageOrNull(),1)+suffix,null),Triple("Jours avec donnée",values.size.toString(),null)))
    }}

private val STAT_DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.CANADA_FRENCH)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StatsPeriodSelector(start:LocalDate,end:LocalDate,custom:Boolean,onPreset:(Int)->Unit,onCustom:(LocalDate,LocalDate)->Unit) {
    var dialog by remember { mutableStateOf<String?>(null) }
    var draftStart by remember(start) { mutableStateOf(start) };var draftEnd by remember(end) { mutableStateOf(end) }
    Column(Modifier.padding(horizontal=20.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            listOf(7 to "7 j",30 to "30 j",90 to "90 j",180 to "180 j",365 to "1 an").forEach { (days,label) ->
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
        }){Text(if(target=="start") "Suivant" else "Appliquer")}},dismissButton={TextButton(onClick={dialog=null}){Text("Annuler")}}){DatePicker(state)}
    }
}

private fun strength(r: Double): String = when {
    abs(r) < 0.2 -> "faible"
    abs(r) < 0.4 -> "modérée"
    else -> "forte"
}

/* ---------- Repères (insights) ---------- */

@Composable
fun InsightsScreen(drinks:List<DrinkEntity>,checkIns:List<CheckInEntity>,settings:LocalSettings) {
    val data=remember(drinks,checkIns,settings){personalAnalytics(drinks,checkIns,settings)}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { PageHeaderLite("Associations personnelles", "Repères")
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
        Spacer(Modifier.height(28.dp))
    }
}

private fun personalAnalytics(drinks:List<DrinkEntity>,checkIns:List<CheckInEntity>,settings:LocalSettings):JSONObject{
    val totals=drinks.groupBy{runCatching{trackedDay(it.startedAt,settings.dayStartHour).toString()}.getOrDefault(it.startedAt.take(10))}.mapValues{(_,rows)->rows.sumOf{alcoholGrams(it.volumeMl,it.abvPercent,it.quantity)}}
    val parsed=checkIns.mapNotNull{runCatching{JSONObject(it.payload)}.getOrNull()};val rows=parsed.mapNotNull{c->totals[c.optString("local_date")]?.let{actual->Triple(c,actual,maxOf(0.0,actual-c.optDouble("planned_grams",0.0)))}}
    fun association(name:String,value:(JSONObject)->Double?):JSONObject{val pairs=rows.mapNotNull{(c,_,excess)->value(c)?.let{it to excess}};val out=JSONObject().put("factor",name).put("sample_size",pairs.size);if(pairs.size<5)return out.put("status","insufficient_data")
        val ax=pairs.map{it.first}.average();val ay=pairs.map{it.second}.average();val top=pairs.sumOf{(it.first-ax)*(it.second-ay)};val bottom=kotlin.math.sqrt(pairs.sumOf{(it.first-ax)*(it.first-ax)}*pairs.sumOf{(it.second-ay)*(it.second-ay)});return out.put("coefficient",if(bottom==0.0)JSONObject.NULL else top/bottom).put("language","Tes dépassements ont été plus fréquents lorsque $name.")}
    val excess=rows.count{it.third>0};return JSONObject().put("days_available",(totals.keys+parsed.map{it.optString("local_date")}).size).put("events_available",excess)
        .put("associations",JSONArray().put(association("l’envie de boire était plus forte"){it.optDouble("craving")}).put(association("la confiance était plus faible"){10-it.optDouble("confidence")}).put(association("le stress était plus élevé"){if(it.has("stress"))it.optDouble("stress")else null}))
        .put("disclaimer","Ces résultats décrivent des associations personnelles; ils ne démontrent pas une cause.")
        .put("model_readiness",JSONObject().put("descriptive",rows.size>=7).put("associations",rows.size>=20&&excess>=5).put("regularized_model",rows.size>=42&&excess>=10).put("temporal_model",rows.size>=90))
}

/* ---------- Succès ---------- */

@Composable
fun SuccessScreen(drinks:List<DrinkEntity>,tracked:List<TrackedDayEntity>,checkIns:List<CheckInEntity>,goals:List<GoalEntity>,settings:LocalSettings) {
    val data=remember(drinks,tracked,checkIns,goals,settings){localSuccess(drinks,tracked,checkIns,goals,settings)}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { PageHeaderLite("Progrès orientés réduction", "Succès")
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
        Spacer(Modifier.height(28.dp))
    }
}

private fun localSuccess(drinks:List<DrinkEntity>,tracked:List<TrackedDayEntity>,checkIns:List<CheckInEntity>,goals:List<GoalEntity>,settings:LocalSettings):JSONObject{
    val byDay=drinks.groupBy{runCatching{trackedDay(it.startedAt,settings.dayStartHour)}.getOrNull()}.filterKeys{it!=null}.mapKeys{it.key!!}.mapValues{(_,r)->r.sumOf{alcoholGrams(it.volumeMl,it.abvPercent,it.quantity)}}
    val observed=(byDay.keys+tracked.filter{it.sober}.map{LocalDate.parse(it.day)}).toSortedSet();var streak=0;var best=0;var previous:LocalDate?=null;for(day in observed){if(previous?.plusDays(1)!=day)streak=0;if((byDay[day]?:0.0)==0.0){streak++;best=maxOf(best,streak)}else streak=0;previous=day}
    val values=observed.map{byDay[it]?:0.0};val reduction=if(values.size>=60){val previous=values.takeLast(60).take(30).average();val latest=values.takeLast(30).average();if(previous>0)maxOf(0.0,(previous-latest)/previous*100)else 0.0}else 0.0
    data class Def(val id:String,val title:String,val desc:String,val current:Double,val target:Double,val cat:String)
    val defs=mutableListOf<Def>();listOf(1 to "Premier pas",3 to "Carnet ouvert",7 to "Une semaine de données",14 to "Deux semaines de données",30 to "Un mois documenté",60 to "Suivi régulier",90 to "Un trimestre documenté",180 to "Six mois de recul",365 to "Une année de données").forEach{(n,t)->defs+=Def("logged_$n",t,"$n journée${if(n>1)"s"else""} renseignée${if(n>1)"s"else""}",observed.size.toDouble(),n.toDouble(),"tracking")}
    listOf(3,7,14,30).forEach{n->defs+=Def("dry_$n",if(n==3)"Respiration"else if(n==7)"Semaine claire"else if(n==14)"Cap des deux semaines"else"Mois sans alcool","$n jours consécutifs sans alcool",best.toDouble(),n.toDouble(),"streak")}
    defs+=Def("reduce_10","Tendance inversée","Moyenne mobile 30 jours réduite d’au moins 10 %",reduction,10.0,"reduction");defs+=Def("reduce_25","Virage durable","Moyenne mobile 30 jours réduite d’au moins 25 %",reduction,25.0,"reduction")
    val months=observed.groupBy{java.time.YearMonth.from(it)}.toSortedMap().values.map{days->days.sumOf{byDay[it]?:0.0}};val monthReduction=if(months.size>=2&&months[months.lastIndex-1]>0)maxOf(0.0,(months[months.lastIndex-1]-months.last())/months[months.lastIndex-1]*100)else 0.0;defs+=Def("month_10","Mois en progrès","Diminution mensuelle d’au moins 10 %",monthReduction,10.0,"calendar")
    listOf(1 to "Premier check-in",7 to "Prendre du recul",30 to "Repères réguliers",90 to "Habitude de réflexion").forEach{(n,t)->defs+=Def("checkin_$n",t,"$n check-in${if(n>1)"s"else""} personnel${if(n>1)"s"else""} complété${if(n>1)"s"else""}",checkIns.size.toDouble(),n.toDouble(),"checkin")}
    val achieved=goals.count{it.achieved};listOf(1,3,5,10).forEach{n->defs+=Def("goals_$n",if(n==1)"Premier objectif atteint"else"$n objectifs atteints","$n objectif${if(n>1)"s"else""} personnel${if(n>1)"s"else""} atteint${if(n>1)"s"else""}",achieved.toDouble(),n.toDouble(),"goal")}
    var weekendStreak=0;val today=LocalDate.now();var monday=today.minusDays(today.dayOfWeek.value-1L).minusWeeks(1);while(monday>=observed.firstOrNull()?.minusDays(6)?:today){val days=(0L..6L).map{monday.plusDays(it)};if(days.all{it in observed}&&days.take(5).all{(byDay[it]?:0.0)==0.0}&&days.takeLast(2).any{(byDay[it]?:0.0)>0})weekendStreak++ else if(weekendStreak>0)break;monday=monday.minusWeeks(1)};listOf(2,4,8,12).forEach{n->defs+=Def("weekend_$n",when(n){2->"Guerrier du week-end · 2 semaines";4->"Guerrier du week-end · 1 mois";8->"Guerrier du week-end · 2 mois";else->"Guerrier du week-end · 3 mois"},"Consommation limitée au samedi et dimanche pendant $n semaines complètes",weekendStreak.toDouble(),n.toDouble(),"weekend")}
    val badges=JSONArray();defs.forEach{d->badges.put(JSONObject().put("id",d.id).put("title",d.title).put("description",d.desc).put("unlocked",d.current>=d.target).put("current",minOf(d.current,d.target)).put("target",d.target).put("progress_percent",minOf(100.0,d.current/d.target*100)).put("category",d.cat))};return JSONObject().put("badges",badges)
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
fun GoalsScreen(repository:SyncRepository,localGoals:List<GoalEntity>,drinks:List<DrinkEntity>,tracked:List<TrackedDayEntity>,settings:LocalSettings,onSync:()->Unit) {
    val goals=remember(localGoals,drinks,tracked,settings){localGoalRows(localGoals,drinks,tracked,settings)}
    var error by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(goals.toString()){for(i in 0 until goals.length()){val g=goals.getJSONObject(i);val reached=if(g.optString("temporal_mode")=="consecutive_weeks")g.optInt("consecutive_weeks_achieved")>=g.optInt("consecutive_weeks",1)else g.optBoolean("on_track");if(reached)repository.markGoalAchieved(g.getString("client_id"))}}
    PullToRefreshBox(isRefreshing = false, onRefresh = onSync, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            PageHeaderLite("Suivi", "Objectifs")
            Button(onClick = { adding = true }, modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth()) { Text("Ajouter un objectif") }
            error?.let { Text(it, Modifier.padding(20.dp), color = Pine.copy(alpha = .7f)) }
            val list = goals
            if (list.length() == 0) SectionCard("Aucun objectif") {
                Text("Ajoute un objectif pour suivre ta progression semaine après semaine.", color = Pine.copy(alpha = .7f))
            }
            for (i in 0 until list.length()) {
                val g = list.optJSONObject(i) ?: continue
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
                            runCatching { repository.deleteGoal(g.getString("client_id")) }
                                .onSuccess { onSync() }.onFailure { error = it.message }
                        }
                    }) { Text("Retirer", color = Color(0xFFD9534F)) }
                }
            }
            Spacer(Modifier.height(28.dp))
        }
    }
    if (adding) GoalDialog(onDismiss = { adding = false }) { payload ->
        scope.launch {
            runCatching { repository.createGoal(payload) }.onSuccess { adding = false;onSync() }.onFailure { error = it.message }
        }
    }
}

private fun localGoalRows(goals:List<GoalEntity>,drinks:List<DrinkEntity>,tracked:List<TrackedDayEntity>,settings:LocalSettings):JSONArray{
    val now=LocalDate.now();val byDay=drinks.groupBy{runCatching{trackedDay(it.startedAt,settings.dayStartHour)}.getOrNull()}.filterKeys{it!=null}.mapKeys{it.key!!}.mapValues{(_,r)->r.sumOf{alcoholGrams(it.volumeMl,it.abvPercent,it.quantity)}}
    val monday=now.minusDays(now.dayOfWeek.value-1L);val week=(0L..6L).map{monday.plusDays(it)};val weekGrams=week.sumOf{byDay[it]?:0.0};val weekStd=week.sumOf{day->drinks.filter{runCatching{trackedDay(it.startedAt,settings.dayStartHour)==day}.getOrDefault(false)}.sumOf{canadianStandards(it.volumeMl,it.abvPercent,it.quantity,settings.standardDrinkGrams)}};val free=week.count{(byDay[it]?:0.0)==0.0&&(it<=now)&&(byDay.containsKey(it)||tracked.any{t->t.day==it.toString()})};val drinking=week.count{(byDay[it]?:0.0)>0}
    val sorted=drinks.sortedBy{it.startedAt};val sessions=mutableListOf<MutableList<DrinkEntity>>();for(d in sorted){val at=runCatching{parseDrinkTime(d.startedAt)}.getOrNull();val end=sessions.lastOrNull()?.lastOrNull()?.let{parseDrinkTime(it.startedAt).plusMinutes(it.durationMinutes.toLong())};if(at==null||end==null||java.time.Duration.between(end,at).toHours()>=settings.sessionGapHours)sessions+=mutableListOf(d)else sessions.last()+=d};val peak=sessions.takeLast(20).maxOfOrNull{r->r.sumOf{alcoholGrams(it.volumeMl,it.abvPercent,it.quantity)}}?:0.0
    val recent=(0L..6L).map{now.minusDays(it)}.filter{byDay.containsKey(it)||tracked.any{t->t.day==it.toString()}};val moving=if(recent.isEmpty())0.0 else recent.sumOf{byDay[it]?:0.0}/recent.size
    val values=mapOf("max_grams_week" to weekGrams,"max_standards" to weekStd,"min_alcohol_free_days" to free.toDouble(),"max_drinking_days" to drinking.toDouble(),"max_grams_session" to peak,"max_moving_7_grams" to moving)
    fun completedStreak(g:GoalEntity):Int{if(g.kind !in setOf("max_grams_week","max_standards","min_alcohol_free_days","max_drinking_days","max_moving_7_grams"))return 0;var count=0;var start=monday.minusWeeks(1);while(true){val ds=(0L..6L).map{start.plusDays(it)};if(!ds.all{byDay.containsKey(it)||tracked.any{t->t.day==it.toString()}})break;val grams=ds.sumOf{byDay[it]?:0.0};val value=when(g.kind){"max_grams_week"->grams;"max_standards"->ds.sumOf{day->drinks.filter{runCatching{trackedDay(it.startedAt,settings.dayStartHour)==day}.getOrDefault(false)}.sumOf{canadianStandards(it.volumeMl,it.abvPercent,it.quantity,settings.standardDrinkGrams)}};"min_alcohol_free_days"->ds.count{(byDay[it]?:0.0)==0.0}.toDouble();"max_drinking_days"->ds.count{(byDay[it]?:0.0)>0}.toDouble();else->grams/7};val met=if(g.kind=="min_alcohol_free_days")value>=g.target else value<=g.target;if(!met)break;count++;start=start.minusWeeks(1)};return count}
    val out=JSONArray();goals.forEach{g->val current=values[g.kind];val onTrack=current?.let{if(g.kind=="min_alcohol_free_days"||g.kind=="monthly_reduction")it>=g.target else it<=g.target};out.put(JSONObject().put("client_id",g.clientId).put("id",g.serverId?:-1).put("kind",g.kind).put("target",g.target).put("active",g.active).put("current",current).put("on_track",onTrack).put("progress_percent",current?.let{if(g.target>0)it/g.target*100 else null}).put("temporal_mode",g.temporalMode).put("consecutive_weeks",g.consecutiveWeeks).put("consecutive_weeks_achieved",completedStreak(g)).put("due_date",g.dueDate).put("days_remaining",g.dueDate?.let{maxOf(0,java.time.temporal.ChronoUnit.DAYS.between(now,LocalDate.parse(it)).toInt())}))};return out
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
