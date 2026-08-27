package ca.repere.core

import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId

/** Accepts both local backend timestamps and offset-aware offline timestamps. */
fun parseDrinkTime(raw:String):OffsetDateTime = runCatching { OffsetDateTime.parse(raw) }
    .getOrElse { LocalDateTime.parse(raw).atZone(ZoneId.systemDefault()).toOffsetDateTime() }

fun normalizeDrinkTime(raw:String):String=parseDrinkTime(raw).toString()
