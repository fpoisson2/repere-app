package ca.repere.core

private const val ALCOHOL_DENSITY = .789
const val CANADIAN_STANDARD_GRAMS = 13.45
const val US_STANDARD_GRAMS = 14.0
const val UK_STANDARD_GRAMS = 8.0
const val ML_PER_FLUID_OUNCE = 29.5735

fun alcoholGrams(volumeMl:Double,abvPercent:Double,quantity:Int=1):Double =
    volumeMl * abvPercent / 100 * ALCOHOL_DENSITY * quantity

/** [standardGrams] is the user-configurable grams of pure alcohol per "standard drink" (default: Canadian standard). */
fun canadianStandards(volumeMl:Double,abvPercent:Double,quantity:Int=1,standardGrams:Double=CANADIAN_STANDARD_GRAMS):Double =
    alcoholGrams(volumeMl,abvPercent,quantity) / standardGrams

fun mlToOunces(ml:Double):Double = ml / ML_PER_FLUID_OUNCE
fun ouncesToMl(oz:Double):Double = oz * ML_PER_FLUID_OUNCE
