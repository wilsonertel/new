package com.petcamel

enum class LocationType { OASIS, VILLAGE, PYRAMID, STABLE }

data class WorldLocation(
    val name: String,
    val type: LocationType,
    val tileX: Int,
    val tileY: Int,
    val arrivalRadius: Float = 4.5f
)
