package ca.repere.mobile

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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

@Composable
private fun PageHeaderLite(eyebrow: String, title: String) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
        Text(
            eyebrow.uppercase(Locale.getDefault()),
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
        colors = CardDefaults.cardColors(containerColor = CardSurface),
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

/** Patterns are resolved against the app locale (Android 13+ per-app language), and cached per locale. */
private val DATE_FORMATS = java.util.concurrent.ConcurrentHashMap<Pair<String, Locale>, DateTimeFormatter>()
private fun dateFormat(pattern: String): DateTimeFormatter = Locale.getDefault().let { locale ->
    DATE_FORMATS.getOrPut(pattern to locale) { DateTimeFormatter.ofPattern(pattern, locale) }
}
private fun shortDate() = dateFormat("d MMM")
private fun dayDate() = dateFormat("EEEE d MMMM yyyy")
private fun monthLabel() = dateFormat("MMM")

private fun axisText(value: Double): String = when {
    value == 0.0 -> "0"
    abs(value) >= 10 -> String.format(Locale.getDefault(), "%.0f", value)
    abs(value) >= 1 -> String.format(Locale.getDefault(), "%.1f", value)
    else -> String.format(Locale.getDefault(), "%.2f", value)
}

private val AXIS_WIDTH = 40.dp

/** Left-hand scale shared by every chart: one label per gridline, so the numbers and the grid always agree. */
@Composable
private fun ChartYAxis(max: Double, height: Dp, min: Double = 0.0) {
    Column(Modifier.width(AXIS_WIDTH).height(height).padding(end = 6.dp), verticalArrangement = Arrangement.SpaceBetween, horizontalAlignment = Alignment.End) {
        (0..GRID_DIVISIONS).forEach { i ->
            Text(axisText(max - (max - min) * i / GRID_DIVISIONS), style = MaterialTheme.typography.labelMedium, color = Pine.copy(alpha = .55f), maxLines = 1)
        }
    }
}

/** Ticks under the canvas, indented past the Y scale so they line up with the plot area. */
@Composable
private fun ChartXAxis(labels: List<String>) {
    if (labels.none { it.isNotBlank() }) return
    Row(Modifier.fillMaxWidth().padding(start = AXIS_WIDTH, top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        labels.forEach { Text(it, style = MaterialTheme.typography.labelMedium, color = Pine.copy(alpha = .55f), maxLines = 1) }
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
private fun ChartHint(text: String = stringResource(R.string.chart_hint)) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = Pine.copy(alpha = .5f), modifier = Modifier.padding(top = 10.dp))
}

private const val GRID_DIVISIONS = 4

private fun DrawScope.gridlines() {
    (0..GRID_DIVISIONS).forEach { i ->
        val y = size.height * i / GRID_DIVISIONS
        drawLine(Pine.copy(alpha = if (i == 0 || i == GRID_DIVISIONS) .24f else .10f), Offset(0f, y), Offset(size.width, y), 1.5f)
    }
}

private fun DrawScope.thresholdLine(threshold: Double?, max: Double) {
    threshold?.let { t ->
        val y = (size.height - (t / max * size.height)).toFloat()
        drawLine(Danger, Offset(0f, y), Offset(size.width, y), 3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 9f)))
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

private data class CatBar(val label: String, val value: Double, val color: Color, val note: String? = null)

/** Labelled horizontal bars — far more legible than a handful of hairline vertical bars on a phone. */
@Composable
private fun CategoryBars(rows: List<CatBar>, unit: String, digits: Int = 1) {
    val max = rows.maxOfOrNull { it.value }?.coerceAtLeast(.01) ?: .01
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        rows.forEach { row ->
            Column {
                Row(Modifier.fillMaxWidth()) {
                    Text(row.label, Modifier.weight(1f).padding(end = 10.dp), style = MaterialTheme.typography.bodyLarge, color = Pine.copy(alpha = .85f))
                    Text(valued(row.value, digits, unit), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, color = Pine)
                }
                Spacer(Modifier.height(5.dp))
                Box(Modifier.fillMaxWidth().height(14.dp).background(Mint.copy(alpha = .55f), RoundedCornerShape(7.dp))) {
                    Box(Modifier.fillMaxWidth((row.value / max).toFloat().coerceIn(.001f, 1f)).height(14.dp).background(row.color, RoundedCornerShape(7.dp)))
                }
                if (row.note != null) Text(row.note, style = MaterialTheme.typography.bodyMedium, color = Pine.copy(alpha = .6f), modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

@Composable
private fun MetricChips(metric: String, onSelect: (String) -> Unit) {
    Row(Modifier.padding(bottom = 6.dp)) {
        listOf("standards" to R.string.metric_standards, "grams" to R.string.metric_grams).forEach { (value, label) ->
            FilterChip(metric == value, { onSelect(value) }, { Text(stringResource(label)) }, Modifier.padding(end = 8.dp))
        }
    }
}

/** Animates 0→1 whenever [key] changes, so a chart's bars/lines grow in instead of popping in fully drawn. */
@Composable
private fun chartReveal(key: Any?): Float {
    val progress = remember(key) { Animatable(0f) }
    LaunchedEffect(key) { progress.animateTo(1f, tween(650, easing = FastOutSlowInEasing)) }
    return progress.value
}

@Composable
private fun BarChart(
    values: List<Double>, colors: List<Color>, threshold: Double? = null, modifier: Modifier = Modifier,
    labels: List<String> = emptyList(), unit: String = "", height: Dp = 180.dp, digits: Int = 2,
    xLabels: List<String> = emptyList(),
) {
    val max = (values.maxOrNull() ?: 0.0).coerceAtLeast(threshold ?: 0.0).coerceAtLeast(0.01)
    var selected by remember(values) { mutableStateOf<Int?>(null) }
    val reveal = chartReveal(values)
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
                    val h = (v / max * size.height).toFloat() * reveal
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
        ChartXAxis(xLabels)
        val i = selected
        if (i != null) ChartSelection(labels.getOrNull(i) ?: stringResource(R.string.chart_value, i + 1), valued(values[i], digits, unit)) else ChartHint()
    }
}

/** Daily bars behind one or more overlay lines, shared Y scale, scrubbable. */
@Composable
private fun ComboChart(
    bars: List<Double>, lines: List<Pair<Color, List<Double?>>>, threshold: Double? = null, modifier: Modifier = Modifier,
    labels: List<String> = emptyList(), unit: String = "", lineNames: List<String> = emptyList(),
    height: Dp = 230.dp, xLabels: List<String> = emptyList(), thresholdName: String? = null, barName: String = stringResource(R.string.chart_series_per_day),
) {
    val movingNames = lineNames.map { stringResource(R.string.chart_moving_average, it) }
    val allLine = lines.flatMap { it.second }.filterNotNull()
    val max = (bars.maxOrNull() ?: 0.0)
        .coerceAtLeast(allLine.maxOrNull() ?: 0.0)
        .coerceAtLeast(threshold ?: 0.0)
        .coerceAtLeast(0.01)
    var selected by remember(bars) { mutableStateOf<Int?>(null) }
    val reveal = chartReveal(bars)
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
                fun y(v: Double) = size.height - (v / max * size.height).toFloat() * reveal
                bars.forEachIndexed { i, v ->
                    val h = (v / max * size.height).toFloat() * reveal
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
                        series.getOrNull(i)?.let { v -> drawCircle(CardSurface, 11f, Offset(x, y(v))); drawCircle(color, 8f, Offset(x, y(v))) }
                    }
                }
            }
        }
        ChartXAxis(xLabels)
        ChartLegend(
            buildList {
                add(Pine.copy(alpha = .22f) to barName)
                lines.forEachIndexed { n, pair -> movingNames.getOrNull(n)?.let { add(pair.first to it) } }
                if (threshold != null && thresholdName != null) add(Danger to thresholdName)
            },
        )
        val i = selected
        if (i != null) ChartSelection(
            labels.getOrNull(i) ?: stringResource(R.string.chart_point, i + 1),
            stringResource(R.string.chart_day_value, valued(bars[i], 2, unit)),
            lines.mapIndexedNotNull { n, pair ->
                pair.second.getOrNull(i)?.let { stringResource(R.string.chart_moving_average_short, lineNames.getOrElse(n) { "" }, valued(it, 2, unit)) }
            }.joinToString(" · ").takeIf { it.isNotBlank() },
        ) else ChartHint()
    }
}

@Composable
private fun LineChart(
    values: List<Double?>, modifier: Modifier = Modifier, color: Color = Pine, labels: List<String> = emptyList(),
    unit: String = "", height: Dp = 190.dp, xLabels: List<String> = emptyList(),
) {
    val present = values.filterNotNull()
    val min = present.minOrNull() ?: 0.0
    val max = present.maxOrNull() ?: 1.0
    val span = (max - min).takeIf { it > 0 } ?: 1.0
    var selected by remember(values) { mutableStateOf<Int?>(null) }
    val reveal = chartReveal(values)
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
                fun y(v: Double): Float {
                    val target = (size.height - ((v - min) / span * size.height)).toFloat()
                    return size.height + (target - size.height) * reveal
                }
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
                    values[i]?.let { v -> drawCircle(CardSurface, 11f, Offset(x, y(v))); drawCircle(Amber, 8f, Offset(x, y(v))) }
                }
            }
        }
        ChartXAxis(xLabels)
        val i = selected
        if (i != null) ChartSelection(labels.getOrNull(i) ?: stringResource(R.string.chart_point, i + 1), values[i]?.let { valued(it, 2, unit) } ?: stringResource(R.string.chart_no_data)) else ChartHint()
    }
}

/** Evenly spaced date ticks for a daily series, so the X axis says more than just its two ends. */
private fun dateTicks(dates: List<LocalDate>, count: Int = 4): List<String> {
    if (dates.isEmpty()) return emptyList()
    if (dates.size <= count) return dates.map { it.format(shortDate()) }
    return (0 until count).map { dates[(it * (dates.size - 1)) / (count - 1)].format(shortDate()) }
}

/** Dots on a fixed scale — for values that are positions (a clock hour), not quantities to compare against zero. */
@Composable
private fun ScatterChart(
    values: List<Double>, max: Double, labels: List<String> = emptyList(), modifier: Modifier = Modifier,
    color: Color = Pine, height: Dp = 190.dp, xLabels: List<String> = emptyList(),
    valueText: (Double) -> String = { fmt(it, 2) },
) {
    var selected by remember(values) { mutableStateOf<Int?>(null) }
    val reveal = chartReveal(values)
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
                fun center(i: Int) = (i + .5f) * slot
                fun y(v: Double): Float {
                    val target = (size.height - (v / max * size.height)).toFloat()
                    return size.height + (target - size.height) * reveal
                }
                val radius = (slot * .38f).coerceIn(3.5f, 9f)
                values.forEachIndexed { i, v -> drawCircle(color.copy(alpha = .75f), radius, Offset(center(i), y(v))) }
                selected?.let { i ->
                    val x = center(i)
                    drawLine(Amber, Offset(x, 0f), Offset(x, size.height), 3f)
                    drawCircle(CardSurface, radius + 4f, Offset(x, y(values[i]))); drawCircle(Amber, radius + 1f, Offset(x, y(values[i])))
                }
            }
        }
        ChartXAxis(xLabels)
        val i = selected
        if (i != null) ChartSelection(labels.getOrNull(i) ?: stringResource(R.string.chart_point, i + 1), valueText(values[i])) else ChartHint()
    }
}

/** Signed bars around a zero baseline — a rise and a drop must not look alike. */
@Composable
private fun DivergingBars(
    values: List<Double?>, labels: List<String> = emptyList(), unit: String = "", modifier: Modifier = Modifier,
    height: Dp = 190.dp, xLabels: List<String> = emptyList(), upColor: Color = Danger, downColor: Color = Pine,
) {
    val bound = (values.filterNotNull().maxOfOrNull { abs(it) } ?: 0.0).coerceAtLeast(0.01)
    var selected by remember(values) { mutableStateOf<Int?>(null) }
    val reveal = chartReveal(values)
    Column {
        Row(Modifier.fillMaxWidth()) {
            ChartYAxis(bound, height, -bound)
            Canvas(
                modifier.weight(1f).height(height)
                    .pointerInput(values) { detectTapGestures { tap -> if (values.isNotEmpty()) selected = ((tap.x / size.width) * values.size).toInt().coerceIn(0, values.lastIndex) } }
                    .pointerInput(values) { detectHorizontalDragGestures { change, _ -> if (values.isNotEmpty()) selected = ((change.position.x / size.width) * values.size).toInt().coerceIn(0, values.lastIndex) } },
            ) {
                gridlines()
                if (values.isEmpty()) return@Canvas
                val zero = size.height / 2
                val slot = size.width / values.size
                val gap = if (values.size > 40) 1f else 3f
                val bw = (slot - gap).coerceAtLeast(1.5f)
                drawLine(Pine.copy(alpha = .45f), Offset(0f, zero), Offset(size.width, zero), 2.5f)
                values.forEachIndexed { i, v ->
                    if (v == null) return@forEachIndexed
                    val h = (abs(v) / bound * (size.height / 2)).toFloat() * reveal
                    if (h <= 0f) return@forEachIndexed
                    val hc = h.coerceAtLeast(2f)
                    val top = if (v >= 0) zero - hc else zero
                    drawRect(if (v >= 0) upColor.copy(alpha = .8f) else downColor.copy(alpha = .8f), Offset(i * slot + gap / 2, top), Size(bw, hc))
                }
                selected?.let { i ->
                    drawRect(Amber.copy(alpha = .22f), Offset(i * slot, 0f), Size(slot, size.height))
                    val x = (i + .5f) * slot
                    drawLine(Amber, Offset(x, 0f), Offset(x, size.height), 3f)
                }
            }
        }
        ChartXAxis(xLabels)
        val i = selected
        if (i != null) ChartSelection(
            labels.getOrNull(i) ?: stringResource(R.string.chart_point, i + 1),
            values[i]?.let { stringResource(R.string.chart_signed_value, if (it > 0) "+" else "", valued(it, 2, unit)) } ?: stringResource(R.string.chart_no_data),
            values[i]?.let { stringResource(if (it > 0) R.string.chart_up_from_previous else if (it < 0) R.string.chart_down_from_previous else R.string.chart_same_as_previous) },
        ) else ChartHint()
    }
}

/** GitHub-style calendar: one column per week, one row per weekday, so a date is findable at a glance. */
@Composable
private fun CalendarHeatmap(days: List<LocalStatDay>, selected: LocalStatDay?, onSelect: (LocalStatDay) -> Unit) {
    if (days.isEmpty()) return
    val cell = 18.dp; val gap = 3.dp
    val byDate = remember(days) { days.associateBy { it.date } }
    val last = days.last().date
    val weeks = remember(days) {
        val gridStart = days.first().date.minusDays((days.first().date.dayOfWeek.value - 1).toLong())
        generateSequence(gridStart) { if (it.plusWeeks(1) <= last) it.plusWeeks(1) else null }.toList()
    }
    val max = days.maxOfOrNull { it.grams }?.coerceAtLeast(.01) ?: .01
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        Column(verticalArrangement = Arrangement.spacedBy(gap), modifier = Modifier.padding(end = 6.dp)) {
            Spacer(Modifier.height(cell))
            stringArrayResource(R.array.weekday_initials).forEach {
                Box(Modifier.height(cell), contentAlignment = Alignment.CenterStart) {
                    Text(it, style = MaterialTheme.typography.labelMedium, color = Pine.copy(alpha = .5f))
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
            weeks.forEachIndexed { w, weekStart ->
                Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                    // A month name sits above the week that opens it.
                    val newMonth = (0..6).map { weekStart.plusDays(it.toLong()) }.any { it.dayOfMonth == 1 } || w == 0
                    Box(Modifier.height(cell), contentAlignment = Alignment.CenterStart) {
                        if (newMonth) Text(weekStart.plusDays(6).format(monthLabel()), style = MaterialTheme.typography.labelMedium, color = Pine.copy(alpha = .6f), maxLines = 1)
                    }
                    (0..6).forEach { d ->
                        val date = weekStart.plusDays(d.toLong())
                        val day = byDate[date]
                        Box(
                            Modifier.size(cell).background(
                                when {
                                    day == null -> Color.Transparent
                                    day == selected -> Amber
                                    !day.observed -> GridLine
                                    day.sober -> Mint.copy(alpha = .85f)
                                    else -> Pine.copy(alpha = (.25 + .75 * day.grams / max).toFloat())
                                },
                                RoundedCornerShape(4.dp),
                            ).pointerInput(day) { if (day != null) detectTapGestures { onSelect(day) } },
                        )
                    }
                }
            }
        }
    }
}

/** Weekday x hour grid, mirroring the web `weekday_hour` chart: where in the week the drinking actually happens. */
@Composable
private fun WeekdayHourHeatmap(days: List<LocalStatDay>) {
    val grid = remember(days) {
        val cells = Array(7) { DoubleArray(24) }
        days.forEach { day ->
            day.drinks.forEach { drink ->
                runCatching { parseDrinkTime(drink.startedAt).hour }.getOrNull()?.let { hour ->
                    cells[day.date.dayOfWeek.value - 1][hour] += alcoholGrams(drink.volumeMl, drink.abvPercent, drink.quantity)
                }
            }
        }
        cells
    }
    val max = grid.maxOf { row -> row.maxOrNull() ?: 0.0 }.coerceAtLeast(.01)
    var selected by remember(days) { mutableStateOf<Pair<Int, Int>?>(null) }
    val names = stringArrayResource(R.array.weekday_names)
    Column {
        Row(Modifier.fillMaxWidth().padding(start = 30.dp, bottom = 3.dp)) {
            (0..23).forEach { hour ->
                Box(Modifier.weight(1f)) {
                    if (hour % 6 == 0) Text(hour.toString().padStart(2, '0'), style = MaterialTheme.typography.labelSmall, color = Pine.copy(alpha = .5f), maxLines = 1)
                }
            }
        }
        (0..6).forEach { weekday ->
            Row(Modifier.fillMaxWidth().padding(bottom = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(names[weekday].take(3), Modifier.width(30.dp), style = MaterialTheme.typography.labelMedium, color = Pine.copy(alpha = .6f), maxLines = 1)
                (0..23).forEach { hour ->
                    val value = grid[weekday][hour]
                    Box(
                        // Tap target first, gutter second: the cells are narrow enough already.
                        Modifier.weight(1f).height(22.dp).pointerInput(weekday to hour) { detectTapGestures { selected = weekday to hour } }
                            .padding(horizontal = 1.dp).background(
                                if (selected == weekday to hour) Amber else if (value <= 0) Mint.copy(alpha = .35f) else Pine.copy(alpha = (.2 + .8 * value / max).toFloat()),
                                RoundedCornerShape(3.dp),
                            ),
                    )
                }
            }
        }
        val cell = selected
        if (cell != null) ChartSelection(
            stringResource(R.string.chart_slot_label, names[cell.first], cell.second.toString().padStart(2, '0')),
            stringResource(R.string.chart_pure_alcohol_grams, fmt(grid[cell.first][cell.second], 1)),
            stringResource(R.string.chart_period_total),
        ) else ChartHint(stringResource(R.string.chart_hint_slot_cell))
    }
}

private fun fmt(value: Double?, digits: Int = 1): String =
    if (value == null) "—" else String.format(Locale.getDefault(), "%.${digits}f", value)

/** Number plus unit, with no dangling space when a chart carries no unit. */
private fun valued(value: Double?, digits: Int, unit: String): String =
    if (unit.isBlank()) fmt(value, digits) else "${fmt(value, digits)} $unit"

/* ---------- Stats ---------- */

private val HEALTH_LABELS = mapOf(
    "sleep" to R.string.health_sleep, "steps" to R.string.health_steps, "exercise" to R.string.health_exercise,
    "resting_heart_rate" to R.string.health_resting_heart_rate, "heart_rate" to R.string.health_heart_rate,
    "hrv_rmssd" to R.string.health_hrv,
)

@Composable private fun healthLabel(type: String): String = HEALTH_LABELS[type]?.let { stringResource(it) } ?: type

@Composable
fun StatsScreen(context: Context, drinks:List<DrinkEntity>, trackedDays:List<TrackedDayEntity>, healthRows:List<HealthAggregateEntity>,settings:LocalSettings) {
    var start by remember { mutableStateOf(LocalDate.now().minusDays(89)) }
    var end by remember { mutableStateOf(LocalDate.now()) }
    var custom by remember { mutableStateOf(false) }
    val days=remember(drinks,start,end){generateSequence(start){if(it<end)it.plusDays(1)else null}.map { day ->
        val rows=drinks.filter { runCatching{trackedDay(it.startedAt,settings.dayStartHour)==day}.getOrDefault(false) };val sober=trackedDays.any{it.day==day.toString()&&it.sober};LocalStatDay(day,rows.sumOf{alcoholGrams(it.volumeMl,it.abvPercent,it.quantity)},rows.sumOf{canadianStandards(it.volumeMl,it.abvPercent,it.quantity,settings.standardDrinkGrams)},rows,rows.isNotEmpty()||sober,sober)
    }.toList()};val observed=days.filter{it.observed};val drinking=days.filter{it.grams>0};val totalStd=observed.sumOf{it.standards};val totalGrams=observed.sumOf{it.grams}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        PageHeaderLite(stringResource(R.string.stats_eyebrow), stringResource(R.string.nav_stats))
        StatsPeriodSelector(start, end, custom, onPreset = { days -> start=LocalDate.now().minusDays(days-1L);end=LocalDate.now();custom=false }, onCustom = { a,b -> start=a;end=b;custom=true })
        SectionCard(stringResource(R.string.stats_period_title), stringResource(R.string.stats_period_range, start.format(statDate()), end.format(statDate()))) {
            val std=stringResource(R.string.unit_standards)
            MetricGrid(listOf(
                Triple(stringResource(R.string.stats_observed_days), observed.size.toString(), null),
                Triple(stringResource(R.string.stats_alcohol_free_days), stringResource(R.string.stats_count_with_percent,observed.count{it.sober},fmt(observed.count{it.sober}*100.0/observed.size.coerceAtLeast(1),0)), null),
                Triple(stringResource(R.string.stats_days_without_data), days.count{!it.observed}.toString(), null),
                Triple(stringResource(R.string.stats_total_standards), fmt(totalStd,1), null),
                Triple(stringResource(R.string.stats_average_per_observed_day), valued(totalStd/observed.size.coerceAtLeast(1),2,std), null),
                Triple(stringResource(R.string.stats_maximum), valued(observed.maxOfOrNull{it.standards},2,std), stringResource(R.string.stats_highest_day_help,fmt(observed.maxOfOrNull{it.grams},0))),
            ))
            Spacer(Modifier.height(10.dp))
            Text(stringResource(R.string.stats_total_pure_alcohol,fmt(totalGrams,0)), style = MaterialTheme.typography.bodyMedium, color = Pine.copy(alpha=.7f))
        }
        LocalTrendSection(days)
        DistributionSummary(observed,settings.standardDrinkGrams)
        HeatmapSection(days)
        DistributionChart(days)
        WeekdayChart(days)
        WeekdayHourSection(days)
        HourChart(drinking.flatMap{it.drinks})
        FirstDrinkChart(drinking)
        DayToDayChart(days)
        PeriodBars(stringResource(R.string.stats_weekly_title),aggregateLocal(days,false))
        PeriodBars(stringResource(R.string.stats_monthly_title),aggregateLocal(days,true),monthly=true)
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
    SectionCard(stringResource(R.string.stats_trend_title),stringResource(R.string.stats_trend_subtitle)){
        MetricChips(metric){metric=it}
        if(rows.isEmpty()){Text(stringResource(R.string.stats_trend_empty),style=MaterialTheme.typography.bodyLarge,color=Pine.copy(alpha=.7f));return@SectionCard}
        ComboChart(daily,listOf(Pine to moving(7),Amber to moving(30),Pine.copy(alpha=.45f) to moving(90)),
            threshold=if(metric=="standards")3.0 else null,
            labels=rows.map{it.date.format(dayDate())},unit=stringResource(if(metric=="grams")R.string.unit_grams else R.string.unit_standards),
            lineNames=listOf(stringResource(R.string.stats_window_7),stringResource(R.string.stats_window_30),stringResource(R.string.stats_window_90)),
            xLabels=dateTicks(rows.map{it.date}),
            thresholdName=stringResource(R.string.stats_trend_threshold),
            barName=stringResource(if(metric=="grams")R.string.stats_trend_bars_grams else R.string.stats_trend_bars_standards))
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.stats_trend_start,rows.first().date.format(statDate())),style=MaterialTheme.typography.bodyMedium,color=Pine.copy(alpha=.65f))
    }
}

@Composable private fun HeatmapSection(days:List<LocalStatDay>){var selected by remember(days){mutableStateOf<LocalStatDay?>(null)}
    SectionCard(stringResource(R.string.stats_calendar_title),stringResource(R.string.stats_calendar_subtitle)){
        CalendarHeatmap(days,selected){selected=it}
        Spacer(Modifier.height(10.dp))
        ChartLegend(listOf(GridLine to stringResource(R.string.stats_legend_no_data),Mint.copy(alpha=.85f) to stringResource(R.string.stats_legend_sober),Pine.copy(alpha=.45f) to stringResource(R.string.stats_legend_low),Pine to stringResource(R.string.stats_legend_high)))
        val day=selected
        if(day!=null)ChartSelection(day.date.format(dayDate()),
            if(!day.observed)stringResource(R.string.chart_no_data) else if(day.sober)stringResource(R.string.stats_sober_day_value) else stringResource(R.string.stats_day_value,fmt(day.grams,1),fmt(day.standards,2)),
            if(day.observed&&!day.sober)pluralStringResource(R.plurals.stats_drinks_count,day.drinks.sumOf{d->d.quantity},day.drinks.sumOf{d->d.quantity}) else null)
        else ChartHint(stringResource(R.string.chart_hint_day_cell))
    }
}

@Composable private fun WeekdayHourSection(days:List<LocalStatDay>){
    SectionCard(stringResource(R.string.stats_weekhour_title),stringResource(R.string.stats_weekhour_subtitle)){WeekdayHourHeatmap(days)}
}

private fun aggregateLocal(days:List<LocalStatDay>,monthly:Boolean):List<LocalPeriod> = days.groupBy{if(monthly)it.date.withDayOfMonth(1) else it.date.minusDays(it.date.dayOfWeek.value-1L)}.toSortedMap().map{(date,rows)->LocalPeriod(date.toString(),rows.filter{it.observed}.sumOf{it.standards},rows.count{it.sober})}.takeLast(12)

private fun clockText(hours: Double): String {
    val minutes = (hours * 60).roundToInt().mod(24 * 60)
    return "${(minutes / 60).toString().padStart(2, '0')}:${(minutes % 60).toString().padStart(2, '0')}"
}

private fun periodLabel(label:String,monthly:Boolean):String = runCatching{LocalDate.parse(label).format(if(monthly)dateFormat("MMMM yyyy") else shortDate())}.getOrDefault(label)

@Composable private fun PeriodBars(title:String,rows:List<LocalPeriod>,monthly:Boolean=false){SectionCard(title,stringResource(R.string.stats_periods_subtitle)){
    if(rows.isEmpty()){Text(stringResource(R.string.stats_periods_empty),style=MaterialTheme.typography.bodyLarge,color=Pine.copy(alpha=.7f));return@SectionCard}
    val peak=rows.maxOf{it.standards}
    CategoryBars(rows.reversed().map{row->CatBar(if(monthly)periodLabel(row.label,true) else stringResource(R.string.stats_week_of,periodLabel(row.label,false)),row.standards,if(row.standards>=peak&&peak>0)Amber else Pine,pluralStringResource(R.plurals.stats_alcohol_free_count,row.alcoholFree,row.alcoholFree))},stringResource(R.string.unit_standards),1)
}}

@Composable private fun SessionsSection(drinks:List<DrinkEntity>,standardGrams:Double=CANADIAN_STANDARD_GRAMS){val sorted=drinks.sortedBy{it.startedAt};val sessions=mutableListOf<MutableList<DrinkEntity>>();sorted.forEach{d->val at=runCatching{parseDrinkTime(d.startedAt)}.getOrNull();val last=sessions.lastOrNull()?.lastOrNull()?.let{runCatching{parseDrinkTime(it.startedAt).plusMinutes(it.durationMinutes.toLong())}.getOrNull()};if(last==null||at==null||java.time.Duration.between(last,at).toHours()>=8)sessions.add(mutableListOf(d))else sessions.last().add(d)}
    val unit=stringResource(R.string.unit_standards)
    SectionCard(stringResource(R.string.stats_sessions_title),stringResource(R.string.stats_sessions_subtitle)) { sessions.takeLast(8).reversed().forEach { rows -> val std=rows.sumOf{canadianStandards(it.volumeMl,it.abvPercent,it.quantity,standardGrams)};val count=rows.sumOf{it.quantity};StatRow(runCatching{LocalDate.parse(rows.first().startedAt.take(10)).format(statDate())}.getOrDefault(rows.first().startedAt.take(10)),"${valued(std,2,unit)} · ${pluralStringResource(R.plurals.stats_drinks_count,count,count)}") } }
}

@Composable private fun WeekdayChart(days:List<LocalStatDay>){val labels=stringArrayResource(R.array.weekday_names);val values=(1..7).map{w->days.filter{it.date.dayOfWeek.value==w}.sumOf{it.grams}}
    SectionCard(stringResource(R.string.stats_weekday_title),stringResource(R.string.stats_weekday_subtitle)){CategoryBars(labels.mapIndexed{i,l->CatBar(l,values[i],if(values[i]>=(values.maxOrNull()?:0.0)&&values[i]>0)Amber else Pine)},stringResource(R.string.unit_grams),0)}}

@Composable private fun HourChart(drinks:List<DrinkEntity>){val values=(0..23).map{hour->drinks.filter{runCatching{parseDrinkTime(it.startedAt).hour==hour}.getOrDefault(false)}.sumOf{alcoholGrams(it.volumeMl,it.abvPercent,it.quantity)}};val groups=drinks.groupBy{it.startedAt.take(10)}
    fun decimal(t:OffsetDateTime)=t.hour+t.minute/60.0
    val first=groups.values.mapNotNull{rows->rows.mapNotNull{runCatching{parseDrinkTime(it.startedAt)}.getOrNull()}.minOrNull()?.let(::decimal)}.averageOrNull()
    val last=groups.values.mapNotNull{rows->rows.mapNotNull{d->runCatching{parseDrinkTime(d.startedAt).plusMinutes(d.durationMinutes.toLong())}.getOrNull()}.maxOrNull()?.let(::decimal)}.averageOrNull()
    SectionCard(stringResource(R.string.stats_hour_title),stringResource(R.string.stats_hour_subtitle)){
        val slots=(0..23).map{stringResource(R.string.stats_hour_slot,it.toString().padStart(2,'0'),(it+1).mod(24).toString().padStart(2,'0'))}
        BarChart(values,values.map{if(it>0)Pine else Mint},labels=slots,unit=stringResource(R.string.unit_grams),digits=1,height=170.dp,
            xLabels=listOf(stringResource(R.string.stats_hour_axis_00),stringResource(R.string.stats_hour_axis_06),stringResource(R.string.stats_hour_axis_12),stringResource(R.string.stats_hour_axis_18),stringResource(R.string.stats_hour_axis_23)))
        Spacer(Modifier.height(8.dp))
        MetricGrid(listOf(
            Triple(stringResource(R.string.stats_first_drink_metric),first?.let(::clockText)?:"—",stringResource(R.string.stats_first_drink_metric_help)),
            Triple(stringResource(R.string.stats_last_drink_metric),last?.let(::clockText)?:"—",stringResource(R.string.stats_last_drink_metric_help)),
        ))
    }}

@Composable private fun DistributionChart(days:List<LocalStatDay>){val observed=days.filter{it.observed};val labels=stringArrayResource(R.array.stats_intensity_buckets);val values=listOf(observed.count{it.sober},observed.count{it.standards in .000001..1.0},observed.count{it.standards>1&&it.standards<=2},observed.count{it.standards>2&&it.standards<=4},observed.count{it.standards>4}).map{it.toDouble()}
    val colors=listOf(Mint,Pine.copy(alpha=.4f),Pine.copy(alpha=.65f),Amber,Danger)
    SectionCard(stringResource(R.string.stats_distribution_days_title),stringResource(R.string.stats_distribution_days_subtitle)){CategoryBars(labels.mapIndexed{i,l->CatBar(l,values[i],colors[i],if(observed.isEmpty())null else stringResource(R.string.stats_percent_of_observed,fmt(values[i]*100.0/observed.size,0)))},stringResource(R.string.unit_days),0)}}

@Composable private fun FirstDrinkChart(days:List<LocalStatDay>){val rows=days.mapNotNull{day->day.drinks.minByOrNull{it.startedAt}?.let{d->runCatching{parseDrinkTime(d.startedAt).let{day.date to (it.hour+it.minute/60.0)}}.getOrNull()}}
    SectionCard(stringResource(R.string.stats_first_drink_title),stringResource(R.string.stats_first_drink_subtitle)){
        if(rows.isEmpty()){Text(stringResource(R.string.stats_first_drink_empty),style=MaterialTheme.typography.bodyLarge,color=Pine.copy(alpha=.7f));return@SectionCard}
        ScatterChart(rows.map{it.second},24.0,labels=rows.map{it.first.format(dayDate())},xLabels=dateTicks(rows.map{it.first}),valueText={clockText(it)})
        Spacer(Modifier.height(8.dp))
        MetricGrid(listOf(Triple(stringResource(R.string.stats_earliest),clockText(rows.minOf{it.second}),null),Triple(stringResource(R.string.stats_latest),clockText(rows.maxOf{it.second}),null)))
    }}

@Composable private fun DayToDayChart(days:List<LocalStatDay>){val rows=days.dropWhile{!it.observed};val changes=rows.mapIndexed{i,d->if(i==0||!d.observed||!rows[i-1].observed)null else d.standards-rows[i-1].standards}
    SectionCard(stringResource(R.string.stats_daytoday_title),stringResource(R.string.stats_daytoday_subtitle)){
        if(changes.count{it!=null}<2){Text(stringResource(R.string.stats_daytoday_empty),style=MaterialTheme.typography.bodyLarge,color=Pine.copy(alpha=.7f));return@SectionCard}
        DivergingBars(changes,labels=rows.map{it.date.format(dayDate())},unit=stringResource(R.string.unit_standards),xLabels=dateTicks(rows.map{it.date}))
        ChartLegend(listOf(Pine to stringResource(R.string.stats_legend_down),Danger to stringResource(R.string.stats_legend_up)))
    }}

private fun List<Double>.averageOrNull()=if(isEmpty())null else average()
private fun quantile(sorted:List<Double>,q:Double):Double?{if(sorted.isEmpty())return null;val p=(sorted.size-1)*q;val lo=p.toInt();val hi=kotlin.math.ceil(p).toInt();return if(lo==hi)sorted[lo]else sorted[lo]+(sorted[hi]-sorted[lo])*(p-lo)}

// Label / value / help, in the same order and with the same wording as the web `distributionHelp` tooltips.
private val DISTRIBUTION_STATS = listOf(
    R.string.stats_mean to R.string.stats_mean_help,
    R.string.stats_median to R.string.stats_median_help,
    R.string.stats_q1 to R.string.stats_q1_help,
    R.string.stats_q3 to R.string.stats_q3_help,
    R.string.stats_p90 to R.string.stats_p90_help,
    R.string.stats_stddev to R.string.stats_stddev_help,
    R.string.stats_cv to R.string.stats_cv_help,
    R.string.stats_minimum to R.string.stats_minimum_help,
    R.string.stats_maximum to R.string.stats_maximum_help,
)

@Composable private fun DistributionSummary(days:List<LocalStatDay>,standardGrams:Double=CANADIAN_STANDARD_GRAMS){var metric by remember{mutableStateOf("standards")};val values=days.map{if(metric=="grams")it.grams else it.standards}.sorted();val mean=values.averageOrNull();val sd=mean?.let{m->kotlin.math.sqrt(values.sumOf{(it-m)*(it-m)}/values.size.coerceAtLeast(1))};val unit=stringResource(if(metric=="grams")R.string.unit_grams else R.string.unit_standards)
    val shown=listOf(valued(mean,2,unit),valued(quantile(values,.5),2,unit),valued(quantile(values,.25),2,unit),valued(quantile(values,.75),2,unit),
        valued(quantile(values,.9),2,unit),valued(sd,2,unit),if(mean==null||mean==0.0)"—"else fmt(sd!!/mean,2),valued(values.minOrNull(),2,unit),valued(values.maxOrNull(),2,unit))
    SectionCard(stringResource(R.string.stats_distribution_title),stringResource(R.string.stats_distribution_subtitle,fmt(standardGrams,2))){
        MetricChips(metric){metric=it}
        MetricGrid(DISTRIBUTION_STATS.mapIndexed{i,(label,help)->Triple(stringResource(label),shown[i],stringResource(help))})
        Spacer(Modifier.height(10.dp))
        Text(stringResource(R.string.stats_distribution_tap_hint),style=MaterialTheme.typography.labelLarge,color=Pine.copy(alpha=.5f))
    }}

@Composable private fun LocalHealthSection(rows:List<HealthAggregateEntity>,start:LocalDate,end:LocalDate){val filtered=rows.filter{it.localDate>=start.toString()&&it.localDate<=end.toString()};val types=filtered.map{it.recordType}.distinct();if(types.isEmpty()){SectionCard(stringResource(R.string.stats_health_title)){Text(stringResource(R.string.stats_health_empty),style=MaterialTheme.typography.bodyLarge,color=Pine.copy(alpha=.7f))};return};var metric by remember(types){mutableStateOf(types.first())};val selected=filtered.filter{it.recordType==metric}.groupBy{it.localDate}.toSortedMap();val values=selected.mapValues{(_,rs)->rs.mapNotNull{runCatching{JSONObject(it.payload).optDouble("value")}.getOrNull()}.averageOrNull()};val unit=filtered.firstOrNull{it.recordType==metric}?.let{runCatching{JSONObject(it.payload).optString("unit")}.getOrDefault("")}?:""
    SectionCard(stringResource(R.string.stats_health_title),stringResource(R.string.stats_health_subtitle)){
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom=6.dp)){types.forEach{t->FilterChip(metric==t,{metric=t},{Text(healthLabel(t))},Modifier.padding(end=8.dp))}}
        val suffix=unit.trim()
        LineChart(values.values.toList(),color=Amber,labels=values.keys.map{runCatching{LocalDate.parse(it).format(dayDate())}.getOrDefault(it)},unit=suffix,xLabels=dateTicks(values.keys.mapNotNull{runCatching{LocalDate.parse(it)}.getOrNull()}))
        Spacer(Modifier.height(10.dp))
        MetricGrid(listOf(Triple(stringResource(R.string.stats_mean),valued(values.values.filterNotNull().averageOrNull(),1,suffix),null),Triple(stringResource(R.string.stats_health_days_with_data),values.size.toString(),null)))
    }}

private fun statDate() = dateFormat("d MMM yyyy")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StatsPeriodSelector(start:LocalDate,end:LocalDate,custom:Boolean,onPreset:(Int)->Unit,onCustom:(LocalDate,LocalDate)->Unit) {
    var dialog by remember { mutableStateOf<String?>(null) }
    var draftStart by remember(start) { mutableStateOf(start) };var draftEnd by remember(end) { mutableStateOf(end) }
    Column(Modifier.padding(horizontal=20.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            listOf(7,30,90,180,365).forEach { days ->
                val label=if(days==365)stringResource(R.string.stats_preset_year) else stringResource(R.string.stats_preset_days,days)
                FilterChip(selected=!custom && start==LocalDate.now().minusDays(days-1L) && end==LocalDate.now(),onClick={onPreset(days)},label={Text(label)},modifier=Modifier.padding(end=6.dp))
            }
            FilterChip(selected=custom,onClick={draftStart=start;draftEnd=end;dialog="start"},label={Text(stringResource(R.string.stats_preset_custom))})
        }
        Text(stringResource(R.string.stats_range_short,start.format(statDate()),end.format(statDate())),style=MaterialTheme.typography.bodySmall,color=Pine.copy(alpha=.65f),modifier=Modifier.padding(top=4.dp,bottom=4.dp))
    }
    dialog?.let { target ->
        val initial=if(target=="start")draftStart else draftEnd
        val state=rememberDatePickerState(initialSelectedDateMillis=initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),selectableDates=object:SelectableDates{override fun isSelectableDate(utcTimeMillis:Long)=utcTimeMillis<=System.currentTimeMillis()})
        DatePickerDialog(onDismissRequest={dialog=null},confirmButton={TextButton(onClick={
            val picked=state.selectedDateMillis?.let{Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()}?:initial
            if(target=="start"){draftStart=picked;dialog="end"}else{draftEnd=picked;if(draftStart<=picked)onCustom(draftStart,picked);dialog=null}
        }){Text(stringResource(if(target=="start") R.string.stats_next else R.string.stats_apply))}},dismissButton={TextButton(onClick={dialog=null}){Text(stringResource(R.string.action_cancel))}}){DatePicker(state)}
    }
}

private fun strength(r: Double): Int = when {
    abs(r) < 0.2 -> R.string.strength_weak
    abs(r) < 0.4 -> R.string.strength_moderate
    else -> R.string.strength_strong
}

/* ---------- Repères (insights) ---------- */

@Composable
fun InsightsScreen(drinks:List<DrinkEntity>,checkIns:List<CheckInEntity>,settings:LocalSettings) {
    val data=remember(drinks,checkIns,settings){personalAnalytics(drinks,checkIns,settings)}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { PageHeaderLite(stringResource(R.string.insights_eyebrow), stringResource(R.string.nav_insights))
        val ready = data.optJSONObject("model_readiness") ?: JSONObject()
        val notYet = stringResource(R.string.insights_not_yet)
        SectionCard(stringResource(R.string.insights_readiness_title), stringResource(R.string.insights_readiness_subtitle)) {
            StatRow(stringResource(R.string.insights_days_available), data.optInt("days_available").toString())
            StatRow(stringResource(R.string.insights_events_available), data.optInt("events_available").toString())
            StatRow(stringResource(R.string.insights_descriptive), if (ready.optBoolean("descriptive")) stringResource(R.string.insights_ready_fem) else notYet)
            StatRow(stringResource(R.string.insights_associations), if (ready.optBoolean("associations")) stringResource(R.string.insights_ready_fem_plural) else notYet)
            StatRow(stringResource(R.string.insights_regularized_model), if (ready.optBoolean("regularized_model")) stringResource(R.string.insights_ready_masc) else notYet)
        }
        val associations = data.optJSONArray("associations") ?: JSONArray()
        for (i in 0 until associations.length()) {
            val a = associations.optJSONObject(i) ?: continue
            val factor = stringResource(INSIGHT_FACTORS[a.optString("factor")] ?: R.string.insights_factor_craving)
            SectionCard(factor.replaceFirstChar { it.uppercase() }) {
                val coef = a.numOrNull("coefficient")
                if (a.optString("status") == "insufficient_data" || coef == null) {
                    Text(pluralStringResource(R.plurals.insights_insufficient_sample, a.optInt("sample_size"), a.optInt("sample_size")), color = Pine.copy(alpha = .7f))
                } else {
                    Text(stringResource(R.string.insights_association_sentence, factor), color = Pine.copy(alpha = .8f))
                    Spacer(Modifier.height(6.dp))
                    StatRow(stringResource(R.string.insights_coefficient), stringResource(R.string.insights_coefficient_value, fmt(coef, 2), stringResource(strength(coef))))
                    StatRow(stringResource(R.string.insights_observations), a.optInt("sample_size").toString())
                }
            }
        }
        Text(
            stringResource(R.string.insights_disclaimer),
            Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall, color = Pine.copy(alpha = .6f),
        )
        Spacer(Modifier.height(28.dp))
    }
}

private val INSIGHT_FACTORS = mapOf(
    "craving" to R.string.insights_factor_craving,
    "confidence" to R.string.insights_factor_confidence,
    "stress" to R.string.insights_factor_stress,
)

private fun personalAnalytics(drinks:List<DrinkEntity>,checkIns:List<CheckInEntity>,settings:LocalSettings):JSONObject{
    val totals=drinks.groupBy{runCatching{trackedDay(it.startedAt,settings.dayStartHour).toString()}.getOrDefault(it.startedAt.take(10))}.mapValues{(_,rows)->rows.sumOf{alcoholGrams(it.volumeMl,it.abvPercent,it.quantity)}}
    val parsed=checkIns.mapNotNull{runCatching{JSONObject(it.payload)}.getOrNull()};val rows=parsed.mapNotNull{c->totals[c.optString("local_date")]?.let{actual->Triple(c,actual,maxOf(0.0,actual-c.optDouble("planned_grams",0.0)))}}
    fun association(name:String,value:(JSONObject)->Double?):JSONObject{val pairs=rows.mapNotNull{(c,_,excess)->value(c)?.let{it to excess}};val out=JSONObject().put("factor",name).put("sample_size",pairs.size);if(pairs.size<5)return out.put("status","insufficient_data")
        val ax=pairs.map{it.first}.average();val ay=pairs.map{it.second}.average();val top=pairs.sumOf{(it.first-ax)*(it.second-ay)};val bottom=kotlin.math.sqrt(pairs.sumOf{(it.first-ax)*(it.first-ax)}*pairs.sumOf{(it.second-ay)*(it.second-ay)});return out.put("coefficient",if(bottom==0.0)JSONObject.NULL else top/bottom)}
    val excess=rows.count{it.third>0};return JSONObject().put("days_available",(totals.keys+parsed.map{it.optString("local_date")}).size).put("events_available",excess)
        .put("associations",JSONArray().put(association("craving"){it.optDouble("craving")}).put(association("confidence"){10-it.optDouble("confidence")}).put(association("stress"){if(it.has("stress"))it.optDouble("stress")else null}))
        .put("model_readiness",JSONObject().put("descriptive",rows.size>=7).put("associations",rows.size>=20&&excess>=5).put("regularized_model",rows.size>=42&&excess>=10).put("temporal_model",rows.size>=90))
}

/* ---------- Succès ---------- */

@Composable
fun SuccessScreen(drinks:List<DrinkEntity>,tracked:List<TrackedDayEntity>,checkIns:List<CheckInEntity>,goals:List<GoalEntity>,settings:LocalSettings) {
    val badges=remember(drinks,tracked,checkIns,goals,settings){localSuccess(drinks,tracked,checkIns,goals,settings)}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { PageHeaderLite(stringResource(R.string.success_eyebrow), stringResource(R.string.nav_success))
        val unlocked = badges.count { it.unlocked }
        SectionCard(stringResource(R.string.success_overview)) {
            StatRow(stringResource(R.string.success_badges_earned), stringResource(R.string.success_badge_ratio, unlocked, badges.size))
        }
        badges.forEach { badge ->
            val progress = badge.progressPercent.coerceIn(0.0, 100.0)
            SectionCard(badge.title()) {
                Text(badge.description(), color = Pine.copy(alpha = .78f))
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { (progress / 100.0).toFloat() },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    if (badge.unlocked) stringResource(R.string.success_unlocked) else stringResource(R.string.percent_value, progress.roundToInt()),
                    style = MaterialTheme.typography.labelMedium, color = Pine,
                )
            }
        }
        Spacer(Modifier.height(28.dp))
    }
}

/**
 * A badge carries resource ids and a count rather than sentences, so the wording stays in
 * `strings.xml` and the maths stays here. [plural] marks a description that needs a quantity.
 */
private data class Badge(
    val titleRes: Int, val descRes: Int, val count: Int, val unlocked: Boolean, val progressPercent: Double,
    val titleCount: Int? = null, val plural: Boolean = true,
) {
    @Composable fun title(): String = if (titleCount == null) stringResource(titleRes) else stringResource(titleRes, titleCount)
    @Composable fun description(): String = if (plural) pluralStringResource(descRes, count, count) else stringResource(descRes, count)
}

private fun localSuccess(drinks:List<DrinkEntity>,tracked:List<TrackedDayEntity>,checkIns:List<CheckInEntity>,goals:List<GoalEntity>,settings:LocalSettings):List<Badge>{
    val byDay=drinks.groupBy{runCatching{trackedDay(it.startedAt,settings.dayStartHour)}.getOrNull()}.filterKeys{it!=null}.mapKeys{it.key!!}.mapValues{(_,r)->r.sumOf{alcoholGrams(it.volumeMl,it.abvPercent,it.quantity)}}
    val observed=(byDay.keys+tracked.filter{it.sober}.map{LocalDate.parse(it.day)}).toSortedSet();var streak=0;var best=0;var previous:LocalDate?=null;for(day in observed){if(previous?.plusDays(1)!=day)streak=0;if((byDay[day]?:0.0)==0.0){streak++;best=maxOf(best,streak)}else streak=0;previous=day}
    val values=observed.map{byDay[it]?:0.0};val reduction=if(values.size>=60){val previous=values.takeLast(60).take(30).average();val latest=values.takeLast(30).average();if(previous>0)maxOf(0.0,(previous-latest)/previous*100)else 0.0}else 0.0
    val defs=mutableListOf<Badge>()
    fun badge(titleRes:Int,descRes:Int,n:Int,current:Double,titleCount:Int?=null,plural:Boolean=true)=
        Badge(titleRes,descRes,n,current>=n,minOf(100.0,current/n*100),titleCount,plural)
    listOf(1 to R.string.badge_logged_1,3 to R.string.badge_logged_3,7 to R.string.badge_logged_7,14 to R.string.badge_logged_14,
        30 to R.string.badge_logged_30,60 to R.string.badge_logged_60,90 to R.string.badge_logged_90,180 to R.string.badge_logged_180,
        365 to R.string.badge_logged_365).forEach{(n,t)->defs+=badge(t,R.plurals.badge_logged_desc,n,observed.size.toDouble())}
    listOf(3 to R.string.badge_dry_3,7 to R.string.badge_dry_7,14 to R.string.badge_dry_14,30 to R.string.badge_dry_30)
        .forEach{(n,t)->defs+=badge(t,R.plurals.badge_dry_desc,n,best.toDouble())}
    defs+=badge(R.string.badge_reduce_10,R.string.badge_reduce_desc,10,reduction,plural=false)
    defs+=badge(R.string.badge_reduce_25,R.string.badge_reduce_desc,25,reduction,plural=false)
    val months=observed.groupBy{java.time.YearMonth.from(it)}.toSortedMap().values.map{days->days.sumOf{byDay[it]?:0.0}};val monthReduction=if(months.size>=2&&months[months.lastIndex-1]>0)maxOf(0.0,(months[months.lastIndex-1]-months.last())/months[months.lastIndex-1]*100)else 0.0
    defs+=badge(R.string.badge_month_10,R.string.badge_month_desc,10,monthReduction,plural=false)
    listOf(1 to R.string.badge_checkin_1,7 to R.string.badge_checkin_7,30 to R.string.badge_checkin_30,90 to R.string.badge_checkin_90)
        .forEach{(n,t)->defs+=badge(t,R.plurals.badge_checkin_desc,n,checkIns.size.toDouble())}
    val achieved=goals.count{it.achieved}
    listOf(1,3,5,10).forEach{n->defs+=badge(if(n==1)R.string.badge_goals_1 else R.string.badge_goals_n,R.plurals.badge_goals_desc,n,achieved.toDouble(),titleCount=if(n==1)null else n)}
    var weekendStreak=0;val today=LocalDate.now();var monday=today.minusDays(today.dayOfWeek.value-1L).minusWeeks(1);while(monday>=observed.firstOrNull()?.minusDays(6)?:today){val days=(0L..6L).map{monday.plusDays(it)};if(days.all{it in observed}&&days.take(5).all{(byDay[it]?:0.0)==0.0}&&days.takeLast(2).any{(byDay[it]?:0.0)>0})weekendStreak++ else if(weekendStreak>0)break;monday=monday.minusWeeks(1)};listOf(2 to R.string.badge_weekend_2,4 to R.string.badge_weekend_4,8 to R.string.badge_weekend_8,12 to R.string.badge_weekend_12)
        .forEach{(n,t)->defs+=badge(t,R.string.badge_weekend_desc,n,weekendStreak.toDouble(),plural=false)}
    return defs
}

/* ---------- Objectifs ---------- */

private val GOAL_KINDS = listOf(
    "max_moving_7_grams" to R.string.goal_kind_max_moving_7_grams,
    "max_grams_week" to R.string.goal_kind_max_grams_week,
    "max_standards" to R.string.goal_kind_max_standards,
    "min_alcohol_free_days" to R.string.goal_kind_min_alcohol_free_days,
    "max_drinking_days" to R.string.goal_kind_max_drinking_days,
    "max_grams_session" to R.string.goal_kind_max_grams_session,
    "monthly_reduction" to R.string.goal_kind_monthly_reduction,
)
private val GOAL_LABELS = GOAL_KINDS.toMap()

@Composable private fun goalKindLabel(kind: String): String = GOAL_LABELS[kind]?.let { stringResource(it) } ?: kind

@Composable
private fun durationText(g: JSONObject): String = when (g.optString("temporal_mode")) {
    "deadline" -> {
        val due = g.optString("due_date").takeIf { it.isNotBlank() && it != "null" } ?: "?"
        val left = if (g.isNull("days_remaining")) null else g.optInt("days_remaining")
        if (left == null) stringResource(R.string.goal_deadline, due) else stringResource(R.string.goal_deadline_with_days, due, left)
    }
    else -> {
        val weeks = (if (g.isNull("consecutive_weeks")) null else g.optInt("consecutive_weeks"))?.toString() ?: "?"
        val done = if (g.isNull("consecutive_weeks_achieved")) null else g.optInt("consecutive_weeks_achieved")
        if (done == null) stringResource(R.string.goal_weeks, weeks) else stringResource(R.string.goal_weeks_with_progress, weeks, done, weeks)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GoalsScreen(repository:SyncRepository,localGoals:List<GoalEntity>,drinks:List<DrinkEntity>,tracked:List<TrackedDayEntity>,settings:LocalSettings,onSync:()->Unit) {
    val goals=remember(localGoals,drinks,tracked,settings){localGoalRows(localGoals,drinks,tracked,settings)}
    var error by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val alreadyAchieved = remember(localGoals) { localGoals.filter { it.achieved }.map { it.clientId }.toSet() }
    LaunchedEffect(goals.toString()){for(i in 0 until goals.length()){val g=goals.getJSONObject(i);val reached=if(g.optString("temporal_mode")=="consecutive_weeks")g.optInt("consecutive_weeks_achieved")>=g.optInt("consecutive_weeks",1)else g.optBoolean("on_track")
        if(reached){val clientId=g.getString("client_id");if(clientId !in alreadyAchieved)haptic.performHapticFeedback(HapticFeedbackType.LongPress);repository.markGoalAchieved(clientId)}}}
    PullToRefreshBox(isRefreshing = false, onRefresh = onSync, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            PageHeaderLite(stringResource(R.string.goals_eyebrow), stringResource(R.string.nav_goals))
            Button(onClick = { adding = true }, modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth()) { Text(stringResource(R.string.goals_add)) }
            error?.let { Text(it, Modifier.padding(20.dp), color = Pine.copy(alpha = .7f)) }
            val list = goals
            if (list.length() == 0) SectionCard(stringResource(R.string.goals_none_title)) {
                Text(stringResource(R.string.goals_none_body), color = Pine.copy(alpha = .7f))
            }
            for (i in 0 until list.length()) {
                val g = list.optJSONObject(i) ?: continue
                SectionCard(goalKindLabel(g.optString("kind"))) {
                    StatRow(stringResource(R.string.goals_target), fmt(g.numOrNull("target"), 1))
                    StatRow(stringResource(R.string.goals_current), fmt(g.numOrNull("current"), 1))
                    val onTrack = if (g.isNull("on_track")) null else g.optBoolean("on_track")
                    StatRow(stringResource(R.string.goals_status), when (onTrack) { true -> stringResource(R.string.goals_on_track); false -> stringResource(R.string.goals_off_track); else -> "—" })
                    Text(durationText(g), style = MaterialTheme.typography.bodySmall, color = Pine.copy(alpha = .7f))
                    g.numOrNull("progress_percent")?.let { p ->
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(progress = { (p / 100.0).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    }
                    if (!g.optBoolean("active")) Text(stringResource(R.string.goals_paused), style = MaterialTheme.typography.labelMedium, color = Amber)
                    TextButton(onClick = {
                        scope.launch {
                            runCatching { repository.deleteGoal(g.getString("client_id")) }
                                .onSuccess { onSync() }.onFailure { error = it.message }
                        }
                    }) { Text(stringResource(R.string.goals_remove), color = Danger) }
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
        title = { Text(stringResource(R.string.goals_new)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box {
                    OutlinedTextField(goalKindLabel(kind), {}, readOnly = true, label = { Text(stringResource(R.string.goals_type)) }, modifier = Modifier.fillMaxWidth(),
                        trailingIcon = { TextButton(onClick = { kindOpen = true }) { Text(stringResource(R.string.action_change)) } })
                    DropdownMenu(expanded = kindOpen, onDismissRequest = { kindOpen = false }) {
                        GOAL_KINDS.forEach { (k, label) -> DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = { kind = k; kindOpen = false }) }
                    }
                }
                OutlinedTextField(target, { target = it.filter { c -> c.isDigit() || c == '.' || c == ',' } }, label = { Text(stringResource(R.string.goals_target)) }, singleLine = true, keyboardOptions = numeric, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.goals_deadline_switch), Modifier.weight(1f))
                    Switch(checked = deadlineMode, onCheckedChange = { deadlineMode = it })
                }
                if (deadlineMode) OutlinedTextField(dueDate, { dueDate = it }, label = { Text(stringResource(R.string.goals_due_date_label)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                else OutlinedTextField(weeks, { weeks = it.filter(Char::isDigit) }, label = { Text(stringResource(R.string.goals_weeks_label)) }, singleLine = true, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number), modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val payload = JSONObject().put("kind", kind).put("target", target.replace(',', '.').toDoubleOrNull() ?: 0.0)
                if (deadlineMode) payload.put("temporal_mode", "deadline").put("due_date", dueDate.trim())
                else payload.put("temporal_mode", "consecutive_weeks").put("consecutive_weeks", weeks.toIntOrNull()?.coerceAtLeast(1) ?: 1)
                onCreate(payload)
            }) { Text(stringResource(R.string.goals_create)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
