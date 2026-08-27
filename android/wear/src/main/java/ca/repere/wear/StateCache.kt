package ca.repere.wear

import android.content.Context
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlinx.coroutines.withTimeoutOrNull

/** Shared best-effort fetch of /api/wear/state, cached in SharedPreferences for the complications. */
object StateCache {
    suspend fun refresh(context: Context) {
        val prefs = context.getSharedPreferences("repere", Context.MODE_PRIVATE)
        withTimeoutOrNull(8_000) {
            runCatching {
                val state = Api.state(context)
                val activeDrink = state.optJSONObject("active")
                val startedAt = activeDrink?.let {
                    parseMillis(it.optString("started_at_utc").ifBlank { it.optString("started_at") })
                } ?: 0L
                prefs.edit()
                    .putBoolean("active", activeDrink != null)
                    .putLong("active_started_at", startedAt)
                    .putFloat("today_standard", state.optDouble("today_standard_drinks", prefs.getFloat("today_standard", 0f).toDouble()).toFloat())
                    .putFloat("bac_g_per_l", state.optDouble("bac_g_per_l", 0.0).toFloat())
                    .putString("bac_trend", state.optString("bac_trend", "stable"))
                    .apply()
            }
        }
    }

    fun parseMillis(value: String): Long = runCatching {
        OffsetDateTime.parse(value).toInstant().toEpochMilli()
    }.recoverCatching {
        OffsetDateTime.of(LocalDateTime.parse(value), ZoneOffset.UTC).toInstant().toEpochMilli()
    }.getOrDefault(0L)
}
