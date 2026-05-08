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
    private val camel = CamelEntity(40f, 30f)  // start on open sand above the central oasis

    // ── Camera ─────────────────────────────────────────────────────────────────
    private var tileSize = 64f
    private var camX = 0f
    private var camY = 0f

    // ── Game loop ──────────────────────────────────────────────────────────────
    private var gameThread: Thread? = null
    @Volatile private var running = false

    // ── Input ──────────────────────────────────────────────────────────────────
    @Volatile private var inputDx = 0f
    @Volatile private var inputDy = 0f
    private var lastInputMs = System.currentTimeMillis()
    private val autoWanderAfterMs = 30_000L

    // ── HUD state ──────────────────────────────────────────────────────────────
    private var locationLabel = ""
    @Volatile private var locationLabelTimer = 0f
    private var currentLocation: WorldLocation? = null
    private var camelFacingLeft = false  // always side-view; flip on left movement
    private var dpadUp = false; private var dpadDown = false
    private var dpadLeft = false; private var dpadRight = false
    private var dpadCX = 0f; private var dpadCY = 0f
    private var dpadR = 0f  // set in onSizeChanged, scaled to screen

    // ── Minimap ────────────────────────────────────────────────────────────────
    private var minimapBmp: Bitmap? = null

    // ── Tile colors ────────────────────────────────────────────────────────────
    private val tileColors = mapOf(
        Tile.SAND          to Color.rgb(224, 214, 176),
        Tile.DEEP_SAND     to Color.rgb(200, 190, 152),
        Tile.DUNE          to Color.rgb(212, 202, 164),
        Tile.WATER         to Color.rgb( 58,  96, 140),
        Tile.GRASS         to Color.rgb(108, 148,  88),
        Tile.STONE_PATH    to Color.rgb(160, 152, 136),
        Tile.BUILDING      to Color.rgb(112,  96,  80),
        Tile.PYRAMID       to Color.rgb(172, 160, 128),
        Tile.PYRAMID_STEPS to Color.rgb(192, 180, 148),
        Tile.PALM          to Color.rgb( 64, 108,  52),
        Tile.CACTUS        to Color.rgb( 72, 112,  60)
    )

    // ── Shared paints ──────────────────────────────────────────────────────────
    private fun p(color: Int, style: Paint.Style = Paint.Style.FILL) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; this.style = style }

    private val hudBg       = p(Color.argb(180, 12, 12, 12))
    private val hudText     = p(Color.WHITE).apply { typeface = Typeface.MONOSPACE; textSize = 30f }
    private val hudSmall    = p(Color.WHITE).apply { typeface = Typeface.MONOSPACE; textSize = 22f }
    private val loveBarFill = p(Color.rgb(220, 80, 80))
    private val loveBarTrack= p(Color.argb(100, 255, 255, 255))
    private val dpadBg      = p(Color.argb(150, 10, 10, 10))
    private val dpadActive  = p(Color.argb(210, 210, 200, 180))
    private val dpadText    = p(Color.WHITE).apply { typeface = Typeface.MONOSPACE; textAlign = Paint.Align.CENTER }
    private val feedBtn     = p(Color.argb(170, 10, 10, 10))
    private val locationText= p(Color.WHITE).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.CENTER; textSize = 38f
    }
    private val whiteFill   = p(Color.WHITE)

    // ── Camel palette ──────────────────────────────────────────────────────────
    private val cBody    = p(Color.rgb(213, 170, 102))
    private val cShade   = p(Color.rgb(185, 143, 78))
    private val cOutline = p(Color.rgb(72, 42, 12), Paint.Style.STROKE).apply {
        strokeWidth = 3.5f; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val cHoof    = p(Color.rgb(72, 42, 12))
    private val cEyeW    = p(Color.WHITE)

    init { holder.addCallback(this); isFocusable = true }

    // ── Surface lifecycle ──────────────────────────────────────────────────────
    override fun surfaceCreated(holder: SurfaceHolder) {
        running = true
        gameThread = Thread(this).also { it.start() }
    }
    override fun surfaceChanged(holder: SurfaceHolder, f: Int, w: Int, h: Int) {}
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        running = false
        try { gameThread?.join(1500) } catch (_: InterruptedException) {}
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        tileSize = w / 9f
        // D-pad: scale with screen so it's always a comfortable size
        dpadR    = w * 0.13f
        dpadCX   = w * 0.22f
        dpadCY   = h * 0.82f
        buildMinimap()
        snapCamera()
    }

    // ── Game loop ──────────────────────────────────────────────────────────────
    override fun run() {
        var lastNs = System.nanoTime()
        while (running) {
            val now = System.nanoTime()
            val dt = ((now - lastNs) / 1e9f).coerceAtMost(0.05f)
            lastNs = now
            update(dt)
            val canvas = try { holder.lockCanvas() } catch (_: Exception) { null } ?: continue
            try { renderFrame(canvas) } finally { holder.unlockCanvasAndPost(canvas) }
            val elapsed = (System.nanoTime() - now) / 1_000_000L
            val sleep = 16L - elapsed
            if (sleep > 0) try { Thread.sleep(sleep) } catch (_: InterruptedException) { break }
        }
    }

    // ── Update ─────────────────────────────────────────────────────────────────
    private fun update(dt: Float) {
        if (System.currentTimeMillis() - lastInputMs > autoWanderAfterMs && !camel.autoWandering)
            camel.startAutoWander(world)

        camel.update(dt, inputDx, inputDy, world)
        snapCamera()

        val loc = world.getLocationAt(camel.x, camel.y)
        if (loc != currentLocation) {
            currentLocation = loc
            if (loc != null) {
                locationLabel = loc.name
                locationLabelTimer = 3.5f
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
        drawCamel(canvas)
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
                    val path = Path().apply {
                        moveTo(sx + ts*.05f, wy)
                        quadTo(sx + ts*.28f, wy - ts*.06f, sx + ts*.5f, wy)
                        quadTo(sx + ts*.72f, wy + ts*.06f, sx + ts*.95f, wy)
                    }
                    canvas.drawPath(path, wp)
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
                val leaf = p(Color.rgb(50, 100, 40))
                repeat(6) { i ->
                    val a = Math.PI * 2 * i / 6
                    val lx = sx + ts*.5f + cos(a).toFloat() * ts*.28f
                    val ly = sy + ts*.22f + sin(a).toFloat() * ts*.16f
                    canvas.drawOval(RectF(lx-ts*.1f, ly-ts*.055f, lx+ts*.1f, ly+ts*.055f), leaf)
                }
                canvas.drawCircle(sx+ts*.5f, sy+ts*.2f, ts*.13f, leaf)
            }
            Tile.CACTUS -> {
                val cp = p(Color.rgb(55, 100, 50))
                canvas.drawRoundRect(RectF(sx+ts*.42f, sy+ts*.18f, sx+ts*.58f, sy+ts*.88f), 5f, 5f, cp)
                canvas.drawRoundRect(RectF(sx+ts*.18f, sy+ts*.38f, sx+ts*.43f, sy+ts*.52f), 4f, 4f, cp)
                canvas.drawRoundRect(RectF(sx+ts*.57f, sy+ts*.46f, sx+ts*.82f, sy+ts*.60f), 4f, 4f, cp)
                canvas.drawRoundRect(RectF(sx+ts*.18f, sy+ts*.26f, sx+ts*.30f, sy+ts*.40f), 4f, 4f, cp)
                canvas.drawRoundRect(RectF(sx+ts*.70f, sy+ts*.33f, sx+ts*.82f, sy+ts*.48f), 4f, 4f, cp)
            }
            Tile.BUILDING -> {
                canvas.drawRect(sx, sy, sx+ts, sy+ts, p(Color.rgb(88,76,64)))
                val win = p(Color.rgb(50,42,34))
                canvas.drawRect(sx+ts*.18f, sy+ts*.18f, sx+ts*.44f, sy+ts*.52f, win)
                canvas.drawRect(sx+ts*.56f, sy+ts*.18f, sx+ts*.82f, sy+ts*.52f, win)
                canvas.drawRect(sx+ts*.36f, sy+ts*.62f, sx+ts*.64f, sy+ts*.90f, p(Color.rgb(70,58,46)))
            }
            Tile.STONE_PATH -> {
                val lp = p(Color.rgb(136,128,112), Paint.Style.STROKE).apply { strokeWidth = 1f }
                canvas.drawLine(sx+ts*.1f, sy+ts*.5f, sx+ts*.9f, sy+ts*.5f, lp)
                canvas.drawLine(sx+ts*.5f, sy+ts*.1f, sx+ts*.5f, sy+ts*.9f, lp)
            }
            Tile.PYRAMID -> {
                canvas.drawRect(sx, sy, sx+ts, sy+ts, p(Color.rgb(140,128,100)))
                canvas.drawRect(sx+ts*.5f, sy, sx+ts, sy+ts, p(Color.rgb(100,90,70)))
                canvas.drawRect(sx+ts*.1f, sy+ts*.1f, sx+ts*.9f, sy+ts*.9f,
                    p(Color.rgb(180,168,140), Paint.Style.STROKE).apply { strokeWidth = 1.5f })
            }
            Tile.PYRAMID_STEPS ->
                canvas.drawLine(sx, sy+ts*.5f, sx+ts, sy+ts*.5f,
                    p(Color.rgb(172,162,130), Paint.Style.STROKE).apply { strokeWidth = 1f })
            Tile.DUNE ->
                canvas.drawArc(RectF(sx, sy+ts*.25f, sx+ts, sy+ts*1.25f), 180f, 180f, false,
                    p(Color.rgb(195,185,145), Paint.Style.STROKE).apply { strokeWidth = 2.5f })
            Tile.DEEP_SAND ->
                listOf(.25f to .30f, .60f to .68f, .80f to .22f, .42f to .75f).forEach { (fx,fy) ->
                    canvas.drawCircle(sx+fx*ts, sy+fy*ts, ts*.035f, p(Color.rgb(180,170,132)))
                }
        }
    }

    // ── Camel ──────────────────────────────────────────────────────────────────
    private fun drawCamel(canvas: Canvas) {
        // Update facing direction — only horizontal movement changes it
        when (camel.direction) {
            CamelEntity.Direction.LEFT  -> camelFacingLeft = true
            CamelEntity.Direction.RIGHT -> camelFacingLeft = false
            else -> { /* keep last horizontal facing */ }
        }
        val sx = camel.x * tileSize - camX
        val sy = camel.y * tileSize - camY
        canvas.save()
        canvas.translate(sx, sy)
        val bob = if (camel.isMoving) sin(camel.walkPhase).toFloat() * tileSize * 0.025f else 0f
        // Always draw the side view — camel faces left or right, never front/back
        drawCamelSide(canvas, tileSize, bob, camelFacingLeft)
        canvas.restore()
    }

    private fun drawCamelSide(canvas: Canvas, ts: Float, bob: Float, flip: Boolean) {
        canvas.save()
        if (flip) canvas.scale(-1f, 1f)

        val cy = bob
        val sw = if (camel.isMoving) sin(camel.walkPhase).toFloat() * ts * .09f else 0f

        // stroke width scaled to tile
        cOutline.strokeWidth = ts * .045f

        // ── Back legs (drawn behind body) ──────────────────────────────────────
        fun leg(x: Float, y: Float, swing: Float, shade: Boolean) {
            val lp = if (shade) cShade else cBody
            canvas.drawRoundRect(RectF(x - ts*.07f + swing, y, x + ts*.07f + swing, y + ts*.32f), ts*.065f, ts*.065f, lp)
            canvas.drawRoundRect(RectF(x - ts*.07f + swing, y, x + ts*.07f + swing, y + ts*.32f), ts*.065f, ts*.065f, cOutline)
            canvas.drawOval(RectF(x - ts*.09f + swing, y + ts*.27f, x + ts*.09f + swing, y + ts*.38f), cHoof)
        }
        leg(-ts*.18f,  cy + ts*.08f,  sw,   true)   // back-right (shade)
        leg(-ts*.08f,  cy + ts*.08f, -sw,   false)  // back-left
        leg( ts*.10f,  cy + ts*.08f,  sw,   true)   // front-right (shade)
        leg( ts*.20f,  cy + ts*.08f, -sw,   false)  // front-left

        // ── Body ───────────────────────────────────────────────────────────────
        canvas.drawOval(RectF(-ts*.32f, cy - ts*.20f, ts*.28f, cy + ts*.18f), cBody)
        canvas.drawOval(RectF(-ts*.32f, cy - ts*.20f, ts*.28f, cy + ts*.18f), cOutline)

        // ── Hump ───────────────────────────────────────────────────────────────
        val hump = Path().apply {
            moveTo(-ts*.04f, cy - ts*.17f)
            cubicTo(-ts*.04f, cy - ts*.52f, -ts*.30f, cy - ts*.52f, -ts*.30f, cy - ts*.17f)
            close()
        }
        canvas.drawPath(hump, cBody)
        canvas.drawPath(hump, cOutline)

        // ── Tail ───────────────────────────────────────────────────────────────
        val tailStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(72, 42, 12); style = Paint.Style.STROKE
            strokeWidth = ts * .05f; strokeCap = Paint.Cap.ROUND
        }
        val tailPath = Path().apply {
            moveTo(-ts*.30f, cy + ts*.04f)
            cubicTo(-ts*.44f, cy, -ts*.44f, cy + ts*.18f, -ts*.32f, cy + ts*.16f)
        }
        canvas.drawPath(tailPath, tailStroke)
        canvas.drawCircle(-ts*.32f, cy + ts*.18f, ts*.055f, cHoof)

        // ── Neck ───────────────────────────────────────────────────────────────
        val neck = Path().apply {
            moveTo(ts*.18f, cy - ts*.14f)
            cubicTo(ts*.22f, cy - ts*.35f, ts*.34f, cy - ts*.46f, ts*.40f, cy - ts*.56f)
            cubicTo(ts*.46f, cy - ts*.44f, ts*.38f, cy - ts*.32f, ts*.30f, cy - ts*.12f)
            close()
        }
        canvas.drawPath(neck, cBody)
        canvas.drawPath(neck, cOutline)

        // ── Head (big round) ───────────────────────────────────────────────────
        val hx = ts*.44f; val hy = cy - ts*.64f; val hr = ts*.20f
        canvas.drawCircle(hx, hy, hr, cBody)
        canvas.drawCircle(hx, hy, hr, cOutline)

        // ── Snout ──────────────────────────────────────────────────────────────
        canvas.drawOval(RectF(ts*.50f, cy - ts*.56f, ts*.70f, cy - ts*.42f), cBody)
        canvas.drawOval(RectF(ts*.50f, cy - ts*.56f, ts*.70f, cy - ts*.42f), cOutline)
        canvas.drawCircle(ts*.665f, cy - ts*.455f, ts*.028f, cHoof)  // nostril

        // ── Ear ────────────────────────────────────────────────────────────────
        val ear = Path().apply {
            moveTo(ts*.34f, cy - ts*.78f)
            cubicTo(ts*.28f, cy - ts*.92f, ts*.44f, cy - ts*.92f, ts*.46f, cy - ts*.78f)
            close()
        }
        canvas.drawPath(ear, cBody)
        canvas.drawPath(ear, cOutline)

        // ── Big cute eye ───────────────────────────────────────────────────────
        canvas.drawCircle(ts*.48f, cy - ts*.66f, ts*.075f, cHoof)        // pupil
        canvas.drawCircle(ts*.462f, cy - ts*.678f, ts*.028f, cEyeW)     // shine

        canvas.restore()
    }

    private fun drawCamelFront(canvas: Canvas, ts: Float, bob: Float) {
        val cy = bob
        cOutline.strokeWidth = ts * .045f
        val sw = if (camel.isMoving) sin(camel.walkPhase).toFloat() * ts * .06f else 0f
        // legs
        fun leg(x: Float, swing: Float, shade: Boolean) {
            val lp = if (shade) cShade else cBody
            canvas.drawRoundRect(RectF(x - ts*.065f + swing, cy + ts*.10f, x + ts*.065f + swing, cy + ts*.38f), ts*.06f, ts*.06f, lp)
            canvas.drawRoundRect(RectF(x - ts*.065f + swing, cy + ts*.10f, x + ts*.065f + swing, cy + ts*.38f), ts*.06f, ts*.06f, cOutline)
            canvas.drawOval(RectF(x - ts*.085f + swing, cy + ts*.32f, x + ts*.085f + swing, cy + ts*.42f), cHoof)
        }
        leg(-ts*.18f,  sw,   true); leg(-ts*.06f, -sw,  false)
        leg( ts*.06f,  sw,   false); leg( ts*.18f, -sw,  true)
        // body
        canvas.drawOval(RectF(-ts*.24f, cy - ts*.22f, ts*.24f, cy + ts*.18f), cBody)
        canvas.drawOval(RectF(-ts*.24f, cy - ts*.22f, ts*.24f, cy + ts*.18f), cOutline)
        // two humps seen from front
        canvas.drawOval(RectF(-ts*.20f, cy - ts*.40f, ts*.04f, cy - ts*.16f), cBody)
        canvas.drawOval(RectF(-ts*.20f, cy - ts*.40f, ts*.04f, cy - ts*.16f), cOutline)
        canvas.drawOval(RectF(-ts*.04f, cy - ts*.36f, ts*.18f, cy - ts*.14f), cBody)
        canvas.drawOval(RectF(-ts*.04f, cy - ts*.36f, ts*.18f, cy - ts*.14f), cOutline)
        // head
        canvas.drawCircle(0f, cy + ts*.30f, ts*.17f, cBody)
        canvas.drawCircle(0f, cy + ts*.30f, ts*.17f, cOutline)
        // ears
        val earP = Path()
        earP.moveTo(-ts*.14f, cy + ts*.17f); earP.cubicTo(-ts*.22f, cy + ts*.06f, -ts*.08f, cy + ts*.04f, -ts*.06f, cy + ts*.16f); earP.close()
        canvas.drawPath(earP, cBody); canvas.drawPath(earP, cOutline)
        val earP2 = Path()
        earP2.moveTo(ts*.14f, cy + ts*.17f); earP2.cubicTo(ts*.22f, cy + ts*.06f, ts*.08f, cy + ts*.04f, ts*.06f, cy + ts*.16f); earP2.close()
        canvas.drawPath(earP2, cBody); canvas.drawPath(earP2, cOutline)
        // eyes
        canvas.drawCircle(-ts*.07f, cy + ts*.30f, ts*.060f, cHoof)
        canvas.drawCircle(-ts*.083f, cy + ts*.284f, ts*.022f, cEyeW)
        canvas.drawCircle( ts*.07f, cy + ts*.30f, ts*.060f, cHoof)
        canvas.drawCircle( ts*.057f, cy + ts*.284f, ts*.022f, cEyeW)
    }

    private fun drawCamelBack(canvas: Canvas, ts: Float, bob: Float) {
        val cy = bob
        cOutline.strokeWidth = ts * .045f
        val sw = if (camel.isMoving) sin(camel.walkPhase).toFloat() * ts * .06f else 0f
        // legs
        fun leg(x: Float, swing: Float, shade: Boolean) {
            val lp = if (shade) cShade else cBody
            canvas.drawRoundRect(RectF(x - ts*.065f + swing, cy + ts*.10f, x + ts*.065f + swing, cy + ts*.38f), ts*.06f, ts*.06f, lp)
            canvas.drawRoundRect(RectF(x - ts*.065f + swing, cy + ts*.10f, x + ts*.065f + swing, cy + ts*.38f), ts*.06f, ts*.06f, cOutline)
            canvas.drawOval(RectF(x - ts*.085f + swing, cy + ts*.32f, x + ts*.085f + swing, cy + ts*.42f), cHoof)
        }
        leg(-ts*.18f,  sw, true); leg(-ts*.06f, -sw, false)
        leg( ts*.06f,  sw, false); leg( ts*.18f, -sw, true)
        canvas.drawOval(RectF(-ts*.24f, cy - ts*.22f, ts*.24f, cy + ts*.18f), cBody)
        canvas.drawOval(RectF(-ts*.24f, cy - ts*.22f, ts*.24f, cy + ts*.18f), cOutline)
        // hump (seen from behind = dome)
        canvas.drawOval(RectF(-ts*.16f, cy - ts*.46f, ts*.16f, cy - ts*.14f), cBody)
        canvas.drawOval(RectF(-ts*.16f, cy - ts*.46f, ts*.16f, cy - ts*.14f), cOutline)
        // tail
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(72,42,12); style = Paint.Style.STROKE; strokeWidth = ts*.05f; strokeCap = Paint.Cap.ROUND
        }
        canvas.drawArc(RectF(-ts*.14f, cy + ts*.04f, ts*.04f, cy + ts*.28f), 90f, -200f, false, tp)
        canvas.drawCircle(ts*.01f, cy + ts*.06f, ts*.05f, cHoof)
        // back of head (no eyes)
        canvas.drawCircle(0f, cy - ts*.34f, ts*.14f, cBody)
        canvas.drawCircle(0f, cy - ts*.34f, ts*.14f, cOutline)
    }

    // ── HUD ────────────────────────────────────────────────────────────────────
    private fun drawHUD(canvas: Canvas) {
        val love = camelState.currentLove()
        val name = camelState.name.takeIf { camelState.isNamed && it.isNotBlank() } ?: "???"
        val pad = 14f; val cardW = 240f; val cardH = 76f
        canvas.drawRoundRect(RectF(pad, pad, pad + cardW, pad + cardH), 14f, 14f, hudBg)
        hudText.textSize = 28f
        canvas.drawText(name, pad + 12f, pad + 30f, hudText)
        val bx = pad + 12f; val by = pad + 42f; val bw = cardW - 24f; val bh = 16f
        canvas.drawRoundRect(RectF(bx, by, bx + bw, by + bh), bh/2, bh/2, loveBarTrack)
        val fill = bw * (love / CamelState.MAX_LOVE)
        if (fill > 0f) canvas.drawRoundRect(RectF(bx, by, bx + fill, by + bh), bh/2, bh/2, loveBarFill)
        hudSmall.textSize = 19f
        canvas.drawText("${love.toInt()}%", bx + bw + 8f, by + bh - 1f, hudSmall)

        val btnW = 130f; val btnH = 64f
        val btnX = width - btnW - 16f; val btnY = height - btnH - 16f
        canvas.drawRoundRect(RectF(btnX, btnY, btnX + btnW, btnY + btnH), 14f, 14f, feedBtn)
        dpadText.textSize = 30f
        canvas.drawText("FEED", btnX + btnW / 2f, btnY + btnH * 0.66f, dpadText)

        drawMinimap(canvas)
    }

    private fun drawMinimap(canvas: Canvas) {
        val bmp = minimapBmp ?: return
        val ms = 88f; val mx = width - ms - 14f; val my = 14f
        canvas.drawRoundRect(RectF(mx - 2f, my - 2f, mx + ms + 2f, my + ms + 2f), 4f, 4f, p(Color.argb(200, 0, 0, 0)))
        canvas.drawBitmap(bmp, Rect(0, 0, world.width, world.height), RectF(mx, my, mx + ms, my + ms), null)
        val dotX = mx + (camel.x / world.width) * ms
        val dotY = my + (camel.y / world.height) * ms
        canvas.drawCircle(dotX, dotY, 3.5f, whiteFill)
    }

    private fun buildMinimap() {
        val bmp = Bitmap.createBitmap(world.width, world.height, Bitmap.Config.ARGB_8888)
        val px = IntArray(world.width * world.height)
        for (y in 0 until world.height) for (x in 0 until world.width) {
            px[y * world.width + x] = when (world.getTile(x, y)) {
                Tile.WATER         -> Color.rgb( 58,  96, 140)
                Tile.GRASS         -> Color.rgb(108, 148,  88)
                Tile.PALM          -> Color.rgb( 40,  90,  40)
                Tile.STONE_PATH    -> Color.rgb(160, 152, 136)
                Tile.BUILDING      -> Color.rgb( 88,  72,  60)
                Tile.PYRAMID, Tile.PYRAMID_STEPS -> Color.rgb(172, 160, 128)
                Tile.CACTUS        -> Color.rgb( 72, 112,  60)
                Tile.DEEP_SAND     -> Color.rgb(200, 190, 152)
                Tile.DUNE          -> Color.rgb(212, 202, 164)
                else               -> Color.rgb(224, 214, 176)
            }
        }
        bmp.setPixels(px, 0, world.width, 0, 0, world.width, world.height)
        minimapBmp = bmp
    }

    // ── Location banner ────────────────────────────────────────────────────────
    private fun drawLocationBanner(canvas: Canvas) {
        val alpha = ((locationLabelTimer / 3.5f) * 255).toInt().coerceIn(0, 255)
        locationText.alpha = alpha
        val tw = locationText.measureText(locationLabel)
        val cx = width / 2f; val by = height * 0.14f
        canvas.drawRoundRect(RectF(cx - tw/2 - 22f, by - 34f, cx + tw/2 + 22f, by + 12f), 12f, 12f,
            p(Color.argb(alpha * 2 / 3, 0, 0, 0)))
        canvas.drawText(locationLabel, cx, by, locationText)
        locationText.alpha = 255
    }

    // ── D-pad ──────────────────────────────────────────────────────────────────
    private fun drawDpad(canvas: Canvas) {
        val cx = dpadCX; val cy = dpadCY; val r = dpadR; val btnR = r * 0.56f
        canvas.drawCircle(cx, cy, btnR * 0.55f, dpadBg)
        val dirs   = listOf(0f to -r, 0f to r, -r to 0f, r to 0f)
        val labels = listOf("▲", "▼", "◀", "▶")
        val active = listOf(dpadUp, dpadDown, dpadLeft, dpadRight)
        dirs.forEachIndexed { i, (dx, dy) ->
            val bx = cx + dx; val by2 = cy + dy
            canvas.drawCircle(bx, by2, btnR, if (active[i]) dpadActive else dpadBg)
            dpadText.textSize = btnR * 0.82f
            canvas.drawText(labels[i], bx, by2 + dpadText.textSize * 0.36f, dpadText)
        }
    }

    // ── Input ──────────────────────────────────────────────────────────────────
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val tx = event.x; val ty = event.y
        val btnW = 130f; val btnH = 64f
        val feedX = width - btnW - 16f; val feedY = height - btnH - 16f

        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                if (tx >= feedX && tx <= feedX + btnW && ty >= feedY && ty <= feedY + btnH) {
                    handleFeed(); return true
                }
                updateDpad(tx, ty)
                if (inputDx != 0f || inputDy != 0f) {
                    lastInputMs = System.currentTimeMillis()
                    camel.autoWandering = false
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> clearDpad()
        }
        return true
    }

    private fun updateDpad(tx: Float, ty: Float) {
        val dx = tx - dpadCX; val dy = ty - dpadCY
        val dist = sqrt(dx * dx + dy * dy)
        // Accept touch anywhere within 2.2x the dpad radius — very generous
        if (dist > dpadR * 2.2f) { clearDpad(); return }
        dpadUp = false; dpadDown = false; dpadLeft = false; dpadRight = false
        inputDx = 0f; inputDy = 0f
        if (dist < dpadR * 0.22f) return  // dead zone at exact center
        if (abs(dx) >= abs(dy)) {
            if (dx > 0) { dpadRight = true; inputDx = 1f } else { dpadLeft = true; inputDx = -1f }
        } else {
            if (dy > 0) { dpadDown = true; inputDy = 1f } else { dpadUp = true; inputDy = -1f }
        }
    }

    private fun clearDpad() {
        inputDx = 0f; inputDy = 0f
        dpadUp = false; dpadDown = false; dpadLeft = false; dpadRight = false
    }

    private fun handleFeed() {
        camelState = camelState.withFeed()
        stateManager.save(camelState)
        lastInputMs = System.currentTimeMillis()
    }

    fun onResume() { camelState = stateManager.load() }
    fun onPause()  { stateManager.save(camelState) }
}
