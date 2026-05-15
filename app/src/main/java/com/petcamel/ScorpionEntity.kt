package com.petcamel

import kotlin.math.*

class ScorpionEntity(var x: Float, var y: Float) {

    enum class State { WALKING, RESTING }

    var state = State.RESTING
    var facingLeft = false
    var walkPhase = 0f
    var isMoving = false

    val speed = 0.40f

    private var targetX = x
    private var targetY = y
    private var restTimer = (Math.random() * 5f + 2f).toFloat()

    private var stuckTimer = 0f
    private var lastX = x; private var lastY = y

    fun update(dt: Float, world: GameWorld) {
        when (state) {
            State.RESTING -> {
                isMoving = false
                restTimer -= dt
                if (restTimer <= 0f) {
                    if (pickTarget(world)) state = State.WALKING
                    else restTimer = 2f
                }
            }
            State.WALKING -> {
                val dx = targetX - x; val dy = targetY - y
                val d = sqrt(dx * dx + dy * dy)
                if (d < 0.35f) {
                    isMoving = false
                    state = State.RESTING
                    restTimer = (Math.random() * 7f + 3f).toFloat()
                } else {
                    val ndx = dx / d; val ndy = dy / d
                    val moved = moveWithCollision(ndx * speed * dt, ndy * speed * dt, world)
                    if (moved) {
                        facingLeft = ndx < 0f
                        isMoving = true
                        walkPhase += dt * speed * 12f
                    } else {
                        state = State.RESTING; restTimer = 1.5f
                    }
                }
                stuckTimer += dt
                if (stuckTimer >= 2.5f) {
                    val dd = sqrt((x - lastX).pow(2) + (y - lastY).pow(2))
                    if (dd < 0.08f) { state = State.RESTING; restTimer = 2f }
                    lastX = x; lastY = y; stuckTimer = 0f
                }
            }
        }
    }

    private fun pickTarget(world: GameWorld): Boolean {
        repeat(30) {
            val angle = Math.random() * Math.PI * 2
            val r = 1.5f + (Math.random() * 6f).toFloat()
            val tx = (x + cos(angle).toFloat() * r).toInt()
            val ty = (y + sin(angle).toFloat() * r).toInt()
            if (tx in 1..(world.width - 2) && ty in 1..(world.height - 2) &&
                world.isWalkable(tx, ty)) {
                targetX = tx.toFloat(); targetY = ty.toFloat(); return true
            }
        }
        return false
    }

    private fun moveWithCollision(dx: Float, dy: Float, world: GameWorld): Boolean {
        val h = 0.16f; var moved = false
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
