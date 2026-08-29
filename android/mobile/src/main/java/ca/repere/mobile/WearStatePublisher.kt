package ca.repere.mobile

import android.content.Context
import ca.repere.core.*
import ca.repere.data.DrinkEntity
import ca.repere.data.LocalSettings
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import java.time.LocalDate
import java.time.OffsetDateTime

object WearStatePublisher {
    fun publish(context:Context,drinks:List<DrinkEntity>,settings:LocalSettings){
        val credentials=CredentialStore(context);val weight=credentials.bacWeightKg();val ratio=credentials.bacDistributionRatio();val now=OffsetDateTime.now()
        val inputs=drinks.mapNotNull{d->runCatching{BacDrink(parseDrinkTime(d.startedAt),d.durationMinutes,d.volumeMl*d.quantity*d.abvPercent/100*.789)}.getOrNull()}
        val profile=if(weight!=null&&ratio!=null)BacProfile(weight,ratio,credentials.bacEliminationRate())else null
        val current=profile?.let{bacAt(inputs,it,now)*10}?:0.0;val future=profile?.let{bacAt(inputs,it,now.plusMinutes(10))*10}?:current
        val todayStandard=drinks.filter{runCatching{trackedDay(it.startedAt,settings.dayStartHour)==LocalDate.now()}.getOrDefault(false)}.sumOf{canadianStandards(it.volumeMl,it.abvPercent,it.quantity)}
        val active=drinks.firstOrNull{it.active}
        val request=PutDataMapRequest.create("/repere/config").apply{dataMap.putLong("synced_at",System.currentTimeMillis())
            dataMap.putBoolean("active",active!=null);dataMap.putLong("active_started_at",active?.let{runCatching{parseDrinkTime(it.startedAt).toInstant().toEpochMilli()}.getOrDefault(0L)}?:0L)
            dataMap.putFloat("today_standard",todayStandard.toFloat());dataMap.putFloat("bac_g_per_l",current.toFloat())
            dataMap.putString("bac_trend",if(future>current+.01)"hausse"else if(future<current-.01)"baisse"else"stable")}.asPutDataRequest().setUrgent()
        Wearable.getDataClient(context).putDataItem(request)
    }
}
