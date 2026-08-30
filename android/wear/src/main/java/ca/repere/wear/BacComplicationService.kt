package ca.repere.wear

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.*
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import java.util.Locale

/** Estimated blood alcohol (g/L) for a separate watch-face slot. Tap opens the app; never a mesure réelle. */
class BacComplicationService : SuspendingComplicationDataSourceService() {
    override fun getPreviewData(type: ComplicationType): ComplicationData = build(type, 0.32f, "baisse")

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData {
        StateCache.refresh(this)
        val prefs = getSharedPreferences("repere", MODE_PRIVATE)
        return build(request.complicationType, prefs.getFloat("bac_g_per_l", 0f), prefs.getString("bac_trend", "stable") ?: "stable")
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 2,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun build(type: ComplicationType, gPerL: Float, trend: String): ComplicationData {
        val value = String.format(Locale.getDefault(), "%.2f", gPerL)
        val icon = MonochromaticImage.Builder(Icon.createWithResource(this, R.drawable.ic_bac)).build()
        val description = PlainComplicationText.Builder(getString(R.string.wear_bac_description, value, trend)).build()
        return when (type) {
            ComplicationType.RANGED_VALUE ->
                RangedValueComplicationData.Builder(
                    value = gPerL.coerceIn(0f, 2f), min = 0f, max = 2f,
                    contentDescription = description,
                ).setText(PlainComplicationText.Builder(value).build())
                    .setMonochromaticImage(icon)
                    .setTapAction(openApp())
                    .build()
            ComplicationType.MONOCHROMATIC_IMAGE ->
                MonochromaticImageComplicationData.Builder(icon, description).setTapAction(openApp()).build()
            else ->
                ShortTextComplicationData.Builder(
                    PlainComplicationText.Builder(getString(R.string.wear_bac_value, value)).build(), description,
                ).setMonochromaticImage(icon).setTapAction(openApp()).build()
        }
    }
}
