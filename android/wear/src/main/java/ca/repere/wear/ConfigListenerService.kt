package ca.repere.wear

import android.content.ComponentName
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService

class ConfigListenerService : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        var touched = false
        events.filter { it.type == DataEvent.TYPE_CHANGED && it.dataItem.uri.path == "/repere/config" }.forEach {
            val map = DataMapItem.fromDataItem(it.dataItem).dataMap
            val prefs=getSharedPreferences("repere",MODE_PRIVATE)
            val phoneStateAt=map.getLong("synced_at");val localActionAt=prefs.getLong("local_action_at",0L)
            val edit=prefs.edit()
                .putFloat("today_standard",map.getFloat("today_standard"))
                .putFloat("bac_g_per_l",map.getFloat("bac_g_per_l"))
                .putString("bac_trend",map.getString("bac_trend")?:"stable")
            // A delayed DataItem must never undo a start/finish performed locally on the watch.
            // A later phone publication may still reconcile state after queued commands arrive.
            if(phoneStateAt>=localActionAt)edit.putBoolean("active",map.getBoolean("active"))
                .putLong("active_started_at",map.getLong("active_started_at"))
            edit.apply()
            touched = true
        }
        // The phone owns the offline state; redraw directly from the received cache.
        if (touched) {
            listOf(QuickDrinkComplicationService::class.java,BacComplicationService::class.java).forEach{service->
                ComplicationDataSourceUpdateRequester.create(this,ComponentName(this,service)).requestUpdateAll()
            }
            androidx.wear.tiles.TileService.getUpdater(this).requestUpdate(RepereTileService::class.java)
        }
    }
}
