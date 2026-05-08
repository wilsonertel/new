package com.petcamel

import kotlin.math.*

class WanderCamel(var x: Float, var y: Float, val name: String = "") {

    enum class State { WANDERING, RESTING, PLAYING }
    enum class HerdRole { LEADER, FOLLOWER, SCOUT, GUARDIAN, TROUBLEMAKER }

    var state = State.WANDERING
    var herdRole = HerdRole.FOLLOWER
    var facingLeft = false
    var walkPhase = 0f
    var isMoving = false
    val speed = 2.0f

    var bondCount = 0
    var followTimer = 0f

    private var targetX = x
    private var targetY = y
    private var restTimer = 0f

    // Stuck detection
    private var lastX = x; private var lastY = y
    private var stuckCheckTimer = 0f

    // Play state
    var playTimer = 0f
    var playAngle = 0f
    var playCX = 0f; var playCY = 0f
    val playDuration = 6f
    val playRadius = 1.5f

    // 30-second cooldown so the same pair can't immediately re-trigger
    var playCooldown = 0f

    fun startPlaying(cx: Float, cy: Float) {
        state = State.PLAYING
        playTimer = playDuration
        playCX = cx; playCY = cy
        playAngle = atan2(y - cy, x - cx)
        playCooldown = 30f
    }

    fun update(dt: Float, world: GameWorld, playerX: Float = -1f, playerY: Float = -1f) {
        playCooldown -= dt
        if (followTimer > 0f) followTimer -= dt

        when (state) {
            State.PLAYING  -> updatePlay(dt, world)
            State.RESTING  -> {
                isMoving = false
                restTimer -= dt
                if (restTimer <= 0f) { state = State.WANDERING; pickTarget(world) }
            }
            State.WANDERING -> {
                // If following player and player coords are valid
                if (followTimer > 0f && playerX >= 0f && playerY >= 0f) {
                    targetX = playerX
                    targetY = playerY
                }
                updateWander(dt, world)
            }
        }
    }

    private fun updatePlay(dt: Float, world: GameWorld) {
        playTimer -= dt
        if (playTimer <= 0f) { state = State.WANDERING; pickTarget(world); return }
        playAngle += dt * 2.8f
        x = playCX + cos(playAngle).toFloat() * playRadius
        y = playCY + sin(playAngle).toFloat() * playRadius
        facingLeft = cos(playAngle) < 0f
        isMoving = true
        walkPhase += dt * 5f
    }

    private fun updateWander(dt: Float, world: GameWorld) {
        val dx = targetX - x; val dy = targetY - y
        val dist = sqrt(dx * dx + dy * dy)

        if (dist < 0.5f) {
            isMoving = false
            val loc = world.getLocationAt(x, y)
            if (loc?.type == LocationType.OASIS) {
                state = State.RESTING; restTimer = 10f
            } else {
                pickTarget(world)
            }
            return
        }

        val ndx = dx / dist; val ndy = dy / dist
        val moved = moveWithCollision(ndx * speed * dt, ndy * speed * dt, world)
        if (moved) {
            facingLeft = ndx < 0f
            isMoving = true
            walkPhase += dt * speed * 3f
        }

        stuckCheckTimer += dt
        if (stuckCheckTimer >= 2f) {
            val d = sqrt((x - lastX).pow(2) + (y - lastY).pow(2))
            if (d < 0.15f) pickTarget(world)
            lastX = x; lastY = y; stuckCheckTimer = 0f
        }
    }

    fun pickTarget(world: GameWorld) {
        val candidates = world.locations.filter { loc ->
            val dx = loc.tileX - x; val dy = loc.tileY - y
            sqrt(dx * dx + dy * dy) > 8f
        }
        val target = if (candidates.isNotEmpty()) candidates.random() else world.locations.random()
        val angle = Math.random() * Math.PI * 2
        targetX = target.tileX + cos(angle).toFloat() * 3.5f
        targetY = target.tileY + sin(angle).toFloat() * 3.5f
    }

    private fun moveWithCollision(dx: Float, dy: Float, world: GameWorld): Boolean {
        val h = 0.3f
        var moved = false
        val nx = x + dx
        if (world.isWalkable((nx - h).toInt(), y.toInt()) &&
            world.isWalkable((nx + h).toInt(), y.toInt()) &&
            world.isWalkable((nx - h).toInt(), (y + h).toInt()) &&
            world.isWalkable((nx + h).toInt(), (y + h).toInt())) {
            x = nx.coerceIn(h, world.width - h); moved = true
        }
        val ny = y + dy
        if (world.isWalkable(x.toInt(), (ny - h).toInt()) &&
            world.isWalkable(x.toInt(), (ny + h).toInt()) &&
            world.isWalkable((x + h).toInt(), (ny - h).toInt()) &&
            world.isWalkable((x + h).toInt(), (ny + h).toInt())) {
            y = ny.coerceIn(h, world.height - h); moved = true
        }
        return moved
    }
}
