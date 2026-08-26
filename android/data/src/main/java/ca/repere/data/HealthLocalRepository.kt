package ca.repere.data

import android.content.Context
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray

class HealthLocalRepository(context:Context) {
    private val dao=RepereDatabase.get(context).dao()
    fun observe():Flow<List<HealthAggregateEntity>> = dao.observeHealth()

    suspend fun store(rows:JSONArray):Int {
        val entities=buildList { for(index in 0 until rows.length()){
            val row=rows.getJSONObject(index);val day=row.getString("local_date");val type=row.getString("record_type");val origin=row.getString("origin_package")
            add(HealthAggregateEntity("$day|$type|$origin",day,type,origin,row.toString(),true))
        }}
        if(entities.isNotEmpty())dao.putHealth(entities);return entities.size
    }

    suspend fun pending():Pair<JSONArray,List<String>> {
        val rows=dao.pendingHealth();val array=JSONArray();rows.forEach{array.put(org.json.JSONObject(it.payload))}
        return array to rows.map{it.id}
    }
    suspend fun markSynced(ids:List<String>){if(ids.isNotEmpty())dao.markHealthSynced(ids)}
}
