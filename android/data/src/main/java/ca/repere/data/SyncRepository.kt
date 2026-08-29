package ca.repere.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import ca.repere.core.CredentialStore
import ca.repere.core.normalizeDrinkTime

/**
 * Coordinates Room-first synchronization. The HTTP transport is intentionally injected next;
 * keeping this boundary explicit prevents UI code from becoming the network source of truth.
 */
class SyncRepository(context: Context) {
    private val syncDataVersion=1
    private val appContext = context.applicationContext
    private val dao = RepereDatabase.get(context).dao()

    fun observeDrinks():Flow<List<DrinkEntity>> = dao.observeDrinks()
    fun observePresets():Flow<List<PresetEntity>> = dao.observeVisiblePresets()
    fun observeTrackedDays():Flow<List<TrackedDayEntity>> = dao.observeTrackedDays()
    fun observeCheckIns():Flow<List<CheckInEntity>> = dao.observeCheckIns()
    fun observeGoals():Flow<List<GoalEntity>> = dao.observeGoals()
    fun observeSettings():Flow<LocalSettings?> = dao.observeSettings()
    suspend fun recentStartTimes():List<String> = dao.recentStartTimes()
    suspend fun localDayStartHour():Int = dao.settings()?.dayStartHour ?: 8
    suspend fun localDrinks():List<DrinkEntity> = dao.drinks()
    suspend fun localSettings():LocalSettings = dao.settings()?:LocalSettings()

    suspend fun ensureOfflineDefaults(){
        if(dao.settings()==null)dao.putSettings(LocalSettings())
        if(dao.presetCount()>0)return
        dao.putPresets(listOf(
            PresetEntity(-1,"Bière 333 ml","bière",333.0,5.0),PresetEntity(-2,"Bière 473 ml","bière",473.0,5.0),
            PresetEntity(-3,"Vin 150 ml","vin",150.0,12.0),PresetEntity(-4,"Spiritueux 43 ml","spiritueux",43.0,40.0)
        ))
    }

    suspend fun synchronize() {
        val credentials=CredentialStore(appContext)
        if(!credentials.syncEnabled())return
        val server=credentials.server().trimEnd('/');val token=credentials.token()
        if (server.isBlank() || token.isBlank()) return
        val me=request(server,token,"/api/auth/me","GET",null);bindSyncAccount("$server#${me.optString("id",me.optString("username"))}")
        val refreshSnapshot=credentials.syncDataVersion()<syncDataVersion
        if(refreshSnapshot)dao.clearSyncState()
        push(server, token)
        pushCheckIns(server,token);pushGoals(server,token);pushPresets(server,token);pushSettings(server,token)
        pullPresets(server,token)
        pull(server, token)
        if(refreshSnapshot)credentials.setSyncDataVersion(syncDataVersion)
        pullCheckIns(server,token);pullGoals(server,token);pullSettings(server,token)
    }

    suspend fun createOffline(drink: DrinkEntity) {
        dao.putDrink(drink.copy(serverId=null,dirty=true,deleted=false,
            pendingMutationId=drink.pendingMutationId ?: UUID.randomUUID().toString()))
    }

    suspend fun createFromPreset(preset:PresetEntity,startedAt:String,durationMinutes:Int=30) {
        createOffline(DrinkEntity(UUID.randomUUID().toString(),null,preset.name,preset.type,
            preset.volumeMl,preset.abvPercent,1,startedAt,durationMinutes,null,false,true,false,
            UUID.randomUUID().toString()))
    }

    suspend fun createCustom(name:String,volumeMl:Double,abvPercent:Double,quantity:Int,startedAt:String,durationMinutes:Int=30) {
        createOffline(DrinkEntity(UUID.randomUUID().toString(),null,name,null,volumeMl,abvPercent,
            quantity,startedAt,durationMinutes,null,false,true,false,UUID.randomUUID().toString()))
    }

    suspend fun startFromWear(operationId:String,volumeMl:Double,abvPercent:Double,startedAt:String) {
        val clientId="wear:$operationId";if(dao.findByClientId(clientId)!=null)return
        dao.putDrink(DrinkEntity(clientId,null,"Consommation Wear OS","autre",volumeMl,abvPercent,1,
            startedAt,0,null,true,true,false,operationId))
    }

    suspend fun finishFromWear(endedAt:String) {
        val drink=dao.activeDrink()?:return;val end=ca.repere.core.parseDrinkTime(endedAt)
        val duration=java.time.Duration.between(ca.repere.core.parseDrinkTime(drink.startedAt),end).toMinutes().toInt().coerceAtLeast(0)
        dao.putDrink(drink.copy(durationMinutes=duration,active=false,dirty=true,pendingMutationId=UUID.randomUUID().toString()))
    }

    suspend fun updateOffline(clientId:String,name:String,volumeMl:Double,abvPercent:Double,quantity:Int,startedAt:String,durationMinutes:Int) {
        val drink=dao.findByClientId(clientId) ?: return
        dao.putDrink(drink.copy(name=name,volumeMl=volumeMl,abvPercent=abvPercent,quantity=quantity,
            startedAt=startedAt,durationMinutes=durationMinutes,dirty=true,deleted=false,pendingMutationId=UUID.randomUUID().toString()))
    }

    suspend fun markDeleted(clientId: String) {
        val drink=dao.findByClientId(clientId) ?: return
        if (drink.serverId==null) dao.deleteDrink(clientId)
        else dao.putDrink(drink.copy(dirty=true,deleted=true,pendingMutationId=UUID.randomUUID().toString()))
    }

    private suspend fun push(server:String,token:String) {
        val pending=dao.pendingDrinks();if(pending.isEmpty())return
        val mutations=JSONArray()
        pending.forEach { drink ->
            val operation=if(drink.deleted)"delete" else if(drink.serverId==null)"create" else "update"
            mutations.put(JSONObject().put("mutation_id",drink.pendingMutationId ?: drink.clientId)
                .put("operation",operation).apply {
                    drink.serverId?.let { put("server_id",it) }
                    if(operation!="delete")put("data",drink.toJson())
                })
        }
        val response=request(server,token,"/api/sync","POST",JSONObject().put("mutations",mutations))
        val results=response.getJSONArray("results")
        for(index in 0 until results.length()) {
            val result=results.getJSONObject(index);val mutationId=result.getString("mutation_id")
            val local=pending.firstOrNull { (it.pendingMutationId ?: it.clientId)==mutationId } ?: continue
            if(local.deleted)dao.deleteDrink(local.clientId)
            else dao.putDrink(local.copy(serverId=result.getLong("server_id"),dirty=false,pendingMutationId=null))
        }
    }

    private suspend fun pull(server:String,token:String) {
        var cursor=dao.syncState()?.cursor ?: 0
        var more:Boolean
        do {
            val response=request(server,token,"/api/sync?cursor=$cursor","GET",null)
            response.optJSONObject("bac_profile")?.let { profile ->
                val weight=profile.optDouble("weight_kg",Double.NaN);val ratio=profile.optDouble("distribution_ratio",Double.NaN)
                if(weight.isFinite()&&ratio.isFinite())CredentialStore(appContext).saveBacProfile(weight,ratio,profile.optDouble("elimination_rate",.015))
            }
            val tracked=response.optJSONArray("tracked_days") ?: JSONArray();val trackedRows=(0 until tracked.length()).map{tracked.getJSONObject(it)}.map{TrackedDayEntity(it.getString("day"),it.optBoolean("sober",true))}
            dao.clearTrackedDays();if(trackedRows.isNotEmpty())dao.putTrackedDays(trackedRows)
            val changes=response.getJSONArray("changes")
            for(index in 0 until changes.length()) {
                val change=changes.getJSONObject(index);val serverId=change.getLong("entity_id")
                val current=dao.findByServerId(serverId)
                if(change.getString("operation")=="delete") {
                    if(current!=null && !current.dirty)dao.deleteDrink(current.clientId)
                } else if(current==null || !current.dirty) {
                    val data=change.getJSONObject("payload")
                    dao.putDrink(data.toEntity(current?.clientId ?: "server:$serverId"))
                }
            }
            cursor=response.getLong("cursor");dao.setSyncState(SyncState(cursor=cursor))
            more=response.getBoolean("has_more")
        } while(more)
    }

    suspend fun setSoberDay(day:String,sober:Boolean){if(sober)dao.putTrackedDays(listOf(TrackedDayEntity(day)))else dao.deleteTrackedDay(day)}

    suspend fun saveCheckIn(payload:JSONObject){
        val id=payload.optString("id").ifBlank{UUID.randomUUID().toString()};payload.put("id",id)
        dao.putCheckIn(CheckInEntity(id,payload.getString("local_date"),payload.toString()))
    }

    suspend fun createGoal(payload:JSONObject){
        val id=UUID.randomUUID().toString();dao.putGoal(GoalEntity(id,null,payload.getString("kind"),payload.getDouble("target"),
            temporalMode=payload.optString("temporal_mode","consecutive_weeks"),consecutiveWeeks=payload.optInt("consecutive_weeks").takeIf{it>0},
            dueDate=payload.optString("due_date").takeIf{it.isNotBlank()},startedOn=java.time.LocalDate.now().toString()))
    }
    suspend fun deleteGoal(clientId:String){val row=dao.goal(clientId)?:return;if(row.serverId==null)dao.deleteGoal(clientId)else dao.putGoal(row.copy(deleted=true,dirty=true,lastError=null))}
    suspend fun markGoalAchieved(clientId:String){dao.goal(clientId)?.takeIf{!it.achieved}?.let{dao.putGoal(it.copy(achieved=true))}}
    suspend fun saveSettings(dayStartHour:Int,sessionGapHours:Double=8.0,trackingStartDate:String?=null){
        val old=dao.settings()?:LocalSettings();dao.putSettings(old.copy(dayStartHour=dayStartHour,sessionGapHours=sessionGapHours,trackingStartDate=trackingStartDate,dirty=true))
    }
    suspend fun saveMeasurementPreferences(standardDrinkGrams:Double,volumeUnit:String){
        val old=dao.settings()?:LocalSettings();dao.putSettings(old.copy(standardDrinkGrams=standardDrinkGrams,volumeUnit=volumeUnit,dirty=true))
    }
    suspend fun savePreset(preset:PresetEntity,name:String,type:String,volume:Double,abv:Double){
        val id=if(preset.serverId==0L)-System.currentTimeMillis() else preset.serverId
        dao.putPresets(listOf(PresetEntity(id,name,type,volume,abv,true,false,UUID.randomUUID().toString())))
    }
    suspend fun deletePresetLocal(preset:PresetEntity){if(preset.serverId<=0)dao.deletePreset(preset.serverId)else dao.putPresets(listOf(preset.copy(dirty=true,deleted=true,mutationId=UUID.randomUUID().toString())))}

    private suspend fun bindSyncAccount(account:String){val settings=dao.settings()?:LocalSettings();val previous=settings.syncAccount;if(previous!=null&&previous!=account){
        dao.removeSyncedDrinks();dao.detachPendingDrinks();dao.removeSyncedPresets();dao.removeSyncedGoals();dao.removeSyncedCheckIns();dao.clearTrackedDays();dao.clearSyncState()
    };if(previous!=account)dao.putSettings(settings.copy(syncAccount=account))}

    private suspend fun pushCheckIns(server:String,token:String){for(row in dao.pendingCheckIns())runCatching{
        request(server,token,"/api/check-ins","POST",JSONObject(row.payload));dao.putCheckIn(row.copy(dirty=false,lastError=null))
    }.onFailure{dao.putCheckIn(row.copy(lastError=it.message))}}
    private suspend fun pushGoals(server:String,token:String){for(row in dao.pendingGoals())runCatching{
        if(row.deleted){row.serverId?.let{requestRaw(server,token,"/api/goals/$it","DELETE",JSONObject())};dao.deleteGoal(row.clientId)}
        else if(row.serverId==null){val body=goalJson(row);val result=request(server,token,"/api/goals","POST",body);dao.putGoal(row.copy(serverId=result.getLong("id"),dirty=false,lastError=null))}
        else {request(server,token,"/api/goals/${row.serverId}","PATCH",goalJson(row));dao.putGoal(row.copy(dirty=false,lastError=null))}
    }.onFailure{dao.putGoal(row.copy(lastError=it.message))}}
    private fun goalJson(row:GoalEntity)=JSONObject().put("kind",row.kind).put("target",row.target).put("active",row.active).put("temporal_mode",row.temporalMode)
        .put("consecutive_weeks",row.consecutiveWeeks).put("due_date",row.dueDate)
    private suspend fun pushPresets(server:String,token:String){for(row in dao.pendingPresets())runCatching{
        if(row.deleted){if(row.serverId>0)requestRaw(server,token,"/api/presets/${row.serverId}","DELETE",JSONObject());dao.deletePreset(row.serverId)}
        else {val body=JSONObject().put("name",row.name).put("drink_type",row.type).put("volume_ml",row.volumeMl).put("abv_percent",row.abvPercent)
            if(row.serverId<=0){val result=request(server,token,"/api/presets","POST",body);dao.deletePreset(row.serverId);dao.putPresets(listOf(row.copy(serverId=result.getLong("id"),dirty=false,mutationId=null)))}
            else {request(server,token,"/api/presets/${row.serverId}","PATCH",body);dao.putPresets(listOf(row.copy(dirty=false,mutationId=null)))}}
    }}
    private suspend fun pushSettings(server:String,token:String){val row=dao.settings()?:return;if(!row.dirty)return;request(server,token,"/api/settings","PATCH",JSONObject().put("day_start_hour",row.dayStartHour).put("session_gap_hours",row.sessionGapHours).put("tracking_start_date",row.trackingStartDate).put("standard_drink_grams",row.standardDrinkGrams).put("volume_unit",row.volumeUnit));dao.putSettings(row.copy(dirty=false))}

    private suspend fun pullPresets(server:String,token:String) {
        val rows=requestArray(server,token,"/api/wear/presets")
        val presets=buildList {
            for(index in 0 until rows.length()) {
                val row=rows.getJSONObject(index)
                add(PresetEntity(row.getLong("id"),row.getString("name"),row.getString("drink_type"),
                    row.getDouble("volume_ml"),row.getDouble("abv_percent")))
            }
        }
        val pending=dao.pendingPresets();dao.clearPresets();dao.putPresets(presets+pending)
    }

    private suspend fun pullCheckIns(server:String,token:String){val rows=requestArray(server,token,"/api/check-ins");for(i in 0 until rows.length()){val value=rows.getJSONObject(i);val id=value.getString("id");dao.putCheckIn(CheckInEntity(id,value.getString("local_date"),value.toString(),false))}}
    private suspend fun pullGoals(server:String,token:String){val rows=requestArray(server,token,"/api/goals");val pending=dao.pendingGoals();dao.removeSyncedGoals();for(i in 0 until rows.length()){val g=rows.getJSONObject(i);val serverId=g.getLong("id");val local=pending.firstOrNull{it.serverId==serverId};if(local==null)dao.putGoal(GoalEntity("server:$serverId",serverId,g.getString("kind"),g.getDouble("target"),g.optBoolean("active",true),g.optString("temporal_mode","consecutive_weeks"),g.optInt("consecutive_weeks").takeIf{it>0},g.optString("due_date").takeIf{it.isNotBlank()&&it!="null"},g.optString("started_on",java.time.LocalDate.now().toString()),false))}}
    private suspend fun pullSettings(server:String,token:String){val me=request(server,token,"/api/auth/me","GET",null);val old=dao.settings()?:LocalSettings();if(!old.dirty)dao.putSettings(old.copy(dayStartHour=me.optInt("day_start_hour",old.dayStartHour),sessionGapHours=me.optDouble("session_gap_hours",old.sessionGapHours),trackingStartDate=me.optString("tracking_start_date").takeIf{it.isNotBlank()&&it!="null"},standardDrinkGrams=me.optDouble("standard_drink_grams",old.standardDrinkGrams),volumeUnit=me.optString("volume_unit").ifBlank{old.volumeUnit}))}

    private fun DrinkEntity.toJson()=JSONObject().put("drink_name",name).put("drink_type",type)
        .put("volume_ml",volumeMl).put("abv_percent",abvPercent).put("quantity",quantity)
        .put("started_at",startedAt).put("duration_minutes",durationMinutes)
        .put("timezone_id",ZoneId.systemDefault().id)
        .put("utc_offset_minutes",runCatching { OffsetDateTime.parse(startedAt).offset.totalSeconds / 60 }.getOrNull())
        .put("notes",notes).put("cost",JSONObject.NULL)

    private fun JSONObject.toEntity(clientId:String)=DrinkEntity(clientId,getLong("id"),getString("drink_name"),
        nullableString("drink_type"),getDouble("volume_ml"),getDouble("abv_percent"),
        getInt("quantity"),normalizeDrinkTime(getString("started_at")),getInt("duration_minutes"),
        nullableString("notes"),optBoolean("is_active"),false,false,null)

    private fun JSONObject.nullableString(key:String)=if(!has(key)||isNull(key))null else getString(key)

    private suspend fun request(server:String,token:String,path:String,method:String,body:JSONObject?):JSONObject=withContext(Dispatchers.IO) {
        val connection=URL(server+path).openConnection() as HttpURLConnection
        connection.requestMethod=method;connection.connectTimeout=10000;connection.readTimeout=15000
        connection.setRequestProperty("Authorization","Bearer $token");connection.setRequestProperty("Content-Type","application/json")
        if(body!=null){connection.doOutput=true;connection.outputStream.use{it.write(body.toString().toByteArray())}}
        val text=(if(connection.responseCode in 200..299)connection.inputStream else connection.errorStream).bufferedReader().readText()
        if(connection.responseCode !in 200..299)error(runCatching{JSONObject(text).optString("detail")}.getOrDefault("Erreur ${connection.responseCode}"))
        JSONObject(text)
    }

    private suspend fun requestRaw(server:String,token:String,path:String,method:String,body:JSONObject?)=withContext(Dispatchers.IO){
        val connection=URL(server+path).openConnection() as HttpURLConnection;connection.requestMethod=method;connection.connectTimeout=10000;connection.readTimeout=15000
        connection.setRequestProperty("Authorization","Bearer $token");connection.setRequestProperty("Content-Type","application/json")
        if(body!=null&&method!="GET"){connection.doOutput=true;connection.outputStream.use{it.write(body.toString().toByteArray())}}
        val code=connection.responseCode;if(code !in 200..299)error("Erreur $code");connection.disconnect()
    }


    private suspend fun requestArray(server:String,token:String,path:String):JSONArray=withContext(Dispatchers.IO) {
        val connection=URL(server+path).openConnection() as HttpURLConnection
        connection.requestMethod="GET";connection.connectTimeout=10000;connection.readTimeout=15000
        connection.setRequestProperty("Authorization","Bearer $token")
        val text=(if(connection.responseCode in 200..299)connection.inputStream else connection.errorStream).bufferedReader().readText()
        if(connection.responseCode !in 200..299)error("Erreur ${connection.responseCode}")
        JSONArray(text)
    }
}
