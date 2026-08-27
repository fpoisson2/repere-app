package ca.repere.wear

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.*
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import java.time.Instant
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

class QuickDrinkComplicationService : SuspendingComplicationDataSourceService() {
    override fun getPreviewData(type: ComplicationType): ComplicationData =
        build(type, active = false, startedAtMillis = 0L, todayStandard = 2.0)

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData {
        val prefs = getSharedPreferences("repere", MODE_PRIVATE)
        return build(
            request.complicationType,
            active = prefs.getBoolean("active", false),
            startedAtMillis = prefs.getLong("active_started_at", 0L),
            todayStandard = prefs.getFloat("today_standard", 0f).toDouble(),
        )
    }

    /** Tap always just opens the app; it never starts or stops a consumption. */
    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun build(
        type: ComplicationType,
        active: Boolean,
        startedAtMillis: Long,
        todayStandard: Double,
    ): ComplicationData {
        val icon = MonochromaticImage.Builder(Icon.createWithResource(this, R.drawable.ic_drink)).build()
        val description = PlainComplicationText.Builder(
            if (active) "Répère : consommation en cours" else "Répère : consommations standard aujourd'hui",
        ).build()
        val text: ComplicationText = if (active && startedAtMillis > 0L) {
            // Stopwatch style ticks up as h:mm:ss / m:ss while the watch face is interactive.
            TimeDifferenceComplicationText.Builder(
                TimeDifferenceStyle.STOPWATCH,
                CountUpTimeReference(Instant.ofEpochMilli(startedAtMillis)),
            ).setMinimumTimeUnit(TimeUnit.SECONDS).build()
        } else {
            PlainComplicationText.Builder(formatStandard(todayStandard)).build()
        }
        return when (type) {
            ComplicationType.MONOCHROMATIC_IMAGE ->
                MonochromaticImageComplicationData.Builder(icon, description).setTapAction(openApp()).build()
            else ->
                ShortTextComplicationData.Builder(text, description)
                    .setMonochromaticImage(icon)
                    .setTapAction(openApp())
                    .build()
        }
    }

    /** One decimal, except above 10 where it is rounded to a whole number. */
    private fun formatStandard(value: Double): String =
        if (value > 10.0) value.roundToInt().toString()
        else String.format(Locale.getDefault(), "%.1f", value)
}
