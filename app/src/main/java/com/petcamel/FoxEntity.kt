package com.petcamel

import kotlin.math.*

class FoxEntity(var x: Float, var y: Float) {

    enum class State { HIDING, WATCHING, APPROACHING, SNIFFING, FLEEING }

    var state = State.HIDING
    var facingLeft = false
    var walkPhase = 0f
    var isMoving = false

    // Sniff head bob (0..1)
    var sniffBob = 0f

    private val hideSpeed  = 2.8f
    private val walkSpeed  = 0.9f
    private val fleeSpeed  = 4.2f

    private var targetX = x
    private var targetY = y

    // Timers
    private var watchTimer  = 0f   // how long fox has been watching
    private var pauseTimer  = 0f   // pause mid-approach
    private var sniffTimer  = 0f   // total sniff duration
    private var fleeTimer   = 0f   // ensures fox fully reaches hide spot

    // Approach rhythm: take a few steps then pause
    private var approachStepTimer  = 0f
    private var approachPauseTimer = 0f

    // How long player must be still before fox becomes curious
    private var playerStillTimer = 0f
    private var playerLastX = x
    private var playerLastY = y

    // Stuck detection
    private var stuckTimer = 0f
    private var lastX = x; private var lastY = y

    fun update(dt: Float, world: GameWorld, playerX: Float, playerY: Float, playerMoving: Boolean) {
        // Track how long player hasn't moved (for WATCHING→APPROACHING trigger)
        val pd = sqrt((playerX - playerLastX).pow(2) + (playerY - playerLastY).pow(2))
        if (pd > 0.2f) { playerStillTimer = 0f; playerLastX = playerX; playerLastY = playerY }
        else playerStillTimer += dt

        val toPX = playerX - x; val toPY = playerY - y
        val distToPlayer = sqrt(toPX * toPX + toPY * toPY)

        when (state) {
            State.HIDING -> {
                // Move toward hide target
                moveTowardTarget(dt, world, hideSpeed)

                // Once arrived and far enough, start watching if player is reasonably close
                val toDist = sqrt((targetX - x).pow(2) + (targetY - y).pow(2))
                if (toDist < 0.6f) {
                    isMoving = false
                    if (distToPlayer in 8f..22f) {
                        state = State.WATCHING
                        watchTimer = 0f
                        facingLeft = playerX < x
                    } else if (distToPlayer < 8f) {
                        // Too close — flee further
                        pickHidingSpot(world, playerX, playerY, minDist = 16f)
                    }
                }

                // Always flee if player gets too close while hiding
                if (distToPlayer < 5f) {
                    state = State.FLEEING
                    fleeTimer = 0f
                    pickHidingSpot(world, playerX, playerY, minDist = 18f)
                }
            }

            State.WATCHING -> {
                isMoving = false
                facingLeft = playerX < x

                // If player moves close while fox watches, flee
                if (distToPlayer < 4f || (distToPlayer < 7f && playerMoving)) {
                    state = State.FLEEING
                    fleeTimer = 0f
                    pickHidingSpot(world, playerX, playerY, minDist = 18f)
                    return
                }

                // If player leaves range, go back to hiding
                if (distToPlayer > 25f) {
                    state = State.HIDING
                    pickHidingSpot(world, playerX, playerY, minDist = 12f)
                    return
                }

                watchTimer += dt
                // After player has been still ~5-8s and fox has been watching at least 3s, approach
                if (playerStillTimer > 5f && watchTimer > 3f && distToPlayer > 6f) {
                    state = State.APPROACHING
                    approachStepTimer  = 1.2f + (Math.random() * 0.8f).toFloat()
                    approachPauseTimer = 0f
                }
            }

            State.APPROACHING -> {
                // If player moves, flee
                if (playerMoving && distToPlayer < 14f) {
                    state = State.FLEEING
                    fleeTimer = 0f
                    pickHidingSpot(world, playerX, playerY, minDist = 18f)
                    return
                }

                if (distToPlayer < 1.8f) {
                    // Reached player — start sniffing
                    state = State.SNIFFING
                    sniffTimer = 5f + (Math.random() * 4f).toFloat()
                    walkPhase = 0f
                    isMoving = false
                    return
                }

                if (approachPauseTimer > 0f) {
                    // Pausing — face player and wait
                    approachPauseTimer -= dt
                    isMoving = false
                    facingLeft = playerX < x
                } else {
                    // Walk a few steps toward player
                    approachStepTimer -= dt
                    targetX = playerX; targetY = playerY
                    moveTowardTarget(dt, world, walkSpeed)
                    if (approachStepTimer <= 0f) {
                        approachPauseTimer = 0.8f + (Math.random() * 1.2f).toFloat()
                        approachStepTimer  = 1.0f + (Math.random() * 0.8f).toFloat()
                    }
                }
            }

            State.SNIFFING -> {
                isMoving = true
                sniffTimer -= dt
                sniffBob = abs(sin(walkPhase * 2.5f)) * 0.12f
                walkPhase += dt * 1.8f

                // Slow orbit around player
                val sniffAngle = atan2(y - playerY, x - playerX) + dt * 0.55f
                val sniffR = 1.6f
                val nx = playerX + cos(sniffAngle) * sniffR
                val ny = playerY + sin(sniffAngle) * sniffR
                val tdx = nx - x; val tdy = ny - y
                val td = sqrt(tdx * tdx + tdy * tdy).coerceAtLeast(0.001f)
                moveWithCollision(tdx / td * walkSpeed * 0.8f * dt, tdy / td * walkSpeed * 0.8f * dt, world)
                facingLeft = tdx < 0f   // face the orbit direction, not the player

                // If player moves, flee
                if (playerMoving) {
                    state = State.FLEEING
                    fleeTimer = 0f
                    pickHidingSpot(world, playerX, playerY, minDist = 18f)
                    return
                }

                if (sniffTimer <= 0f) {
                    // Done sniffing — flee
                    state = State.FLEEING
                    fleeTimer = 0f
                    pickHidingSpot(world, playerX, playerY, minDist = 20f)
                }
            }

            State.FLEEING -> {
                isMoving = true
                moveTowardTarget(dt, world, fleeSpeed)
                fleeTimer += dt
                val toDist = sqrt((targetX - x).pow(2) + (targetY - y).pow(2))
                if (toDist < 0.6f || fleeTimer > 8f) {
                    state = State.HIDING
                    watchTimer = 0f
                    isMoving = false
                }
            }
        }

        // Stuck detection
        stuckTimer += dt
        if (stuckTimer >= 2.5f) {
            val d = sqrt((x - lastX).pow(2) + (y - lastY).pow(2))
            if (d < 0.1f && (state == State.HIDING || state == State.FLEEING)) {
                pickHidingSpot(world, playerX, playerY, minDist = 12f)
            }
            lastX = x; lastY = y; stuckTimer = 0f
        }
    }

    fun pickHidingSpot(world: GameWorld, playerX: Float, playerY: Float, minDist: Float = 12f) {
        val rng = java.util.Random()
        repeat(40) {
            val tx = 5 + rng.nextInt(world.width - 10)
            val ty = 5 + rng.nextInt(world.height - 10)
            if (!world.isWalkable(tx, ty)) return@repeat
            val dp = sqrt((tx - playerX).pow(2) + (ty - playerY).pow(2))
            val df = sqrt((tx - x).pow(2) + (ty - y).pow(2))
            if (dp >= minDist && df > 4f) {
                targetX = tx.toFloat(); targetY = ty.toFloat(); return
            }
        }
        // Fallback: any walkable spot far from player
        repeat(20) {
            val tx = 5 + rng.nextInt(world.width - 10)
            val ty = 5 + rng.nextInt(world.height - 10)
            if (world.isWalkable(tx, ty)) { targetX = tx.toFloat(); targetY = ty.toFloat(); return }
        }
    }

    private fun moveTowardTarget(dt: Float, world: GameWorld, speed: Float) {
        val dx = targetX - x; val dy = targetY - y
        val d = sqrt(dx * dx + dy * dy)
        if (d < 0.5f) { isMoving = false; return }
        val ndx = dx / d; val ndy = dy / d
        moveWithCollision(ndx * speed * dt, ndy * speed * dt, world)
        facingLeft = ndx < 0f
        isMoving = true
        walkPhase += dt * speed * 3.5f
    }

    private fun moveWithCollision(dx: Float, dy: Float, world: GameWorld): Boolean {
        val h = 0.22f; var moved = false
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
