package ca.repere.mobile

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.aggregate.AggregateRequest
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Reads only granted types and emits daily aggregates; missing values stay absent. */
class HealthConnectBridge(private val context: Context) {
    companion object {
        val granularPermissions = mapOf(
            "sleep" to HealthPermission.getReadPermission(SleepSessionRecord::class),
            "hrv_rmssd" to HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class),
            "resting_heart_rate" to HealthPermission.getReadPermission(RestingHeartRateRecord::class),
            "heart_rate" to HealthPermission.getReadPermission(HeartRateRecord::class),
            "steps" to HealthPermission.getReadPermission(StepsRecord::class),
            "exercise" to HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        )
    }
    private val client by lazy { HealthConnectClient.getOrCreate(context) }
    fun available() = HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE
    suspend fun granted() = if (available()) client.permissionController.getGrantedPermissions() else emptySet()

    suspend fun aggregateDay(day: LocalDate, zone: ZoneId = ZoneId.systemDefault()): JSONArray {
        val output=JSONArray(); if (!available()) return output
        val granted=granted();val start=day.atStartOfDay(zone).toInstant();val end=day.plusDays(1).atStartOfDay(zone).toInstant()
        val filter=TimeRangeFilter.between(start,end)
        suspend fun add(type:String,value:Double?,unit:String,origin:String,samples:Int,covered:Long,method:String) {
            if(value==null)return
            output.put(JSONObject().put("local_date",day.toString()).put("record_type",type).put("value",value)
                .put("unit",unit).put("window_start_utc",start.toString()).put("window_end_utc",end.toString())
                .put("origin_package",origin).put("aggregation_method",method).put("sample_count",samples)
                .put("expected_window_minutes",1440).put("observed_minutes",covered)
                .put("coverage_ratio",(covered/1440.0).coerceIn(0.0,1.0)))
        }
        if(granularPermissions["steps"] in granted) {
            val result=client.aggregate(AggregateRequest(setOf(StepsRecord.COUNT_TOTAL),filter))
            add("steps",result[StepsRecord.COUNT_TOTAL]?.toDouble(),"count","health_connect",1,1440,"health_connect_aggregate")
        }
        if(granularPermissions["sleep"] in granted) {
            val rows=client.readRecords(ReadRecordsRequest(SleepSessionRecord::class,filter)).records
            add("sleep",rows.sumOf{Duration.between(it.startTime,it.endTime).toMinutes()}.toDouble(),"minutes",
                rows.firstOrNull()?.metadata?.dataOrigin?.packageName ?: "health_connect",rows.size,
                rows.sumOf{Duration.between(it.startTime,it.endTime).toMinutes()},"interval_sum")
        }
        if(granularPermissions["exercise"] in granted) {
            val rows=client.readRecords(ReadRecordsRequest(ExerciseSessionRecord::class,filter)).records
            val minutes=rows.sumOf{Duration.between(it.startTime,it.endTime).toMinutes()}
            add("exercise",minutes.toDouble(),"minutes",rows.firstOrNull()?.metadata?.dataOrigin?.packageName ?: "health_connect",rows.size,minutes,"interval_sum")
        }
        if(granularPermissions["hrv_rmssd"] in granted) {
            val rows=client.readRecords(ReadRecordsRequest(HeartRateVariabilityRmssdRecord::class,filter)).records
            add("hrv_rmssd",rows.map{it.heartRateVariabilityMillis}.takeIf{it.isNotEmpty()}?.average(),"ms",rows.firstOrNull()?.metadata?.dataOrigin?.packageName ?: "health_connect",rows.size,if(rows.isEmpty())0 else 1,"sample_mean")
        }
        if(granularPermissions["resting_heart_rate"] in granted) {
            val rows=client.readRecords(ReadRecordsRequest(RestingHeartRateRecord::class,filter)).records
            add("resting_heart_rate",rows.map{it.beatsPerMinute.toDouble()}.takeIf{it.isNotEmpty()}?.average(),"bpm",rows.firstOrNull()?.metadata?.dataOrigin?.packageName ?: "health_connect",rows.size,if(rows.isEmpty())0 else 1,"sample_mean")
        }
        if(granularPermissions["heart_rate"] in granted) {
            val result=client.aggregate(AggregateRequest(setOf(HeartRateRecord.BPM_AVG,HeartRateRecord.MEASUREMENTS_COUNT),filter))
            add("heart_rate",result[HeartRateRecord.BPM_AVG]?.toDouble(),"bpm","health_connect",result[HeartRateRecord.MEASUREMENTS_COUNT]?.toInt() ?: 0,1440,"health_connect_aggregate")
        }
        return output
    }
}
