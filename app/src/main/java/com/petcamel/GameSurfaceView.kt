package com.petcamel

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import kotlin.math.*

class GameSurfaceView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : SurfaceView(context, attrs), SurfaceHolder.Callback, Runnable {

    // ── State ──────────────────────────────────────────────────────────────────
    private val stateManager = CamelStateManager(context)
    @Volatile var camelState = stateManager.load()

    private val world = GameWorld()
    private val camel = CamelEntity(40f, 30f)

    // ── Wandering NPC camels ───────────────────────────────────────────────────
    private val wanderCamels = listOf(
        WanderCamel(16f, 14f),
        WanderCamel(49f, 12f),
        WanderCamel(65f, 28f),
        WanderCamel(24f, 66f)
    ).also { list -> list.forEach { it.pickTarget(world) } }

    // ── NPCs (2 per village) ───────────────────────────────────────────────────
    data class Npc(val tx: Float, val ty: Float, val left: Boolean)
    private val npcs = world.locations
        .filter { it.type == LocationType.VILLAGE }
        .flatMap { loc ->
            listOf(
                Npc(loc.tileX + 2.5f, loc.tileY + 1.5f, false),
                Npc(loc.tileX - 2.5f, loc.tileY - 1.0f, true)
            )
        }

    // ── Camera ─────────────────────────────────────────────────────────────────
    private var tileSize = 64f
    private var camX = 0f; private var camY = 0f

    // ── Game loop ──────────────────────────────────────────────────────────────
    private var gameThread: Thread? = null
    @Volatile private var running = false

    // ── Input ──────────────────────────────────────────────────────────────────
    @Volatile private var inputDx = 0f
    @Volatile private var inputDy = 0f
    private var lastInputMs = System.currentTimeMillis()
    private val autoWanderAfterMs = 30_000L

    // ── Player play state ──────────────────────────────────────────────────────
    private var playerPlaying = false
    private var playerPlayTimer = 0f
    private var playerPlayAngle = 0f
    private var playerPlayCX = 0f; private var playerPlayCY = 0f
    private val playDuration = 6f
    private val playRadius = 1.5f

    // ── HUD / UI state ─────────────────────────────────────────────────────────
    private var locationLabel = ""
    @Volatile private var locationLabelTimer = 0f
    private var currentLocation: WorldLocation? = null
    private var camelFacingLeft = false
    private var dpadUp = false; private var dpadDown = false
    private var dpadLeft = false; private var dpadRight = false
    private var dpadCX = 0f; private var dpadCY = 0f; private var dpadR = 0f

    // ── Minimap ────────────────────────────────────────────────────────────────
    private var minimapBmp: Bitmap? = null

    // ── Tile base colours ──────────────────────────────────────────────────────
    private val tileColors = mapOf(
        Tile.SAND          to Color.rgb(224, 214, 176),
        Tile.DEEP_SAND     to Color.rgb(200, 190, 152),
        Tile.DUNE          to Color.rgb(212, 202, 164),
        Tile.WATER         to Color.rgb( 58,  96, 140),
        Tile.GRASS         to Color.rgb(108, 148,  88),
        Tile.STONE_PATH    to Color.rgb(160, 152, 136),
        Tile.BUILDING      to Color.rgb( 96, 130, 110),   // rooftop colour
        Tile.PYRAMID       to Color.rgb(192, 178, 142),
        Tile.PYRAMID_STEPS to Color.rgb(204, 192, 158),
        Tile.PALM          to Color.rgb( 64, 108,  52),
        Tile.CACTUS        to Color.rgb( 72, 112,  60)
    )

    // ── Paints ─────────────────────────────────────────────────────────────────
    private fun p(color: Int, style: Paint.Style = Paint.Style.FILL) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; this.style = style }

    private val hudBg        = p(Color.argb(180, 12, 12, 12))
    private val hudText      = p(Color.WHITE).apply { typeface = Typeface.MONOSPACE; textSize = 30f }
    private val hudSmall     = p(Color.WHITE).apply { typeface = Typeface.MONOSPACE; textSize = 22f }
    private val loveBarFill  = p(Color.rgb(220, 80, 80))
    private val loveBarTrack = p(Color.argb(100, 255, 255, 255))
    private val dpadBg       = p(Color.argb(150, 10, 10, 10))
    private val dpadActive   = p(Color.argb(210, 210, 200, 180))
    private val dpadText     = p(Color.WHITE).apply { typeface = Typeface.MONOSPACE; textAlign = Paint.Align.CENTER }
    private val feedBtn      = p(Color.argb(170, 10, 10, 10))
    private val locText      = p(Color.WHITE).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.CENTER; textSize = 38f
    }
    private val whiteFill    = p(Color.WHITE)

    // Camel palette
    private val cBody    = p(Color.rgb(213, 170, 102))
    private val cShade   = p(Color.rgb(185, 143,  78))
    private val cHoof    = p(Color.rgb( 72,  42,  12))
    private val cEyeW    = p(Color.WHITE)
    private val cOutline = p(Color.rgb(72, 42, 12), Paint.Style.STROKE).apply {
        strokeWidth = 3.5f; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }

    init { holder.addCallback(this); isFocusable = true; isFocusableInTouchMode = true }

    // ── Surface lifecycle ──────────────────────────────────────────────────────
    override fun surfaceCreated(holder: SurfaceHolder) { running = true; gameThread = Thread(this).also { it.start() } }
    override fun surfaceChanged(holder: SurfaceHolder, f: Int, w: Int, h: Int) {}
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        running = false; try { gameThread?.join(1500) } catch (_: InterruptedException) {}
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        tileSize = w / 9f
        dpadR = w * 0.13f; dpadCX = w * 0.22f; dpadCY = h * 0.82f
        buildMinimap(); snapCamera()
    }

    // ── Game loop ──────────────────────────────────────────────────────────────
    override fun run() {
        var lastNs = System.nanoTime()
        while (running) {
            val now = System.nanoTime()
            val dt = ((now - lastNs) / 1e9f).coerceAtMost(0.05f); lastNs = now
            update(dt)
            val canvas = try { holder.lockCanvas() } catch (_: Exception) { null } ?: continue
            try { renderFrame(canvas) } finally { holder.unlockCanvasAndPost(canvas) }
            val sleep = 16L - (System.nanoTime() - now) / 1_000_000L
            if (sleep > 0) try { Thread.sleep(sleep) } catch (_: InterruptedException) { break }
        }
    }

    // ── Update ─────────────────────────────────────────────────────────────────
    private fun update(dt: Float) {
        // Auto wander trigger
        if (System.currentTimeMillis() - lastInputMs > autoWanderAfterMs && !camel.autoWandering && !playerPlaying)
            camel.startAutoWander(world)

        // Player play state
        if (playerPlaying) {
            playerPlayTimer -= dt
            if (playerPlayTimer <= 0f) { playerPlaying = false }
            else {
                playerPlayAngle -= dt * 2.8f   // opposite orbit direction
                camel.x = playerPlayCX + cos(playerPlayAngle).toFloat() * playRadius
                camel.y = playerPlayCY + sin(playerPlayAngle).toFloat() * playRadius
                camelFacingLeft = cos(playerPlayAngle) < 0f
                camel.isMoving = true; camel.walkPhase += dt * 5f
            }
        } else {
            camel.update(dt, inputDx, inputDy, world)
            when (camel.direction) {
                CamelEntity.Direction.LEFT  -> camelFacingLeft = true
                CamelEntity.Direction.RIGHT -> camelFacingLeft = false
                else -> {}
            }
        }

        // Update NPC camels
        wanderCamels.forEach { it.update(dt, world) }

        // Play proximity: player in wander mode, within 2 tiles of an NPC camel
        if (camel.autoWandering && !playerPlaying) {
            for (wc in wanderCamels) {
                if (wc.state == WanderCamel.State.PLAYING) continue
                val dx = wc.x - camel.x; val dy = wc.y - camel.y
                if (sqrt(dx * dx + dy * dy) < 2.0f) {
                    val cx = (camel.x + wc.x) / 2f; val cy = (camel.y + wc.y) / 2f
                    wc.startPlaying(cx, cy)
                    playerPlaying = true; playerPlayTimer = playDuration
                    playerPlayCX = cx; playerPlayCY = cy
                    playerPlayAngle = atan2(camel.y - cy, camel.x - cx)
                    camel.autoWandering = false
                    break
                }
            }
        }

        snapCamera()

        // Location arrival
        val loc = world.getLocationAt(camel.x, camel.y)
        if (loc != currentLocation) {
            currentLocation = loc
            if (loc != null) {
                locationLabel = loc.name; locationLabelTimer = 3.5f
                if (loc.type == LocationType.OASIS) {
                    val s = camelState
                    camelState = s.copy(
                        loveAtLastInteraction = (s.currentLove() + 5f).coerceAtMost(CamelState.MAX_LOVE),
                        lastInteractionTime = System.currentTimeMillis()
                    )
                    stateManager.save(camelState)
                }
            }
        }
        if (locationLabelTimer > 0f) locationLabelTimer -= dt
    }

    private fun snapCamera() {
        val w = width.toFloat(); val h = height.toFloat()
        camX = (camel.x * tileSize - w / 2f).coerceIn(0f, world.width * tileSize - w)
        camY = (camel.y * tileSize - h / 2f).coerceIn(0f, world.height * tileSize - h)
    }

    // ── Render ─────────────────────────────────────────────────────────────────
    private fun renderFrame(canvas: Canvas) {
        canvas.drawColor(Color.rgb(224, 214, 176))
        drawTiles(canvas)
        drawNpcs(canvas)
        drawWanderCamels(canvas)
        drawCamel(canvas)
        drawStructures(canvas)      // 3-D pyramids on top
        drawHUD(canvas)
        drawDpad(canvas)
        if (locationLabelTimer > 0f) drawLocationBanner(canvas)
    }

    // ── Tiles ──────────────────────────────────────────────────────────────────
    private fun drawTiles(canvas: Canvas) {
        val ts = tileSize
        val x0 = (camX / ts).toInt().coerceAtLeast(0)
        val y0 = (camY / ts).toInt().coerceAtLeast(0)
        val x1 = ((camX + width) / ts).toInt().coerceAtMost(world.width - 1)
        val y1 = ((camY + height) / ts).toInt().coerceAtMost(world.height - 1)
        for (ty in y0..y1) for (tx in x0..x1)
            drawTile(canvas, world.getTile(tx, ty), tx * ts - camX, ty * ts - camY, ts)
    }

    private fun drawTile(canvas: Canvas, tile: Int, sx: Float, sy: Float, ts: Float) {
        canvas.drawRect(sx, sy, sx + ts, sy + ts, p(tileColors[tile] ?: tileColors[Tile.SAND]!!))
        when (tile) {
            Tile.WATER -> {
                val wp = p(Color.rgb(80, 120, 170), Paint.Style.STROKE).apply { strokeWidth = 1.8f }
                for (row in 0..1) {
                    val wy = sy + ts * (0.38f + row * 0.26f)
                    canvas.drawPath(Path().apply {
                        moveTo(sx + ts*.05f, wy); quadTo(sx + ts*.28f, wy - ts*.06f, sx + ts*.5f, wy)
                        quadTo(sx + ts*.72f, wy + ts*.06f, sx + ts*.95f, wy)
                    }, wp)
                }
            }
            Tile.GRASS -> {
                val gp = p(Color.rgb(80, 130, 60), Paint.Style.STROKE).apply { strokeWidth = 1.5f }
                listOf(.2f to .65f, .5f to .35f, .75f to .70f, .38f to .80f, .62f to .45f).forEach { (fx, fy) ->
                    canvas.drawLine(sx+fx*ts, sy+fy*ts, sx+(fx-.03f)*ts, sy+(fy-.18f)*ts, gp)
                }
            }
            Tile.PALM -> {
                canvas.drawRect(sx+ts*.44f, sy+ts*.3f, sx+ts*.56f, sy+ts*.85f, p(Color.rgb(90,65,40)))
                val lf = p(Color.rgb(50, 100, 40))
                repeat(6) { i ->
                    val a = Math.PI * 2 * i / 6
                    val lx = sx + ts*.5f + cos(a).toFloat() * ts*.28f; val ly = sy + ts*.22f + sin(a).toFloat() * ts*.16f
                    canvas.drawOval(RectF(lx-ts*.1f, ly-ts*.055f, lx+ts*.1f, ly+ts*.055f), lf)
                }
                canvas.drawCircle(sx+ts*.5f, sy+ts*.2f, ts*.13f, lf)
            }
            Tile.CACTUS -> {
                val cp = p(Color.rgb(55, 100, 50))
                canvas.drawRoundRect(RectF(sx+ts*.42f,sy+ts*.18f,sx+ts*.58f,sy+ts*.88f),5f,5f,cp)
                canvas.drawRoundRect(RectF(sx+ts*.18f,sy+ts*.38f,sx+ts*.43f,sy+ts*.52f),4f,4f,cp)
                canvas.drawRoundRect(RectF(sx+ts*.57f,sy+ts*.46f,sx+ts*.82f,sy+ts*.60f),4f,4f,cp)
            }
            Tile.BUILDING -> {
                // Flat rooftop visible from above (slightly greenish clay)
                canvas.drawRect(sx, sy, sx+ts, sy+ts, p(Color.rgb(96, 130, 110)))
                // Roof edge shadow strip along bottom (the "wall top")
                canvas.drawRect(sx, sy+ts*.78f, sx+ts, sy+ts, p(Color.rgb(70, 100, 85)))
                // Roof detail: subtle cross-beam lines
                val beam = p(Color.rgb(78, 110, 94), Paint.Style.STROKE).apply { strokeWidth = 1.5f }
                canvas.drawLine(sx+ts*.2f, sy+ts*.1f, sx+ts*.2f, sy+ts*.78f, beam)
                canvas.drawLine(sx+ts*.5f, sy+ts*.1f, sx+ts*.5f, sy+ts*.78f, beam)
                canvas.drawLine(sx+ts*.8f, sy+ts*.1f, sx+ts*.8f, sy+ts*.78f, beam)
                // Front wall face hanging below tile (gives height illusion)
                val wallH = ts * 0.55f
                val wallFill = p(Color.rgb(188, 158, 120))
                canvas.drawRect(sx, sy+ts, sx+ts, sy+ts+wallH, wallFill)
                val winP = p(Color.rgb(120, 90, 60))
                canvas.drawRoundRect(RectF(sx+ts*.12f,sy+ts*1.05f,sx+ts*.42f,sy+ts*1.38f),4f,4f,winP)
                canvas.drawRoundRect(RectF(sx+ts*.58f,sy+ts*1.05f,sx+ts*.88f,sy+ts*1.38f),4f,4f,winP)
                canvas.drawRect(sx+ts*.38f,sy+ts*1.30f,sx+ts*.62f,sy+ts+wallH,p(Color.rgb(100,70,45)))
                val wallEdge = p(Color.rgb(140,110,80), Paint.Style.STROKE).apply { strokeWidth = 2f }
                canvas.drawRect(sx, sy+ts, sx+ts, sy+ts+wallH, wallEdge)
                // Top edge line separating roof from wall
                canvas.drawLine(sx, sy+ts, sx+ts, sy+ts, p(Color.rgb(60, 48, 36), Paint.Style.STROKE).apply { strokeWidth = 2.5f })
            }
            Tile.STONE_PATH -> {
                val lp = p(Color.rgb(136,128,112), Paint.Style.STROKE).apply { strokeWidth = 1f }
                canvas.drawLine(sx+ts*.1f,sy+ts*.5f,sx+ts*.9f,sy+ts*.5f,lp)
                canvas.drawLine(sx+ts*.5f,sy+ts*.1f,sx+ts*.5f,sy+ts*.9f,lp)
            }
            Tile.PYRAMID, Tile.PYRAMID_STEPS -> {
                // Just draw the sandy base — the 3-D shape is drawn in drawStructures()
                val shade = if (tile == Tile.PYRAMID) Color.rgb(192, 178, 142) else Color.rgb(208, 196, 160)
                canvas.drawRect(sx, sy, sx+ts, sy+ts, p(shade))
                if (tile == Tile.PYRAMID_STEPS) {
                    val lp = p(Color.rgb(168,154,118), Paint.Style.STROKE).apply { strokeWidth = 1f }
                    canvas.drawLine(sx, sy+ts*.5f, sx+ts, sy+ts*.5f, lp)
                }
            }
            Tile.DUNE -> canvas.drawArc(RectF(sx,sy+ts*.25f,sx+ts,sy+ts*1.25f),180f,180f,false,
                p(Color.rgb(195,185,145), Paint.Style.STROKE).apply { strokeWidth = 2.5f })
            Tile.DEEP_SAND -> listOf(.25f to .30f,.60f to .68f,.80f to .22f,.42f to .75f).forEach { (fx,fy) ->
                canvas.drawCircle(sx+fx*ts,sy+fy*ts,ts*.035f,p(Color.rgb(180,170,132)))
            }
        }
    }

    // ── 3-D pyramid structures (drawn after tiles, before camels) ──────────────
    private fun drawStructures(canvas: Canvas) {
        world.locations.filter { it.type == LocationType.PYRAMID }.forEach { loc ->
            val cx = loc.tileX * tileSize - camX
            val cy = loc.tileY * tileSize - camY
            drawPyramid3D(canvas, cx, cy)
        }
    }

    private fun drawPyramid3D(canvas: Canvas, cx: Float, cy: Float) {
        val ts = tileSize
        val halfBase = ts * 5.2f   // matches the 5-tile radius footprint
        val height   = ts * 6.8f   // how tall it appears

        // Apex is above-centre; base is at cy + small offset downward
        val peakX  = cx
        val peakY  = cy - height + ts * 0.5f
        val baseY  = cy + ts * 0.4f
        val baseL  = cx - halfBase
        val baseR  = cx + halfBase

        // Left (sun-lit) face
        val leftFace = Path().apply {
            moveTo(baseL, baseY); lineTo(peakX, peakY); lineTo(cx, baseY); close()
        }
        // Right (shadow) face
        val rightFace = Path().apply {
            moveTo(cx, baseY); lineTo(peakX, peakY); lineTo(baseR, baseY); close()
        }
        canvas.drawPath(leftFace,  p(Color.rgb(210, 196, 152)))
        canvas.drawPath(rightFace, p(Color.rgb(158, 144, 100)))

        // Stone course lines
        val stoneP = p(Color.rgb(130, 118, 84), Paint.Style.STROKE).apply { strokeWidth = 1.8f }
        for (i in 1..7) {
            val t = i / 8f
            val lx = baseL + (peakX - baseL) * t
            val rx = baseR + (peakX - baseR) * t
            val ly = baseY + (peakY - baseY) * t
            canvas.drawLine(lx, ly, rx, ly, stoneP)
        }

        // Centre ridge line
        val ridge = p(Color.rgb(120, 108, 72), Paint.Style.STROKE).apply { strokeWidth = 2f }
        canvas.drawLine(cx, baseY, peakX, peakY, ridge)

        // Outer outline
        val op = p(Color.rgb(100, 88, 56), Paint.Style.STROKE).apply { strokeWidth = 3f; strokeJoin = Paint.Join.ROUND }
        val outline = Path().apply {
            moveTo(baseL, baseY); lineTo(peakX, peakY); lineTo(baseR, baseY)
        }
        canvas.drawPath(outline, op)

        // Entrance arch at base centre
        val archP = p(Color.rgb(80, 68, 40))
        canvas.drawArc(RectF(cx - ts*.18f, baseY - ts*.22f, cx + ts*.18f, baseY + ts*.04f), 180f, 180f, true, archP)
    }

    // ── NPCs ───────────────────────────────────────────────────────────────────
    private fun drawNpcs(canvas: Canvas) {
        val ts = tileSize
        npcs.forEach { npc ->
            val sx = npc.tx * ts - camX; val sy = npc.ty * ts - camY
            // Cull if off screen
            if (sx < -ts * 2 || sx > width + ts * 2 || sy < -ts * 2 || sy > height + ts * 2) return@forEach
            drawNpc(canvas, sx, sy, ts, npc.left)
        }
    }

    private fun drawNpc(canvas: Canvas, sx: Float, sy: Float, ts: Float, facingLeft: Boolean) {
        canvas.save()
        if (facingLeft) canvas.scale(-1f, 1f, sx, sy)

        val skin   = p(Color.rgb(200, 160, 110))
        val robe   = p(Color.rgb(240, 235, 215))
        val accent = p(Color.rgb(180,  60,  40))  // sash
        val dark   = p(Color.rgb( 60,  50,  40))
        val outline= p(Color.rgb( 60,  50,  40), Paint.Style.STROKE).apply { strokeWidth = ts*.03f }

        // Legs
        canvas.drawRoundRect(RectF(sx-ts*.07f, sy+ts*.22f, sx+ts*.01f, sy+ts*.46f), ts*.04f, ts*.04f, dark)
        canvas.drawRoundRect(RectF(sx+ts*.01f, sy+ts*.22f, sx+ts*.09f, sy+ts*.46f), ts*.04f, ts*.04f, dark)
        // Robe body
        canvas.drawRoundRect(RectF(sx-ts*.12f, sy-ts*.14f, sx+ts*.12f, sy+ts*.28f), ts*.06f, ts*.06f, robe)
        canvas.drawRoundRect(RectF(sx-ts*.12f, sy-ts*.14f, sx+ts*.12f, sy+ts*.28f), ts*.06f, ts*.06f, outline)
        // Sash
        canvas.drawRect(sx-ts*.12f, sy+ts*.04f, sx+ts*.12f, sy+ts*.10f, accent)
        // Arm
        canvas.drawRoundRect(RectF(sx+ts*.09f, sy-ts*.10f, sx+ts*.16f, sy+ts*.12f), ts*.04f, ts*.04f, robe)
        // Head
        canvas.drawCircle(sx, sy-ts*.24f, ts*.13f, skin)
        canvas.drawCircle(sx, sy-ts*.24f, ts*.13f, outline)
        // Keffiyeh (head wrap)
        val wrap = p(Color.rgb(200, 180, 140))
        canvas.drawArc(RectF(sx-ts*.13f, sy-ts*.38f, sx+ts*.13f, sy-ts*.13f), 180f, 180f, false, wrap)
        canvas.drawRect(sx-ts*.14f, sy-ts*.30f, sx+ts*.02f, sy-ts*.12f, wrap)
        // Eye
        canvas.drawCircle(sx+ts*.05f, sy-ts*.24f, ts*.025f, dark)

        canvas.restore()
    }

    // ── Camel drawing (player + NPC shared) ────────────────────────────────────
    private fun drawCamel(canvas: Canvas) {
        val sx = camel.x * tileSize - camX; val sy = camel.y * tileSize - camY
        canvas.save(); canvas.translate(sx, sy)
        val bob = if (camel.isMoving) sin(camel.walkPhase).toFloat() * tileSize * .025f else 0f
        drawCamelSprite(canvas, tileSize, bob, camelFacingLeft)
        canvas.restore()
        // Play sparkles
        if (playerPlaying) drawPlaySparkles(canvas, sx, sy, tileSize)
    }

    private fun drawWanderCamels(canvas: Canvas) {
        val ts = tileSize
        wanderCamels.forEach { wc ->
            val sx = wc.x * ts - camX; val sy = wc.y * ts - camY
            if (sx < -ts*2 || sx > width+ts*2 || sy < -ts*2 || sy > height+ts*2) return@forEach
            canvas.save(); canvas.translate(sx, sy)
            val bob = if (wc.isMoving) sin(wc.walkPhase).toFloat() * ts * .025f else 0f
            drawCamelSprite(canvas, ts, bob, wc.facingLeft, shade = true)
            canvas.restore()
            if (wc.state == WanderCamel.State.PLAYING) drawPlaySparkles(canvas, sx, sy, ts)
        }
    }

    private fun drawPlaySparkles(canvas: Canvas, sx: Float, sy: Float, ts: Float) {
        val sp = p(Color.rgb(255, 200, 50)).apply { textSize = ts * .3f; textAlign = Paint.Align.CENTER }
        canvas.drawText("♪", sx, sy - ts * .6f, sp)
        canvas.drawText("~", sx + ts * .3f, sy - ts * .35f, sp)
    }

    private fun drawCamelSprite(canvas: Canvas, ts: Float, bob: Float, flipLeft: Boolean, shade: Boolean = false) {
        canvas.save()
        if (flipLeft) canvas.scale(-1f, 1f)

        val cy = bob
        cOutline.strokeWidth = ts * .045f

        val fill = if (shade) cShade else cBody

        fun leg(x: Float, swing: Float, dark: Boolean) {
            val lp = if (dark) cShade else fill
            canvas.drawRoundRect(RectF(x-ts*.07f+swing, cy+ts*.09f, x+ts*.07f+swing, cy+ts*.38f), ts*.06f, ts*.06f, lp)
            canvas.drawRoundRect(RectF(x-ts*.07f+swing, cy+ts*.09f, x+ts*.07f+swing, cy+ts*.38f), ts*.06f, ts*.06f, cOutline)
            canvas.drawOval(RectF(x-ts*.09f+swing, cy+ts*.32f, x+ts*.09f+swing, cy+ts*.43f), cHoof)
        }
        val sw = if (cBody == fill || shade) (sin(if (shade) (System.nanoTime() / 200_000_000f) else 0f) * ts * .09f) else 0f
        val walkSw = sw

        leg(-ts*.18f,  walkSw, true);  leg(-ts*.07f, -walkSw, false)
        leg( ts*.09f,  walkSw, true);  leg( ts*.20f, -walkSw, false)

        // Body
        canvas.drawOval(RectF(-ts*.32f, cy-ts*.20f, ts*.28f, cy+ts*.18f), fill)
        canvas.drawOval(RectF(-ts*.32f, cy-ts*.20f, ts*.28f, cy+ts*.18f), cOutline)

        // Hump
        val hump = Path().apply {
            moveTo(-ts*.04f, cy-ts*.17f)
            cubicTo(-ts*.04f, cy-ts*.52f, -ts*.30f, cy-ts*.52f, -ts*.30f, cy-ts*.17f); close()
        }
        canvas.drawPath(hump, fill); canvas.drawPath(hump, cOutline)

        // Tail
        canvas.drawPath(Path().apply {
            moveTo(-ts*.30f, cy+ts*.04f); cubicTo(-ts*.44f, cy, -ts*.44f, cy+ts*.18f, -ts*.32f, cy+ts*.16f)
        }, p(Color.rgb(72,42,12), Paint.Style.STROKE).apply { strokeWidth=ts*.05f; strokeCap=Paint.Cap.ROUND })
        canvas.drawCircle(-ts*.32f, cy+ts*.18f, ts*.055f, cHoof)

        // Neck
        val neck = Path().apply {
            moveTo(ts*.18f, cy-ts*.14f); cubicTo(ts*.22f, cy-ts*.35f, ts*.34f, cy-ts*.46f, ts*.40f, cy-ts*.56f)
            cubicTo(ts*.46f, cy-ts*.44f, ts*.38f, cy-ts*.32f, ts*.30f, cy-ts*.12f); close()
        }
        canvas.drawPath(neck, fill); canvas.drawPath(neck, cOutline)

        // Head
        canvas.drawCircle(ts*.44f, cy-ts*.64f, ts*.20f, fill)
        canvas.drawCircle(ts*.44f, cy-ts*.64f, ts*.20f, cOutline)

        // Snout
        canvas.drawOval(RectF(ts*.50f, cy-ts*.56f, ts*.70f, cy-ts*.42f), fill)
        canvas.drawOval(RectF(ts*.50f, cy-ts*.56f, ts*.70f, cy-ts*.42f), cOutline)
        canvas.drawCircle(ts*.665f, cy-ts*.455f, ts*.028f, cHoof)

        // Ear
        val ear = Path().apply {
            moveTo(ts*.34f, cy-ts*.78f); cubicTo(ts*.28f, cy-ts*.92f, ts*.44f, cy-ts*.92f, ts*.46f, cy-ts*.78f); close()
        }
        canvas.drawPath(ear, fill); canvas.drawPath(ear, cOutline)

        // Big cute eye
        canvas.drawCircle(ts*.48f, cy-ts*.66f, ts*.075f, cHoof)
        canvas.drawCircle(ts*.462f, cy-ts*.678f, ts*.028f, cEyeW)

        canvas.restore()
    }

    // ── HUD ────────────────────────────────────────────────────────────────────
    private fun drawHUD(canvas: Canvas) {
        val love = camelState.currentLove()
        val name = camelState.name.takeIf { camelState.isNamed && it.isNotBlank() } ?: "???"
        val pad = 14f; val cardW = 240f; val cardH = 76f
        canvas.drawRoundRect(RectF(pad, pad, pad+cardW, pad+cardH), 14f, 14f, hudBg)
        hudText.textSize = 28f; canvas.drawText(name, pad+12f, pad+30f, hudText)
        val bx=pad+12f; val by=pad+42f; val bw=cardW-24f; val bh=16f
        canvas.drawRoundRect(RectF(bx,by,bx+bw,by+bh),bh/2,bh/2,loveBarTrack)
        val fill=bw*(love/CamelState.MAX_LOVE)
        if (fill>0f) canvas.drawRoundRect(RectF(bx,by,bx+fill,by+bh),bh/2,bh/2,loveBarFill)
        hudSmall.textSize=19f; canvas.drawText("${love.toInt()}%",bx+bw+8f,by+bh-1f,hudSmall)
        val btnW=130f; val btnH=64f; val btnX=width-btnW-16f; val btnY=height-btnH-16f
        canvas.drawRoundRect(RectF(btnX,btnY,btnX+btnW,btnY+btnH),14f,14f,feedBtn)
        dpadText.textSize=30f; canvas.drawText("FEED",btnX+btnW/2f,btnY+btnH*.66f,dpadText)
        drawMinimap(canvas)
    }

    private fun drawMinimap(canvas: Canvas) {
        val bmp = minimapBmp ?: return
        val ms=88f; val mx=width-ms-14f; val my=14f
        canvas.drawRoundRect(RectF(mx-2f,my-2f,mx+ms+2f,my+ms+2f),4f,4f,p(Color.argb(200,0,0,0)))
        canvas.drawBitmap(bmp,Rect(0,0,world.width,world.height),RectF(mx,my,mx+ms,my+ms),null)
        canvas.drawCircle(mx+(camel.x/world.width)*ms, my+(camel.y/world.height)*ms, 3.5f, whiteFill)
        wanderCamels.forEach { wc ->
            canvas.drawCircle(mx+(wc.x/world.width)*ms, my+(wc.y/world.height)*ms, 2.5f,
                p(Color.rgb(200,160,80)))
        }
    }

    private fun buildMinimap() {
        val bmp = Bitmap.createBitmap(world.width, world.height, Bitmap.Config.ARGB_8888)
        val px = IntArray(world.width * world.height)
        for (y in 0 until world.height) for (x in 0 until world.width)
            px[y*world.width+x] = when(world.getTile(x,y)) {
                Tile.WATER         -> Color.rgb(58,96,140)
                Tile.GRASS         -> Color.rgb(108,148,88)
                Tile.PALM          -> Color.rgb(40,90,40)
                Tile.STONE_PATH    -> Color.rgb(160,152,136)
                Tile.BUILDING      -> Color.rgb(88,72,60)
                Tile.PYRAMID, Tile.PYRAMID_STEPS -> Color.rgb(172,160,128)
                Tile.CACTUS        -> Color.rgb(72,112,60)
                Tile.DEEP_SAND     -> Color.rgb(200,190,152)
                Tile.DUNE          -> Color.rgb(212,202,164)
                else               -> Color.rgb(224,214,176)
            }
        bmp.setPixels(px,0,world.width,0,0,world.width,world.height)
        minimapBmp = bmp
    }

    // ── Location banner ────────────────────────────────────────────────────────
    private fun drawLocationBanner(canvas: Canvas) {
        val alpha = ((locationLabelTimer/3.5f)*255).toInt().coerceIn(0,255)
        locText.alpha = alpha
        val tw = locText.measureText(locationLabel); val cx=width/2f; val by=height*.14f
        canvas.drawRoundRect(RectF(cx-tw/2-22f,by-34f,cx+tw/2+22f,by+12f),12f,12f,p(Color.argb(alpha*2/3,0,0,0)))
        canvas.drawText(locationLabel, cx, by, locText); locText.alpha = 255
    }

    // ── D-pad ──────────────────────────────────────────────────────────────────
    private fun drawDpad(canvas: Canvas) {
        val cx=dpadCX; val cy=dpadCY; val r=dpadR; val btnR=r*.56f
        canvas.drawCircle(cx,cy,btnR*.55f,dpadBg)
        val dirs=listOf(0f to -r,0f to r,-r to 0f,r to 0f)
        val labels=listOf("▲","▼","◀","▶"); val active=listOf(dpadUp,dpadDown,dpadLeft,dpadRight)
        dirs.forEachIndexed { i,(dx,dy) ->
            val bx=cx+dx; val by2=cy+dy
            canvas.drawCircle(bx,by2,btnR,if(active[i])dpadActive else dpadBg)
            dpadText.textSize=btnR*.82f; canvas.drawText(labels[i],bx,by2+dpadText.textSize*.36f,dpadText)
        }
    }

    // ── Input ──────────────────────────────────────────────────────────────────
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val tx=event.x; val ty=event.y
        val btnW=130f; val btnH=64f; val feedX=width-btnW-16f; val feedY=height-btnH-16f
        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                if (tx>=feedX && tx<=feedX+btnW && ty>=feedY && ty<=feedY+btnH) { handleFeed(); return true }
                updateDpad(tx,ty)
                if (inputDx!=0f || inputDy!=0f) { lastInputMs=System.currentTimeMillis(); camel.autoWandering=false; playerPlaying=false }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> clearDpad()
        }
        return true
    }

    private fun updateDpad(tx: Float, ty: Float) {
        val dx=tx-dpadCX; val dy=ty-dpadCY; val dist=sqrt(dx*dx+dy*dy)
        if (dist>dpadR*2.2f) { clearDpad(); return }
        dpadUp=false; dpadDown=false; dpadLeft=false; dpadRight=false; inputDx=0f; inputDy=0f
        if (dist<dpadR*.22f) return
        if (abs(dx)>=abs(dy)) { if(dx>0){dpadRight=true;inputDx=1f} else{dpadLeft=true;inputDx=-1f} }
        else { if(dy>0){dpadDown=true;inputDy=1f} else{dpadUp=true;inputDy=-1f} }
    }

    private fun clearDpad() { inputDx=0f; inputDy=0f; dpadUp=false; dpadDown=false; dpadLeft=false; dpadRight=false }

    private fun handleFeed() {
        camelState=camelState.withFeed(); stateManager.save(camelState); lastInputMs=System.currentTimeMillis()
    }

    fun onResume() { camelState=stateManager.load() }
    fun onPause()  { stateManager.save(camelState) }
}
