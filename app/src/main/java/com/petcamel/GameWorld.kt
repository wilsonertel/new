package com.petcamel

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random

class GameWorld {
    val width = 160
    val height = 160
    val tiles = Array(height) { IntArray(width) { Tile.SAND } }

    val locations = listOf(
        WorldLocation("Oasis of Dawn",        LocationType.OASIS,    24,  24),
        WorldLocation("Hidden Palms",          LocationType.OASIS,    90,  16),
        WorldLocation("Mirage Pool",           LocationType.OASIS,   136,  44),
        WorldLocation("Ancient Waters",        LocationType.OASIS,   144, 116),
        WorldLocation("Sunset Oasis",          LocationType.OASIS,   100, 140),
        WorldLocation("Desert Rose",           LocationType.OASIS,    44, 136),
        WorldLocation("River Oasis",           LocationType.OASIS,    16,  84),
        WorldLocation("Heart of the Sahara",   LocationType.OASIS,    80,  76),
        WorldLocation("Al-Fayyum",             LocationType.VILLAGE,  56,  44),
        WorldLocation("Kharga",                LocationType.VILLAGE, 116,  88),
        WorldLocation("Siwa",                  LocationType.VILLAGE,  36, 116),
        WorldLocation("Pyramid of Giza",       LocationType.PYRAMID, 124,  28),
        WorldLocation("Red Pyramid",           LocationType.PYRAMID,  68, 104),
        WorldLocation("Desert Stable",         LocationType.STABLE,  110,  70, arrivalRadius = 3.5f)
    )

    val oases get() = locations.filter { it.type == LocationType.OASIS }

    private val rng = Random(42)

    init {
        generateTerrain()
        placeAllOases()
        placeAllVillages()
        placeAllPyramids()
        scatterDetails()
        placeAllStables()
    }

    private fun generateTerrain() {
        for (y in 0 until height) for (x in 0 until width) {
            tiles[y][x] = if (rng.nextFloat() < 0.22f) Tile.DEEP_SAND else Tile.SAND
        }
        repeat(48) {
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
            for (bx in 0..1) {
                set(cx + dx + bx, cy + dy,     Tile.BUILDING)
                set(cx + dx + bx, cy + dy + 1, Tile.BUILDING_FRONT)
            }
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

    private fun placeStable(cx: Int, cy: Int) {
        // Walkable interior floor (5×5) and approach path (open south entrance)
        for (dy in -2..2) for (dx in -2..2) set(cx + dx, cy + dy, Tile.STONE_PATH)
        for (dx in -1..1) set(cx + dx, cy - 3, Tile.STONE_PATH)
        // 3-sided walls — back, left, right; south front left open for entry
        for (dx in -3..3) set(cx + dx, cy + 3, Tile.BUILDING)
        for (dy in -2..3) set(cx - 3, cy + dy, Tile.BUILDING)
        for (dy in -2..3) set(cx + 3, cy + dy, Tile.BUILDING)
    }

    private fun placeAllStables() =
        locations.filter { it.type == LocationType.STABLE }.forEach { placeStable(it.tileX, it.tileY) }

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
