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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.LocalBar
import androidx.compose.material.icons.filled.Percent
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
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
import ca.repere.data.WearStatePublisher
import ca.repere.data.HealthAggregateEntity
import ca.repere.data.CheckInEntity
import ca.repere.data.GoalEntity
import ca.repere.data.LocalSettings
import ca.repere.core.canadianStandards
import ca.repere.core.CANADIAN_STANDARD_GRAMS
import ca.repere.core.US_STANDARD_GRAMS
import ca.repere.core.UK_STANDARD_GRAMS
import ca.repere.core.mlToOunces
import ca.repere.core.ouncesToMl
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
import kotlinx.coroutines.Job
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

/** Sync state. It used to be a French string that `startsWith` was matched against. */
private enum class SyncStatus(val labelRes:Int) {
    OFFLINE_READY(R.string.status_offline_ready), LOCAL_ONLY(R.string.status_local_only),
    SYNCING(R.string.status_syncing), UP_TO_DATE(R.string.status_up_to_date),
    OFFLINE_KEPT(R.string.status_offline_kept), PENDING_SEND(R.string.status_pending_send),
    KEPT_LOCALLY(R.string.status_kept_locally), PENDING_EDIT(R.string.status_pending_edit),
    PENDING_DELETE(R.string.status_pending_delete), SYNC_ENABLED(R.string.status_sync_enabled),
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
    var status by remember{mutableStateOf(if(syncEnabled)SyncStatus.OFFLINE_READY else SyncStatus.LOCAL_ONLY)}
    var syncing by remember{mutableStateOf(false)}
    val updateManager=remember{AppUpdateManagerFactory.create(context)}
    var availableUpdate by remember{mutableStateOf<AppUpdateInfo?>(null)}
    var updateReadyToInstall by remember{mutableStateOf(false)}
    val scope=rememberCoroutineScope()
    val snackbarHostState=remember{SnackbarHostState()}
    var hiddenDrinkIds by remember{mutableStateOf(setOf<String>())}
    var pendingDeleteJob by remember{mutableStateOf<Job?>(null)}
    val visibleDrinks=remember(drinks,hiddenDrinkIds){drinks.filter{it.clientId !in hiddenDrinkIds}}

    fun synchronize()=scope.launch {
        if(token.isBlank()||!syncEnabled)return@launch
        syncing=true;status=SyncStatus.SYNCING
        runCatching{Net.flush(context);repository.synchronize()}.onSuccess{status=SyncStatus.UP_TO_DATE;WearStatePublisher.publish(context,drinks,localSettings?:LocalSettings())}
            .onFailure{status=SyncStatus.OFFLINE_KEPT}
        syncing=false
    }
    // Deletion is deferred behind an undo window: the row disappears from view immediately,
    // but nothing is written to Room (and thus nothing can be pushed to the server) until the
    // snackbar times out without the user tapping "Annuler".
    fun requestDelete(id:String){
        hiddenDrinkIds=hiddenDrinkIds+id
        pendingDeleteJob?.cancel()
        pendingDeleteJob=scope.launch {
            val result=snackbarHostState.showSnackbar(context.getString(R.string.drink_deleted),actionLabel=context.getString(R.string.action_cancel),duration=SnackbarDuration.Short)
            if(result==SnackbarResult.ActionPerformed){hiddenDrinkIds=hiddenDrinkIds-id}
            else{repository.markDeleted(id);status=SyncStatus.PENDING_DELETE;synchronize();hiddenDrinkIds=hiddenDrinkIds-id}
        }
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

    Scaffold(containerColor=Paper,snackbarHost={SnackbarHost(snackbarHostState)},bottomBar={NavigationBar(containerColor=Color.White){Destination.entries.filter{it.inBar}.forEach{item ->
        val label=stringResource(item.labelRes)
        NavigationBarItem(selected=destination==item,onClick={destination=item},
            icon={Icon(item.icon,contentDescription=label)},
            label={Text(label,maxLines=1,style=MaterialTheme.typography.labelSmall)})
    }}}){padding ->
        Box(Modifier.padding(padding).fillMaxSize()){
            when(destination){
                Destination.NOW -> MaintenantScreen(context,repository,visibleDrinks,presets,trackedDays,checkIns,localSettings?:LocalSettings(),status,openCheckIn,{synchronize()},
                    onCustom={name,volume,abv,quantity,startedAt,duration -> scope.launch{
                        repository.createCustom(name,volume,abv,quantity,startedAt,duration);status=if(syncEnabled)SyncStatus.PENDING_SEND else SyncStatus.KEPT_LOCALLY;synchronize()
                    }},
                    onEdit={id,name,volume,abv,quantity,startedAt,duration -> scope.launch{
                        repository.updateOffline(id,name,volume,abv,quantity,startedAt,duration);status=SyncStatus.PENDING_EDIT;synchronize()
                    }},
                    onDelete={id -> requestDelete(id)},
                    onSober={day,sober->scope.launch{repository.setSoberDay(day,sober)}})
                Destination.STATS -> StatsScreen(context,analysisDrinks,trackedDays,healthRows,localSettings?:LocalSettings())
                Destination.INSIGHTS -> InsightsScreen(analysisDrinks,checkIns,localSettings?:LocalSettings())
                Destination.SUCCESS -> SuccessScreen(analysisDrinks,trackedDays,checkIns,goals,localSettings?:LocalSettings())
                Destination.GOALS -> GoalsScreen(repository,goals,drinks,trackedDays,localSettings?:LocalSettings(),{synchronize()})
                Destination.HISTORY -> HistoryScreen(visibleDrinks){clientId -> requestDelete(clientId)}
                Destination.HEALTH -> HealthScreen(context,server,token)
                Destination.SETTINGS -> SettingsScreen(context,repository,drinks,localSettings?:LocalSettings(),server,{server=it},token,{token=it;syncEnabled=credentials.syncEnabled();destination=Destination.NOW},syncEnabled,{enabled->syncEnabled=enabled;status=if(enabled)SyncStatus.SYNC_ENABLED else SyncStatus.LOCAL_ONLY},status,onOpenHistory={destination=Destination.HISTORY},onOpenHealth={destination=Destination.HEALTH},onCheckUpdates={checkForUpdates(showFlow=true)})
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
        Column(Modifier.weight(1f)){Text(eyebrow.uppercase(Locale.getDefault()),style=MaterialTheme.typography.labelSmall,color=Pine.copy(alpha=.65f),fontWeight=FontWeight.Bold)
            Text(title,style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Black)}
        trailing?.invoke()
    }
}

private val PRESET_NEW = PresetEntity(0L, "", "autre", 333.0, 5.0)
private const val DAILY_GUIDELINE_STANDARDS = 3.0

/** Pulses only while a sync is in flight, so the dot doesn't animate forever in the background. */
@Composable
private fun StatusDot(status: SyncStatus) {
    val syncing = status == SyncStatus.SYNCING
    val color = if (status == SyncStatus.UP_TO_DATE) Mint else Amber
    if (syncing) {
        val pulse = rememberInfiniteTransition(label = "syncPulse")
        val alpha by pulse.animateFloat(.4f, 1f, infiniteRepeatable(tween(700, easing = LinearEasing), RepeatMode.Reverse), label = "syncPulseAlpha")
        Box(Modifier.size(9.dp).alpha(alpha).background(color, RoundedCornerShape(9.dp)))
    } else {
        Box(Modifier.size(9.dp).background(color, RoundedCornerShape(9.dp)))
    }
}

@Composable
private fun DaySummaryCard(isToday: Boolean, dayKey: String, standards: Double, status: SyncStatus) {
    val animatedStandards by animateFloatAsState(
        standards.toFloat(),
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "standardsValue",
    )
    val progress by animateFloatAsState((standards / DAILY_GUIDELINE_STANDARDS).toFloat().coerceIn(0f, 1f), animationSpec = tween(700), label = "standardsProgress")
    val overGuideline = standards > DAILY_GUIDELINE_STANDARDS
    Card(Modifier.padding(horizontal = 20.dp).fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = PineDark), shape = RoundedCornerShape(28.dp)) {
        Column(Modifier.padding(24.dp)) {
            Text(if (isToday) stringResource(R.string.today_caps) else dayKey, color = Mint.copy(alpha = .75f), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            Text(String.format(Locale.getDefault(), "%.1f", animatedStandards), color = Color.White, style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Black)
            Text(pluralStringResource(R.plurals.canadian_standards_label, if (standards >= 2) 2 else 1), color = Mint)
            Spacer(Modifier.height(14.dp))
            Box(Modifier.fillMaxWidth().height(8.dp).background(Color.White.copy(alpha = .16f), RoundedCornerShape(6.dp))) {
                Box(Modifier.fillMaxWidth(progress.coerceAtLeast(.02f)).height(8.dp).background(if (overGuideline) Amber else Mint, RoundedCornerShape(6.dp)))
            }
            Text(
                stringResource(if (overGuideline) R.string.guideline_over else R.string.guideline_within, fmtDouble(DAILY_GUIDELINE_STANDARDS)),
                Modifier.padding(top = 6.dp), style = MaterialTheme.typography.labelSmall, color = Mint.copy(alpha = .8f),
            )
            if (isToday) {
                Spacer(Modifier.height(14.dp)); HorizontalDivider(color = Mint.copy(alpha = .25f))
                Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(status)
                    Spacer(Modifier.width(8.dp)); Text(stringResource(status.labelRes), color = Color.White, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

private fun fmtDouble(value: Double): String = String.format(Locale.getDefault(), "%.0f", value)

/** One-shot fade + rise entrance, used to make the top-of-screen action zone feel alive without looping forever. */
@Composable
private fun EntranceFade(content: @Composable () -> Unit) {
    val state = remember { MutableTransitionState(false) }.apply { targetState = true }
    AnimatedVisibility(visibleState = state, enter = fadeIn(tween(420)) + slideInVertically(tween(420)) { it / 4 }) { content() }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun MaintenantScreen(
    context:Context,
    repository:SyncRepository, drinks:List<DrinkEntity>, presets:List<PresetEntity>, trackedDays:List<TrackedDayEntity>, checkIns:List<CheckInEntity>, settings:LocalSettings, status:SyncStatus, openCheckIn:Boolean, onSync:()->Unit,
    onCustom:(String,Double,Double,Int,String,Int)->Unit,
    onEdit:(String,String,Double,Double,Int,String,Int)->Unit, onDelete:(String)->Unit, onSober:(String,Boolean)->Unit,
) {
    var day by remember { mutableStateOf(LocalDate.now()) }
    val isToday = day == LocalDate.now()
    val dayKey = day.toString()
    var editing by remember { mutableStateOf<DrinkEntity?>(null) }
    var creatingFrom by remember { mutableStateOf<PresetEntity?>(null) }
    var creatingBlank by remember { mutableStateOf(false) }
    var editingPreset by remember { mutableStateOf<PresetEntity?>(null) }
    var checkIn by remember { mutableStateOf(false) }
    var dayMessage by remember { mutableStateOf<String?>(null) }
    var soberSuccess by remember { mutableStateOf(false) }
    var checkInSuccess by remember { mutableStateOf(false) }
    var checkInRefresh by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val refreshing = status == SyncStatus.SYNCING
    val dayHasCheckIn = remember(checkIns, dayKey, checkInRefresh) { checkIns.any { it.localDate == dayKey } }
    LaunchedEffect(dayKey) { dayMessage = null }
    LaunchedEffect(openCheckIn) { if (openCheckIn) checkIn = true }
    LaunchedEffect(checkInSuccess) { if (checkInSuccess) { kotlinx.coroutines.delay(2400); checkInSuccess = false } }
    PullToRefreshBox(isRefreshing = refreshing, onRefresh = onSync, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { day = day.minusDays(1) }) { Icon(Icons.Filled.ChevronLeft, stringResource(R.string.day_previous)) }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        if (isToday) stringResource(R.string.today) else day.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, Locale.getDefault()).replaceFirstChar { it.uppercase() },
                        fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleLarge,
                    )
                    Text(day.format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.getDefault())), style = MaterialTheme.typography.bodySmall, color = Pine.copy(alpha = .65f))
                }
                IconButton(onClick = { day = day.plusDays(1) }, enabled = !isToday) { Icon(Icons.Filled.ChevronRight, stringResource(R.string.day_next)) }
            }
            // Action zone stays at the very top: check-in, quick add and custom entry are the most-used gestures.
            EntranceFade {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { checkIn = true },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(16.dp),
                        colors = if (dayHasCheckIn) ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Pine) else ButtonDefaults.buttonColors(),
                    ) {
                        Icon(if (dayHasCheckIn) Icons.Filled.CheckCircle else Icons.AutoMirrored.Filled.Assignment, null)
                        Spacer(Modifier.width(8.dp)); Text(stringResource(if (dayHasCheckIn) R.string.checkin_done else R.string.checkin))
                    }
                    OutlinedButton(onClick = { creatingBlank = true }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(16.dp)) {
                        Icon(Icons.Filled.LocalBar, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.action_add))
                    }
                }
            }
            AnimatedVisibility(
                visible = checkInSuccess,
                enter = fadeIn(tween(220)) + slideInVertically(tween(220)) { -it / 2 },
                exit = androidx.compose.animation.fadeOut(tween(220)),
            ) {
                Row(
                    Modifier.padding(horizontal = 20.dp, vertical = 4.dp).fillMaxWidth()
                        .background(Mint, RoundedCornerShape(14.dp)).padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.CheckCircle, null, tint = Pine)
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.checkin_saved), color = Pine, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                }
            }
            EntranceFade {
                Column {
                    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.quick_add), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        TextButton(onClick = { editingPreset = PRESET_NEW }) { Text(stringResource(R.string.favourite_new)) }
                    }
                    Text(stringResource(R.string.favourite_long_press), Modifier.padding(start = 20.dp, bottom = 8.dp), style = MaterialTheme.typography.bodySmall, color = Pine.copy(alpha = .55f))
                    if (presets.isEmpty()) Text(stringResource(R.string.favourites_empty), Modifier.padding(horizontal = 20.dp), color = Pine.copy(alpha = .65f))
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
                                    Text(stringResource(R.string.preset_summary, preset.volumeMl.toInt(), preset.abvPercent.toString()), style = MaterialTheme.typography.bodySmall, color = Pine.copy(alpha = .65f))
                                }
                            }
                        }
                    }
                }
            }
            HorizontalDivider(Modifier.padding(horizontal = 20.dp, vertical = 18.dp), color = Pine.copy(alpha = .08f))
            // Everything below reflects the selected day, so it crossfades when day navigation changes it.
            Crossfade(targetState = day, animationSpec = tween(320), label = "dayContent") { d ->
                val dIsToday = d == LocalDate.now()
                val dKey = d.toString()
                val dDrinks = drinks.filter { runCatching { trackedDay(it.startedAt, settings.dayStartHour) == d }.getOrDefault(false) }.sortedBy { it.startedAt }
                val dStandards = dDrinks.sumOf { canadianStandards(it.volumeMl, it.abvPercent, it.quantity, settings.standardDrinkGrams) }
                val dStatus = if (dDrinks.isNotEmpty()) null else if (trackedDays.any { it.day == dKey && it.sober }) "sober" else null
                Column {
                    DaySummaryCard(dIsToday, dKey, dStandards, status)
                    if (dIsToday) BacCard(context, drinks) else if (dDrinks.isNotEmpty()) HistoricalBacCard(context, dDrinks)
                    LastCheckInCard(checkIns, d, checkInRefresh)
                    Text(stringResource(R.string.drinks_title), Modifier.padding(start = 20.dp, top = 12.dp, bottom = 8.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    if (dDrinks.isEmpty()) {
                        if (dStatus == "sober") {
                            Text(stringResource(R.string.day_marked_sober), Modifier.padding(horizontal = 20.dp), color = Pine)
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        runCatching { Net.send(context, "/api/days/sober/$dKey", JSONObject(), "DELETE") }
                                            .onSuccess { onSober(dKey,false); dayMessage = context.getString(R.string.sober_day_cancelled) }
                                            .onFailure { dayMessage = it.message ?: context.getString(R.string.sober_day_cancel_failed) }
                                    }
                                },
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp).fillMaxWidth(),
                            ) { Text(stringResource(R.string.sober_day_cancel)) }
                        } else {
                            Text(stringResource(R.string.day_no_drinks), Modifier.padding(horizontal = 20.dp), color = Pine.copy(alpha = .65f))
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        runCatching { Net.send(context, "/api/days/sober", JSONObject().put("date", dKey)) }
                                            .onSuccess { onSober(dKey,true); soberSuccess = true }
                                            .onFailure { dayMessage = it.message ?: context.getString(R.string.sober_day_save_failed) }
                                    }
                                },
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp).fillMaxWidth(),
                            ) { Text(stringResource(R.string.sober_day_mark)) }
                        }
                    }
                    dayMessage?.let { Text(it, Modifier.padding(horizontal = 20.dp, vertical = 4.dp), color = Pine.copy(alpha = .72f)) }
                    dDrinks.forEach { drink -> DrinkRow(drink, onEdit = { editing = drink }, onDelete = { onDelete(drink.clientId) }) }
                }
            }
            Spacer(Modifier.height(28.dp))
        }
    }
    val prefill = creatingFrom
    editingPreset?.let { p -> PresetEditorDialog(repository, p, onDismiss = { editingPreset = null }) { editingPreset = null; onSync() } }
    if (creatingBlank) DrinkEditorDialog(day, null, standardGrams = settings.standardDrinkGrams, volumeUnit = settings.volumeUnit, onDismiss = { creatingBlank = false }) { n, v, a, q, started, dur -> onCustom(n, v, a, q, started, dur); creatingBlank = false }
    if (prefill != null) DrinkEditorDialog(day, null, prefillName = prefill.name, prefillVolume = prefill.volumeMl.toInt(), prefillAbv = prefill.abvPercent, standardGrams = settings.standardDrinkGrams, volumeUnit = settings.volumeUnit, onDismiss = { creatingFrom = null }) { n, v, a, q, started, dur -> onCustom(n, v, a, q, started, dur); creatingFrom = null }
    editing?.let { d -> DrinkEditorDialog(day, d, standardGrams = settings.standardDrinkGrams, volumeUnit = settings.volumeUnit, onDismiss = { editing = null }) { n, v, a, q, started, dur -> onEdit(d.clientId, n, v, a, q, started, dur); editing = null } }
    if (checkIn) CheckInDialog(day, onDismiss = { checkIn = false }) { payload ->
        scope.launch {
            runCatching { repository.saveCheckIn(payload) }
                .onFailure { dayMessage = it.message ?: context.getString(R.string.checkin_not_sent) }
                .onSuccess { checkInSuccess = true; checkInRefresh++; onSync() }
        }
        checkIn = false
    }
    if (soberSuccess) AlertDialog(
        onDismissRequest = { soberSuccess = false },
        confirmButton = { TextButton(onClick = { soberSuccess = false }) { Text(stringResource(R.string.action_continue)) } },
        icon = { Text("🎉", style = MaterialTheme.typography.displaySmall) },
        title = { Text(stringResource(R.string.sober_day_saved_title)) },
        text = { Text(stringResource(R.string.sober_day_saved_body)) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DrinkEditorDialog(
    day:LocalDate,
    existing:DrinkEntity?,
    prefillName:String=stringResource(R.string.drink_default_name), prefillVolume:Int=333, prefillAbv:Double=5.0,
    standardGrams:Double=CANADIAN_STANDARD_GRAMS, volumeUnit:String="ml",
    onDismiss:()->Unit,
    onSave:(String,Double,Double,Int,String,Int)->Unit,
) {
    val isOz = volumeUnit == "oz"
    fun mlToDisplay(ml:Double) = if (isOz) mlToOunces(ml) else ml
    fun displayToMl(value:Double) = if (isOz) ouncesToMl(value) else value
    var name by remember{mutableStateOf(existing?.name ?: prefillName)}
    var volumeInput by remember{mutableStateOf(String.format(Locale.getDefault(), if (isOz) "%.1f" else "%.0f", mlToDisplay((existing?.volumeMl ?: prefillVolume.toDouble()))))}
    var abv by remember{mutableStateOf((existing?.abvPercent ?: prefillAbv).toString())}
    var quantity by remember{mutableStateOf((existing?.quantity ?: 1).toString())}
    var duration by remember{mutableStateOf((existing?.durationMinutes ?: 30).toString())}
    val parsed = remember(existing?.startedAt) { existing?.startedAt?.let { runCatching { parseDrinkTime(it) }.getOrNull() } }
    var time by remember { mutableStateOf(parsed?.toLocalTime() ?: java.time.LocalTime.now().withSecond(0).withNano(0)) }
    var date by remember { mutableStateOf(parsed?.toLocalDate() ?: day) }
    var pickTime by remember { mutableStateOf(false) }
    var pickDate by remember { mutableStateOf(false) }
    val numeric = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
    val decimal = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal)
    val liveStandards = remember(volumeInput, abv, quantity) {
        val v = displayToMl(volumeInput.replace(',', '.').toDoubleOrNull() ?: 0.0)
        val a = abv.replace(',', '.').toDoubleOrNull() ?: 0.0
        val q = quantity.toIntOrNull()?.coerceAtLeast(1) ?: 1
        canadianStandards(v, a, q, standardGrams)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        icon = { Icon(Icons.Filled.LocalBar, null, tint = Pine) },
        title = { Text(stringResource(if (existing == null) R.string.drink_new else R.string.drink_edit), fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                OutlinedTextField(
                    name, { name = it }, label = { Text(stringResource(R.string.field_name)) }, singleLine = true,
                    leadingIcon = { Icon(Icons.Filled.LocalBar, null) }, modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        volumeInput, { volumeInput = if (isOz) it.filter { c -> c.isDigit() || c == '.' || c == ',' } else it.filter(Char::isDigit) },
                        label = { Text(stringResource(R.string.field_volume)) }, suffix = { Text(stringResource(if (isOz) R.string.unit_oz else R.string.unit_ml)) },
                        singleLine = true, keyboardOptions = if (isOz) decimal else numeric, modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        abv, { abv = it.filter { c -> c.isDigit() || c == '.' || c == ',' } }, label = { Text(stringResource(R.string.field_alcohol)) }, suffix = { Text(stringResource(R.string.unit_percent)) },
                        singleLine = true, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
                        leadingIcon = { Icon(Icons.Filled.Percent, null) }, modifier = Modifier.weight(1f),
                    )
                }
                Row(Modifier.fillMaxWidth().background(Paper, RoundedCornerShape(16.dp)).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.field_quantity), Modifier.weight(1f), color = Pine.copy(alpha = .8f))
                    val q = quantity.toIntOrNull()?.coerceAtLeast(1) ?: 1
                    OutlinedIconButton(onClick = { quantity = (q - 1).coerceAtLeast(1).toString() }, enabled = q > 1) { Icon(Icons.Filled.Remove, stringResource(R.string.action_decrease)) }
                    Text(q.toString(), Modifier.padding(horizontal = 18.dp), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    OutlinedIconButton(onClick = { quantity = (q + 1).toString() }) { Icon(Icons.Filled.Add, stringResource(R.string.action_increase)) }
                }
                Row(Modifier.fillMaxWidth().background(Mint, RoundedCornerShape(16.dp)).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.EmojiEvents, null, tint = Pine, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        pluralStringResource(R.plurals.live_standards, if (liveStandards >= 2) 2 else 1, String.format(Locale.getDefault(), "%.2f", liveStandards)),
                        color = Pine, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    DateTimeChoice(stringResource(R.string.field_date),date.format(DateTimeFormatter.ofPattern("d MMM yyyy",Locale.getDefault())),Icons.Filled.CalendarToday,{pickDate=true},Modifier.weight(1f))
                    DateTimeChoice(stringResource(R.string.field_time),time.format(DateTimeFormatter.ofPattern("HH:mm")),Icons.Filled.Schedule,{pickTime=true},Modifier.weight(1f))
                }
                OutlinedTextField(
                    duration, { duration = it.filter(Char::isDigit) }, label = { Text(stringResource(R.string.field_duration)) }, suffix = { Text(stringResource(R.string.unit_minutes)) },
                    singleLine = true, keyboardOptions = numeric, modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                val started = date.atTime(time).atZone(java.time.ZoneId.systemDefault()).toOffsetDateTime().toString()
                val volumeMl = displayToMl(volumeInput.replace(',', '.').toDoubleOrNull() ?: mlToDisplay(333.0))
                onSave(name, volumeMl, abv.replace(',', '.').toDoubleOrNull() ?: 5.0, quantity.toIntOrNull()?.coerceAtLeast(1) ?: 1, started, duration.toIntOrNull()?.coerceAtLeast(0) ?: 30)
            }, shape = RoundedCornerShape(14.dp)) { Text(stringResource(if (existing == null) R.string.action_add else R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
    if (pickTime) {
        val state = rememberTimePickerState(initialHour = time.hour, initialMinute = time.minute, is24Hour = true)
        AlertDialog(onDismissRequest={pickTime=false},confirmButton={TextButton(onClick={time=java.time.LocalTime.of(state.hour,state.minute);pickTime=false}){Text(stringResource(R.string.action_ok))}},dismissButton={TextButton(onClick={pickTime=false}){Text(stringResource(R.string.action_cancel))}},text={TimePicker(state=state)})
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
        }){Text(stringResource(R.string.action_ok))}},dismissButton={TextButton(onClick={pickDate=false}){Text(stringResource(R.string.action_cancel))}}){ DatePicker(state=state) }
    }
}

@Composable private fun DateTimeChoice(label:String,value:String,icon:ImageVector,onClick:()->Unit,modifier:Modifier=Modifier){
    Card(modifier,shape=RoundedCornerShape(16.dp),colors=CardDefaults.cardColors(containerColor=Paper)){
        Column(Modifier.padding(horizontal=12.dp,vertical=10.dp)){
            Row(verticalAlignment=Alignment.CenterVertically){Icon(icon,null,tint=Pine.copy(alpha=.72f),modifier=Modifier.size(17.dp));Spacer(Modifier.width(6.dp));Text(label,style=MaterialTheme.typography.labelMedium,color=Pine.copy(alpha=.72f),maxLines=1)}
            Text(value,style=MaterialTheme.typography.bodyMedium,fontWeight=FontWeight.Bold,color=Pine,maxLines=1,softWrap=false,modifier=Modifier.padding(top=4.dp))
            TextButton(onClick=onClick,contentPadding=PaddingValues(0.dp),modifier=Modifier.heightIn(min=36.dp)){Text(stringResource(R.string.action_choose))}
        }
    }
}

@Composable
private fun PresetEditorDialog(repository:SyncRepository, preset:PresetEntity, onDismiss:()->Unit, onSaved:()->Unit) {
    val context = LocalContext.current
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
        title = { Text(stringResource(if (isNew) R.string.preset_new else R.string.preset_edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.field_name)) }, singleLine = true)
                OutlinedTextField(type, { type = it }, label = { Text(stringResource(R.string.field_type)) }, singleLine = true)
                OutlinedTextField(volume, { volume = it.filter(Char::isDigit) }, label = { Text(stringResource(R.string.field_volume_ml)) }, singleLine = true, keyboardOptions = numeric)
                OutlinedTextField(abv, { abv = it.filter { c -> c.isDigit() || c == '.' || c == ',' } }, label = { Text(stringResource(R.string.field_alcohol_percent)) }, singleLine = true, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal))
                message?.let { Text(it, color = Color(0xFFD9534F), style = MaterialTheme.typography.bodySmall) }
                if (!isNew) TextButton(onClick = {
                    scope.launch {
                        busy = true
                        runCatching { repository.deletePresetLocal(preset) }
                            .onSuccess { onSaved() }.onFailure { message = it.message ?: context.getString(R.string.delete_failed); busy = false }
                    }
                }) { Text(stringResource(R.string.preset_delete), color = Color(0xFFD9534F)) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                scope.launch {
                    busy = true
                    runCatching { repository.savePreset(preset,name.trim(),type.trim().ifBlank { "autre" },volume.toDoubleOrNull() ?: 0.0,abv.replace(',', '.').toDoubleOrNull() ?: 0.0) }
                        .onSuccess { onSaved() }.onFailure { message = it.message ?: context.getString(R.string.save_failed); busy = false }
                }
            }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
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
            if(weight.toDoubleOrNull()!=null&&ratio!=null){localProfile.saveBacProfile(weight.toDouble(),ratio!!,elimination.toDoubleOrNull()?:.015);localProfile.saveBodyMetrics(sex,height.toDoubleOrNull())}
        }
    }
    val sexLabel = mapOf("unspecified" to R.string.sex_unspecified, "female" to R.string.sex_female, "male" to R.string.sex_male)
    val numeric = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
    Text(stringResource(R.string.body_profile_title), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.body_profile_subtitle), style = MaterialTheme.typography.bodySmall, color = Pine.copy(alpha = .65f))
    Box {
        OutlinedTextField(sexLabel[sex]?.let { stringResource(it) } ?: sex, {}, readOnly = true, label = { Text(stringResource(R.string.field_sex)) }, modifier = Modifier.fillMaxWidth(), trailingIcon = { TextButton(onClick = { sexOpen = true }) { Text(stringResource(R.string.action_change)) } })
        DropdownMenu(expanded = sexOpen, onDismissRequest = { sexOpen = false }) {
            sexLabel.forEach { (v, l) -> DropdownMenuItem(text = { Text(stringResource(l)) }, onClick = { sex = v; sexOpen = false }) }
        }
    }
    OutlinedTextField(weight, { weight = it.filter(Char::isDigit) }, label = { Text(stringResource(R.string.field_weight_kg)) }, singleLine = true, keyboardOptions = numeric, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(height, { height = it.filter(Char::isDigit) }, label = { Text(stringResource(R.string.field_height_cm)) }, singleLine = true, keyboardOptions = numeric, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(elimination,{elimination=it.filter{char->char.isDigit()||char=='.'}},label={Text(stringResource(R.string.elimination_rate))},supportingText={Text(stringResource(R.string.elimination_rate_hint))},singleLine=true,keyboardOptions=androidx.compose.foundation.text.KeyboardOptions(keyboardType=androidx.compose.ui.text.input.KeyboardType.Decimal),modifier=Modifier.fillMaxWidth())
    ratio?.let { Text(stringResource(R.string.distribution_factor, String.format(Locale.getDefault(), "%.3f", it)), style = MaterialTheme.typography.bodySmall, color = Pine.copy(alpha = .7f)) }
    Button(onClick = {
        scope.launch {
            val localWeight=weight.toDoubleOrNull();val localRatio=localWeight?.let { distributionRatio(sex,height.toDoubleOrNull(),it,ratio?:.6) };val localElimination=elimination.toDoubleOrNull()
            if(localWeight==null||localRatio==null){message=context.getString(R.string.weight_required);return@launch};if(localElimination==null||localElimination !in .005..0.03){message=context.getString(R.string.invalid_elimination_rate);return@launch}
            ratio=localRatio;localProfile.saveBacProfile(localWeight,localRatio,localElimination);localProfile.saveBodyMetrics(sex,height.toDoubleOrNull());message=context.getString(R.string.profile_saved_device)
            val body = JSONObject().put("sex", sex)
            weight.toDoubleOrNull()?.let { body.put("weight_kg", it) }
            height.toDoubleOrNull()?.let { body.put("height_cm", it) }
            body.put("elimination_rate",localElimination)
            runCatching { Net.send(context, "/api/settings", body, "PATCH") }
                .onSuccess { message = context.getString(R.string.profile_saved_scheduled) }
                .onFailure { message = context.getString(R.string.profile_saved_pending) }
        }
    }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.save_profile)) }
    message?.let { Text(it, color = Pine.copy(alpha = .72f), style = MaterialTheme.typography.bodySmall) }
}

@Composable
private fun BacCard(context:Context,drinks:List<DrinkEntity>) {
    val credentials=remember{CredentialStore(context)};val weight=credentials.bacWeightKg();val ratio=credentials.bacDistributionRatio()
    val profile=if(weight!=null&&ratio!=null)BacProfile(weight,ratio,credentials.bacEliminationRate())else null
    if(profile==null){Card(Modifier.padding(horizontal=20.dp,vertical=8.dp).fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=Color.White)){Text(stringResource(R.string.bac_profile_missing_offline),Modifier.padding(20.dp),color=Pine.copy(alpha=.7f))};return}
    var now by remember{mutableStateOf(OffsetDateTime.now())}
    LaunchedEffect(Unit){while(true){kotlinx.coroutines.delay(60_000);now=OffsetDateTime.now()}}
    val allDrinks=remember(drinks){drinks.mapNotNull{d->runCatching{BacDrink(parseDrinkTime(d.startedAt),d.durationMinutes,d.volumeMl*d.quantity*d.abvPercent/100*.789,d.active)}.getOrNull()}}
    val localDrinks=remember(allDrinks,now){recentForBac(allDrinks,now)}
    val current=bacAt(localDrinks,profile,now)*10;val peak=(peakBac(localDrinks,profile)?:0.0)*10
    val future=bacAt(localDrinks,profile,now.plusMinutes(10))*10;val trend=stringResource(if(future>current+.01)R.string.bac_trend_up else if(future<current-.01)R.string.bac_trend_down else R.string.bac_trend_stable)
    val zero=(1..24*12).firstOrNull{bacAt(localDrinks,profile,now.plusMinutes(it*5L))<=.00001}?.let{now.plusMinutes(it*5L)}
    val animatedCurrent by animateFloatAsState(current.toFloat(), animationSpec = tween(650), label = "bacCurrent")
    Card(Modifier.padding(horizontal = 20.dp, vertical = 8.dp).fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.padding(20.dp)) {
            Text(stringResource(R.string.bac_estimated_caps), style = MaterialTheme.typography.labelSmall, color = Pine.copy(alpha = .6f), fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(String.format(Locale.getDefault(), "%.2f", animatedCurrent), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Black, color = Pine)
                Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.unit_gl), color = Pine.copy(alpha = .6f), modifier = Modifier.padding(bottom = 6.dp))
            }
            Text(
                when {
                    current<=.001 -> stringResource(R.string.bac_at_zero, String.format(Locale.getDefault(), "%.2f", peak))
                    else -> {
                        val base = stringResource(R.string.bac_trend, trend, String.format(Locale.getDefault(), "%.2f", peak))
                        zero?.let { stringResource(R.string.bac_back_to_zero, base, it.format(DateTimeFormatter.ofPattern("HH:mm"))) } ?: base
                    }
                },
                style = MaterialTheme.typography.bodySmall, color = Pine.copy(alpha = .72f),
            )
            if (current > .001 || localDrinks.isNotEmpty()) BacForecastChart(localDrinks, profile, now, zero)
            Text(
                stringResource(R.string.bac_disclaimer_live),
                Modifier.padding(top = 12.dp), style = MaterialTheme.typography.labelSmall, color = Amber,
            )
        }
    }
}

/** Draws where the estimated BAC is headed: a recent-past trace plus the forward projection toward zero, with a marker at "now". */
@Composable
private fun BacForecastChart(localDrinks: List<BacDrink>, profile: BacProfile, now: OffsetDateTime, zero: OffsetDateTime?) {
    val pastMinutes = 90L
    val stepMinutes = 5L
    val futureMinutes = (zero?.let { java.time.Duration.between(now, it).toMinutes() + 45 } ?: 180L).coerceIn(60L, 8 * 60L)
    val points = remember(localDrinks, profile, now, futureMinutes) {
        val list = mutableListOf<Float>()
        var t = -pastMinutes
        while (t <= futureMinutes) { list.add((bacAt(localDrinks, profile, now.plusMinutes(t)) * 10).toFloat()); t += stepMinutes }
        list
    }
    if (points.size < 2) return
    val nowIndex = (pastMinutes / stepMinutes).toInt().coerceIn(0, points.lastIndex)
    val max = (points.maxOrNull() ?: 0f).coerceAtLeast(.05f)
    Column(Modifier.padding(top = 14.dp)) {
        Canvas(Modifier.fillMaxWidth().height(110.dp)) {
            val stepX = size.width / (points.size - 1)
            val linePath = Path(); val fillPath = Path()
            points.forEachIndexed { i, v ->
                val x = i * stepX; val y = size.height - (v / max * size.height)
                if (i == 0) { linePath.moveTo(x, y); fillPath.moveTo(x, size.height); fillPath.lineTo(x, y) }
                else { linePath.lineTo(x, y); fillPath.lineTo(x, y) }
            }
            fillPath.lineTo(size.width, size.height); fillPath.close()
            drawPath(fillPath, Brush.verticalGradient(listOf(Pine.copy(alpha = .26f), Pine.copy(alpha = .02f))))
            drawPath(linePath, Pine, style = Stroke(width = 4f, cap = StrokeCap.Round))
            val nowX = nowIndex * stepX
            drawLine(
                Amber, Offset(nowX, 0f), Offset(nowX, size.height), strokeWidth = 2.5f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)),
            )
            drawCircle(Amber, 5f, Offset(nowX, size.height - (points[nowIndex] / max * size.height)))
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Text(now.minusMinutes(pastMinutes).format(DateTimeFormatter.ofPattern("HH:mm")), style = MaterialTheme.typography.labelSmall, color = Pine.copy(alpha = .5f))
            Spacer(Modifier.weight(1f))
            Text(stringResource(R.string.bac_now), style = MaterialTheme.typography.labelSmall, color = Amber, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text(now.plusMinutes(futureMinutes).format(DateTimeFormatter.ofPattern("HH:mm")), style = MaterialTheme.typography.labelSmall, color = Pine.copy(alpha = .5f))
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
            Text(stringResource(R.string.bac_peak_title),fontWeight=FontWeight.Bold,style=MaterialTheme.typography.titleMedium)
            if(peak!=null)Text(stringResource(R.string.bac_peak_value,String.format(Locale.getDefault(),"%.2f",peak*10)),style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Black,color=Pine)
            else Text(stringResource(R.string.bac_profile_missing),color=Pine.copy(alpha=.65f))
            Text(stringResource(R.string.bac_disclaimer),style=MaterialTheme.typography.bodySmall,color=Pine.copy(alpha=.62f))
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
            stringResource(R.string.checkin_none_today),
            Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodySmall, color = Pine.copy(alpha = .55f),
        )
        return
    }
    val standards = if (c.isNull("planned_grams")) null else c.optDouble("planned_grams") / 13.45
    Card(Modifier.padding(horizontal = 20.dp, vertical = 8.dp).fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Mint), shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.padding(20.dp)) {
            Text(stringResource(if (isToday) R.string.checkin_today_caps else R.string.checkin_day_caps), style = MaterialTheme.typography.labelSmall, color = Pine.copy(alpha = .65f), fontWeight = FontWeight.Bold)
            Text(
                runCatching { OffsetDateTime.parse(c.optString("observed_at_utc")).atZoneSameInstant(java.time.ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMM · HH:mm", Locale.getDefault())) }.getOrDefault(c.optString("local_date")),
                fontWeight = FontWeight.Bold, color = Pine,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                if (c.isNull("stress")) stringResource(R.string.checkin_scores, c.optInt("craving"), c.optInt("confidence"))
                else stringResource(R.string.checkin_scores_with_stress, c.optInt("craving"), c.optInt("confidence"), c.optInt("stress")),
                style = MaterialTheme.typography.bodySmall, color = Pine.copy(alpha = .8f),
            )
            if (standards != null) Text(stringResource(R.string.checkin_daily_target, String.format(Locale.getDefault(), "%.1f", standards)), style = MaterialTheme.typography.bodySmall, color = Pine.copy(alpha = .8f))
            if (c.optBoolean("post_onset")) Text(stringResource(R.string.checkin_post_onset), style = MaterialTheme.typography.labelSmall, color = Pine.copy(alpha = .6f))
        }
    }
}

@Composable
private fun DrinkRow(drink:DrinkEntity,onEdit:(()->Unit)?=null,onDelete:(()->Unit)?=null) {
    Row(Modifier.padding(horizontal=20.dp,vertical=6.dp).fillMaxWidth().background(Color.White,RoundedCornerShape(18.dp)).padding(start=16.dp,top=8.dp,bottom=8.dp,end=4.dp),verticalAlignment=Alignment.CenterVertically){
        Box(Modifier.size(42.dp).background(if(drink.dirty)Color(0xFFFFE8C2) else Mint,RoundedCornerShape(14.dp)),contentAlignment=Alignment.Center){Text(if(drink.dirty)"↥" else "✓",fontWeight=FontWeight.Bold,color=Pine)}
        Column(Modifier.padding(start=12.dp).weight(1f)){Text(drink.name,fontWeight=FontWeight.Bold)
            Text(stringResource(R.string.drink_row_summary,runCatching{parseDrinkTime(drink.startedAt).format(DateTimeFormatter.ofPattern("HH:mm"))}.getOrDefault(""),drink.volumeMl.toInt(),drink.abvPercent.toString(),drink.quantity),style=MaterialTheme.typography.bodySmall,color=Pine.copy(alpha=.65f))}
        if(onEdit!=null)IconButton(onClick=onEdit){Icon(Icons.Filled.Edit,stringResource(R.string.action_edit),tint=Pine)}
        if(onDelete!=null)IconButton(onClick=onDelete){Icon(Icons.Filled.Delete,stringResource(R.string.action_delete),tint=Pine.copy(alpha=.7f))}
    }
}

@Composable
private fun HistoryScreen(drinks:List<DrinkEntity>,onDelete:(String)->Unit) {
    Column(Modifier.fillMaxSize()){PageHeader(stringResource(R.string.history_eyebrow),stringResource(R.string.nav_history))
        if(drinks.isEmpty())Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){Text(stringResource(R.string.history_empty),Modifier.padding(30.dp),color=Pine.copy(alpha=.65f))}
        else LazyColumn(contentPadding=PaddingValues(bottom=24.dp)){items(drinks,key={it.clientId}){DrinkRow(it,onDelete={onDelete(it.clientId)})}}}
}

private val HEALTH_LONG_LABELS = mapOf(
    "sleep" to R.string.health_long_sleep, "steps" to R.string.health_long_steps, "exercise" to R.string.health_long_exercise,
    "heart_rate" to R.string.health_long_heart_rate, "resting_heart_rate" to R.string.health_long_resting_heart_rate,
    "hrv_rmssd" to R.string.health_long_hrv,
)

@Composable
private fun HealthScreen(context:Context,server:String,token:String) {
    val bridge=remember{HealthConnectBridge(context)};val scope=rememberCoroutineScope()
    val local=remember{HealthLocalRepository(context)};val localRows by local.observe().collectAsState(initial=emptyList())
    val credentials=remember{CredentialStore(context)}
    val initialMessage=stringResource(if(bridge.available())R.string.health_choose_precisely else R.string.health_unavailable)
    var message by remember{mutableStateOf(initialMessage)}
    var granted by remember{mutableStateOf<Set<String>>(emptySet())}
    var backgroundGranted by remember{mutableStateOf(false)}
    val launcher=rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()){result ->
        granted=result;message=context.resources.getQuantityString(R.plurals.health_permissions_granted,result.size,result.size)
        if(token.isNotBlank())scope.launch{HealthConnectBridge.granularPermissions.forEach{(type,permission) ->
            runCatching{Api.put(server,token,"/api/health-connect/permissions",JSONObject().put("permission_type",type)
                .put("status",if(permission in result)"granted" else "denied").put("history_allowed",false).put("background_allowed",false))}
        }}
    }
    val backgroundLauncher=rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()){result ->
        backgroundGranted=HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND in result
        if(backgroundGranted)HealthConnectWorker.enable(context) else HealthConnectWorker.disable(context)
        message=context.getString(if(backgroundGranted)R.string.health_background_enabled else R.string.health_background_denied)
        if(token.isNotBlank())scope.launch{runCatching{Api.put(server,token,"/api/health-connect/permissions",JSONObject()
            .put("permission_type","background").put("status",if(backgroundGranted)"granted" else "denied")
            .put("background_allowed",backgroundGranted).put("history_allowed",false))}}
    }
    LaunchedEffect(Unit){granted=bridge.granted();backgroundGranted=HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND in granted;if(backgroundGranted)HealthConnectWorker.enable(context)}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())){PageHeader(stringResource(R.string.health_eyebrow),stringResource(R.string.nav_health))
        Card(Modifier.padding(horizontal=20.dp).fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=Mint)){Column(Modifier.padding(20.dp)){
            Text(stringResource(R.string.health_control_title),fontWeight=FontWeight.Bold,style=MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.health_control_body),Modifier.padding(top=8.dp),color=Pine.copy(alpha=.78f))
            Text(pluralStringResource(R.plurals.health_local_summaries,localRows.size,localRows.size),Modifier.padding(top=12.dp),fontWeight=FontWeight.Bold,color=Pine)} }
        Text(stringResource(R.string.health_available_data),Modifier.padding(20.dp),fontWeight=FontWeight.Bold,style=MaterialTheme.typography.titleMedium)
        HealthConnectBridge.granularPermissions.forEach{(type,permission) ->
            val label=HEALTH_LONG_LABELS[type]?.let{stringResource(it)}?:type
            Row(Modifier.padding(horizontal=20.dp,vertical=5.dp).fillMaxWidth().background(Color.White,RoundedCornerShape(16.dp)).padding(16.dp),verticalAlignment=Alignment.CenterVertically){Text(label,Modifier.weight(1f));Text(stringResource(if(permission in granted)R.string.health_allowed else R.string.health_not_shared),color=if(permission in granted)Pine else Pine.copy(alpha=.5f),fontWeight=FontWeight.Bold,style=MaterialTheme.typography.labelMedium)}
        }
        Button(onClick={launcher.launch(HealthConnectBridge.granularPermissions.values.toSet()+HealthConnectBridge.HISTORY_PERMISSION)},enabled=bridge.available(),modifier=Modifier.padding(horizontal=20.dp,vertical=14.dp).fillMaxWidth()){Text(stringResource(R.string.health_choose_permissions))}
        if(bridge.backgroundAvailable())Row(Modifier.padding(horizontal=20.dp,vertical=5.dp).fillMaxWidth().background(Color.White,RoundedCornerShape(16.dp)).padding(16.dp),verticalAlignment=Alignment.CenterVertically){
            Column(Modifier.weight(1f)){Text(stringResource(R.string.health_background_title),fontWeight=FontWeight.Bold);Text(stringResource(R.string.health_background_subtitle),style=MaterialTheme.typography.bodySmall,color=Pine.copy(alpha=.65f))}
            Switch(checked=backgroundGranted,onCheckedChange={enabled -> if(enabled)backgroundLauncher.launch(setOf(HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND)) else {backgroundGranted=false;HealthConnectWorker.disable(context);message=context.getString(R.string.health_background_disabled)}})
        }
        var range by remember{mutableIntStateOf(14)}
        var importing by remember{mutableStateOf(false)}
        Text(stringResource(R.string.health_import_range),Modifier.padding(start=20.dp,top=8.dp),fontWeight=FontWeight.Bold,style=MaterialTheme.typography.labelLarge)
        LazyRow(contentPadding=PaddingValues(horizontal=20.dp,vertical=6.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)){
            items(listOf(14,30,90,180,365)){d -> FilterChip(selected=range==d,onClick={range=d},label={Text(if(d>=365)stringResource(R.string.stats_preset_year) else stringResource(R.string.stats_preset_days,d))})}
        }
        Text(stringResource(R.string.health_history_note),Modifier.padding(horizontal=20.dp),style=MaterialTheme.typography.bodySmall,color=Pine.copy(alpha=.6f))
        OutlinedButton(onClick={scope.launch{
            importing=true;message=context.getString(R.string.health_reading_days,range)
            runCatching{val all=JSONArray();repeat(range){index -> val day=bridge.aggregateDay(LocalDate.now().minusDays(index.toLong()));for(i in 0 until day.length())all.put(day.getJSONObject(i))}
                val stored=local.store(all);if(credentials.syncEnabled()&&token.isNotBlank()){val(pending,ids)=local.pending();if(pending.length()>0){Api.postArray(server,token,"/api/health-connect/aggregates",pending);local.markSynced(ids)}};stored}
                .onSuccess{message=context.resources.getQuantityString(if(credentials.syncEnabled())R.plurals.health_import_stored_synced else R.plurals.health_import_stored,it,it)}.onFailure{message=it.message?:context.getString(R.string.health_import_failed)}
            importing=false
        }},enabled=bridge.available()&&granted.isNotEmpty()&&!importing,modifier=Modifier.padding(horizontal=20.dp,vertical=8.dp).fillMaxWidth()){Text(stringResource(if(importing)R.string.health_importing else R.string.action_import))}
        Text(message,Modifier.padding(20.dp),color=Pine.copy(alpha=.72f))
    }
}

@Composable
private fun SettingsScreen(context:Context,repository:SyncRepository,drinks:List<DrinkEntity>,localSettings:LocalSettings,server:String,onServer:(String)->Unit,token:String,onToken:(String)->Unit,syncEnabled:Boolean,onSyncEnabled:(Boolean)->Unit,status:SyncStatus,onOpenHistory:()->Unit,onOpenHealth:()->Unit,onCheckUpdates:suspend()->Boolean) {
    val credentials=remember{CredentialStore(context)};val statusLabel=stringResource(status.labelRes);var message by remember{mutableStateOf(statusLabel)};val scope=rememberCoroutineScope()
    var showUpToDateDialog by remember{mutableStateOf(false)}
    var dayStart by remember{mutableIntStateOf(localSettings.dayStartHour)};var sessionGap by remember{mutableStateOf(localSettings.sessionGapHours)}
    var trackingStart by remember{mutableStateOf(localSettings.trackingStartDate.orEmpty())}
    var standardGramsText by remember{mutableStateOf(String.format(Locale.getDefault(),"%.2f",localSettings.standardDrinkGrams))};var volumeUnit by remember{mutableStateOf(localSettings.volumeUnit)}
    LaunchedEffect(localSettings.dayStartHour,localSettings.sessionGapHours,localSettings.trackingStartDate){dayStart=localSettings.dayStartHour;sessionGap=localSettings.sessionGapHours;trackingStart=localSettings.trackingStartDate.orEmpty()}
    LaunchedEffect(localSettings.standardDrinkGrams,localSettings.volumeUnit){standardGramsText=String.format(Locale.getDefault(),"%.2f",localSettings.standardDrinkGrams);volumeUnit=localSettings.volumeUnit}
    val exportLauncher=rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/csv")){uri -> uri?.let{
        runCatching{val quote:(String)->String={value->"\"${value.replace("\"","\"\"")}\""};val csv=buildString{appendLine("name,volume_ml,abv_percent,quantity,started_at,duration_minutes");drinks.forEach{drink->appendLine(listOf(drink.name,drink.volumeMl.toString(),drink.abvPercent.toString(),drink.quantity.toString(),drink.startedAt,drink.durationMinutes.toString()).joinToString(","){quote(it)})}};context.contentResolver.openOutputStream(it)?.bufferedWriter()?.use{writer->writer.write(csv)}}
            .onSuccess{message=context.getString(R.string.export_complete)}.onFailure{message=context.getString(R.string.export_failed)}}
    }
    var reminderOn by remember{mutableStateOf(CheckInReminder.isEnabled(context))}
    val notifPermission=rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()){granted ->
        reminderOn=granted;CheckInReminder.setEnabled(context,granted)
        if(granted)scope.launch{CheckInReminder.refreshAndSchedule(context)}
        message=context.getString(if(granted)R.string.settings_reminder_on else R.string.settings_notification_denied)
    }
    // Distinct path from "/repere/config" (the consumption/BAC state pushed by WearStatePublisher):
    // sharing one DataItem meant this credentials-only write clobbered the watch's active-drink
    // state with defaults (false/0) every time "Synchroniser la montre" was pressed.
    fun syncToWatch(currentToken:String)=scope.launch{val request=PutDataMapRequest.create("/repere/credentials").apply{dataMap.putString("server",server.trimEnd('/'));dataMap.putString("token",currentToken);dataMap.putLong("updated",System.currentTimeMillis())}.asPutDataRequest().setUrgent();runCatching{Wearable.getDataClient(context).putDataItem(request).await()}}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())){PageHeader(stringResource(R.string.settings_eyebrow),stringResource(R.string.nav_settings))
        Column(Modifier.padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
            OutlinedTextField(server,onServer,label={Text(stringResource(R.string.settings_server_address))},modifier=Modifier.fillMaxWidth(),singleLine=true)
            Text(stringResource(R.string.settings_local_first),color=Pine.copy(alpha=.72f))
            if(token.isBlank()){
                Button(onClick={OAuthClient.start(context,server)},enabled=server.isNotBlank(),modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.settings_sign_in))}
                Text(stringResource(R.string.settings_oauth_note),color=Pine.copy(alpha=.72f),style=MaterialTheme.typography.bodySmall)
            }else{
                Card(colors=CardDefaults.cardColors(containerColor=Mint),modifier=Modifier.fillMaxWidth()){Column(Modifier.padding(18.dp)){Text(stringResource(R.string.settings_device_paired),fontWeight=FontWeight.Bold);Text(stringResource(R.string.settings_offline_active),color=Pine.copy(alpha=.72f))}}
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(stringResource(R.string.settings_sync_with_server),fontWeight=FontWeight.Bold);Text(stringResource(if(syncEnabled)R.string.settings_changes_sent else R.string.settings_data_stays),style=MaterialTheme.typography.bodySmall,color=Pine.copy(alpha=.65f))}
                    Switch(checked=syncEnabled,onCheckedChange={credentials.setSyncEnabled(it);onSyncEnabled(it);if(it)SyncWorker.schedule(context)})}
                OutlinedButton(onClick={syncToWatch(token);message=context.getString(R.string.settings_watch_configured)},modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.settings_sync_watch))}
                TextButton(onClick={scope.launch{OAuthClient.signOut(context);onToken("");message=context.getString(R.string.settings_signed_out)}},modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.settings_sign_out))}
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
            Text(stringResource(R.string.settings_units_title),fontWeight=FontWeight.Bold,style=MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.settings_units_note),style=MaterialTheme.typography.bodySmall,color=Pine.copy(alpha=.65f))
            LazyRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                items(listOf(R.string.country_canada to CANADIAN_STANDARD_GRAMS,R.string.country_usa to US_STANDARD_GRAMS,R.string.country_uk to UK_STANDARD_GRAMS,R.string.country_australia to 10.0)){(labelRes,grams)->
                    val current=standardGramsText.replace(',','.').toDoubleOrNull()
                    FilterChip(selected=current!=null&&kotlin.math.abs(current-grams)<0.01,onClick={standardGramsText=String.format(Locale.getDefault(),"%.2f",grams)},label={Text(stringResource(R.string.standard_chip,stringResource(labelRes),String.format(Locale.getDefault(),"%.2f",grams)),maxLines=1,softWrap=false)})
                }
            }
            OutlinedTextField(
                standardGramsText, {standardGramsText=it.filter{c->c.isDigit()||c=='.'||c==','}},
                label={Text(stringResource(R.string.settings_grams_per_standard))},singleLine=true,
                supportingText={Text(stringResource(R.string.settings_grams_range))},
                keyboardOptions=androidx.compose.foundation.text.KeyboardOptions(keyboardType=androidx.compose.ui.text.input.KeyboardType.Decimal),
                modifier=Modifier.fillMaxWidth(),
            )
            Text(stringResource(R.string.settings_volume_unit),fontWeight=FontWeight.Bold)
            LazyRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                item{FilterChip(selected=volumeUnit=="ml",onClick={volumeUnit="ml"},label={Text(stringResource(R.string.settings_millilitres),maxLines=1,softWrap=false)})}
                item{FilterChip(selected=volumeUnit=="oz",onClick={volumeUnit="oz"},label={Text(stringResource(R.string.settings_fluid_ounces),maxLines=1,softWrap=false)})}
            }
            Button(onClick={scope.launch{
                val grams=standardGramsText.replace(',','.').toDoubleOrNull()?.coerceIn(4.0,30.0)?:localSettings.standardDrinkGrams
                standardGramsText=String.format(Locale.getDefault(),"%.2f",grams)
                repository.saveMeasurementPreferences(grams,volumeUnit);message=context.getString(R.string.settings_units_saved);if(syncEnabled)SyncWorker.schedule(context)
            }},modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.settings_save_units))}
            HorizontalDivider(Modifier.padding(vertical=6.dp))
            Text(stringResource(R.string.settings_app_section),fontWeight=FontWeight.Bold,style=MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                Column(Modifier.weight(1f)){Text(stringResource(R.string.settings_reminder),fontWeight=FontWeight.Bold)
                    Text(stringResource(R.string.settings_reminder_subtitle),style=MaterialTheme.typography.bodySmall,color=Pine.copy(alpha=.65f))}
                Switch(checked=reminderOn,onCheckedChange={want ->
                    if(!want){reminderOn=false;CheckInReminder.setEnabled(context,false);message=context.getString(R.string.settings_reminder_off)}
                    else if(android.os.Build.VERSION.SDK_INT>=33 && androidx.core.content.ContextCompat.checkSelfPermission(context,android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED){
                        notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    }else{reminderOn=true;CheckInReminder.setEnabled(context,true);scope.launch{CheckInReminder.refreshAndSchedule(context)};message=context.getString(R.string.settings_reminder_on)}
                })
            }
            OutlinedButton(onClick=onOpenHealth,modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.settings_health_data))}
            OutlinedButton(onClick=onOpenHistory,modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.settings_full_history))}
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
    if(showUpToDateDialog)AlertDialog(onDismissRequest={showUpToDateDialog=false},confirmButton={TextButton(onClick={showUpToDateDialog=false}){Text(stringResource(R.string.action_ok))}},title={Text(stringResource(R.string.check_updates))},text={Text(stringResource(R.string.up_to_date))})
}

internal object Api {
    suspend fun post(server:String,token:String,path:String,body:JSONObject)=request(server,token,path,body.toString())
    suspend fun postArray(server:String,token:String,path:String,body:JSONArray)=request(server,token,path,body.toString())
    suspend fun put(server:String,token:String,path:String,body:JSONObject)=request(server,token,path,body.toString(),"PUT")
    private suspend fun request(server:String,token:String,path:String,body:String,method:String="POST"):JSONObject=withContext(Dispatchers.IO){
        val connection=URL(server.trimEnd('/')+path).openConnection() as HttpURLConnection;connection.requestMethod=method;connection.connectTimeout=10000;connection.readTimeout=15000;connection.doOutput=true
        connection.setRequestProperty("Content-Type","application/json");if(token.isNotBlank())connection.setRequestProperty("Authorization","Bearer $token")
        connection.outputStream.use{it.write(body.toByteArray())};val text=(if(connection.responseCode in 200..299)connection.inputStream else connection.errorStream).bufferedReader().readText()
        if(connection.responseCode !in 200..299)error(runCatching{JSONObject(text).optString("detail")}.getOrDefault("HTTP ${connection.responseCode}"));JSONObject(text.ifBlank{"{}"})
    }
}
