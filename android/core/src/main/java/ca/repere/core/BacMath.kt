package ca.repere.core

import java.time.OffsetDateTime
import kotlin.math.max

data class BacProfile(val weightKg:Double,val distributionRatio:Double,val eliminationRate:Double=.015)
data class BacDrink(val startedAt:OffsetDateTime,val durationMinutes:Int,val alcoholGrams:Double,val active:Boolean=false)

fun distributionRatio(sex:String,heightCm:Double?,weightKg:Double,stored:Double=.6):Double {
    if(heightCm!=null&&heightCm>100){
        val male=2.447-.09516*40+.1074*heightCm+.3362*weightKg
        val female=-2.097+.1069*heightCm+.2466*weightKg
        val tbw=if(sex=="male")male else if(sex=="female")female else (male+female)/2
        return (tbw/(weightKg*.8065)).coerceIn(.4,.9)
    }
    return if(sex=="male").68 else if(sex=="female").55 else stored
}

/**
 * Android equivalent of backend services.bac_at. Keep both implementations aligned.
 * Each drink's remaining grams is clamped to zero individually before summing: elimination keeps
 * accruing for as long as a drink is tracked, so an old, fully-metabolized drink must not be able
 * to carry a negative "debt" forward that cancels out a different, unrelated drink's absorption.
 */
fun bacAt(drinks:List<BacDrink>,profile:BacProfile,moment:OffsetDateTime):Double {
    val localMoment=moment.toLocalDateTime()
    var remainingGrams=0.0
    drinks.forEach { drink ->
        val localStart=drink.startedAt.toLocalDateTime()
        if(!localMoment.isAfter(localStart))return@forEach
        val elapsedMinutes=java.time.Duration.between(localStart,localMoment).toMillis()/60_000.0
        // While a drink is still in progress its stored duration isn't final yet (often still 0),
        // so keep stretching the absorption window to match real elapsed time instead of assuming
        // the whole thing was downed the moment it was logged.
        val effectiveDurationMinutes=if(drink.active)maxOf(drink.durationMinutes.toDouble(),elapsedMinutes) else drink.durationMinutes.toDouble()
        val absorptionMinutes=max(30.0,effectiveDurationMinutes+30)
        val fraction=(elapsedMinutes/absorptionMinutes).coerceIn(0.0,1.0)
        val absorbed=drink.alcoholGrams*fraction
        val fullAt=localStart.plusMinutes(absorptionMinutes.toLong())
        val eliminationHours=max(0.0,java.time.Duration.between(fullAt,localMoment).toMillis()/3_600_000.0)
        val eliminated=profile.eliminationRate*eliminationHours*profile.weightKg*profile.distributionRatio*10
        remainingGrams+=max(0.0,absorbed-eliminated)
    }
    return max(0.0,remainingGrams/(profile.weightKg*1000*profile.distributionRatio)*100)
}

fun peakBac(drinks:List<BacDrink>,profile:BacProfile):Double? {
    if(drinks.isEmpty()||profile.weightKg<=0||profile.distributionRatio<=0)return null
    val start=drinks.minOf { it.startedAt }.minusHours(1)
    return (0..36*12).maxOf { bacAt(drinks,profile,start.plusMinutes(it*5L)) }
}

/** Mirrors the backend's 36h window on bac_at/bac_projection callers, so peakBac's scan isn't anchored on ancient history. */
fun recentForBac(drinks:List<BacDrink>,moment:OffsetDateTime,hours:Long=36):List<BacDrink> {
    val since=moment.minusHours(hours)
    return drinks.filter { !it.startedAt.isBefore(since) }
}
