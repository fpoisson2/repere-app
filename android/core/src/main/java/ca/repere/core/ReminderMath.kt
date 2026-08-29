package ca.repere.core

import java.time.OffsetDateTime

/** Median first-drink time, ordered relative to the user's configured day boundary. */
fun usualOnsetMinutes(times:List<String>,dayStartHour:Int=8,defaultMinutes:Int=19*60):Int {
    val firstPerTrackedDay=times.mapNotNull{raw->runCatching{parseDrinkTime(raw)}.getOrNull()}
        .groupBy{it.minusHours(dayStartHour.toLong()).toLocalDate()}
        .values.mapNotNull{rows->rows.minByOrNull(OffsetDateTime::toInstant)}
    if(firstPerTrackedDay.isEmpty())return defaultMinutes
    val boundary=dayStartHour*60
    val relative=firstPerTrackedDay.map{((it.hour*60+it.minute-boundary)%1440+1440)%1440}.sorted()
    return (relative[relative.size/2]+boundary)%1440
}
