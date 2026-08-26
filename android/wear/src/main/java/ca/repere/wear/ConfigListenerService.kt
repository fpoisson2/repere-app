package ca.repere.wear

import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService
import ca.repere.core.CredentialStore

class ConfigListenerService : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        events.filter { it.type == DataEvent.TYPE_CHANGED && it.dataItem.uri.path == "/repere/config" }.forEach {
            val map = DataMapItem.fromDataItem(it.dataItem).dataMap
            CredentialStore(this).save(map.getString("server").orEmpty(),map.getString("token").orEmpty())
        }
    }
}
