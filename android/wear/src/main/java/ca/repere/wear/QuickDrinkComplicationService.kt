package ca.repere.wear

import android.app.PendingIntent
import android.content.Intent
import androidx.wear.watchface.complications.data.*
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService

class QuickDrinkComplicationService : SuspendingComplicationDataSourceService() {
    override fun getPreviewData(type: ComplicationType): ComplicationData = data(false)
    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData = data(getSharedPreferences("repere", MODE_PRIVATE).getBoolean("active", false))
    private fun data(active: Boolean): ComplicationData {
        val label = if (active) "Terminer" else "Démarrer"
        val tap = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java).putExtra("quick_toggle", true), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return ShortTextComplicationData.Builder(PlainComplicationText.Builder(label).build(), PlainComplicationText.Builder("Repère : $label une consommation").build()).setTapAction(tap).build()
    }
}
