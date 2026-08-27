package ca.repere.mobile

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import ca.repere.data.SyncRepository
import java.time.Duration
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Daily "time for a check-in?" nudge, fired [LEAD_MINUTES] before the user's usual drinking
 * onset (median first-drink time across recent history; 19:00 default when there is no history).
 */
object CheckInReminder {
    private const val WORK = "repere-checkin-reminder"
    private const val CHANNEL = "checkin_reminder"
    const val LEAD_MINUTES = 90
    private const val DEFAULT_ONSET_MINUTES = 19 * 60
    private const val PREF_ENABLED = "checkin_reminder"
    private const val PREF_MINUTE = "checkin_reminder_minute"

    private fun prefs(context: Context) = context.getSharedPreferences("repere", Context.MODE_PRIVATE)
    fun isEnabled(context: Context) = prefs(context).getBoolean(PREF_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(PREF_ENABLED, enabled).apply()
        if (enabled) schedule(context) else WorkManager.getInstance(context).cancelUniqueWork(WORK)
    }

    /** Recompute the usual onset from local history, then (re)schedule. Call from a coroutine. */
    suspend fun refreshAndSchedule(context: Context) {
        val onset = usualOnsetMinutes(runCatching { SyncRepository(context).recentStartTimes() }.getOrDefault(emptyList()))
        val minute = ((onset - LEAD_MINUTES) % 1440 + 1440) % 1440
        prefs(context).edit().putInt(PREF_MINUTE, minute).apply()
        schedule(context)
    }

    /** Enqueue the next occurrence using the cached reminder time. */
    fun schedule(context: Context) {
        if (!isEnabled(context)) return
        val minute = prefs(context).getInt(PREF_MINUTE, DEFAULT_ONSET_MINUTES - LEAD_MINUTES)
        val time = LocalTime.of(minute / 60, minute % 60)
        val zone = ZoneId.systemDefault()
        val now = ZonedDateTime.now(zone)
        var next = now.toLocalDate().atTime(time).atZone(zone)
        if (!next.isAfter(now.plusMinutes(1))) next = next.plusDays(1)
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK, ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<Worker>()
                .setInitialDelay(Duration.between(now, next).toMillis().coerceAtLeast(60_000), java.util.concurrent.TimeUnit.MILLISECONDS)
                .build(),
        )
    }

    private fun usualOnsetMinutes(times: List<String>): Int {
        val firstPerDay = HashMap<String, Int>()
        for (raw in times) {
            val t = runCatching { OffsetDateTime.parse(raw) }.getOrNull() ?: continue
            val key = t.toLocalDate().toString()
            val mins = t.hour * 60 + t.minute
            firstPerDay[key] = minOf(firstPerDay[key] ?: Int.MAX_VALUE, mins)
        }
        val values = firstPerDay.values.sorted()
        return if (values.isEmpty()) DEFAULT_ONSET_MINUTES else values[values.size / 2]
    }

    fun notify(context: Context) {
        ensureChannel(context)
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val open = PendingIntent.getActivity(
            context, 7,
            Intent(context, MainActivity::class.java).putExtra("open_checkin", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Un check-in ?")
            .setContentText("C’est bientôt ton heure habituelle. Prends un moment pour noter ton intention.")
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(4201, notification) }
    }

    private fun ensureChannel(context: Context) {
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL, "Rappel de check-in", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Rappel avant l’heure habituelle de consommation"
                },
            )
        }
    }

    class Worker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
        override suspend fun doWork(): Result {
            if (isEnabled(applicationContext)) {
                notify(applicationContext)
                refreshAndSchedule(applicationContext) // chain the next day, re-reading history
            }
            return Result.success()
        }
    }
}
