package ca.repere.mobile

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import ca.repere.data.DrinkEntity
import ca.repere.data.PresetEntity
import ca.repere.data.SyncRepository
import ca.repere.data.SyncWorker
import ca.repere.core.canadianStandards
import ca.repere.core.CredentialStore
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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SyncWorker.schedule(this)
        setContent {
            MaterialTheme(
                colorScheme=lightColorScheme(primary=Pine,onPrimary=Color.White,primaryContainer=Mint,
                    background=Paper,surface=Color.White,onSurface=PineDark,secondary=Amber),
                shapes=Shapes(medium=RoundedCornerShape(20.dp),large=RoundedCornerShape(28.dp))
            ) { RepereApp(this) }
        }
    }
}

private enum class Destination(val label:String,val mark:String,val inBar:Boolean=true) {
    NOW("Maintenant","●"), STATS("Stats","▮"), INSIGHTS("Repères","◇"),
    SUCCESS("Succès","★"), GOALS("Objectifs","◎"), SETTINGS("Réglages","⚙"),
    HISTORY("Historique","≡",inBar=false), HEALTH("Santé","♥",inBar=false)
}

@Composable
private fun RepereApp(context:Context) {
    val credentials=remember{CredentialStore(context)}
    val repository=remember{SyncRepository(context)}
    val drinks by repository.observeDrinks().collectAsState(initial=emptyList())
    val presets by repository.observePresets().collectAsState(initial=emptyList())
    var destination by remember{mutableStateOf(Destination.NOW)}
    var server by remember{mutableStateOf(credentials.server(BuildConfig.DEFAULT_SERVER_URL))}
    var token by remember{mutableStateOf(credentials.token())}
    var syncEnabled by remember{mutableStateOf(credentials.syncEnabled())}
    var status by remember{mutableStateOf(if(syncEnabled)"Prêt hors ligne" else "Local uniquement")}
    var syncing by remember{mutableStateOf(false)}
    val scope=rememberCoroutineScope()

    fun synchronize()=scope.launch {
        if(token.isBlank()||!syncEnabled)return@launch
        syncing=true;status="Synchronisation…"
        runCatching{repository.synchronize()}.onSuccess{status="À jour"}
            .onFailure{status="Hors ligne · les saisies sont conservées"}
        syncing=false
    }
    LaunchedEffect(Unit){repository.ensureOfflineDefaults()}
    LaunchedEffect(token,syncEnabled){if(token.isNotBlank()&&syncEnabled)synchronize()}
    // Re-read credentials (e.g. after the OAuth browser redirect) and refresh whenever the app returns to the foreground.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME){
        server=credentials.server(BuildConfig.DEFAULT_SERVER_URL);token=credentials.token();syncEnabled=credentials.syncEnabled()
        if(token.isNotBlank()&&syncEnabled)synchronize()
    }

    Scaffold(containerColor=Paper,bottomBar={NavigationBar(containerColor=Color.White){Destination.entries.filter{it.inBar}.forEach{item ->
        NavigationBarItem(selected=destination==item,onClick={destination=item},icon={Text(item.mark,fontWeight=FontWeight.Bold)},label={Text(item.label)})
    }}}){padding ->
        Box(Modifier.padding(padding).fillMaxSize()){
            when(destination){
                Destination.NOW -> TodayScreen(drinks,presets,status,{synchronize()},onAdd={preset -> scope.launch{
                    repository.createFromPreset(preset,OffsetDateTime.now().toString());status=if(syncEnabled)"En attente d’envoi" else "Conservé localement";synchronize()
                }},onCustom={name,volume,abv,quantity -> scope.launch{
                    repository.createCustom(name,volume,abv,quantity,OffsetDateTime.now().toString());status=if(syncEnabled)"En attente d’envoi" else "Conservé localement";synchronize()
                }})
                Destination.STATS -> StatsScreen(context)
                Destination.INSIGHTS -> InsightsScreen(context)
                Destination.SUCCESS -> SuccessScreen(context)
                Destination.GOALS -> GoalsScreen(context)
                Destination.HISTORY -> HistoryScreen(drinks){clientId -> scope.launch{repository.markDeleted(clientId);status=if(syncEnabled)"Suppression en attente" else "Suppression locale";synchronize()}}
                Destination.HEALTH -> HealthScreen(context,server,token)
                Destination.SETTINGS -> SettingsScreen(context,server,{server=it},token,{token=it;syncEnabled=credentials.syncEnabled();destination=Destination.NOW},syncEnabled,{enabled->syncEnabled=enabled;status=if(enabled)"Synchronisation activée" else "Local uniquement"},status,onOpenHistory={destination=Destination.HISTORY},onOpenHealth={destination=Destination.HEALTH})
            }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TodayScreen(drinks:List<DrinkEntity>,presets:List<PresetEntity>,status:String,onSync:()->Unit,onAdd:(PresetEntity)->Unit,onCustom:(String,Double,Double,Int)->Unit) {
    val today=LocalDate.now().toString();val todayDrinks=drinks.filter{it.startedAt.take(10)==today}
    val standards=todayDrinks.sumOf{canadianStandards(it.volumeMl,it.abvPercent,it.quantity)}
    var custom by remember{mutableStateOf(false)}
    val refreshing=status.startsWith("Synchronisation")
    PullToRefreshBox(isRefreshing=refreshing,onRefresh=onSync,modifier=Modifier.fillMaxSize()){
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())){
        PageHeader("Repère quotidien","Ce soir")
        Card(Modifier.padding(horizontal=20.dp).fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=PineDark),shape=RoundedCornerShape(28.dp)){
            Column(Modifier.padding(24.dp)){
                Text("AUJOURD’HUI",color=Mint.copy(alpha=.75f),style=MaterialTheme.typography.labelSmall,fontWeight=FontWeight.Bold)
                Text(String.format(Locale.CANADA_FRENCH,"%.1f",standards),color=Color.White,style=MaterialTheme.typography.displayMedium,fontWeight=FontWeight.Black)
                Text("standard${if(standards>=2)"s" else ""} canadien${if(standards>=2)"s" else ""}",color=Mint)
                Spacer(Modifier.height(18.dp));HorizontalDivider(color=Mint.copy(alpha=.25f))
                Row(Modifier.padding(top=14.dp),verticalAlignment=Alignment.CenterVertically){Box(Modifier.size(9.dp).background(if(status.startsWith("À jour"))Mint else Amber,RoundedCornerShape(9.dp)))
                    Spacer(Modifier.width(8.dp));Text(status,color=Color.White,style=MaterialTheme.typography.bodySmall)}
            }
        }
        Text("Ajouter rapidement",Modifier.padding(start=20.dp,top=26.dp,bottom=10.dp),style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
        if(presets.isEmpty()) Text("Connecte-toi une première fois pour télécharger tes favoris.",Modifier.padding(horizontal=20.dp),color=Pine.copy(alpha=.65f))
        else LazyRow(contentPadding=PaddingValues(horizontal=20.dp),horizontalArrangement=Arrangement.spacedBy(10.dp)){items(presets){preset ->
            ElevatedCard(onClick={onAdd(preset)},modifier=Modifier.width(156.dp),shape=RoundedCornerShape(20.dp)){Column(Modifier.padding(16.dp)){
                Text("＋",color=Pine,style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Light);Spacer(Modifier.height(12.dp));Text(preset.name,fontWeight=FontWeight.Bold,maxLines=2)
                Text("${preset.volumeMl.toInt()} ml · ${preset.abvPercent}%",style=MaterialTheme.typography.bodySmall,color=Pine.copy(alpha=.65f))}}
        }}
        OutlinedButton(onClick={custom=true},modifier=Modifier.padding(horizontal=20.dp,vertical=12.dp).fillMaxWidth()){Text("Personnaliser une consommation")}
        Text("Entrées du jour",Modifier.padding(start=20.dp,top=26.dp,bottom=8.dp),style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
        if(todayDrinks.isEmpty()) Text("Aucune consommation saisie. La journée reste ouverte.",Modifier.padding(horizontal=20.dp),color=Pine.copy(alpha=.65f))
        todayDrinks.forEach{DrinkRow(it)};Spacer(Modifier.height(24.dp))
    }
    }
    if(custom)CustomDrinkDialog(onDismiss={custom=false}){name,volume,abv,quantity -> onCustom(name,volume,abv,quantity);custom=false}
}

@Composable
private fun CustomDrinkDialog(onDismiss:()->Unit,onSave:(String,Double,Double,Int)->Unit) {
    var name by remember{mutableStateOf("Consommation")};var volume by remember{mutableStateOf("341")};var abv by remember{mutableStateOf("5")};var quantity by remember{mutableStateOf("1")}
    AlertDialog(onDismissRequest=onDismiss,title={Text("Consommation personnalisée")},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
        OutlinedTextField(name,{name=it},label={Text("Nom")},singleLine=true)
        OutlinedTextField(volume,{volume=it.filter{c->c.isDigit()}},label={Text("Volume (ml)")},singleLine=true)
        OutlinedTextField(abv,{abv=it.filter{c->c.isDigit()||c=='.'||c==','}},label={Text("Alcool (%)")},singleLine=true)
        OutlinedTextField(quantity,{quantity=it.filter(Char::isDigit)},label={Text("Quantité")},singleLine=true)
    }},confirmButton={TextButton(onClick={onSave(name,volume.toDoubleOrNull()?:341.0,abv.replace(',','.').toDoubleOrNull()?:5.0,quantity.toIntOrNull()?.coerceAtLeast(1)?:1)}){Text("Ajouter")}},dismissButton={TextButton(onClick=onDismiss){Text("Annuler")}})
}

@Composable
private fun DrinkRow(drink:DrinkEntity,onDelete:(()->Unit)?=null) {
    Row(Modifier.padding(horizontal=20.dp,vertical=6.dp).fillMaxWidth().background(Color.White,RoundedCornerShape(18.dp)).padding(16.dp),verticalAlignment=Alignment.CenterVertically){
        Box(Modifier.size(42.dp).background(if(drink.dirty)Color(0xFFFFE8C2) else Mint,RoundedCornerShape(14.dp)),contentAlignment=Alignment.Center){Text(if(drink.dirty)"↥" else "✓",fontWeight=FontWeight.Bold,color=Pine)}
        Column(Modifier.padding(start=12.dp).weight(1f)){Text(drink.name,fontWeight=FontWeight.Bold);Text("${drink.volumeMl.toInt()} ml · ${drink.abvPercent}% · ×${drink.quantity}",style=MaterialTheme.typography.bodySmall,color=Pine.copy(alpha=.65f))}
        if(onDelete==null)Text(runCatching{OffsetDateTime.parse(drink.startedAt).format(DateTimeFormatter.ofPattern("HH:mm"))}.getOrDefault(""),style=MaterialTheme.typography.labelMedium)
        else TextButton(onClick=onDelete){Text("Retirer")}
    }
}

@Composable
private fun HistoryScreen(drinks:List<DrinkEntity>,onDelete:(String)->Unit) {
    Column(Modifier.fillMaxSize()){PageHeader("Copie locale","Historique")
        if(drinks.isEmpty())Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){Text("L’historique apparaîtra après la première synchronisation.",Modifier.padding(30.dp),color=Pine.copy(alpha=.65f))}
        else LazyColumn(contentPadding=PaddingValues(bottom=24.dp)){items(drinks,key={it.clientId}){DrinkRow(it){onDelete(it.clientId)}}}}
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
        Button(onClick={launcher.launch(HealthConnectBridge.granularPermissions.values.toSet())},enabled=bridge.available(),modifier=Modifier.padding(horizontal=20.dp,vertical=14.dp).fillMaxWidth()){Text("Choisir les autorisations")}
        if(bridge.backgroundAvailable())Row(Modifier.padding(horizontal=20.dp,vertical=5.dp).fillMaxWidth().background(Color.White,RoundedCornerShape(16.dp)).padding(16.dp),verticalAlignment=Alignment.CenterVertically){
            Column(Modifier.weight(1f)){Text("Synchronisation automatique",fontWeight=FontWeight.Bold);Text("Résumés récents, environ deux fois par jour",style=MaterialTheme.typography.bodySmall,color=Pine.copy(alpha=.65f))}
            Switch(checked=backgroundGranted,onCheckedChange={enabled -> if(enabled)backgroundLauncher.launch(setOf(HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND)) else {backgroundGranted=false;HealthConnectWorker.disable(context);message="Synchronisation automatique désactivée."}})
        }
        OutlinedButton(onClick={scope.launch{
            message="Lecture des 14 derniers jours…"
            runCatching{val all=JSONArray();repeat(14){index -> val day=bridge.aggregateDay(LocalDate.now().minusDays(index.toLong()));for(i in 0 until day.length())all.put(day.getJSONObject(i))}
                val stored=local.store(all);if(credentials.syncEnabled()&&token.isNotBlank()){val(pending,ids)=local.pending();if(pending.length()>0){Api.postArray(server,token,"/api/health-connect/aggregates",pending);local.markSynced(ids)}};stored}
                .onSuccess{message="$it résumé(s) conservé(s) localement${if(credentials.syncEnabled())" et synchronisé(s)" else ""}."}.onFailure{message=it.message?:"Import impossible"}
        }},enabled=bridge.available()&&granted.isNotEmpty(),modifier=Modifier.padding(horizontal=20.dp).fillMaxWidth()){Text("Importer les 14 derniers jours")}
        Text(message,Modifier.padding(20.dp),color=Pine.copy(alpha=.72f))
    }
}

@Composable
private fun SettingsScreen(context:Context,server:String,onServer:(String)->Unit,token:String,onToken:(String)->Unit,syncEnabled:Boolean,onSyncEnabled:(Boolean)->Unit,status:String,onOpenHistory:()->Unit,onOpenHealth:()->Unit) {
    val credentials=remember{CredentialStore(context)};var message by remember{mutableStateOf(status)};val scope=rememberCoroutineScope()
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
            Text("Application",fontWeight=FontWeight.Bold,style=MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick=onOpenHealth,modifier=Modifier.fillMaxWidth()){Text("Données de santé (Health Connect)")}
            OutlinedButton(onClick=onOpenHistory,modifier=Modifier.fillMaxWidth()){Text("Historique complet")}
            Spacer(Modifier.height(20.dp))
        }
    }
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
