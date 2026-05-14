package com.petcamel

object Tile {
    const val SAND           = 0
    const val DEEP_SAND      = 1
    const val WATER          = 2
    const val GRASS          = 3
    const val STONE_PATH     = 4
    const val BUILDING       = 5
    const val PYRAMID        = 6
    const val PYRAMID_STEPS  = 7
    const val PALM           = 8
    const val CACTUS         = 9
    const val DUNE           = 10
    const val BUILDING_FRONT = 11

    fun isWalkable(tile: Int) = tile != WATER && tile != BUILDING && tile != BUILDING_FRONT && tile != PALM
        && tile != PYRAMID && tile != PYRAMID_STEPS
}
