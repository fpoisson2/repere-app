package ca.repere.mobile

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
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
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import ca.repere.data.DrinkEntity
import ca.repere.data.TrackedDayEntity
import ca.repere.data.HealthAggregateEntity
import ca.repere.core.alcoholGrams
import ca.repere.core.canadianStandards
import ca.repere.core.parseDrinkTime

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
private fun BarChart(values: List<Double>, colors: List<Color>, threshold: Double? = null, modifier: Modifier = Modifier, labels:List<String> = emptyList(), unit:String="") {
    val max = (values.maxOrNull() ?: 0.0).coerceAtLeast(threshold ?: 0.0).coerceAtLeast(0.01)
    var selected by remember(values){mutableStateOf<Int?>(null)}
    Column { Canvas(modifier.fillMaxWidth().height(140.dp).pointerInput(values){detectTapGestures{tap->if(values.isNotEmpty())selected=((tap.x/size.width)*values.size).toInt().coerceIn(0,values.lastIndex)}}) {
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
        selected?.let{i->val x=(i+.5f)*size.width/values.size;drawLine(Amber,Offset(x,0f),Offset(x,size.height),3f)}
    };selected?.let{i->ChartSelection((labels.getOrNull(i)?:"Valeur ${i+1}"),"${fmt(values[i],2)}$unit")} }
}

/** Daily bars behind one or more overlay lines, shared Y scale. */
@Composable
private fun ComboChart(bars: List<Double>, lines: List<Pair<Color, List<Double?>>>, threshold: Double? = null, modifier: Modifier = Modifier, labels:List<String> = emptyList(), unit:String="", lineNames:List<String> = listOf("7 j","30 j","90 j")) {
    val allLine = lines.flatMap { it.second }.filterNotNull()
    val max = (bars.maxOrNull() ?: 0.0)
        .coerceAtLeast(allLine.maxOrNull() ?: 0.0)
        .coerceAtLeast(threshold ?: 0.0)
        .coerceAtLeast(0.01)
    var selected by remember(bars){mutableStateOf<Int?>(null)}
    Column { Canvas(modifier.fillMaxWidth().height(150.dp).pointerInput(bars){detectTapGestures{tap->if(bars.isNotEmpty())selected=((tap.x/size.width)*bars.size).toInt().coerceIn(0,bars.lastIndex)}}) {
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
        selected?.let{i->val x=i*size.width/(bars.size-1).coerceAtLeast(1);drawLine(Amber,Offset(x,0f),Offset(x,size.height),3f);drawCircle(Amber,6f,Offset(x,(size.height-(bars[i]/max*size.height)).toFloat()))}
    };selected?.let{i->val details=buildString{append("Jour : ${fmt(bars[i],2)}$unit");lines.forEachIndexed{n,(_,s)->s.getOrNull(i)?.let{append(" · ${lineNames.getOrNull(n)?:"Moy."} : ${fmt(it,2)}$unit")}}};ChartSelection(labels.getOrNull(i)?:"Point ${i+1}",details)} }
}

@Composable
private fun LineChart(values: List<Double?>, modifier: Modifier = Modifier, color: Color = Pine, labels:List<String> = emptyList(), unit:String="") {
    val present = values.filterNotNull()
    val min = present.minOrNull() ?: 0.0
    val max = (present.maxOrNull() ?: 1.0)
    val span = (max - min).takeIf { it > 0 } ?: 1.0
    var selected by remember(values){mutableStateOf<Int?>(null)}
    Column { Canvas(modifier.fillMaxWidth().height(130.dp).pointerInput(values){detectTapGestures{tap->if(values.isNotEmpty())selected=((tap.x/size.width)*values.size).toInt().coerceIn(0,values.lastIndex)}}) {
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
        selected?.let{i->val x=i*size.width/(values.size-1).coerceAtLeast(1);drawLine(Amber,Offset(x,0f),Offset(x,size.height),3f)}
    };selected?.let{i->ChartSelection(labels.getOrNull(i)?:"Point ${i+1}",values[i]?.let{"${fmt(it,2)}$unit"}?:"Aucune donnée")} }
}

@Composable private fun ChartSelection(label:String,value:String){Surface(color=Mint.copy(alpha=.45f),shape=RoundedCornerShape(10.dp),modifier=Modifier.padding(top=8.dp).fillMaxWidth()){Column(Modifier.padding(horizontal=12.dp,vertical=8.dp)){Text(label,fontWeight=FontWeight.Bold,style=MaterialTheme.typography.labelMedium);Text(value,style=MaterialTheme.typography.bodySmall,color=Pine.copy(alpha=.75f))}}}

private fun fmt(value: Double?, digits: Int = 1): String =
    if (value == null) "—" else String.format(Locale.CANADA_FRENCH, "%.${digits}f", value)

/* ---------- Stats ---------- */

private val HEALTH_LABELS = mapOf(
    "sleep" to "Sommeil", "steps" to "Pas", "exercise" to "Exercice",
    "resting_heart_rate" to "FC repos", "heart_rate" to "FC moyenne", "hrv_rmssd" to "VFC",
)

@Composable
fun StatsScreen(context: Context, drinks:List<DrinkEntity>, trackedDays:List<TrackedDayEntity>, healthRows:List<HealthAggregateEntity>) {
    var start by remember { mutableStateOf(LocalDate.now().minusDays(89)) }
    var end by remember { mutableStateOf(LocalDate.now()) }
    var custom by remember { mutableStateOf(false) }
    val days=remember(drinks,start,end){generateSequence(start){if(it<end)it.plusDays(1)else null}.map { day ->
        val rows=drinks.filter { it.startedAt.take(10)==day.toString() };val sober=trackedDays.any{it.day==day.toString()&&it.sober};LocalStatDay(day,rows.sumOf{alcoholGrams(it.volumeMl,it.abvPercent,it.quantity)},rows.sumOf{canadianStandards(it.volumeMl,it.abvPercent,it.quantity)},rows,rows.isNotEmpty()||sober,sober)
    }.toList()};val observed=days.filter{it.observed};val drinking=days.filter{it.grams>0};val totalStd=observed.sumOf{it.standards};val totalGrams=observed.sumOf{it.grams}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        PageHeaderLite("Analyse", "Stats")
        StatsPeriodSelector(start, end, custom, onPreset = { days -> start=LocalDate.now().minusDays(days-1L);end=LocalDate.now();custom=false }, onCustom = { a,b -> start=a;end=b;custom=true })
        SectionCard("Période observée", "Du ${start.format(STAT_DATE)} au ${end.format(STAT_DATE)}") {
            StatRow("Jours observés",observed.size.toString());StatRow("Jours sans alcool","${observed.count{it.sober}} (${fmt(observed.count{it.sober}*100.0/observed.size.coerceAtLeast(1),0)} %)")
            StatRow("Jours sans donnée",days.count{!it.observed}.toString());StatRow("Total standards",fmt(totalStd,2));StatRow("Moyenne / jour observé","${fmt(totalStd/observed.size.coerceAtLeast(1),2)} std")
            StatRow("Maximum","${fmt(observed.maxOfOrNull{it.standards},2)} std · ${fmt(observed.maxOfOrNull{it.grams},0)} g")
        }
        DistributionSummary(observed)
        LocalTrendSection(days)
        HeatmapSection(days)
        WeekdayChart(days)
        HourChart(drinking.flatMap{it.drinks})
        DistributionChart(days)
        FirstDrinkChart(drinking)
        DayToDayChart(days)
        PeriodBars("Évolution par semaine",aggregateLocal(days,false))
        PeriodBars("Évolution par mois",aggregateLocal(days,true),monthly=true)
        LocalHealthSection(healthRows,start,end)
        SessionsSection(drinking.flatMap{it.drinks})
        Spacer(Modifier.height(28.dp))
    }
}

private data class LocalStatDay(val date:LocalDate,val grams:Double,val standards:Double,val drinks:List<DrinkEntity>,val observed:Boolean,val sober:Boolean)
private data class LocalPeriod(val label:String,val standards:Double,val alcoholFree:Int)

@Composable private fun LocalTrendSection(days:List<LocalStatDay>){
    var metric by remember{mutableStateOf("standards")};val daily=days.map{if(metric=="grams")it.grams else it.standards}
    fun moving(n:Int)=days.indices.map{i->days.subList(maxOf(0,i-n+1),i+1).filter{it.observed}.map{if(metric=="grams")it.grams else it.standards}.takeIf{it.isNotEmpty()}?.average()}
    SectionCard("Moyennes mobiles","Barres = jour · vert = 7 j · ambre = 30 j · gris = 90 j"){
        Row{listOf("standards" to "Standards","grams" to "Grammes").forEach{(v,l)->FilterChip(metric==v,{metric=v},{Text(l)},Modifier.padding(end=6.dp))}}
        ChartFrame(if(metric=="grams")"g / jour" else "standards / jour",days.firstOrNull()?.date.toString().takeLast(5),days.lastOrNull()?.date.toString().takeLast(5)){
            ComboChart(daily,listOf(Pine to moving(7),Amber to moving(30),Pine.copy(alpha=.4f) to moving(90)),if(metric=="standards")3.0 else null,labels=days.map{it.date.toString()},unit=if(metric=="grams")" g" else " std")
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun HeatmapSection(days:List<LocalStatDay>){val max=days.maxOfOrNull{it.grams}?.coerceAtLeast(.01)?:.01;var selected by remember(days){mutableStateOf<LocalStatDay?>(null)}
    SectionCard("Calendrier de consommation","Intensité quotidienne · calcul local"){
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement=Arrangement.spacedBy(3.dp),verticalArrangement=Arrangement.spacedBy(3.dp)){days.forEach{d->Box(Modifier.size(14.dp).background(if(selected==d)Amber else if(!d.observed)Color(0xFFE2E5E3) else if(d.sober)Mint.copy(alpha=.65f)else Pine.copy(alpha=(.2+.8*d.grams/max).toFloat()),RoundedCornerShape(2.dp)).pointerInput(d){detectTapGestures{selected=d}})}}
        Spacer(Modifier.height(8.dp));Text("${days.firstOrNull()?.date?:"—"} — ${days.lastOrNull()?.date?:"—"}",style=MaterialTheme.typography.labelSmall,color=Pine.copy(alpha=.55f))
        selected?.let{ChartSelection(it.date.toString(),if(!it.observed)"Aucune donnée" else if(it.sober)"Journée sobre · 0 g" else "${fmt(it.grams,1)} g · ${fmt(it.standards,2)} std · ${it.drinks.sumOf{d->d.quantity}} consommation${if(it.drinks.sumOf{d->d.quantity}>1)"s"else""}")}
    }
}

private fun aggregateLocal(days:List<LocalStatDay>,monthly:Boolean):List<LocalPeriod> = days.groupBy{if(monthly)it.date.withDayOfMonth(1) else it.date.minusDays(it.date.dayOfWeek.value-1L)}.toSortedMap().map{(date,rows)->LocalPeriod(date.toString(),rows.filter{it.observed}.sumOf{it.standards},rows.count{it.sober})}.takeLast(12)

@Composable private fun PeriodBars(title:String,rows:List<LocalPeriod>,monthly:Boolean=false){SectionCard(title,"Standards totaux · 12 dernières périodes"){
    ChartFrame("standards",rows.firstOrNull()?.label?.takeLast(5)?:"",rows.lastOrNull()?.label?.takeLast(5)?:""){BarChart(rows.map{it.standards},rows.map{Pine},labels=rows.map{it.label},unit=" std")}
    rows.takeLast(if(monthly)12 else 6).forEach{val label=if(monthly)runCatching{LocalDate.parse(it.label).format(DateTimeFormatter.ofPattern("MMM yyyy",Locale.CANADA_FRENCH))}.getOrDefault(it.label)else it.label;StatRow(label,"${fmt(it.standards,1)} std · ${it.alcoholFree} j sobres")}
}}

@Composable private fun SessionsSection(drinks:List<DrinkEntity>){val sorted=drinks.sortedBy{it.startedAt};val sessions=mutableListOf<MutableList<DrinkEntity>>();sorted.forEach{d->val at=runCatching{parseDrinkTime(d.startedAt)}.getOrNull();val last=sessions.lastOrNull()?.lastOrNull()?.let{runCatching{parseDrinkTime(it.startedAt).plusMinutes(it.durationMinutes.toLong())}.getOrNull()};if(last==null||at==null||java.time.Duration.between(last,at).toHours()>=8)sessions.add(mutableListOf(d))else sessions.last().add(d)}
    SectionCard("Sessions","Écart de 8 h · calcul local") { sessions.takeLast(8).reversed().forEach { rows -> val std=rows.sumOf{canadianStandards(it.volumeMl,it.abvPercent,it.quantity)};StatRow(rows.first().startedAt.take(10),"${fmt(std,2)} std · ${rows.sumOf{it.quantity}} consommation${if(rows.sumOf{it.quantity}>1)"s"else""}") } }
}

@Composable private fun WeekdayChart(days:List<LocalStatDay>){val labels=listOf("Lun","Mar","Mer","Jeu","Ven","Sam","Dim");val values=(1..7).map{w->days.filter{it.date.dayOfWeek.value==w}.sumOf{it.grams}}
    SectionCard("Grammes par jour de semaine","Somme cumulée sur la période"){BarChart(values,values.map{Pine},labels=labels,unit=" g")}}

@Composable private fun HourChart(drinks:List<DrinkEntity>){val values=(0..23).map{hour->drinks.filter{runCatching{parseDrinkTime(it.startedAt).hour==hour}.getOrDefault(false)}.sumOf{alcoholGrams(it.volumeMl,it.abvPercent,it.quantity)}};val groups=drinks.groupBy{it.startedAt.take(10)}
    fun decimal(t:OffsetDateTime)=t.hour+t.minute/60.0
    val first=groups.values.mapNotNull{rows->rows.mapNotNull{runCatching{parseDrinkTime(it.startedAt)}.getOrNull()}.minOrNull()?.let(::decimal)}.averageOrNull()
    val last=groups.values.mapNotNull{rows->rows.mapNotNull{d->runCatching{parseDrinkTime(d.startedAt).plusMinutes(d.durationMinutes.toLong())}.getOrNull()}.maxOrNull()?.let(::decimal)}.averageOrNull()
    fun clock(v:Double?)=v?.let{val mins=(it*60).roundToInt().mod(24*60);"${(mins/60).toString().padStart(2,'0')}:${(mins%60).toString().padStart(2,'0')}"}?:"—"
    SectionCard("Par heure de début","Hauteur = grammes d’alcool pur · axe horizontal = heure"){BarChart(values,values.map{if(it>0)Pine else Mint},labels=(0..23).map{"${it.toString().padStart(2,'0')} h"},unit=" g");StatRow("Première consommation habituelle",clock(first));StatRow("Dernière consommation habituelle",clock(last))}}

@Composable private fun DistributionChart(days:List<LocalStatDay>){val observed=days.filter{it.observed};val labels=listOf("0","≤ 1","1–2","2–4","> 4");val values=listOf(observed.count{it.sober},observed.count{it.standards in .000001..1.0},observed.count{it.standards>1&&it.standards<=2},observed.count{it.standards>2&&it.standards<=4},observed.count{it.standards>4}).map{it.toDouble()}
    SectionCard("Répartition des journées","Nombre de jours par intensité"){BarChart(values,listOf(Mint,Pine.copy(alpha=.35f),Pine.copy(alpha=.55f),Amber,Color(0xFFD9534F)),labels=labels,unit=" j")}}

@Composable private fun FirstDrinkChart(days:List<LocalStatDay>){val rows=days.mapNotNull{day->day.drinks.minByOrNull{it.startedAt}?.let{d->runCatching{parseDrinkTime(d.startedAt).let{day.date.toString() to (it.hour+it.minute/60.0)}}.getOrNull()}}
    SectionCard("Heure de première consommation","Journées avec consommation"){BarChart(rows.map{it.second},rows.map{Pine},labels=rows.map{it.first},unit=" h")}}

@Composable private fun DayToDayChart(days:List<LocalStatDay>){val changes=days.mapIndexed{i,d->if(i==0||!d.observed||!days[i-1].observed)null else d.standards-days[i-1].standards}
    SectionCard("Consommation d’un jour à l’autre","Variation en standards"){LineChart(changes,color=Pine,labels=days.map{it.date.toString()},unit=" std")}}

private fun List<Double>.averageOrNull()=if(isEmpty())null else average()
private fun quantile(sorted:List<Double>,q:Double):Double?{if(sorted.isEmpty())return null;val p=(sorted.size-1)*q;val lo=p.toInt();val hi=kotlin.math.ceil(p).toInt();return if(lo==hi)sorted[lo]else sorted[lo]+(sorted[hi]-sorted[lo])*(p-lo)}

@Composable private fun DistributionSummary(days:List<LocalStatDay>){var metric by remember{mutableStateOf("standards")};val values=days.map{if(metric=="grams")it.grams else it.standards}.sorted();val mean=values.averageOrNull();val sd=mean?.let{m->kotlin.math.sqrt(values.sumOf{(it-m)*(it-m)}/values.size.coerceAtLeast(1))};val unit=if(metric=="grams")" g"else" standards"
    SectionCard("Distribution complète","Par journée observée · 1 consommation standard canadienne = 13,45 g d’alcool pur."){
        Row{listOf("standards" to "Standards","grams" to "Grammes").forEach{(v,l)->FilterChip(metric==v,{metric=v},{Text(l)},Modifier.padding(end=6.dp))}}
        StatRow("Moyenne",fmt(mean,2)+unit);StatRow("Médiane",fmt(quantile(values,.5),2)+unit);StatRow("Quartile 1",fmt(quantile(values,.25),2)+unit);StatRow("Quartile 3",fmt(quantile(values,.75),2)+unit);StatRow("P90",fmt(quantile(values,.9),2)+unit);StatRow("Écart-type",fmt(sd,2)+unit);StatRow("Coeff. variation",if(mean==null||mean==0.0)"—"else fmt(sd!!/mean,2));StatRow("Minimum",fmt(values.minOrNull(),2)+unit);StatRow("Maximum",fmt(values.maxOrNull(),2)+unit)
    }}

@Composable private fun LocalHealthSection(rows:List<HealthAggregateEntity>,start:LocalDate,end:LocalDate){val filtered=rows.filter{it.localDate>=start.toString()&&it.localDate<=end.toString()};val types=filtered.map{it.recordType}.distinct();if(types.isEmpty()){SectionCard("Données de santé"){Text("Aucune donnée Health Connect locale pour cette période.",color=Pine.copy(alpha=.7f))};return};var metric by remember(types){mutableStateOf(types.first())};val selected=filtered.filter{it.recordType==metric}.groupBy{it.localDate}.toSortedMap();val values=selected.mapValues{(_,rs)->rs.mapNotNull{runCatching{JSONObject(it.payload).optDouble("value")}.getOrNull()}.averageOrNull()};val unit=filtered.firstOrNull{it.recordType==metric}?.let{runCatching{JSONObject(it.payload).optString("unit")}.getOrDefault("")}?:""
    SectionCard("Données de santé","Données locales Health Connect"){
        Row(Modifier.horizontalScroll(rememberScrollState())){types.forEach{t->FilterChip(metric==t,{metric=t},{Text(HEALTH_LABELS[t]?:t)},Modifier.padding(end=6.dp))}}
        Spacer(Modifier.height(8.dp));LineChart(values.values.toList(),color=Amber,labels=values.keys.toList(),unit=if(unit.isBlank())""else" $unit");StatRow("Moyenne",fmt(values.values.filterNotNull().averageOrNull(),1)+(if(unit.isBlank())""else" $unit"));StatRow("Jours avec donnée",values.size.toString())
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
        runCatching { Net.array(context, "/api/goals") }.onSuccess { goals = it }.onFailure { goals=JSONArray();error = "Hors ligne · les objectifs locaux restent disponibles" }
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
