package com.petcamel

import kotlin.math.*

class NpcEntity(
    var x: Float, var y: Float,
    val villageX: Int, val villageY: Int,
    val type: Type,
    val npcId: Int = 0
) {
    enum class Type { PETTER, FEEDER }
    enum class State { WALKING, WAITING, STOPPED }

    var state = State.WAITING
    var facingLeft = false
    var facingDX = 1f; var facingDY = 0f   // world-space facing (normalized)
    var walkPhase = 0f
    var isMoving = false
    val speed = 1.0f

    private val wanderRadius = 4f
    private var targetX = x
    private var targetY = y
    private var waitTimer = (Math.random() * 3f).toFloat() + 1f

    private var stuckTimer = 0f
    private var lastX = x; private var lastY = y

    var interactCooldown = 0f
    var showInteractTimer = 0f

    fun update(dt: Float, world: GameWorld, playerX: Float, playerY: Float, playerMoving: Boolean, isNight: Boolean = false): Boolean {
        interactCooldown -= dt
        showInteractTimer -= dt

        // During night NPCs stop completely
        if (isNight) {
            state = State.STOPPED
            isMoving = false
            return false
        }

        val pdx = playerX - x; val pdy = playerY - y
        val playerDist = sqrt(pdx * pdx + pdy * pdy)

        if (playerDist < 2.2f) {
            state = State.STOPPED
            isMoving = false
            facingLeft = playerX < x
            if (playerDist < 1.6f && !playerMoving && interactCooldown <= 0f) {
                interactCooldown = 6f
                showInteractTimer = 1.8f
                return true
            }
            return false
        }

        when (state) {
            State.STOPPED, State.WAITING -> {
                isMoving = false
                waitTimer -= dt
                if (waitTimer <= 0f) {
                    pickTarget(world)
                    state = State.WALKING
                }
            }
            State.WALKING -> {
                val dx = targetX - x; val dy = targetY - y
                val dist = sqrt(dx * dx + dy * dy)
                if (dist < 0.4f) {
                    state = State.WAITING
                    waitTimer = 2f + (Math.random() * 3f).toFloat()
                    isMoving = false
                } else {
                    val ndx = dx / dist; val ndy = dy / dist
                    val moved = moveWithCollision(ndx * speed * dt, ndy * speed * dt, world)
                    if (moved) {
                        facingLeft = ndx < 0f
                        facingDX = ndx; facingDY = ndy
                        isMoving = true
                        walkPhase += dt * speed * 5f
                    } else {
                        state = State.WAITING; waitTimer = 1f
                    }
                    stuckTimer += dt
                    if (stuckTimer >= 2f) {
                        val d = sqrt((x - lastX).pow(2) + (y - lastY).pow(2))
                        if (d < 0.1f) { state = State.WAITING; waitTimer = 1f }
                        lastX = x; lastY = y; stuckTimer = 0f
                    }
                }
            }
        }
        return false
    }

    fun pickTarget(world: GameWorld) {
        repeat(20) {
            val angle = Math.random() * Math.PI * 2
            val r = (0.8f + Math.random() * wanderRadius).toFloat()
            val tx = villageX + cos(angle).toFloat() * r
            val ty = villageY + sin(angle).toFloat() * r
            if (world.isWalkable(tx.toInt(), ty.toInt()) &&
                world.isWalkable(tx.toInt(), (ty + 0.3f).toInt())) {
                targetX = tx; targetY = ty; return
            }
        }
    }

    private fun moveWithCollision(dx: Float, dy: Float, world: GameWorld): Boolean {
        val h = 0.28f; var moved = false
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
