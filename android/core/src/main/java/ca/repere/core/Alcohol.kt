package ca.repere.core

private const val ALCOHOL_DENSITY = .789
private const val CANADIAN_STANDARD_GRAMS = 13.45

fun alcoholGrams(volumeMl:Double,abvPercent:Double,quantity:Int=1):Double =
    volumeMl * abvPercent / 100 * ALCOHOL_DENSITY * quantity

fun canadianStandards(volumeMl:Double,abvPercent:Double,quantity:Int=1):Double =
    alcoholGrams(volumeMl,abvPercent,quantity) / CANADIAN_STANDARD_GRAMS
