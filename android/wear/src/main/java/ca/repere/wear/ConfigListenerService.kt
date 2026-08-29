package ca.repere.wear

import android.content.ComponentName
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService
import ca.repere.core.CredentialStore

class ConfigListenerService : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        var touched = false
        events.filter { it.type == DataEvent.TYPE_CHANGED && it.dataItem.uri.path == "/repere/config" }.forEach {
            val map = DataMapItem.fromDataItem(it.dataItem).dataMap
            val server = map.getString("server").orEmpty()
            val token = map.getString("token").orEmpty()
            if (server.isNotBlank() && token.isNotBlank()) CredentialStore(this).save(server, token)
            touched = true
        }
        // The phone bumps /repere/config after each successful sync; refresh the complication so a
        // drink deleted or edited on the phone shows up on the watch face without opening the app.
        if (touched) {
            listOf(QuickDrinkComplicationService::class.java,BacComplicationService::class.java).forEach{service->
                ComplicationDataSourceUpdateRequester.create(this,ComponentName(this,service)).requestUpdateAll()
            }
            androidx.wear.tiles.TileService.getUpdater(this).requestUpdate(RepereTileService::class.java)
        }
    }
}
