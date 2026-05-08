package com.petcamel

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class CamelEntity(var x: Float = 40f, var y: Float = 38f) {

    enum class Direction { UP, DOWN, LEFT, RIGHT }

    var direction = Direction.DOWN
    var isMoving = false
    var walkPhase = 0f

    val playerSpeed = 4.5f
    val wanderSpeed = 2.2f

    var autoWandering = false
    private var wanderTargetX = 0f
    private var wanderTargetY = 0f
    private var waitAtDestTimer = 0f
    private var arrivedAtDest = false

    fun update(dt: Float, inputDx: Float, inputDy: Float, world: GameWorld) {
        if (inputDx != 0f || inputDy != 0f) {
            autoWandering = false
            arrivedAtDest = false
            val moved = moveWithCollision(inputDx * playerSpeed * dt, inputDy * playerSpeed * dt, world)
            isMoving = moved
            if (moved) {
                when {
                    abs(inputDx) >= abs(inputDy) ->
                        direction = if (inputDx > 0) Direction.RIGHT else Direction.LEFT
                    else ->
                        direction = if (inputDy > 0) Direction.DOWN else Direction.UP
                }
                walkPhase += dt * playerSpeed * 3f
            }
            return
        }

        if (autoWandering) {
            val dx = wanderTargetX - x
            val dy = wanderTargetY - y
            val dist = sqrt(dx * dx + dy * dy)

            if (dist < 0.4f) {
                isMoving = false
                if (!arrivedAtDest) { arrivedAtDest = true; waitAtDestTimer = 2.5f }
                waitAtDestTimer -= dt
                if (waitAtDestTimer <= 0f) pickNewWanderTarget(world)
            } else {
                val ndx = dx / dist
                val ndy = dy / dist
                val moved = moveWithCollision(ndx * wanderSpeed * dt, ndy * wanderSpeed * dt, world)
                isMoving = moved
                if (moved) {
                    when {
                        abs(ndx) >= abs(ndy) ->
                            direction = if (ndx > 0) Direction.RIGHT else Direction.LEFT
                        else ->
                            direction = if (ndy > 0) Direction.DOWN else Direction.UP
                    }
                    walkPhase += dt * wanderSpeed * 3f
                } else {
                    // Stuck — pick a new target
                    pickNewWanderTarget(world)
                }
            }
        } else {
            isMoving = false
        }
    }

    fun startAutoWander(world: GameWorld) {
        autoWandering = true
        arrivedAtDest = false
        pickNewWanderTarget(world)
    }

    private fun pickNewWanderTarget(world: GameWorld) {
        arrivedAtDest = false
        val candidates = world.locations.filter { loc ->
            val dx = loc.tileX - x; val dy = loc.tileY - y
            sqrt(dx * dx + dy * dy) > 10f
        }
        val target = if (candidates.isNotEmpty()) candidates.random() else world.locations.random()
        // Offset 3.5 tiles from centre so we never aim directly at oasis water
        val angle = Math.random() * Math.PI * 2
        wanderTargetX = target.tileX + cos(angle).toFloat() * 3.5f
        wanderTargetY = target.tileY + sin(angle).toFloat() * 3.5f
    }

    private fun moveWithCollision(dx: Float, dy: Float, world: GameWorld): Boolean {
        val half = 0.32f
        var moved = false

        val nx = x + dx
        if (world.isWalkable((nx - half).toInt(), y.toInt()) &&
            world.isWalkable((nx + half).toInt(), y.toInt()) &&
            world.isWalkable((nx - half).toInt(), (y + half).toInt()) &&
            world.isWalkable((nx + half).toInt(), (y + half).toInt())) {
            x = nx.coerceIn(half, world.width - half)
            moved = true
        }

        val ny = y + dy
        if (world.isWalkable(x.toInt(), (ny - half).toInt()) &&
            world.isWalkable(x.toInt(), (ny + half).toInt()) &&
            world.isWalkable((x + half).toInt(), (ny - half).toInt()) &&
            world.isWalkable((x + half).toInt(), (ny + half).toInt())) {
            y = ny.coerceIn(half, world.height - half)
            moved = true
        }

        return moved
    }
}
