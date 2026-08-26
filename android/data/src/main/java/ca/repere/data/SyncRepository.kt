package ca.repere.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import ca.repere.core.CredentialStore

/**
 * Coordinates Room-first synchronization. The HTTP transport is intentionally injected next;
 * keeping this boundary explicit prevents UI code from becoming the network source of truth.
 */
class SyncRepository(context: Context) {
    private val appContext = context.applicationContext
    private val dao = RepereDatabase.get(context).dao()

    fun observeDrinks():Flow<List<DrinkEntity>> = dao.observeDrinks()
    fun observePresets():Flow<List<PresetEntity>> = dao.observePresets()

    suspend fun ensureOfflineDefaults(){
        if(dao.presetCount()>0)return
        dao.putPresets(listOf(
            PresetEntity(-1,"Bière 341 ml","bière",341.0,5.0),PresetEntity(-2,"Bière 473 ml","bière",473.0,5.0),
            PresetEntity(-3,"Vin 150 ml","vin",150.0,12.0),PresetEntity(-4,"Spiritueux 43 ml","spiritueux",43.0,40.0)
        ))
    }

    suspend fun synchronize() {
        val credentials=CredentialStore(appContext)
        if(!credentials.syncEnabled())return
        val server=credentials.server().trimEnd('/');val token=credentials.token()
        if (server.isBlank() || token.isBlank()) return
        push(server, token)
        pullPresets(server,token)
        pull(server, token)
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

    suspend fun createCustom(name:String,volumeMl:Double,abvPercent:Double,quantity:Int,startedAt:String) {
        createOffline(DrinkEntity(UUID.randomUUID().toString(),null,name,null,volumeMl,abvPercent,
            quantity,startedAt,30,null,false,true,false,UUID.randomUUID().toString()))
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

    private suspend fun pullPresets(server:String,token:String) {
        val rows=requestArray(server,token,"/api/wear/presets")
        val presets=buildList {
            for(index in 0 until rows.length()) {
                val row=rows.getJSONObject(index)
                add(PresetEntity(row.getLong("id"),row.getString("name"),row.getString("drink_type"),
                    row.getDouble("volume_ml"),row.getDouble("abv_percent")))
            }
        }
        dao.clearPresets();dao.putPresets(presets)
    }

    private fun DrinkEntity.toJson()=JSONObject().put("drink_name",name).put("drink_type",type)
        .put("volume_ml",volumeMl).put("abv_percent",abvPercent).put("quantity",quantity)
        .put("started_at",startedAt).put("duration_minutes",durationMinutes)
        .put("notes",notes).put("cost",JSONObject.NULL)

    private fun JSONObject.toEntity(clientId:String)=DrinkEntity(clientId,getLong("id"),getString("drink_name"),
        nullableString("drink_type"),getDouble("volume_ml"),getDouble("abv_percent"),
        getInt("quantity"),getString("started_at"),getInt("duration_minutes"),
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


    private suspend fun requestArray(server:String,token:String,path:String):JSONArray=withContext(Dispatchers.IO) {
        val connection=URL(server+path).openConnection() as HttpURLConnection
        connection.requestMethod="GET";connection.connectTimeout=10000;connection.readTimeout=15000
        connection.setRequestProperty("Authorization","Bearer $token")
        val text=(if(connection.responseCode in 200..299)connection.inputStream else connection.errorStream).bufferedReader().readText()
        if(connection.responseCode !in 200..299)error("Erreur ${connection.responseCode}")
        JSONArray(text)
    }
}
