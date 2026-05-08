package com.petcamel

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.abs
import kotlin.math.sin

class CamelGameView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var state: CamelState = CamelState.default()
        set(value) {
            field = value
            invalidate()
        }

    var onPet: (() -> Unit)? = null

    private val blackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.FILL
    }
    private val whitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val thinStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = 36f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val smallTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = 26f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
    }
    private val grayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.LTGRAY
        style = Paint.Style.FILL
    }
    private val darkGrayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.DKGRAY
        style = Paint.Style.FILL
    }

    private var walkPhase = 0f
    private var camelX = 0f
    private var walkDirection = 1f
    private var heartAnimAlpha = 0f
    private var feedAnimAlpha = 0f
    private var heartY = 0f
    private var feedY = 0f
    private var blinkTimer = 0f

    private val walkAnimator = ValueAnimator.ofFloat(0f, (2 * Math.PI).toFloat()).apply {
        duration = 800
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.RESTART
        interpolator = LinearInterpolator()
        addUpdateListener {
            walkPhase = it.animatedValue as Float
            blinkTimer = (blinkTimer + 0.016f) % 6f
            invalidate()
        }
    }

    private val moveAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 4000
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
        interpolator = LinearInterpolator()
        addUpdateListener {
            val t = it.animatedValue as Float
            camelX = width * 0.1f + (width * 0.6f) * t
            walkDirection = if (it.currentPlayTime.toFloat() / it.duration < 0.5f) 1f else -1f
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        camelX = w * 0.3f
        walkAnimator.start()
        moveAnimator.start()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        walkAnimator.cancel()
        moveAnimator.cancel()
    }

    fun triggerHeartAnimation() {
        heartAnimAlpha = 1f
        heartY = height * 0.35f
        val animator = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 1200
            addUpdateListener {
                heartAnimAlpha = it.animatedValue as Float
                heartY -= 1.2f
                invalidate()
            }
        }
        animator.start()
    }

    fun triggerFeedAnimation() {
        feedAnimAlpha = 1f
        feedY = height * 0.35f
        val animator = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 1200
            addUpdateListener {
                feedAnimAlpha = it.animatedValue as Float
                feedY -= 1.0f
                invalidate()
            }
        }
        animator.start()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            val cx = camelX + width * 0.12f
            val cy = height * 0.52f
            val touchRadius = width * 0.2f
            val dx = event.x - cx
            val dy = event.y - cy
            if (dx * dx + dy * dy < touchRadius * touchRadius) {
                onPet?.invoke()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        drawSky(canvas, w, h)
        drawSun(canvas, w, h)
        drawDunes(canvas, w, h)
        drawCamel(canvas, w, h)
        drawLoveMeter(canvas, w, h)
        drawNameLabel(canvas, w, h)
        drawFloatingHeart(canvas, w)
        drawFloatingFood(canvas, w)
    }

    private fun drawSky(canvas: Canvas, w: Float, h: Float) {
        val skyGradient = LinearGradient(
            0f, 0f, 0f, h * 0.6f,
            Color.WHITE, Color.LTGRAY,
            Shader.TileMode.CLAMP
        )
        val skyPaint = Paint().apply { shader = skyGradient }
        canvas.drawRect(0f, 0f, w, h * 0.65f, skyPaint)
    }

    private fun drawSun(canvas: Canvas, w: Float, h: Float) {
        val sunX = w * 0.82f
        val sunY = h * 0.1f
        val sunR = w * 0.06f
        canvas.drawCircle(sunX, sunY, sunR, whitePaint)
        canvas.drawCircle(sunX, sunY, sunR, strokePaint)
        // rays
        for (i in 0 until 8) {
            val angle = Math.PI * 2 * i / 8
            val inner = sunR + w * 0.012f
            val outer = sunR + w * 0.03f
            canvas.drawLine(
                (sunX + Math.cos(angle) * inner).toFloat(),
                (sunY + Math.sin(angle) * inner).toFloat(),
                (sunX + Math.cos(angle) * outer).toFloat(),
                (sunY + Math.sin(angle) * outer).toFloat(),
                strokePaint
            )
        }
    }

    private fun drawDunes(canvas: Canvas, w: Float, h: Float) {
        // back dune (lighter)
        val backDune = Path().apply {
            moveTo(0f, h * 0.62f)
            cubicTo(w * 0.15f, h * 0.50f, w * 0.35f, h * 0.58f, w * 0.55f, h * 0.52f)
            cubicTo(w * 0.70f, h * 0.47f, w * 0.85f, h * 0.55f, w, h * 0.60f)
            lineTo(w, h)
            lineTo(0f, h)
            close()
        }
        canvas.drawPath(backDune, grayPaint)
        canvas.drawPath(backDune, thinStrokePaint)

        // front dune (dark)
        val frontDune = Path().apply {
            moveTo(0f, h * 0.75f)
            cubicTo(w * 0.2f, h * 0.65f, w * 0.4f, h * 0.72f, w * 0.6f, h * 0.66f)
            cubicTo(w * 0.75f, h * 0.61f, w * 0.88f, h * 0.68f, w, h * 0.73f)
            lineTo(w, h)
            lineTo(0f, h)
            close()
        }
        canvas.drawPath(frontDune, darkGrayPaint)

        // sandy ground
        val ground = Paint().apply {
            val sandGrad = LinearGradient(
                0f, h * 0.72f, 0f, h,
                Color.DKGRAY, Color.BLACK,
                Shader.TileMode.CLAMP
            )
            shader = sandGrad
        }
        canvas.drawRect(0f, h * 0.82f, w, h, ground)
    }

    private fun drawCamel(canvas: Canvas, w: Float, h: Float) {
        canvas.save()

        val scale = w / 400f
        val baseX = camelX
        val groundY = h * 0.72f

        // flip when walking left
        if (walkDirection < 0) {
            val mirrorX = baseX + w * 0.12f
            canvas.scale(-1f, 1f, mirrorX, groundY)
        }

        val bodyW = 110f * scale
        val bodyH = 55f * scale
        val bodyX = baseX
        val bodyY = groundY - bodyH - 55f * scale

        // legs with walk animation
        val legW = 10f * scale
        val legH = 55f * scale
        val legY = bodyY + bodyH - 4f * scale
        val frontLegX = bodyX + bodyW * 0.65f
        val backLegX = bodyX + bodyW * 0.2f

        val swing = sin(walkPhase) * 12f * scale
        drawLeg(canvas, frontLegX, legY, legW, legH, swing, scale)
        drawLeg(canvas, frontLegX + legW * 1.6f, legY, legW, legH, -swing, scale)
        drawLeg(canvas, backLegX, legY, legW, legH, -swing, scale)
        drawLeg(canvas, backLegX + legW * 1.6f, legY, legW, legH, swing, scale)

        // body
        val bodyRect = RectF(bodyX, bodyY, bodyX + bodyW, bodyY + bodyH)
        canvas.drawRoundRect(bodyRect, 28f * scale, 28f * scale, whitePaint)
        canvas.drawRoundRect(bodyRect, 28f * scale, 28f * scale, strokePaint)

        // humps
        drawHump(canvas, bodyX + bodyW * 0.38f, bodyY, 26f * scale, 34f * scale, scale)
        drawHump(canvas, bodyX + bodyW * 0.62f, bodyY, 20f * scale, 26f * scale, scale)

        // neck
        val neckPath = Path().apply {
            moveTo(bodyX + bodyW * 0.85f, bodyY + 10f * scale)
            lineTo(bodyX + bodyW * 0.85f + 14f * scale, bodyY + 10f * scale)
            lineTo(bodyX + bodyW + 30f * scale, bodyY - 18f * scale)
            lineTo(bodyX + bodyW + 16f * scale, bodyY - 18f * scale)
            close()
        }
        canvas.drawPath(neckPath, whitePaint)
        canvas.drawPath(neckPath, strokePaint)

        // head
        val headX = bodyX + bodyW + 8f * scale
        val headY = bodyY - 42f * scale
        val headW = 38f * scale
        val headH = 26f * scale
        val headRect = RectF(headX, headY, headX + headW, headY + headH)
        canvas.drawRoundRect(headRect, 12f * scale, 12f * scale, whitePaint)
        canvas.drawRoundRect(headRect, 12f * scale, 12f * scale, strokePaint)

        // snout
        val snoutRect = RectF(headX + headW * 0.55f, headY + headH * 0.45f, headX + headW + 10f * scale, headY + headH * 0.9f)
        canvas.drawRoundRect(snoutRect, 6f * scale, 6f * scale, whitePaint)
        canvas.drawRoundRect(snoutRect, 6f * scale, 6f * scale, strokePaint)

        // nostril
        canvas.drawCircle(headX + headW + 4f * scale, headY + headH * 0.65f, 3f * scale, blackPaint)

        // eye
        val eyeX = headX + headW * 0.7f
        val eyeY = headY + headH * 0.28f
        val isBlinking = blinkTimer % 6f < 0.25f
        if (isBlinking) {
            canvas.drawLine(eyeX - 5f * scale, eyeY, eyeX + 5f * scale, eyeY, strokePaint)
        } else {
            canvas.drawCircle(eyeX, eyeY, 4f * scale, blackPaint)
            canvas.drawCircle(eyeX + 1.5f * scale, eyeY - 1.5f * scale, 1.5f * scale, whitePaint)
        }

        // ear
        val earPath = Path().apply {
            moveTo(headX + headW * 0.3f, headY)
            lineTo(headX + headW * 0.15f, headY - 12f * scale)
            lineTo(headX + headW * 0.45f, headY - 8f * scale)
            close()
        }
        canvas.drawPath(earPath, whitePaint)
        canvas.drawPath(earPath, strokePaint)

        // tail
        val tailPath = Path().apply {
            moveTo(bodyX + 6f * scale, bodyY + bodyH * 0.5f)
            quadTo(bodyX - 18f * scale, bodyY + bodyH * 0.3f + sin(walkPhase) * 5f * scale,
                bodyX - 10f * scale, bodyY + bodyH * 0.1f)
        }
        canvas.drawPath(tailPath, thinStrokePaint)

        canvas.restore()
    }

    private fun drawLeg(canvas: Canvas, x: Float, y: Float, w: Float, h: Float, swing: Float, scale: Float) {
        canvas.save()
        canvas.rotate(swing, x + w / 2, y)
        val legRect = RectF(x, y, x + w, y + h)
        canvas.drawRoundRect(legRect, 5f * scale, 5f * scale, whitePaint)
        canvas.drawRoundRect(legRect, 5f * scale, 5f * scale, strokePaint)
        // hoof
        val hoofRect = RectF(x - 2f * scale, y + h - 8f * scale, x + w + 2f * scale, y + h + 4f * scale)
        canvas.drawRoundRect(hoofRect, 4f * scale, 4f * scale, blackPaint)
        canvas.restore()
    }

    private fun drawHump(canvas: Canvas, cx: Float, baseY: Float, hw: Float, hh: Float, scale: Float) {
        val humpPath = Path().apply {
            moveTo(cx - hw, baseY + 4f * scale)
            cubicTo(cx - hw, baseY - hh, cx + hw, baseY - hh, cx + hw, baseY + 4f * scale)
            close()
        }
        canvas.drawPath(humpPath, whitePaint)
        canvas.drawPath(humpPath, strokePaint)
    }

    private fun drawLoveMeter(canvas: Canvas, w: Float, h: Float) {
        val love = state.currentLove()
        val meterLeft = w * 0.05f
        val meterTop = h * 0.04f
        val meterW = w * 0.55f
        val meterH = h * 0.04f

        // label
        textPaint.textSize = h * 0.028f
        canvas.drawText("LOVE", meterLeft, meterTop - 4f, textPaint)

        // background track
        val trackRect = RectF(meterLeft, meterTop, meterLeft + meterW, meterTop + meterH)
        canvas.drawRoundRect(trackRect, meterH / 2, meterH / 2, whitePaint)
        canvas.drawRoundRect(trackRect, meterH / 2, meterH / 2, strokePaint)

        // fill
        val fillW = meterW * (love / CamelState.MAX_LOVE)
        if (fillW > 0f) {
            val fillRect = RectF(meterLeft, meterTop, meterLeft + fillW, meterTop + meterH)
            canvas.drawRoundRect(fillRect, meterH / 2, meterH / 2, blackPaint)
        }

        // heart icon next to meter
        drawHeart(canvas, meterLeft + meterW + w * 0.06f, meterTop + meterH / 2, meterH * 0.55f, blackPaint)

        // percentage
        smallTextPaint.textSize = h * 0.022f
        val pct = love.toInt()
        canvas.drawText("$pct%", meterLeft + meterW + w * 0.13f, meterTop + meterH * 0.8f, smallTextPaint)
    }

    private fun drawHeart(canvas: Canvas, cx: Float, cy: Float, size: Float, paint: Paint) {
        val heartPath = Path().apply {
            moveTo(cx, cy + size * 0.3f)
            cubicTo(cx - size * 1.2f, cy - size * 0.8f, cx - size * 2f, cy + size * 0.3f, cx, cy + size * 1.6f)
            cubicTo(cx + size * 2f, cy + size * 0.3f, cx + size * 1.2f, cy - size * 0.8f, cx, cy + size * 0.3f)
            close()
        }
        canvas.drawPath(heartPath, paint)
    }

    private fun drawNameLabel(canvas: Canvas, w: Float, h: Float) {
        val name = if (state.isNamed && state.name.isNotBlank()) state.name else "???"
        textPaint.textSize = h * 0.032f
        val textW = textPaint.measureText(name)
        val labelX = camelX + w * 0.12f - textW / 2f
        val labelY = h * 0.38f
        canvas.drawText(name, labelX, labelY, textPaint)
    }

    private fun drawFloatingHeart(canvas: Canvas, w: Float) {
        if (heartAnimAlpha <= 0f) return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            alpha = (heartAnimAlpha * 255).toInt()
            style = Paint.Style.FILL
        }
        drawHeart(canvas, camelX + w * 0.12f, heartY, 14f, paint)
    }

    private fun drawFloatingFood(canvas: Canvas, w: Float) {
        if (feedAnimAlpha <= 0f) return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            alpha = (feedAnimAlpha * 255).toInt()
            style = Paint.Style.FILL
        }
        // draw a simple leaf/food shape
        val fx = camelX + w * 0.18f
        val fy = feedY
        val s = 10f
        val leafPath = Path().apply {
            moveTo(fx, fy - s * 2)
            cubicTo(fx + s * 1.5f, fy - s * 1.5f, fx + s * 1.5f, fy + s, fx, fy + s)
            cubicTo(fx - s * 1.5f, fy + s, fx - s * 1.5f, fy - s * 1.5f, fx, fy - s * 2)
        }
        canvas.drawPath(leafPath, paint)
    }
}
