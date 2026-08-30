package ca.repere.core

private const val LB_PER_KG = 2.2046226218
private const val CM_PER_IN = 2.54

fun kgToLb(kg:Double):Double = kg * LB_PER_KG
fun lbToKg(lb:Double):Double = lb / LB_PER_KG
fun cmToIn(cm:Double):Double = cm / CM_PER_IN
fun inToCm(inches:Double):Double = inches * CM_PER_IN
