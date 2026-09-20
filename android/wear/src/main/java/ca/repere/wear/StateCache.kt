package ca.repere.wear

import android.content.Context
import ca.repere.core.BacDrink
import ca.repere.core.BacProfile
import ca.repere.core.CredentialStore
import ca.repere.core.bacAt
import ca.repere.core.parseDrinkTime
import java.time.Duration
import java.time.OffsetDateTime
import org.json.JSONArray
import org.json.JSONObject

/** Recalculates the cached watch state from the last phone snapshot plus unrelayed Wear actions. */
object StateCache {
    fun refresh(context: Context) {
        val prefs=context.getSharedPreferences("repere",Context.MODE_PRIVATE)
        val weight=doublePref(prefs,"bac_weight_kg_bits",Double.NaN);val ratio=doublePref(prefs,"bac_distribution_ratio_bits",Double.NaN)
        if(!weight.isFinite()||!ratio.isFinite()||weight<=0||ratio<=0)return
        val drinks=mutableListOf<Pair<String,BacDrink>>()
        runCatching{JSONArray(prefs.getString("bac_inputs","[]"))}.getOrNull()?.let{array->for(i in 0 until array.length())runCatching{
            val row=array.getJSONObject(i);drinks+=row.getString("id") to BacDrink(parseDrinkTime(row.getString("started_at")),row.optInt("duration_minutes",30),row.getDouble("alcohol_grams"),row.optBoolean("active"))
        }}
        val pending=runCatching{JSONArray(CredentialStore(context).pendingWearOperations())}.getOrDefault(JSONArray());var addedStandards=0.0
        for(i in 0 until pending.length())runCatching{
            val operation=pending.getJSONObject(i);val body=JSONObject(operation.optString("body").ifBlank{"{}"})
            when(operation.getString("path")){
                "/api/wear/start"->{
                    val id="wear:${operation.getString("id")}";if(drinks.none{it.first==id}){
                        val start=parseDrinkTime(body.getString("started_at"));val grams=body.getDouble("volume_ml")*body.optDouble("quantity",1.0)*body.getDouble("abv_percent")/100*.789
                        drinks+=id to BacDrink(start,0,grams,true)
                        val dayStart=prefs.getInt("day_start_hour",8);val now=OffsetDateTime.now()
                        if(start.toLocalDateTime().minusHours(dayStart.toLong()).toLocalDate()==now.toLocalDateTime().minusHours(dayStart.toLong()).toLocalDate())addedStandards+=grams/doublePref(prefs,"standard_drink_grams_bits",13.45)
                    }
                }
                "/api/wear/finish"->{val index=drinks.indexOfLast{it.second.active};if(index>=0){val old=drinks[index];val end=parseDrinkTime(body.getString("ended_at"));drinks[index]=old.first to old.second.copy(durationMinutes=Duration.between(old.second.startedAt,end).toMinutes().toInt().coerceAtLeast(0),active=false)}}
            }
        }
        val now=OffsetDateTime.now();val profile=BacProfile(weight,ratio,doublePref(prefs,"bac_elimination_rate_bits",.015));val inputs=drinks.map{it.second}
        val current=bacAt(inputs,profile,now)*10;val future=bacAt(inputs,profile,now.plusMinutes(10))*10
        prefs.edit().putFloat("bac_g_per_l",current.toFloat()).putString("bac_trend",if(future>current+.01)"hausse"else if(future<current-.01)"baisse"else"stable")
            .putFloat("today_standard_local",(prefs.getFloat("today_standard",0f)+addedStandards).toFloat()).apply()
    }

    private fun doublePref(prefs:android.content.SharedPreferences,key:String,default:Double)=
        java.lang.Double.longBitsToDouble(prefs.getLong(key,java.lang.Double.doubleToRawLongBits(default)))
}
