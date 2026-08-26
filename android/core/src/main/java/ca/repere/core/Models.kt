package ca.repere.core

/** Shared domain representation used by the phone, watch and synchronization layer. */
data class Drink(
    val serverId: Long?,
    val clientId: String,
    val name: String,
    val type: String?,
    val volumeMl: Double,
    val abvPercent: Double,
    val quantity: Int,
    val startedAt: String,
    val durationMinutes: Int,
    val notes: String?,
    val active: Boolean,
)

data class Preset(
    val serverId: Long,
    val name: String,
    val type: String,
    val volumeMl: Double,
    val abvPercent: Double,
)

enum class PendingOperation { CREATE, UPDATE, DELETE }
