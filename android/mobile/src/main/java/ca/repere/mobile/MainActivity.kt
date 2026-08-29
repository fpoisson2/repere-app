package ca.repere.mobile

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.LocalBar
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import ca.repere.data.DrinkEntity
import ca.repere.data.PresetEntity
import ca.repere.data.SyncRepository
import ca.repere.data.SyncWorker
import ca.repere.data.TrackedDayEntity
import ca.repere.data.HealthAggregateEntity
import ca.repere.data.CheckInEntity
import ca.repere.data.GoalEntity
import ca.repere.data.LocalSettings
import ca.repere.core.canadianStandards
import ca.repere.core.CredentialStore
import ca.repere.core.BacDrink
import ca.repere.core.BacProfile
import ca.repere.core.distributionRatio
import ca.repere.core.peakBac
import ca.repere.core.bacAt
import ca.repere.core.recentForBac
import ca.repere.core.parseDrinkTime
import ca.repere.core.trackedDay
import ca.repere.data.HealthLocalRepository
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SyncWorker.schedule(this)
        CheckInReminder.schedule(this) // re-arm the daily reminder chain if it was enabled
        val openCheckIn = intent?.getBooleanExtra("open_checkin", false) == true
        setContent {
            MaterialTheme(
                colorScheme=lightColorScheme(primary=Pine,onPrimary=Color.White,primaryContainer=Mint,
                    background=Paper,surface=Color.White,onSurface=PineDark,secondary=Amber),
                shapes=Shapes(medium=RoundedCornerShape(20.dp),large=RoundedCornerShape(28.dp))
            ) { RepereApp(this, openCheckIn) }
        }
    }
}

private enum class Destination(val labelRes:Int,val icon:ImageVector,val inBar:Boolean=true) {
    NOW(R.string.nav_now,Icons.Filled.LocalBar),
    STATS(R.string.nav_stats,Icons.AutoMirrored.Filled.ShowChart),
    INSIGHTS(R.string.nav_insights,Icons.Filled.Insights),
    SUCCESS(R.string.nav_success,Icons.Filled.EmojiEvents),
    GOALS(R.string.nav_goals,Icons.Filled.Flag),
    SETTINGS(R.string.nav_settings,Icons.Filled.Settings),
    HISTORY(R.string.nav_history,Icons.Filled.History,inBar=false),
    HEALTH(R.string.nav_health,Icons.Filled.MonitorHeart,inBar=false)
}

@Composable
private fun RepereApp(context:Context, openCheckIn:Boolean=false) {
    val credentials=remember{CredentialStore(context)}
    val repository=remember{SyncRepository(context)}
    val drinks by repository.observeDrinks().collectAsState(initial=emptyList())
    val presets by repository.observePresets().collectAsState(initial=emptyList())
    val trackedDays by repository.observeTrackedDays().collectAsState(initial=emptyList())
    val checkIns by repository.observeCheckIns().collectAsState(initial=emptyList())
    val goals by repository.observeGoals().collectAsState(initial=emptyList())
    val localSettings by repository.observeSettings().collectAsState(initial=null)
    val analysisDrinks=remember(drinks,localSettings?.trackingStartDate){val start=localSettings?.trackingStartDate?.let{runCatching{LocalDate.parse(it)}.getOrNull()};if(start==null)drinks else drinks.filter{drink->runCatching{parseDrinkTime(drink.startedAt).toLocalDate()>=start}.getOrDefault(true)}}
    val healthRows by remember{HealthLocalRepository(context)}.observe().collectAsState(initial=emptyList())
    var destination by remember{mutableStateOf(Destination.NOW)}
    var server by remember{mutableStateOf(credentials.server(BuildConfig.DEFAULT_SERVER_URL))}
    var token by remember{mutableStateOf(credentials.token())}
    var syncEnabled by remember{mutableStateOf(credentials.syncEnabled())}
    var status by remember{mutableStateOf(if(syncEnabled)"Prêt hors ligne" else "Local uniquement")}
    var syncing by remember{mutableStateOf(false)}
    val updateManager=remember{AppUpdateManagerFactory.create(context)}
    var availableUpdate by remember{mutableStateOf<AppUpdateInfo?>(null)}
    var updateReadyToInstall by remember{mutableStateOf(false)}
    val scope=rememberCoroutineScope()

    fun synchronize()=scope.launch {
        if(token.isBlank()||!syncEnabled)return@launch
        syncing=true;status="Synchronisation…"
        runCatching{Net.flush(context);repository.synchronize()}.onSuccess{status="À jour";WearStatePublisher.publish(context,drinks,localSettings?:LocalSettings())}
            .onFailure{status="Hors ligne · les saisies sont conservées"}
        syncing=false
    }
    // Re-checked on every resume (not just on first launch) since a background download can finish, or
    // become available, while the app sits idle; startUpdateFlow is only triggered when showFlow=true.
    suspend fun checkForUpdates(showFlow:Boolean=false):Boolean{
        val info=runCatching{updateManager.appUpdateInfo.await()}.getOrNull()
        updateReadyToInstall=info?.installStatus()==InstallStatus.DOWNLOADED
        val update=info?.takeIf{it.updateAvailability()==UpdateAvailability.UPDATE_AVAILABLE&&it.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE)}
        availableUpdate=update
        if(showFlow&&update!=null)runCatching{updateManager.startUpdateFlow(update,context as Activity,AppUpdateOptions.defaultOptions(AppUpdateType.FLEXIBLE))}
        return update!=null||updateReadyToInstall
    }
    // A flexible update that finishes downloading in the background never surfaces on its own;
    // this listener is what turns that into the "restart to install" banner below.
    DisposableEffect(updateManager){
        val listener=InstallStateUpdatedListener{state->
            when(state.installStatus()){
                InstallStatus.DOWNLOADED->updateReadyToInstall=true
                InstallStatus.INSTALLED,InstallStatus.CANCELED->{updateReadyToInstall=false;availableUpdate=null}
                else->{}
            }
        }
        updateManager.registerListener(listener)
        onDispose{updateManager.unregisterListener(listener)}
    }
    LaunchedEffect(Unit){repository.ensureOfflineDefaults()}
    LaunchedEffect(Unit){checkForUpdates()}
    LaunchedEffect(token,syncEnabled){if(token.isNotBlank()&&syncEnabled)synchronize()}
    LaunchedEffect(drinks,localSettings){WearStatePublisher.publish(context,drinks,localSettings?:LocalSettings())}
    // Re-read credentials (e.g. after the OAuth browser redirect) and refresh whenever the app returns to the foreground.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME){
        server=credentials.server(BuildConfig.DEFAULT_SERVER_URL);token=credentials.token();syncEnabled=credentials.syncEnabled()
        if(token.isNotBlank()&&syncEnabled)synchronize()
        scope.launch{checkForUpdates()}
    }

    Scaffold(containerColor=Paper,bottomBar={NavigationBar(containerColor=Color.White){Destination.entries.filter{it.inBar}.forEach{item ->
        val label=stringResource(item.labelRes)
        NavigationBarItem(selected=destination==item,onClick={destination=item},
            icon={Icon(item.icon,contentDescription=label)},
            label={Text(label,maxLines=1,style=MaterialTheme.typography.labelSmall)})
    }}}){padding ->
        Box(Modifier.padding(padding).fillMaxSize()){
            when(destination){
                Destination.NOW -> MaintenantScreen(context,repository,drinks,presets,trackedDays,checkIns,localSettings?:LocalSettings(),status,openCheckIn,{synchronize()},
                    onCustom={name,volume,abv,quantity,startedAt,duration -> scope.launch{
                        repository.createCustom(name,volume,abv,quantity,startedAt,duration);status=if(syncEnabled)"En attente d’envoi" else "Conservé localement";synchronize()
                    }},
                    onEdit={id,name,volume,abv,quantity,startedAt,duration -> scope.launch{
                        repository.updateOffline(id,name,volume,abv,quantity,startedAt,duration);status="Modification en attente";synchronize()
                    }},
                    onDelete={id -> scope.launch{repository.markDeleted(id);status="Suppression en attente";synchronize()}},
                    onSober={day,sober->scope.launch{repository.setSoberDay(day,sober)}})
                Destination.STATS -> StatsScreen(context,analysisDrinks,trackedDays,healthRows,localSettings?:LocalSettings())
                Destination.INSIGHTS -> InsightsScreen(analysisDrinks,checkIns,localSettings?:LocalSettings())
                Destination.SUCCESS -> SuccessScreen(analysisDrinks,trackedDays,checkIns,goals,localSettings?:LocalSettings())
                Destination.GOALS -> GoalsScreen(repository,goals,drinks,trackedDays,localSettings?:LocalSettings(),{synchronize()})
                Destination.HISTORY -> HistoryScreen(drinks){clientId -> scope.launch{repository.markDeleted(clientId);status=if(syncEnabled)"Suppression en attente" else "Suppression locale";synchronize()}}
                Destination.HEALTH -> HealthScreen(context,server,token)
                Destination.SETTINGS -> SettingsScreen(context,repository,drinks,localSettings?:LocalSettings(),server,{server=it},token,{token=it;syncEnabled=credentials.syncEnabled();destination=Destination.NOW},syncEnabled,{enabled->syncEnabled=enabled;status=if(enabled)"Synchronisation activée" else "Local uniquement"},status,onOpenHistory={destination=Destination.HISTORY},onOpenHealth={destination=Destination.HEALTH},onCheckUpdates={checkForUpdates(showFlow=true)})
            }
            if(updateReadyToInstall){Card(Modifier.align(Alignment.TopCenter).padding(12.dp).fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=Mint)){Row(Modifier.padding(14.dp),verticalAlignment=Alignment.CenterVertically){Text(stringResource(R.string.update_ready_restart),Modifier.weight(1f),fontWeight=FontWeight.Bold);TextButton(onClick={
                // completeUpdate() silently drops failures unless you attach a listener; without this the
                // button could look like it does nothing when the tracked install state is stale.
                runCatching{updateManager.completeUpdate()}.getOrNull()?.addOnFailureListener{
                    Toast.makeText(context,context.getString(R.string.update_restart_failed),Toast.LENGTH_LONG).show()
                    runCatching{context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("market://details?id=${context.packageName}")))}
                }?:Toast.makeText(context,context.getString(R.string.update_restart_failed),Toast.LENGTH_LONG).show()
            }){Text(stringResource(R.string.restart_action))}}}}
            else availableUpdate?.let{info->Card(Modifier.align(Alignment.TopCenter).padding(12.dp).fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=Mint)){Row(Modifier.padding(14.dp),verticalAlignment=Alignment.CenterVertically){Text(stringResource(R.string.update_available),Modifier.weight(1f),fontWeight=FontWeight.Bold);TextButton(onClick={runCatching{updateManager.startUpdateFlow(info,context as Activity,AppUpdateOptions.defaultOptions(AppUpdateType.FLEXIBLE))}}){Text(stringResource(R.string.update_action))}}}}
        }
    }
}

@Composable
private fun PageHeader(eyebrow:String,title:String,trailing:(@Composable () -> Unit)?=null) {
    Row(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=18.dp),verticalAlignment=Alignment.CenterVertically){
        Column(Modifier.weight(1f)){Text(eyebrow.uppercase(Locale.CANADA_FRENCH),style=MaterialTheme.typography.labelSmall,color=Pine.copy(alpha=.65f),fontWeight=FontWeight.Bold)
            Text(title,style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Black)}
        trailing?.invoke()
    }
}

private val PRESET_NEW = PresetEntity(0L, "", "autre", 333.0, 5.0)

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun MaintenantScreen(
    context:Context,
    repository:SyncRepository, drinks:List<DrinkEntity>, presets:List<PresetEntity>, trackedDays:List<TrackedDayEntity>, checkIns:List<CheckInEntity>, settings:LocalSettings, status:String, openCheckIn:Boolean, onSync:()->Unit,
    onCustom:(String,Double,Double,Int,String,Int)->Unit,
    onEdit:(String,String,Double,Double,Int,String,Int)->Unit, onDelete:(String)->Unit, onSober:(String,Boolean)->Unit,
) {
    var day by remember { mutableStateOf(LocalDate.now()) }
    val isToday = day == LocalDate.now()
    val dayKey = day.toString()
    val dayDrinks = drinks.filter { runCatching{trackedDay(it.startedAt,settings.dayStartHour)==day}.getOrDefault(false) }.sortedBy { it.startedAt }
    val standards = dayDrinks.sumOf { canadianStandards(it.volumeMl, it.abvPercent, it.quantity) }
    var editing by remember { mutableStateOf<DrinkEntity?>(null) }
    var creatingFrom by remember { mutableStateOf<PresetEntity?>(null) }
    var creatingBlank by remember { mutableStateOf(false) }
    var editingPreset by remember { mutableStateOf<PresetEntity?>(null) }
    var checkIn by remember { mutableStateOf(false) }
    var dayMessage by remember { mutableStateOf<String?>(null) }
    var soberSuccess by remember { mutableStateOf(false) }
    var dayStatus by remember { mutableStateOf<String?>(null) }
    var checkInRefresh by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val refreshing = status.startsWith("Synchronisation")
    LaunchedEffect(dayKey) { dayMessage = null }
    LaunchedEffect(openCheckIn) { if (openCheckIn) checkIn = true }
    LaunchedEffect(dayKey, dayDrinks.size, trackedDays) {
        dayStatus = if(dayDrinks.isNotEmpty())null else if(trackedDays.any{it.day==dayKey&&it.sober})"sober" else null
    }
    PullToRefreshBox(isRefreshing = refreshing, onRefresh = onSync, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { day = day.minusDays(1) }) { Icon(Icons.Filled.ChevronLeft, "Jour précédent") }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        if (isToday) "Aujourd’hui" else day.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, Locale.CANADA_FRENCH).replaceFirstChar { it.uppercase() },
                        fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleLarge,
                    )
                    Text(day.format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.CANADA_FRENCH)), style = MaterialTheme.typography.bodySmall, color = Pine.copy(alpha = .65f))
                }
                IconButton(onClick = { day = day.plusDays(1) }, enabled = !isToday) { Icon(Icons.Filled.ChevronRight, "Jour suivant") }
            }
            Card(Modifier.padding(horizontal = 20.dp).fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = PineDark), shape = RoundedCornerShape(28.dp)) {
                Column(Modifier.padding(24.dp)) {
                    Text(if (isToday) "AUJOURD’HUI" else dayKey, color = Mint.copy(alpha = .75f), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    Text(String.format(Locale.CANADA_FRENCH, "%.1f", standards), color = Color.White, style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Black)
                    Text("standard${if (standards >= 2) "s" else ""} canadien${if (standards >= 2) "s" else ""}", color = Mint)
                    if (isToday) {
                        Spacer(Modifier.height(14.dp)); HorizontalDivider(color = Mint.copy(alpha = .25f))
                        Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(9.dp).background(if (status.startsWith("À jour")) Mint else Amber, RoundedCornerShape(9.dp)))
                            Spacer(Modifier.width(8.dp)); Text(status, color = Color.White, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            if (isToday) BacCard(context,drinks) else if(dayDrinks.isNotEmpty()) HistoricalBacCard(context,dayDrinks)
            LastCheckInCard(checkIns, day, checkInRefresh)
            OutlinedButton(onClick = { checkIn = true }, modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp).fillMaxWidth()) {
                Icon(Icons.AutoMirrored.Filled.Assignment, null); Spacer(Modifier.width(8.dp)); Text("Faire un check-in")
            }
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Ajouter rapidement", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                TextButton(onClick = { editingPreset = PRESET_NEW }) { Text("Nouveau favori") }
            }
            Text("Appui long sur un favori pour le modifier.", Modifier.padding(start = 20.dp, bottom = 8.dp), style = MaterialTheme.typography.bodySmall, color = Pine.copy(alpha = .55f))
            if (presets.isEmpty()) Text("Connecte-toi une première fois pour télécharger tes favoris.", Modifier.padding(horizontal = 20.dp), color = Pine.copy(alpha = .65f))
            else LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(presets) { preset ->
                    ElevatedCard(
                        modifier = Modifier.width(160.dp).combinedClickable(
                            onClick = { creatingFrom = preset },
                            onLongClick = { if (preset.serverId > 0) editingPreset = preset },
                        ),
                        shape = RoundedCornerShape(20.dp),
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text("＋", color = Pine, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Light)
                            Spacer(Modifier.height(12.dp))
                            Text(preset.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            Text("${preset.volumeMl.toInt()} ml · ${preset.abvPercent}%", style = MaterialTheme.typography.bodySmall, color = Pine.copy(alpha = .65f))
                        }
                    }
                }
            }
            OutlinedButton(onClick = { creatingBlank = true }, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp).fillMaxWidth()) { Text("Personnaliser une consommation") }
            Text("Consommations", Modifier.padding(start = 20.dp, top = 20.dp, bottom = 8.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (dayDrinks.isEmpty()) {
                if (dayStatus == "sober") {
                    Text("Journée marquée sobre.", Modifier.padding(horizontal = 20.dp), color = Pine)
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                runCatching { Net.send(context, "/api/days/sober/$dayKey", JSONObject(), "DELETE") }
                                    .onSuccess { onSober(dayKey,false);dayStatus = null; dayMessage = "Journée sobre annulée" }
                                    .onFailure { dayMessage = it.message ?: "Impossible d’annuler" }
                            }
                        },
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp).fillMaxWidth(),
                    ) { Text("Annuler la journée sobre") }
                } else {
                    Text("Aucune consommation ce jour.", Modifier.padding(horizontal = 20.dp), color = Pine.copy(alpha = .65f))
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                runCatching { Net.send(context, "/api/days/sober", JSONObject().put("date", dayKey)) }
                                    .onSuccess { onSober(dayKey,true);soberSuccess = true; dayStatus = "sober" }
                                    .onFailure { dayMessage = it.message ?: "Impossible d’enregistrer" }
                            }
                        },
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp).fillMaxWidth(),
                    ) { Text("Marquer cette journée sobre") }
                }
            }
            dayMessage?.let { Text(it, Modifier.padding(horizontal = 20.dp, vertical = 4.dp), color = Pine.copy(alpha = .72f)) }
            dayDrinks.forEach { d -> DrinkRow(d, onEdit = { editing = d }, onDelete = { onDelete(d.clientId) }) }
            Spacer(Modifier.height(28.dp))
        }
    }
    val prefill = creatingFrom
    editingPreset?.let { p -> PresetEditorDialog(repository, p, onDismiss = { editingPreset = null }) { editingPreset = null; onSync() } }
    if (creatingBlank) DrinkEditorDialog(day, null, onDismiss = { creatingBlank = false }) { n, v, a, q, started, dur -> onCustom(n, v, a, q, started, dur); creatingBlank = false }
    if (prefill != null) DrinkEditorDialog(day, null, prefillName = prefill.name, prefillVolume = prefill.volumeMl.toInt(), prefillAbv = prefill.abvPercent, onDismiss = { creatingFrom = null }) { n, v, a, q, started, dur -> onCustom(n, v, a, q, started, dur); creatingFrom = null }
    editing?.let { d -> DrinkEditorDialog(day, d, onDismiss = { editing = null }) { n, v, a, q, started, dur -> onEdit(d.clientId, n, v, a, q, started, dur); editing = null } }
    if (checkIn) CheckInDialog(day, onDismiss = { checkIn = false }) { payload ->
        scope.launch {
            runCatching { repository.saveCheckIn(payload) }
                .onFailure { dayMessage = it.message ?: "Check-in non envoyé" }
                .onSuccess { dayMessage = "Check-in enregistré localement"; checkInRefresh++;onSync() }
        }
        checkIn = false
    }
    if (soberSuccess) AlertDialog(
        onDismissRequest = { soberSuccess = false },
        confirmButton = { TextButton(onClick = { soberSuccess = false }) { Text("Continuer") } },
        icon = { Text("🎉", style = MaterialTheme.typography.displaySmall) },
        title = { Text("Journée sobre enregistrée") },
        text = { Text("Bien joué. Cette journée est maintenant comptée comme une journée sans alcool.") },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DrinkEditorDialog(
    day:LocalDate,
    existing:DrinkEntity?,
    prefillName:String="Consommation", prefillVolume:Int=333, prefillAbv:Double=5.0,
    onDismiss:()->Unit,
    onSave:(String,Double,Double,Int,String,Int)->Unit,
) {
    var name by remember{mutableStateOf(existing?.name ?: prefillName)}
    var volume by remember{mutableStateOf((existing?.volumeMl?.toInt() ?: prefillVolume).toString())}
    var abv by remember{mutableStateOf((existing?.abvPercent ?: prefillAbv).toString())}
    var quantity by remember{mutableStateOf((existing?.quantity ?: 1).toString())}
    var duration by remember{mutableStateOf((existing?.durationMinutes ?: 30).toString())}
    val parsed = remember(existing?.startedAt) { existing?.startedAt?.let { runCatching { parseDrinkTime(it) }.getOrNull() } }
    var time by remember { mutableStateOf(parsed?.toLocalTime() ?: java.time.LocalTime.now().withSecond(0).withNano(0)) }
    var date by remember { mutableStateOf(parsed?.toLocalDate() ?: day) }
    var pickTime by remember { mutableStateOf(false) }
    var pickDate by remember { mutableStateOf(false) }
    val numeric = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
    AlertDialog(onDismissRequest=onDismiss,title={Text(if(existing==null)"Nouvelle consommation" else "Modifier la consommation")},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){
            OutlinedTextField(name,{name=it},label={Text("Nom")},singleLine=true)
            OutlinedTextField(volume,{volume=it.filter(Char::isDigit)},label={Text("Volume (ml)")},singleLine=true,keyboardOptions=numeric)
            OutlinedTextField(abv,{abv=it.filter{c->c.isDigit()||c=='.'||c==','}},label={Text("Alcool (%)")},singleLine=true,keyboardOptions=androidx.compose.foundation.text.KeyboardOptions(keyboardType=androidx.compose.ui.text.input.KeyboardType.Decimal))
            OutlinedTextField(quantity,{quantity=it.filter(Char::isDigit)},label={Text("Quantité")},singleLine=true,keyboardOptions=numeric)
            OutlinedTextField(date.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.CANADA_FRENCH)),{},readOnly=true,label={Text("Date")},modifier=Modifier.fillMaxWidth(),trailingIcon={TextButton(onClick={pickDate=true}){Text("Choisir")}})
            OutlinedTextField(time.format(DateTimeFormatter.ofPattern("HH:mm")),{},readOnly=true,label={Text("Heure de début")},modifier=Modifier.fillMaxWidth(),trailingIcon={TextButton(onClick={pickTime=true}){Text("Choisir")}})
            OutlinedTextField(duration,{duration=it.filter(Char::isDigit)},label={Text("Durée (minutes)")},singleLine=true,keyboardOptions=numeric)
        }
    },confirmButton={TextButton(onClick={
        val started=date.atTime(time).atZone(java.time.ZoneId.systemDefault()).toOffsetDateTime().toString()
        onSave(name,volume.toDoubleOrNull()?:333.0,abv.replace(',','.').toDoubleOrNull()?:5.0,quantity.toIntOrNull()?.coerceAtLeast(1)?:1,started,duration.toIntOrNull()?.coerceAtLeast(0)?:30)
    }){Text(if(existing==null)"Ajouter" else "Enregistrer")}},dismissButton={TextButton(onClick=onDismiss){Text("Annuler")}})
    if (pickTime) {
        val state = rememberTimePickerState(initialHour = time.hour, initialMinute = time.minute, is24Hour = true)
        AlertDialog(onDismissRequest={pickTime=false},confirmButton={TextButton(onClick={time=java.time.LocalTime.of(state.hour,state.minute);pickTime=false}){Text("OK")}},dismissButton={TextButton(onClick={pickTime=false}){Text("Annuler")}},text={TimePicker(state=state)})
    }
    if (pickDate) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = date.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= System.currentTimeMillis() + 86_400_000
            },
        )
        DatePickerDialog(onDismissRequest={pickDate=false},confirmButton={TextButton(onClick={
            state.selectedDateMillis?.let { date = java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneOffset.UTC).toLocalDate() }
            pickDate=false
        }){Text("OK")}},dismissButton={TextButton(onClick={pickDate=false}){Text("Annuler")}}){ DatePicker(state=state) }
    }
}

@Composable
private fun PresetEditorDialog(repository:SyncRepository, preset:PresetEntity, onDismiss:()->Unit, onSaved:()->Unit) {
    val isNew = preset.serverId <= 0L
    var name by remember { mutableStateOf(preset.name) }
    var type by remember { mutableStateOf(preset.type) }
    var volume by remember { mutableStateOf(if (isNew) "333" else preset.volumeMl.toInt().toString()) }
    var abv by remember { mutableStateOf(if (isNew) "5" else preset.abvPercent.toString()) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val numeric = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "Nouveau favori" else "Modifier le favori") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Nom") }, singleLine = true)
                OutlinedTextField(type, { type = it }, label = { Text("Type (bière, vin…)") }, singleLine = true)
                OutlinedTextField(volume, { volume = it.filter(Char::isDigit) }, label = { Text("Volume (ml)") }, singleLine = true, keyboardOptions = numeric)
                OutlinedTextField(abv, { abv = it.filter { c -> c.isDigit() || c == '.' || c == ',' } }, label = { Text("Alcool (%)") }, singleLine = true, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal))
                message?.let { Text(it, color = Color(0xFFD9534F), style = MaterialTheme.typography.bodySmall) }
                if (!isNew) TextButton(onClick = {
                    scope.launch {
                        busy = true
                        runCatching { repository.deletePresetLocal(preset) }
                            .onSuccess { onSaved() }.onFailure { message = it.message ?: "Suppression impossible"; busy = false }
                    }
                }) { Text("Supprimer ce favori", color = Color(0xFFD9534F)) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                scope.launch {
                    busy = true
                    runCatching { repository.savePreset(preset,name.trim(),type.trim().ifBlank { "autre" },volume.toDoubleOrNull() ?: 0.0,abv.replace(',', '.').toDoubleOrNull() ?: 0.0) }
                        .onSuccess { onSaved() }.onFailure { message = it.message ?: "Enregistrement impossible"; busy = false }
                }
            }) { Text("Enregistrer") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BodyMetricsSection(context:Context) {
    val localProfile=remember { CredentialStore(context) }
    var sex by remember { mutableStateOf("unspecified") }
    var sexOpen by remember { mutableStateOf(false) }
    var weight by remember { mutableStateOf("") }
    var height by remember { mutableStateOf("") }
    var elimination by remember { mutableStateOf(localProfile.bacEliminationRate().toString()) }
    var ratio by remember { mutableStateOf<Double?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        sex=localProfile.bodySex();localProfile.bodyHeightCm()?.let{height=it.toInt().toString()}
        localProfile.bacWeightKg()?.let { weight=it.toInt().toString() }
        ratio=localProfile.bacDistributionRatio()
        runCatching { Net.json(context, "/api/auth/me") }.onSuccess {
            sex = it.optString("sex", "unspecified").ifBlank { "unspecified" }
            if(!it.isNull("weight_kg"))weight = it.optDouble("weight_kg").toInt().toString()
            if(!it.isNull("height_cm"))height = it.optDouble("height_cm").toInt().toString()
            if(!it.isNull("elimination_rate"))elimination=it.optDouble("elimination_rate").toString()
            val remoteRatio=if (it.isNull("effective_distribution_ratio")) it.doubleOrNull("distribution_ratio") else it.optDouble("effective_distribution_ratio")
            if(remoteRatio!=null)ratio=remoteRatio
            if(weight.toDoubleOrNull()!=null&&ratio!=null)localProfile.saveBacProfile(weight.toDouble(),ratio!!)
        }
    }
    val sexLabel = mapOf("unspecified" to "Non précisé", "female" to "Femme", "male" to "Homme")
    val numeric = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
    Text("Alcoolémie — profil corporel", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
    Text("Sexe, poids et grandeur servent au calcul du taux d’alcoolémie (formule de Watson).", style = MaterialTheme.typography.bodySmall, color = Pine.copy(alpha = .65f))
    Box {
        OutlinedTextField(sexLabel[sex] ?: sex, {}, readOnly = true, label = { Text("Sexe") }, modifier = Modifier.fillMaxWidth(), trailingIcon = { TextButton(onClick = { sexOpen = true }) { Text("Changer") } })
        DropdownMenu(expanded = sexOpen, onDismissRequest = { sexOpen = false }) {
            sexLabel.forEach { (v, l) -> DropdownMenuItem(text = { Text(l) }, onClick = { sex = v; sexOpen = false }) }
        }
    }
    OutlinedTextField(weight, { weight = it.filter(Char::isDigit) }, label = { Text("Poids (kg)") }, singleLine = true, keyboardOptions = numeric, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(height, { height = it.filter(Char::isDigit) }, label = { Text("Grandeur (cm)") }, singleLine = true, keyboardOptions = numeric, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(elimination,{elimination=it.filter{char->char.isDigit()||char=='.'}},label={Text(stringResource(R.string.elimination_rate))},supportingText={Text(stringResource(R.string.elimination_rate_hint))},singleLine=true,keyboardOptions=androidx.compose.foundation.text.KeyboardOptions(keyboardType=androidx.compose.ui.text.input.KeyboardType.Decimal),modifier=Modifier.fillMaxWidth())
    ratio?.let { Text("Facteur de distribution calculé : ${String.format(Locale.CANADA_FRENCH, "%.3f", it)}", style = MaterialTheme.typography.bodySmall, color = Pine.copy(alpha = .7f)) }
    Button(onClick = {
        scope.launch {
            val localWeight=weight.toDoubleOrNull();val localRatio=localWeight?.let { distributionRatio(sex,height.toDoubleOrNull(),it,ratio?:.6) };val localElimination=elimination.toDoubleOrNull()
            if(localWeight==null||localRatio==null){message="Poids requis pour calculer l’alcoolémie";return@launch};if(localElimination==null||localElimination !in .005..0.03){message=context.getString(R.string.invalid_elimination_rate);return@launch}
            ratio=localRatio;localProfile.saveBacProfile(localWeight,localRatio,localElimination);localProfile.saveBodyMetrics(sex,height.toDoubleOrNull());message="Profil enregistré sur l’appareil"
            val body = JSONObject().put("sex", sex)
            weight.toDoubleOrNull()?.let { body.put("weight_kg", it) }
            height.toDoubleOrNull()?.let { body.put("height_cm", it) }
            body.put("elimination_rate",localElimination)
            runCatching { Net.send(context, "/api/settings", body, "PATCH") }
                .onSuccess { ratio = it.doubleOrNull("distribution_ratio") ?: ratio;localProfile.saveBacProfile(localWeight,ratio!!);message = "Profil enregistré localement · synchronisation planifiée" }
                .onFailure { message = "Profil enregistré sur l’appareil · synchronisation en attente" }
        }
    }, modifier = Modifier.fillMaxWidth()) { Text("Enregistrer le profil") }
    message?.let { Text(it, color = Pine.copy(alpha = .72f), style = MaterialTheme.typography.bodySmall) }
}

@Composable
private fun BacCard(context:Context,drinks:List<DrinkEntity>) {
    val credentials=remember{CredentialStore(context)};val weight=credentials.bacWeightKg();val ratio=credentials.bacDistributionRatio()
    val profile=if(weight!=null&&ratio!=null)BacProfile(weight,ratio,credentials.bacEliminationRate())else null
    if(profile==null){Card(Modifier.padding(horizontal=20.dp,vertical=8.dp).fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=Color.White)){Text("Configure ton profil corporel dans Réglages pour estimer l’alcoolémie hors ligne.",Modifier.padding(20.dp),color=Pine.copy(alpha=.7f))};return}
    var now by remember{mutableStateOf(OffsetDateTime.now())}
    LaunchedEffect(Unit){while(true){kotlinx.coroutines.delay(60_000);now=OffsetDateTime.now()}}
    val allDrinks=remember(drinks){drinks.mapNotNull{d->runCatching{BacDrink(parseDrinkTime(d.startedAt),d.durationMinutes,d.volumeMl*d.quantity*d.abvPercent/100*.789,d.active)}.getOrNull()}}
    val localDrinks=remember(allDrinks,now){recentForBac(allDrinks,now)}
    val current=bacAt(localDrinks,profile,now)*10;val peak=(peakBac(localDrinks,profile)?:0.0)*10
    val future=bacAt(localDrinks,profile,now.plusMinutes(10))*10;val trend=if(future>current+.01)"hausse"else if(future<current-.01)"baisse"else"stable"
    val zero=(1..24*12).firstOrNull{bacAt(localDrinks,profile,now.plusMinutes(it*5L))<=.00001}?.let{now.plusMinutes(it*5L)}
    Card(Modifier.padding(horizontal = 20.dp, vertical = 8.dp).fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.padding(20.dp)) {
            Text("ALCOOLÉMIE ESTIMÉE", style = MaterialTheme.typography.labelSmall, color = Pine.copy(alpha = .6f), fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(String.format(Locale.CANADA_FRENCH, "%.2f", current), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Black, color = Pine)
                Spacer(Modifier.width(6.dp)); Text("g/L", color = Pine.copy(alpha = .6f), modifier = Modifier.padding(bottom = 6.dp))
            }
            Text(
                when {
                    current<=.001 -> "À zéro · pic estimé ${String.format(Locale.CANADA_FRENCH, "%.2f", peak)} g/L"
                    else -> "Tendance : $trend · pic ${String.format(Locale.CANADA_FRENCH, "%.2f", peak)} g/L"+
                        (zero?.let{" · retour à 0 vers ${it.format(DateTimeFormatter.ofPattern("HH:mm"))}"}?:"")
                },
                style = MaterialTheme.typography.bodySmall, color = Pine.copy(alpha = .72f),
            )
            Text(
                "Estimation mathématique — jamais un indicateur de capacité à conduire.",
                Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelSmall, color = Amber,
            )
        }
    }
}

@Composable
private fun HistoricalBacCard(context:Context,drinks:List<DrinkEntity>) {
    val credentials=remember { CredentialStore(context) }
    val weight=credentials.bacWeightKg();val ratio=credentials.bacDistributionRatio()
    val peak=remember(drinks,weight,ratio) {
        if(weight==null||ratio==null)null else peakBac(drinks.mapNotNull { d -> runCatching { BacDrink(parseDrinkTime(d.startedAt),d.durationMinutes,d.volumeMl*d.quantity*d.abvPercent/100*.789) }.getOrNull() },BacProfile(weight,ratio,credentials.bacEliminationRate()))
    }
    Card(Modifier.padding(horizontal=20.dp,vertical=10.dp).fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=Color.White),shape=RoundedCornerShape(22.dp)) {
        Column(Modifier.padding(20.dp)) {
            Text("Alcoolémie maximale estimée",fontWeight=FontWeight.Bold,style=MaterialTheme.typography.titleMedium)
            if(peak!=null)Text(String.format(Locale.CANADA_FRENCH,"%.2f g/L",peak*10),style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Black,color=Pine)
            else Text("Configure ton profil corporel dans Réglages.",color=Pine.copy(alpha=.65f))
            Text("Estimation mathématique, jamais une autorisation de conduire.",style=MaterialTheme.typography.bodySmall,color=Pine.copy(alpha=.62f))
        }
    }
}

@Composable
private fun LastCheckInCard(checkIns:List<CheckInEntity>, day:LocalDate, refreshKey:Int) {
    val dayKey = day.toString()
    val isToday = day == LocalDate.now()
    val c=remember(checkIns,dayKey,refreshKey){checkIns.firstOrNull{it.localDate==dayKey}?.let{runCatching{JSONObject(it.payload)}.getOrNull()}}
    if (c == null) {
        if (isToday) Text(
            "Aucun check-in aujourd’hui.",
            Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodySmall, color = Pine.copy(alpha = .55f),
        )
        return
    }
    val standards = if (c.isNull("planned_grams")) null else c.optDouble("planned_grams") / 13.45
    Card(Modifier.padding(horizontal = 20.dp, vertical = 8.dp).fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Mint), shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.padding(20.dp)) {
            Text(if (isToday) "CHECK-IN DU JOUR" else "CHECK-IN DE CETTE JOURNÉE", style = MaterialTheme.typography.labelSmall, color = Pine.copy(alpha = .65f), fontWeight = FontWeight.Bold)
            Text(
                runCatching { OffsetDateTime.parse(c.optString("observed_at_utc")).atZoneSameInstant(java.time.ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMM · HH:mm", Locale.CANADA_FRENCH)) }.getOrDefault(c.optString("local_date")),
                fontWeight = FontWeight.Bold, color = Pine,
            )
            Spacer(Modifier.height(6.dp))
            Text("Envie ${c.optInt("craving")}/10 · confiance ${c.optInt("confidence")}/10" + (if (!c.isNull("stress")) " · stress ${c.optInt("stress")}/10" else ""), style = MaterialTheme.typography.bodySmall, color = Pine.copy(alpha = .8f))
            if (standards != null) Text("Objectif du jour : ${String.format(Locale.CANADA_FRENCH, "%.1f", standards)} conso standard", style = MaterialTheme.typography.bodySmall, color = Pine.copy(alpha = .8f))
            if (c.optBoolean("post_onset")) Text("Rempli après la première consommation", style = MaterialTheme.typography.labelSmall, color = Pine.copy(alpha = .6f))
        }
    }
}

@Composable
private fun DrinkRow(drink:DrinkEntity,onEdit:(()->Unit)?=null,onDelete:(()->Unit)?=null) {
    Row(Modifier.padding(horizontal=20.dp,vertical=6.dp).fillMaxWidth().background(Color.White,RoundedCornerShape(18.dp)).padding(start=16.dp,top=8.dp,bottom=8.dp,end=4.dp),verticalAlignment=Alignment.CenterVertically){
        Box(Modifier.size(42.dp).background(if(drink.dirty)Color(0xFFFFE8C2) else Mint,RoundedCornerShape(14.dp)),contentAlignment=Alignment.Center){Text(if(drink.dirty)"↥" else "✓",fontWeight=FontWeight.Bold,color=Pine)}
        Column(Modifier.padding(start=12.dp).weight(1f)){Text(drink.name,fontWeight=FontWeight.Bold)
            Text("${runCatching{parseDrinkTime(drink.startedAt).format(DateTimeFormatter.ofPattern("HH:mm"))}.getOrDefault("")} · ${drink.volumeMl.toInt()} ml · ${drink.abvPercent}% · ×${drink.quantity}",style=MaterialTheme.typography.bodySmall,color=Pine.copy(alpha=.65f))}
        if(onEdit!=null)IconButton(onClick=onEdit){Icon(Icons.Filled.Edit,"Modifier",tint=Pine)}
        if(onDelete!=null)IconButton(onClick=onDelete){Icon(Icons.Filled.Delete,"Supprimer",tint=Pine.copy(alpha=.7f))}
    }
}

@Composable
private fun HistoryScreen(drinks:List<DrinkEntity>,onDelete:(String)->Unit) {
    Column(Modifier.fillMaxSize()){PageHeader("Copie locale","Historique")
        if(drinks.isEmpty())Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){Text("L’historique apparaîtra après la première synchronisation.",Modifier.padding(30.dp),color=Pine.copy(alpha=.65f))}
        else LazyColumn(contentPadding=PaddingValues(bottom=24.dp)){items(drinks,key={it.clientId}){DrinkRow(it,onDelete={onDelete(it.clientId)})}}}
}

@Composable
private fun HealthScreen(context:Context,server:String,token:String) {
    val bridge=remember{HealthConnectBridge(context)};val scope=rememberCoroutineScope()
    val local=remember{HealthLocalRepository(context)};val localRows by local.observe().collectAsState(initial=emptyList())
    val credentials=remember{CredentialStore(context)}
    var message by remember{mutableStateOf(if(bridge.available())"Choisis précisément les données à partager." else "Health Connect n’est pas disponible sur cet appareil.")}
    var granted by remember{mutableStateOf<Set<String>>(emptySet())}
    var backgroundGranted by remember{mutableStateOf(false)}
    val launcher=rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()){result ->
        granted=result;message="${result.size} autorisation(s) accordée(s)."
        if(token.isNotBlank())scope.launch{HealthConnectBridge.granularPermissions.forEach{(type,permission) ->
            runCatching{Api.put(server,token,"/api/health-connect/permissions",JSONObject().put("permission_type",type)
                .put("status",if(permission in result)"granted" else "denied").put("history_allowed",false).put("background_allowed",false))}
        }}
    }
    val backgroundLauncher=rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()){result ->
        backgroundGranted=HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND in result
        if(backgroundGranted)HealthConnectWorker.enable(context) else HealthConnectWorker.disable(context)
        message=if(backgroundGranted)"Synchronisation Santé en arrière-plan activée." else "Synchronisation en arrière-plan non autorisée."
        if(token.isNotBlank())scope.launch{runCatching{Api.put(server,token,"/api/health-connect/permissions",JSONObject()
            .put("permission_type","background").put("status",if(backgroundGranted)"granted" else "denied")
            .put("background_allowed",backgroundGranted).put("history_allowed",false))}}
    }
    LaunchedEffect(Unit){granted=bridge.granted();backgroundGranted=HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND in granted;if(backgroundGranted)HealthConnectWorker.enable(context)}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())){PageHeader("Données choisies","Santé")
        Card(Modifier.padding(horizontal=20.dp).fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=Mint)){Column(Modifier.padding(20.dp)){
            Text("Tes données restent sous ton contrôle",fontWeight=FontWeight.Bold,style=MaterialTheme.typography.titleMedium)
            Text("Repère conserve des résumés quotidiens sur ce téléphone, jamais les mesures brutes. Une valeur absente ne devient pas zéro.",Modifier.padding(top=8.dp),color=Pine.copy(alpha=.78f))
            Text("${localRows.size} résumé(s) dans la copie locale",Modifier.padding(top=12.dp),fontWeight=FontWeight.Bold,color=Pine)} }
        Text("Données disponibles",Modifier.padding(20.dp),fontWeight=FontWeight.Bold,style=MaterialTheme.typography.titleMedium)
        HealthConnectBridge.granularPermissions.forEach{(type,permission) ->
            val label=mapOf("sleep" to "Sommeil","steps" to "Pas","exercise" to "Exercice","heart_rate" to "Fréquence cardiaque","resting_heart_rate" to "Fréquence au repos","hrv_rmssd" to "Variabilité cardiaque")[type]?:type
            Row(Modifier.padding(horizontal=20.dp,vertical=5.dp).fillMaxWidth().background(Color.White,RoundedCornerShape(16.dp)).padding(16.dp),verticalAlignment=Alignment.CenterVertically){Text(label,Modifier.weight(1f));Text(if(permission in granted)"Autorisé" else "Non partagé",color=if(permission in granted)Pine else Pine.copy(alpha=.5f),fontWeight=FontWeight.Bold,style=MaterialTheme.typography.labelMedium)}
        }
        Button(onClick={launcher.launch(HealthConnectBridge.granularPermissions.values.toSet()+HealthConnectBridge.HISTORY_PERMISSION)},enabled=bridge.available(),modifier=Modifier.padding(horizontal=20.dp,vertical=14.dp).fillMaxWidth()){Text("Choisir les autorisations")}
        if(bridge.backgroundAvailable())Row(Modifier.padding(horizontal=20.dp,vertical=5.dp).fillMaxWidth().background(Color.White,RoundedCornerShape(16.dp)).padding(16.dp),verticalAlignment=Alignment.CenterVertically){
            Column(Modifier.weight(1f)){Text("Synchronisation automatique",fontWeight=FontWeight.Bold);Text("Résumés récents, environ deux fois par jour",style=MaterialTheme.typography.bodySmall,color=Pine.copy(alpha=.65f))}
            Switch(checked=backgroundGranted,onCheckedChange={enabled -> if(enabled)backgroundLauncher.launch(setOf(HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND)) else {backgroundGranted=false;HealthConnectWorker.disable(context);message="Synchronisation automatique désactivée."}})
        }
        var range by remember{mutableIntStateOf(14)}
        var importing by remember{mutableStateOf(false)}
        Text("Période à importer",Modifier.padding(start=20.dp,top=8.dp),fontWeight=FontWeight.Bold,style=MaterialTheme.typography.labelLarge)
        LazyRow(contentPadding=PaddingValues(horizontal=20.dp,vertical=6.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)){
            items(listOf(14,30,90,180,365)){d -> FilterChip(selected=range==d,onClick={range=d},label={Text(if(d>=365)"1 an" else "$d j")})}
        }
        Text("Au-delà de 30 jours, Health Connect exige l’autorisation « historique » (demandée avec les autres).",Modifier.padding(horizontal=20.dp),style=MaterialTheme.typography.bodySmall,color=Pine.copy(alpha=.6f))
        OutlinedButton(onClick={scope.launch{
            importing=true;message="Lecture des $range derniers jours…"
            runCatching{val all=JSONArray();repeat(range){index -> val day=bridge.aggregateDay(LocalDate.now().minusDays(index.toLong()));for(i in 0 until day.length())all.put(day.getJSONObject(i))}
                val stored=local.store(all);if(credentials.syncEnabled()&&token.isNotBlank()){val(pending,ids)=local.pending();if(pending.length()>0){Api.postArray(server,token,"/api/health-connect/aggregates",pending);local.markSynced(ids)}};stored}
                .onSuccess{message="$it résumé(s) conservé(s) localement${if(credentials.syncEnabled())" et synchronisé(s)" else ""}."}.onFailure{message=it.message?:"Import impossible"}
            importing=false
        }},enabled=bridge.available()&&granted.isNotEmpty()&&!importing,modifier=Modifier.padding(horizontal=20.dp,vertical=8.dp).fillMaxWidth()){Text(if(importing)"Import en cours…" else "Importer")}
        Text(message,Modifier.padding(20.dp),color=Pine.copy(alpha=.72f))
    }
}

@Composable
private fun SettingsScreen(context:Context,repository:SyncRepository,drinks:List<DrinkEntity>,localSettings:LocalSettings,server:String,onServer:(String)->Unit,token:String,onToken:(String)->Unit,syncEnabled:Boolean,onSyncEnabled:(Boolean)->Unit,status:String,onOpenHistory:()->Unit,onOpenHealth:()->Unit,onCheckUpdates:suspend()->Boolean) {
    val credentials=remember{CredentialStore(context)};var message by remember{mutableStateOf(status)};val scope=rememberCoroutineScope()
    var showUpToDateDialog by remember{mutableStateOf(false)}
    var dayStart by remember{mutableIntStateOf(localSettings.dayStartHour)};var sessionGap by remember{mutableStateOf(localSettings.sessionGapHours)}
    var trackingStart by remember{mutableStateOf(localSettings.trackingStartDate.orEmpty())}
    LaunchedEffect(localSettings.dayStartHour,localSettings.sessionGapHours,localSettings.trackingStartDate){dayStart=localSettings.dayStartHour;sessionGap=localSettings.sessionGapHours;trackingStart=localSettings.trackingStartDate.orEmpty()}
    val exportLauncher=rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/csv")){uri -> uri?.let{
        runCatching{val quote:(String)->String={value->"\"${value.replace("\"","\"\"")}\""};val csv=buildString{appendLine("name,volume_ml,abv_percent,quantity,started_at,duration_minutes");drinks.forEach{drink->appendLine(listOf(drink.name,drink.volumeMl.toString(),drink.abvPercent.toString(),drink.quantity.toString(),drink.startedAt,drink.durationMinutes.toString()).joinToString(","){quote(it)})}};context.contentResolver.openOutputStream(it)?.bufferedWriter()?.use{writer->writer.write(csv)}}
            .onSuccess{message=context.getString(R.string.export_complete)}.onFailure{message=context.getString(R.string.export_failed)}}
    }
    var reminderOn by remember{mutableStateOf(CheckInReminder.isEnabled(context))}
    val notifPermission=rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()){granted ->
        reminderOn=granted;CheckInReminder.setEnabled(context,granted)
        if(granted)scope.launch{CheckInReminder.refreshAndSchedule(context)}
        message=if(granted)"Rappel de check-in activé" else "Autorisation de notification refusée"
    }
    fun syncToWatch(currentToken:String)=scope.launch{val request=PutDataMapRequest.create("/repere/config").apply{dataMap.putString("server",server.trimEnd('/'));dataMap.putString("token",currentToken);dataMap.putLong("updated",System.currentTimeMillis())}.asPutDataRequest().setUrgent();runCatching{Wearable.getDataClient(context).putDataItem(request).await()}}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())){PageHeader("Téléphone et montre","Réglages")
        Column(Modifier.padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
            OutlinedTextField(server,onServer,label={Text("Adresse du serveur")},modifier=Modifier.fillMaxWidth(),singleLine=true)
            Text("Repère fonctionne entièrement sur ce téléphone. La connexion au serveur est facultative.",color=Pine.copy(alpha=.72f))
            if(token.isBlank()){
                Button(onClick={OAuthClient.start(context,server)},enabled=server.isNotBlank(),modifier=Modifier.fillMaxWidth()){Text("Se connecter à Repère")}
                Text("Une page sécurisée s’ouvrira pour autoriser l’application (OAuth 2.0 + PKCE).",color=Pine.copy(alpha=.72f),style=MaterialTheme.typography.bodySmall)
            }else{
                Card(colors=CardDefaults.cardColors(containerColor=Mint),modifier=Modifier.fillMaxWidth()){Column(Modifier.padding(18.dp)){Text("Appareil associé",fontWeight=FontWeight.Bold);Text("La copie hors ligne est active.",color=Pine.copy(alpha=.72f))}}
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("Synchroniser avec le serveur",fontWeight=FontWeight.Bold);Text(if(syncEnabled)"Les changements seront envoyés" else "Les données restent sur cet appareil",style=MaterialTheme.typography.bodySmall,color=Pine.copy(alpha=.65f))}
                    Switch(checked=syncEnabled,onCheckedChange={credentials.setSyncEnabled(it);onSyncEnabled(it);if(it)SyncWorker.schedule(context)})}
                OutlinedButton(onClick={syncToWatch(token);message="Configuration envoyée à la montre"},modifier=Modifier.fillMaxWidth()){Text("Synchroniser la montre")}
                TextButton(onClick={scope.launch{OAuthClient.signOut(context);onToken("");message="Déconnecté"}},modifier=Modifier.fillMaxWidth()){Text("Se déconnecter")}
            }
            Text(message,color=Pine.copy(alpha=.72f))
            HorizontalDivider(Modifier.padding(vertical=6.dp))
            Text(stringResource(R.string.calculation_history),fontWeight=FontWeight.Bold,style=MaterialTheme.typography.titleMedium)
            OutlinedTextField(trackingStart,{trackingStart=it},label={Text(stringResource(R.string.tracking_start_date))},supportingText={Text(stringResource(R.string.iso_date_hint))},modifier=Modifier.fillMaxWidth(),singleLine=true)
            Text(stringResource(R.string.day_start),fontWeight=FontWeight.Bold)
            Text(stringResource(R.string.day_start_explanation,dayStart),style=MaterialTheme.typography.bodySmall,color=Pine.copy(alpha=.65f))
            Slider(value=dayStart.toFloat(),onValueChange={dayStart=it.toInt()},valueRange=0f..23f,steps=22)
            Text(stringResource(R.string.hour_value,dayStart),fontWeight=FontWeight.Bold)
            Text(stringResource(R.string.session_gap),fontWeight=FontWeight.Bold)
            Text(stringResource(R.string.session_gap_explanation),style=MaterialTheme.typography.bodySmall,color=Pine.copy(alpha=.65f))
            Slider(value=sessionGap.toFloat(),onValueChange={sessionGap=(it*2).toInt()/2.0},valueRange=1f..12f,steps=21)
            Text(stringResource(R.string.hours_value,sessionGap),fontWeight=FontWeight.Bold)
            Button(onClick={scope.launch{val date=trackingStart.trim().takeIf{it.isNotBlank()};if(date!=null&&runCatching{LocalDate.parse(date)}.isFailure){message=context.getString(R.string.invalid_date);return@launch};repository.saveSettings(dayStart,sessionGap,date);message=context.getString(R.string.settings_saved_locally);if(syncEnabled)SyncWorker.schedule(context)}},modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.save_calculation_settings))}
            HorizontalDivider(Modifier.padding(vertical=6.dp))
            BodyMetricsSection(context)
            HorizontalDivider(Modifier.padding(vertical=6.dp))
            Text("Application",fontWeight=FontWeight.Bold,style=MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                Column(Modifier.weight(1f)){Text("Rappel de check-in",fontWeight=FontWeight.Bold)
                    Text("1 h 30 avant ton heure habituelle de consommation",style=MaterialTheme.typography.bodySmall,color=Pine.copy(alpha=.65f))}
                Switch(checked=reminderOn,onCheckedChange={want ->
                    if(!want){reminderOn=false;CheckInReminder.setEnabled(context,false);message="Rappel de check-in désactivé"}
                    else if(android.os.Build.VERSION.SDK_INT>=33 && androidx.core.content.ContextCompat.checkSelfPermission(context,android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED){
                        notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    }else{reminderOn=true;CheckInReminder.setEnabled(context,true);scope.launch{CheckInReminder.refreshAndSchedule(context)};message="Rappel de check-in activé"}
                })
            }
            OutlinedButton(onClick=onOpenHealth,modifier=Modifier.fillMaxWidth()){Text("Données de santé (Health Connect)")}
            OutlinedButton(onClick=onOpenHistory,modifier=Modifier.fillMaxWidth()){Text("Historique complet")}
            OutlinedButton(onClick={exportLauncher.launch("repere-consommations.csv")},modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.export_csv))}
            HorizontalDivider(Modifier.padding(vertical=6.dp))
            Text(stringResource(R.string.about_support),fontWeight=FontWeight.Bold,style=MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.app_version,BuildConfig.VERSION_NAME),color=Pine.copy(alpha=.72f))
            OutlinedButton(onClick={val intent=if(android.os.Build.VERSION.SDK_INT>=33)Intent(android.provider.Settings.ACTION_APP_LOCALE_SETTINGS,Uri.parse("package:${context.packageName}"))else Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:${context.packageName}"));context.startActivity(intent)},modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.language_settings))}
            OutlinedButton(onClick={scope.launch{if(!onCheckUpdates())showUpToDateDialog=true}},modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.check_updates))}
            OutlinedButton(onClick={context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://github.com/fpoisson2/repere-app")))},modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.source_code))}
            if(server.isNotBlank())OutlinedButton(onClick={context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(server.trimEnd('/')+"/about")))},modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.project_website))}
            OutlinedButton(onClick={context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://buymeacoffee.com/fpoisson")))},modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.support_repere))}
            OutlinedButton(onClick={context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://github.com/fpoisson2/repere-app/issues")))},modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.report_issue))}
            Spacer(Modifier.height(20.dp))
        }
    }
    if(showUpToDateDialog)AlertDialog(onDismissRequest={showUpToDateDialog=false},confirmButton={TextButton(onClick={showUpToDateDialog=false}){Text("OK")}},title={Text(stringResource(R.string.check_updates))},text={Text(stringResource(R.string.up_to_date))})
}

internal object Api {
    suspend fun post(server:String,token:String,path:String,body:JSONObject)=request(server,token,path,body.toString())
    suspend fun postArray(server:String,token:String,path:String,body:JSONArray)=request(server,token,path,body.toString())
    suspend fun put(server:String,token:String,path:String,body:JSONObject)=request(server,token,path,body.toString(),"PUT")
    private suspend fun request(server:String,token:String,path:String,body:String,method:String="POST"):JSONObject=withContext(Dispatchers.IO){
        val connection=URL(server.trimEnd('/')+path).openConnection() as HttpURLConnection;connection.requestMethod=method;connection.connectTimeout=10000;connection.readTimeout=15000;connection.doOutput=true
        connection.setRequestProperty("Content-Type","application/json");if(token.isNotBlank())connection.setRequestProperty("Authorization","Bearer $token")
        connection.outputStream.use{it.write(body.toByteArray())};val text=(if(connection.responseCode in 200..299)connection.inputStream else connection.errorStream).bufferedReader().readText()
        if(connection.responseCode !in 200..299)error(runCatching{JSONObject(text).optString("detail")}.getOrDefault("Erreur ${connection.responseCode}"));JSONObject(text.ifBlank{"{}"})
    }
}
