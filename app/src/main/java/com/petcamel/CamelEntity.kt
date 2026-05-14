package com.petcamel

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class CamelEntity(var x: Float = 40f, var y: Float = 38f) {

    enum class Direction { UP, DOWN, LEFT, RIGHT }

    var direction = Direction.DOWN
    var facingX = 0f   // world-space facing direction (normalized)
    var facingY = 1f   // starts facing south (+Y)
    var isMoving = false
    var walkPhase = 0f

    val playerSpeed = 4.5f
    val wanderSpeed = 2.2f

    // Updated each frame by GameSurfaceView based on current love (0.0–1.0)
    var loveRatio  = 1.0f
    var speedBoost = 1.0f   // set by ability system (Dash, SandGlide)

    private fun effectivePlayerSpeed() = playerSpeed * (0.15f + 0.85f * loveRatio) * speedBoost
    private fun effectiveWanderSpeed() = wanderSpeed * (0.15f + 0.85f * loveRatio)

    var autoWandering = false
    private var wanderTargetX = 0f
    private var wanderTargetY = 0f
    private var waitAtDestTimer = 0f
    private var arrivedAtDest = false

    private var stuckCheckTimer = 0f
    private var lastWanderX = x
    private var lastWanderY = y

    fun update(dt: Float, inputDx: Float, inputDy: Float, world: GameWorld) {
        if (inputDx != 0f || inputDy != 0f) {
            autoWandering = false
            arrivedAtDest = false
            val moved = moveWithCollision(inputDx * effectivePlayerSpeed() * dt, inputDy * effectivePlayerSpeed() * dt, world)
            isMoving = moved
            if (moved) {
                val len = sqrt(inputDx * inputDx + inputDy * inputDy).coerceAtLeast(0.001f)
                facingX = inputDx / len; facingY = inputDy / len
                when {
                    abs(inputDx) >= abs(inputDy) ->
                        direction = if (inputDx > 0) Direction.RIGHT else Direction.LEFT
                    else ->
                        direction = if (inputDy > 0) Direction.DOWN else Direction.UP
                }
                walkPhase += dt * effectivePlayerSpeed() * 3f
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
                if (waitAtDestTimer <= 0f) { arrivedAtDest = false; pickNewWanderTarget(world) }
            } else {
                val ndx = dx / dist
                val ndy = dy / dist
                val moved = moveWithCollision(ndx * effectiveWanderSpeed() * dt, ndy * effectiveWanderSpeed() * dt, world)
                isMoving = moved
                if (moved) {
                    facingX = ndx; facingY = ndy
                    when {
                        abs(ndx) >= abs(ndy) ->
                            direction = if (ndx > 0) Direction.RIGHT else Direction.LEFT
                        else ->
                            direction = if (ndy > 0) Direction.DOWN else Direction.UP
                    }
                    walkPhase += dt * effectiveWanderSpeed() * 3f
                }
                stuckCheckTimer += dt
                if (stuckCheckTimer >= 2f) {
                    val d = sqrt((x - lastWanderX).pow(2) + (y - lastWanderY).pow(2))
                    if (d < 0.15f) pickNewWanderTarget(world)
                    lastWanderX = x; lastWanderY = y; stuckCheckTimer = 0f
                }
            }
        } else {
            isMoving = false
        }
    }

    fun redirectWanderTarget(x: Float, y: Float) {
        wanderTargetX = x; wanderTargetY = y
        arrivedAtDest = false
    }

    fun startAutoWander(world: GameWorld) {
        autoWandering = true
        arrivedAtDest = false
        stuckCheckTimer = 0f
        lastWanderX = x; lastWanderY = y
        pickNewWanderTarget(world)
    }

    private fun pickNewWanderTarget(world: GameWorld) {
        arrivedAtDest = false
        val candidates = world.locations.filter { loc ->
            val dx = loc.tileX - x; val dy = loc.tileY - y
            sqrt(dx * dx + dy * dy) > 10f
        }
        val target = if (candidates.isNotEmpty()) candidates.random() else world.locations.random()
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
