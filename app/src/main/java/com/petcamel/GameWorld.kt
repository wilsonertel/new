package com.petcamel

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random

class GameWorld {
    val width = 80
    val height = 80
    val tiles = Array(height) { IntArray(width) { Tile.SAND } }

    val locations = listOf(
        WorldLocation("Oasis of Dawn",        LocationType.OASIS,   12, 12),
        WorldLocation("Hidden Palms",          LocationType.OASIS,   45,  8),
        WorldLocation("Mirage Pool",           LocationType.OASIS,   68, 22),
        WorldLocation("Ancient Waters",        LocationType.OASIS,   72, 58),
        WorldLocation("Sunset Oasis",          LocationType.OASIS,   50, 70),
        WorldLocation("Desert Rose",           LocationType.OASIS,   22, 68),
        WorldLocation("River Oasis",           LocationType.OASIS,    8, 42),
        WorldLocation("Heart of the Sahara",   LocationType.OASIS,   40, 38),
        WorldLocation("Al-Fayyum",             LocationType.VILLAGE, 28, 22),
        WorldLocation("Kharga",                LocationType.VILLAGE, 58, 44),
        WorldLocation("Siwa",                  LocationType.VILLAGE, 18, 58),
        WorldLocation("Pyramid of Giza",       LocationType.PYRAMID, 62, 14),
        WorldLocation("Red Pyramid",           LocationType.PYRAMID, 34, 52)
    )

    val oases get() = locations.filter { it.type == LocationType.OASIS }

    private val rng = Random(42)

    init {
        generateTerrain()
        placeAllOases()
        placeAllVillages()
        placeAllPyramids()
        scatterDetails()
    }

    private fun generateTerrain() {
        for (y in 0 until height) for (x in 0 until width) {
            tiles[y][x] = if (rng.nextFloat() < 0.22f) Tile.DEEP_SAND else Tile.SAND
        }
        repeat(12) {
            val sx = rng.nextInt(width)
            val sy = rng.nextInt(height)
            val len = rng.nextInt(12) + 6
            for (j in 0 until len) {
                val tx = (sx + j).coerceIn(0, width - 1)
                val ty = (sy + (rng.nextInt(3) - 1)).coerceIn(0, height - 1)
                tiles[ty][tx] = Tile.DUNE
            }
        }
    }

    private fun placeOasis(cx: Int, cy: Int) {
        val waterR = 2
        val grassR = 5
        for (dy in -grassR..grassR) for (dx in -grassR..grassR) {
            val x = cx + dx; val y = cy + dy
            if (!inBounds(x, y)) continue
            val dist = sqrt((dx * dx + dy * dy).toDouble())
            when {
                dist <= waterR -> tiles[y][x] = Tile.WATER
                dist <= grassR -> if (tiles[y][x] != Tile.WATER) tiles[y][x] = Tile.GRASS
            }
        }
        // palm trees on grass ring, away from water
        for (dy in -(grassR - 1)..(grassR - 1)) for (dx in -(grassR - 1)..(grassR - 1)) {
            val x = cx + dx; val y = cy + dy
            if (!inBounds(x, y)) continue
            val dist = sqrt((dx * dx + dy * dy).toDouble())
            if (dist > waterR + 1 && dist < grassR - 0.5 && tiles[y][x] == Tile.GRASS && rng.nextFloat() < 0.14f) {
                tiles[y][x] = Tile.PALM
            }
        }
    }

    private fun placeAllOases() = oases.forEach { placeOasis(it.tileX, it.tileY) }

    private fun placeVillage(cx: Int, cy: Int) {
        val r = 6
        // stone path cross + diagonals
        for (i in -r..r) {
            set(cx + i, cy, Tile.STONE_PATH)
            set(cx, cy + i, Tile.STONE_PATH)
        }
        for (i in -3..3) {
            set(cx + i, cy + i, Tile.STONE_PATH)
            set(cx + i, cy - i, Tile.STONE_PATH)
        }
        // buildings in each quadrant
        val offsets = listOf(
            -4 to -4, -4 to -2, -2 to -4,
            4 to -4, 4 to -2, 2 to -4,
            -4 to 2, -4 to 4, -2 to 4,
            4 to 2, 4 to 4, 2 to 4
        )
        offsets.forEach { (dx, dy) ->
            for (by in 0..1) for (bx in 0..1) set(cx + dx + bx, cy + dy + by, Tile.BUILDING)
        }
    }

    private fun placeAllVillages() =
        locations.filter { it.type == LocationType.VILLAGE }.forEach { placeVillage(it.tileX, it.tileY) }

    private fun placePyramid(cx: Int, cy: Int) {
        for (dy in -5..5) for (dx in -5..5) {
            val x = cx + dx; val y = cy + dy
            if (!inBounds(x, y)) continue
            val layer = maxOf(abs(dx), abs(dy))
            tiles[y][x] = if (layer <= 1) Tile.PYRAMID else Tile.PYRAMID_STEPS
        }
    }

    private fun placeAllPyramids() =
        locations.filter { it.type == LocationType.PYRAMID }.forEach { placePyramid(it.tileX, it.tileY) }

    private fun scatterDetails() {
        for (y in 0 until height) for (x in 0 until width) {
            if (tiles[y][x] == Tile.SAND && rng.nextFloat() < 0.013f) tiles[y][x] = Tile.CACTUS
        }
    }

    fun getTile(x: Int, y: Int) = if (inBounds(x, y)) tiles[y][x] else Tile.SAND

    fun isWalkable(x: Int, y: Int) = inBounds(x, y) && Tile.isWalkable(getTile(x, y))

    fun getLocationAt(x: Float, y: Float): WorldLocation? =
        locations.firstOrNull { loc ->
            val dx = loc.tileX - x; val dy = loc.tileY - y
            sqrt((dx * dx + dy * dy).toDouble()) < loc.arrivalRadius
        }

    private fun inBounds(x: Int, y: Int) = x in 0 until width && y in 0 until height
    private fun set(x: Int, y: Int, tile: Int) { if (inBounds(x, y)) tiles[y][x] = tile }
}
