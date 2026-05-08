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
    private val camel = CamelEntity(40f, 38f)

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
    private var dpadUp = false; private var dpadDown = false
    private var dpadLeft = false; private var dpadRight = false
    private var dpadCX = 0f; private var dpadCY = 0f
    private val dpadR = 110f   // pixels from dpad centre to button centre

    // ── Minimap ────────────────────────────────────────────────────────────────
    private var minimapBmp: Bitmap? = null

    // ── Paints ─────────────────────────────────────────────────────────────────
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
    private fun paint(color: Int, style: Paint.Style = Paint.Style.FILL) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; this.style = style }

    private val sandPaint      = paint(Color.rgb(224, 214, 176))
    private val bodyPaint      = paint(Color.rgb(210, 196, 156))
    private val bodyStroke     = paint(Color.BLACK, Paint.Style.STROKE).apply { strokeWidth = 2.5f }
    private val blackFill      = paint(Color.BLACK)
    private val whiteFill      = paint(Color.WHITE)
    private val hudBg          = paint(Color.argb(180,  12,  12,  12))
    private val hudText        = paint(Color.WHITE).apply {
        typeface = Typeface.MONOSPACE; textSize = 30f
    }
    private val hudSmall       = paint(Color.WHITE).apply {
        typeface = Typeface.MONOSPACE; textSize = 22f
    }
    private val loveBarFill    = paint(Color.rgb(230, 80, 80))
    private val loveBarTrack   = paint(Color.argb(100, 255, 255, 255))
    private val dpadBg         = paint(Color.argb(160, 20, 20, 20))
    private val dpadActive     = paint(Color.argb(220, 200, 200, 200))
    private val dpadText       = paint(Color.WHITE).apply {
        typeface = Typeface.MONOSPACE; textAlign = Paint.Align.CENTER
    }
    private val locationText   = paint(Color.WHITE).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.CENTER; textSize = 38f
    }
    private val feedBtn        = paint(Color.argb(180, 20, 20, 20))

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
        dpadCX = w * 0.18f
        dpadCY = h * 0.83f
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
            try { draw(canvas) } finally { holder.unlockCanvasAndPost(canvas) }
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

    // ── Draw ───────────────────────────────────────────────────────────────────
    private fun draw(canvas: Canvas) {
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
        for (ty in y0..y1) for (tx in x0..x1) {
            val sx = tx * ts - camX
            val sy = ty * ts - camY
            drawTile(canvas, world.getTile(tx, ty), sx, sy, ts)
        }
    }

    private fun drawTile(canvas: Canvas, tile: Int, sx: Float, sy: Float, ts: Float) {
        val base = tileColors[tile] ?: tileColors[Tile.SAND]!!
        val bp = paint(base)
        canvas.drawRect(sx, sy, sx + ts, sy + ts, bp)

        when (tile) {
            Tile.WATER -> {
                val wp = paint(Color.rgb(80, 120, 170), Paint.Style.STROKE).apply { strokeWidth = 1.8f }
                for (row in 0..1) {
                    val wy = sy + ts * (0.38f + row * 0.26f)
                    val path = Path().apply {
                        moveTo(sx + ts * 0.05f, wy)
                        quadTo(sx + ts * 0.28f, wy - ts * 0.06f, sx + ts * 0.5f, wy)
                        quadTo(sx + ts * 0.72f, wy + ts * 0.06f, sx + ts * 0.95f, wy)
                    }
                    canvas.drawPath(path, wp)
                }
            }
            Tile.GRASS -> {
                val gp = paint(Color.rgb(80, 130, 60), Paint.Style.STROKE).apply { strokeWidth = 1.5f }
                listOf(.2f to .65f, .5f to .35f, .75f to .70f, .38f to .80f, .62f to .45f)
                    .forEach { (fx, fy) ->
                        canvas.drawLine(sx + fx * ts, sy + fy * ts, sx + (fx - .03f) * ts, sy + (fy - .18f) * ts, gp)
                    }
            }
            Tile.PALM -> {
                val trunk = paint(Color.rgb(90, 65, 40))
                canvas.drawRect(sx + ts * .44f, sy + ts * .3f, sx + ts * .56f, sy + ts * .85f, trunk)
                val leaf = paint(Color.rgb(50, 100, 40))
                repeat(6) { i ->
                    val a = Math.PI * 2 * i / 6
                    val lx = sx + ts * .5f + cos(a).toFloat() * ts * .28f
                    val ly = sy + ts * .22f + sin(a).toFloat() * ts * .16f
                    canvas.drawOval(RectF(lx - ts * .1f, ly - ts * .055f, lx + ts * .1f, ly + ts * .055f), leaf)
                }
                canvas.drawCircle(sx + ts * .5f, sy + ts * .2f, ts * .13f, leaf)
            }
            Tile.CACTUS -> {
                val cp = paint(Color.rgb(55, 100, 50))
                canvas.drawRoundRect(RectF(sx + ts*.42f, sy + ts*.18f, sx + ts*.58f, sy + ts*.88f), 5f, 5f, cp)
                canvas.drawRoundRect(RectF(sx + ts*.18f, sy + ts*.38f, sx + ts*.43f, sy + ts*.52f), 4f, 4f, cp)
                canvas.drawRoundRect(RectF(sx + ts*.57f, sy + ts*.46f, sx + ts*.82f, sy + ts*.60f), 4f, 4f, cp)
                canvas.drawRoundRect(RectF(sx + ts*.18f, sy + ts*.26f, sx + ts*.30f, sy + ts*.40f), 4f, 4f, cp)
                canvas.drawRoundRect(RectF(sx + ts*.70f, sy + ts*.33f, sx + ts*.82f, sy + ts*.48f), 4f, 4f, cp)
            }
            Tile.BUILDING -> {
                canvas.drawRect(sx, sy, sx + ts, sy + ts, paint(Color.rgb(88, 76, 64)))
                val win = paint(Color.rgb(50, 42, 34))
                canvas.drawRect(sx + ts*.18f, sy + ts*.18f, sx + ts*.44f, sy + ts*.52f, win)
                canvas.drawRect(sx + ts*.56f, sy + ts*.18f, sx + ts*.82f, sy + ts*.52f, win)
                canvas.drawRect(sx + ts*.36f, sy + ts*.62f, sx + ts*.64f, sy + ts*.90f, paint(Color.rgb(70, 58, 46)))
            }
            Tile.STONE_PATH -> {
                val lp = paint(Color.rgb(136, 128, 112), Paint.Style.STROKE).apply { strokeWidth = 1f }
                canvas.drawLine(sx + ts*.1f, sy + ts*.5f, sx + ts*.9f, sy + ts*.5f, lp)
                canvas.drawLine(sx + ts*.5f, sy + ts*.1f, sx + ts*.5f, sy + ts*.9f, lp)
            }
            Tile.PYRAMID -> {
                val pp = paint(Color.rgb(140, 128, 100))
                canvas.drawRect(sx, sy, sx + ts, sy + ts, pp)
                val shadow = paint(Color.rgb(100, 90, 70))
                canvas.drawRect(sx + ts*.5f, sy, sx + ts, sy + ts, shadow)
                val lp = paint(Color.rgb(180, 168, 140), Paint.Style.STROKE).apply { strokeWidth = 1.5f }
                canvas.drawRect(sx + ts*.1f, sy + ts*.1f, sx + ts*.9f, sy + ts*.9f, lp)
            }
            Tile.PYRAMID_STEPS -> {
                val lp = paint(Color.rgb(172, 162, 130), Paint.Style.STROKE).apply { strokeWidth = 1f }
                canvas.drawLine(sx, sy + ts * .5f, sx + ts, sy + ts * .5f, lp)
            }
            Tile.DUNE -> {
                val dp = paint(Color.rgb(195, 185, 145), Paint.Style.STROKE).apply { strokeWidth = 2.5f }
                canvas.drawArc(RectF(sx, sy + ts * .25f, sx + ts, sy + ts * 1.25f), 180f, 180f, false, dp)
            }
            Tile.DEEP_SAND -> {
                val dotP = paint(Color.rgb(180, 170, 132))
                listOf(.25f to .30f, .60f to .68f, .80f to .22f, .42f to .75f).forEach { (fx, fy) ->
                    canvas.drawCircle(sx + fx * ts, sy + fy * ts, ts * .035f, dotP)
                }
            }
        }
    }

    // ── Camel sprite ───────────────────────────────────────────────────────────
    private fun drawCamel(canvas: Canvas) {
        val sx = camel.x * tileSize - camX
        val sy = camel.y * tileSize - camY
        val ts = tileSize
        canvas.save()
        canvas.translate(sx, sy)
        val bob = if (camel.isMoving) sin(camel.walkPhase).toFloat() * ts * 0.03f else 0f
        when (camel.direction) {
            CamelEntity.Direction.DOWN  -> drawCamelFront(canvas, ts, bob)
            CamelEntity.Direction.UP    -> drawCamelBack(canvas, ts, bob)
            CamelEntity.Direction.RIGHT -> drawCamelSide(canvas, ts, bob, false)
            CamelEntity.Direction.LEFT  -> drawCamelSide(canvas, ts, bob, true)
        }
        canvas.restore()
    }

    private fun drawCamelFront(canvas: Canvas, ts: Float, bob: Float) {
        val cy = bob
        // legs
        val sw = if (camel.isMoving) sin(camel.walkPhase).toFloat() * ts * .07f else 0f
        legDot(canvas, -ts*.17f + sw, cy + ts*.16f, ts)
        legDot(canvas,  ts*.17f - sw, cy + ts*.16f, ts)
        legDot(canvas, -ts*.13f - sw, cy - ts*.06f, ts)
        legDot(canvas,  ts*.13f + sw, cy - ts*.06f, ts)
        // body
        oval(canvas, -ts*.22f, cy - ts*.24f, ts*.22f, cy + ts*.24f)
        // humps
        canvas.drawCircle(-ts*.09f, cy - ts*.22f, ts*.10f, bodyPaint)
        canvas.drawCircle( ts*.09f, cy - ts*.24f, ts*.09f, bodyPaint)
        canvas.drawCircle(-ts*.09f, cy - ts*.22f, ts*.10f, bodyStroke)
        canvas.drawCircle( ts*.09f, cy - ts*.24f, ts*.09f, bodyStroke)
        // head
        oval(canvas, -ts*.13f, cy + ts*.20f, ts*.13f, cy + ts*.40f)
        // ears
        canvas.drawOval(RectF(-ts*.17f, cy + ts*.17f, -ts*.07f, cy + ts*.27f), bodyPaint)
        canvas.drawOval(RectF( ts*.07f, cy + ts*.17f,  ts*.17f, cy + ts*.27f), bodyPaint)
        // eyes
        canvas.drawCircle(-ts*.06f, cy + ts*.27f, ts*.028f, blackFill)
        canvas.drawCircle( ts*.06f, cy + ts*.27f, ts*.028f, blackFill)
    }

    private fun drawCamelBack(canvas: Canvas, ts: Float, bob: Float) {
        val cy = bob
        val sw = if (camel.isMoving) sin(camel.walkPhase).toFloat() * ts * .07f else 0f
        legDot(canvas, -ts*.17f + sw, cy + ts*.16f, ts)
        legDot(canvas,  ts*.17f - sw, cy + ts*.16f, ts)
        legDot(canvas, -ts*.13f - sw, cy - ts*.06f, ts)
        legDot(canvas,  ts*.13f + sw, cy - ts*.06f, ts)
        oval(canvas, -ts*.22f, cy - ts*.24f, ts*.22f, cy + ts*.24f)
        canvas.drawCircle(-ts*.09f, cy - ts*.22f, ts*.10f, bodyPaint)
        canvas.drawCircle( ts*.09f, cy - ts*.24f, ts*.09f, bodyPaint)
        canvas.drawCircle(-ts*.09f, cy - ts*.22f, ts*.10f, bodyStroke)
        canvas.drawCircle( ts*.09f, cy - ts*.24f, ts*.09f, bodyStroke)
        // head (back)
        oval(canvas, -ts*.11f, cy - ts*.40f, ts*.11f, cy - ts*.22f)
        // tail
        val tailP = paint(Color.rgb(180, 168, 128), Paint.Style.STROKE).apply { strokeWidth = ts * .045f; strokeCap = Paint.Cap.ROUND }
        canvas.drawArc(RectF(-ts*.18f, cy + ts*.15f, ts*.02f, cy + ts*.38f), 90f, -180f, false, tailP)
    }

    private fun drawCamelSide(canvas: Canvas, ts: Float, bob: Float, flip: Boolean) {
        canvas.save()
        if (flip) canvas.scale(-1f, 1f)
        val cy = bob
        val sw = if (camel.isMoving) sin(camel.walkPhase).toFloat() * ts * .10f else 0f
        val legP = paint(Color.rgb(185, 172, 135), Paint.Style.STROKE).apply { strokeWidth = ts * .07f; strokeCap = Paint.Cap.ROUND }
        val hoofP = paint(Color.rgb(60, 50, 40), Paint.Style.STROKE).apply { strokeWidth = ts * .075f; strokeCap = Paint.Cap.ROUND }
        listOf(-ts*.16f to sw, -ts*.05f to -sw, ts*.07f to sw, ts*.18f to -sw).forEach { (lx, s) ->
            canvas.drawLine(lx, cy + ts*.13f, lx + s, cy + ts*.38f, legP)
            canvas.drawLine(lx + s, cy + ts*.38f, lx + s + ts*.05f, cy + ts*.44f, hoofP)
        }
        oval(canvas, -ts*.28f, cy - ts*.18f, ts*.28f, cy + ts*.18f)
        // hump
        canvas.drawOval(RectF(-ts*.12f, cy - ts*.36f, ts*.14f, cy - ts*.08f), bodyPaint)
        canvas.drawOval(RectF(-ts*.12f, cy - ts*.36f, ts*.14f, cy - ts*.08f), bodyStroke)
        // neck
        val neck = Path().apply {
            moveTo(ts*.20f, cy - ts*.14f); lineTo(ts*.28f, cy - ts*.14f)
            lineTo(ts*.38f, cy - ts*.30f); lineTo(ts*.30f, cy - ts*.30f); close()
        }
        canvas.drawPath(neck, bodyPaint); canvas.drawPath(neck, bodyStroke)
        // head
        oval(canvas, ts*.28f, cy - ts*.44f, ts*.48f, cy - ts*.22f)
        // snout
        canvas.drawOval(RectF(ts*.40f, cy - ts*.35f, ts*.55f, cy - ts*.24f), bodyPaint)
        canvas.drawOval(RectF(ts*.40f, cy - ts*.35f, ts*.55f, cy - ts*.24f), bodyStroke)
        canvas.drawCircle(ts*.53f, cy - ts*.27f, ts*.025f, blackFill)
        // eye
        canvas.drawCircle(ts*.38f, cy - ts*.37f, ts*.032f, blackFill)
        canvas.drawCircle(ts*.385f, cy - ts*.378f, ts*.012f, whiteFill)
        // ear
        val ear = Path().apply {
            moveTo(ts*.33f, cy - ts*.42f); lineTo(ts*.26f, cy - ts*.52f); lineTo(ts*.40f, cy - ts*.48f); close()
        }
        canvas.drawPath(ear, bodyPaint); canvas.drawPath(ear, bodyStroke)
        canvas.restore()
    }

    private fun oval(canvas: Canvas, x0: Float, y0: Float, x1: Float, y1: Float) {
        canvas.drawOval(RectF(x0, y0, x1, y1), bodyPaint)
        canvas.drawOval(RectF(x0, y0, x1, y1), bodyStroke)
    }

    private fun legDot(canvas: Canvas, x: Float, y: Float, ts: Float) {
        canvas.drawCircle(x, y, ts * .065f, bodyPaint)
        canvas.drawCircle(x, y, ts * .065f, bodyStroke)
    }

    // ── HUD ────────────────────────────────────────────────────────────────────
    private fun drawHUD(canvas: Canvas) {
        val love = camelState.currentLove()
        val name = camelState.name.takeIf { camelState.isNamed && it.isNotBlank() } ?: "???"

        // Top-left card
        val cardW = 240f; val cardH = 76f; val pad = 14f
        canvas.drawRoundRect(RectF(pad, pad, pad + cardW, pad + cardH), 14f, 14f, hudBg)
        hudText.textSize = 28f
        canvas.drawText(name, pad + 12f, pad + 30f, hudText)

        // Love bar
        val bx = pad + 12f; val by = pad + 42f; val bw = cardW - 24f; val bh = 16f
        canvas.drawRoundRect(RectF(bx, by, bx + bw, by + bh), bh / 2, bh / 2, loveBarTrack)
        val fill = bw * (love / CamelState.MAX_LOVE)
        if (fill > 0f) canvas.drawRoundRect(RectF(bx, by, bx + fill, by + bh), bh / 2, bh / 2, loveBarFill)
        hudSmall.textSize = 19f
        canvas.drawText("${love.toInt()}%", bx + bw + 8f, by + bh - 1f, hudSmall)

        // Feed button (bottom-right)
        val btnW = 130f; val btnH = 64f
        val btnX = width - btnW - 16f; val btnY = height - btnH - 16f
        canvas.drawRoundRect(RectF(btnX, btnY, btnX + btnW, btnY + btnH), 14f, 14f, feedBtn)
        dpadText.textSize = 30f
        canvas.drawText("FEED", btnX + btnW / 2f, btnY + btnH * 0.66f, dpadText)

        // Minimap (top-right)
        drawMinimap(canvas)
    }

    private fun drawMinimap(canvas: Canvas) {
        val bmp = minimapBmp ?: return
        val ms = 88f
        val mx = width - ms - 14f; val my = 14f
        val border = paint(Color.argb(200, 0, 0, 0))
        canvas.drawRoundRect(RectF(mx - 2f, my - 2f, mx + ms + 2f, my + ms + 2f), 4f, 4f, border)
        canvas.drawBitmap(bmp, Rect(0, 0, world.width, world.height), RectF(mx, my, mx + ms, my + ms), null)
        // camel dot
        val dotX = mx + (camel.x / world.width) * ms
        val dotY = my + (camel.y / world.height) * ms
        canvas.drawCircle(dotX, dotY, 3.5f, whiteFill)
        canvas.drawCircle(dotX, dotY, 3.5f, paint(Color.argb(200, 0, 0, 0), Paint.Style.STROKE).apply { strokeWidth = 1f })
    }

    private fun buildMinimap() {
        val bmp = Bitmap.createBitmap(world.width, world.height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(world.width * world.height)
        for (y in 0 until world.height) for (x in 0 until world.width) {
            pixels[y * world.width + x] = when (world.getTile(x, y)) {
                Tile.WATER         -> Color.rgb( 58,  96, 140)
                Tile.GRASS         -> Color.rgb(108, 148,  88)
                Tile.PALM          -> Color.rgb( 40,  90,  40)
                Tile.STONE_PATH    -> Color.rgb(160, 152, 136)
                Tile.BUILDING      -> Color.rgb( 88,  72,  60)
                Tile.PYRAMID,
                Tile.PYRAMID_STEPS -> Color.rgb(172, 160, 128)
                Tile.CACTUS        -> Color.rgb( 72, 112,  60)
                Tile.DEEP_SAND     -> Color.rgb(200, 190, 152)
                Tile.DUNE          -> Color.rgb(212, 202, 164)
                else               -> Color.rgb(224, 214, 176)
            }
        }
        bmp.setPixels(pixels, 0, world.width, 0, 0, world.width, world.height)
        minimapBmp = bmp
    }

    // ── Location banner ────────────────────────────────────────────────────────
    private fun drawLocationBanner(canvas: Canvas) {
        val alpha = ((locationLabelTimer / 3.5f) * 255).toInt().coerceIn(0, 255)
        locationText.alpha = alpha
        val tw = locationText.measureText(locationLabel)
        val cx = width / 2f; val by = height * 0.14f
        val bgP = paint(Color.argb(alpha * 2 / 3, 0, 0, 0))
        canvas.drawRoundRect(RectF(cx - tw / 2 - 22f, by - 34f, cx + tw / 2 + 22f, by + 12f), 12f, 12f, bgP)
        canvas.drawText(locationLabel, cx, by, locationText)
        locationText.alpha = 255
    }

    // ── D-pad ──────────────────────────────────────────────────────────────────
    private fun drawDpad(canvas: Canvas) {
        val cx = dpadCX; val cy = dpadCY; val r = dpadR; val btnR = r * 0.52f
        canvas.drawCircle(cx, cy, btnR * 0.6f, dpadBg)
        val dirs = listOf(0f to -r, 0f to r, -r to 0f, r to 0f)
        val labels = listOf("▲", "▼", "◀", "▶")
        val active = listOf(dpadUp, dpadDown, dpadLeft, dpadRight)
        dirs.forEachIndexed { i, (dx, dy) ->
            val bx = cx + dx; val by2 = cy + dy
            canvas.drawCircle(bx, by2, btnR, if (active[i]) dpadActive else dpadBg)
            dpadText.textSize = btnR * 0.88f
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
        if (dist < dpadR * 0.3f || dist > dpadR * 1.9f) { clearDpad(); return }
        dpadUp = false; dpadDown = false; dpadLeft = false; dpadRight = false
        inputDx = 0f; inputDy = 0f
        if (abs(dx) >= abs(dy)) { if (dx > 0) { dpadRight = true; inputDx = 1f } else { dpadLeft = true; inputDx = -1f } }
        else { if (dy > 0) { dpadDown = true; inputDy = 1f } else { dpadUp = true; inputDy = -1f } }
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
