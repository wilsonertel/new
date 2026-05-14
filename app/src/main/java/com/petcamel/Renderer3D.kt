package com.petcamel

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.*

/**
 * Software perspective renderer for N64-style 3D graphics.
 *
 * Coordinate system: X = East (right), Y = South (down in world), Z = Up.
 * Camera orbits the target at a fixed distance, driven by azDeg / elDeg.
 */
class Renderer3D {

    // Mutable so touch input can orbit the camera
    var azDeg = -25f
    var elDeg = 38f

    // Camera basis vectors — recomputed by updateBasis()
    var fwdX = 0f; var fwdY = 0f; var fwdZ = 0f
    var rgtX = 0f; var rgtY = 0f
    val rgtZ = 0f
    var upX  = 0f; var upY  = 0f; var upZ  = 0f

    // Screen dimensions
    var screenW = 0f
    var screenH = 0f
    var fovScale = 0f     // pixels per (worldUnit / depth)

    // Camera world position (updated per frame)
    var camX = 0f
    var camY = 0f
    var camZ = 0f

    // Camera distance from player, in world tile units
    var camDist = 14f

    init { updateBasis() }

    /** Recompute basis vectors from current azDeg / elDeg. Call after changing either. */
    fun updateBasis() {
        val azRad = azDeg * PI.toFloat() / 180f
        val elRad = elDeg * PI.toFloat() / 180f

        fwdX = sin(azRad) * cos(elRad)
        fwdY = cos(azRad) * cos(elRad)
        fwdZ = -sin(elRad)

        // right = cross(forward, worldUp=(0,0,1))
        val rawRX = fwdY
        val rawRY = -fwdX
        val rawRLen = sqrt(rawRX * rawRX + rawRY * rawRY)
        rgtX = rawRX / rawRLen
        rgtY = rawRY / rawRLen
        // rgtZ is always 0

        // up = cross(right, forward)  rgtZ=0 simplifies the cross product
        upX = rgtY * fwdZ
        upY = -rgtX * fwdZ
        upZ = rgtX * fwdY - rgtY * fwdX
    }

    fun updateSize(w: Float, h: Float) {
        screenW = w
        screenH = h
        // FOV 62° vertical
        fovScale = h / (2f * tan(62f * PI.toFloat() / 360f))
    }

    fun updateCamera(targetX: Float, targetY: Float) {
        camX = targetX - fwdX * camDist
        camY = targetY - fwdY * camDist
        camZ = -fwdZ * camDist
    }

    /**
     * Project world point (wx, wy, wz) to screen [screenX, screenY, depth].
     * Returns null if the point is behind the camera.
     */
    fun project(wx: Float, wy: Float, wz: Float = 0f): FloatArray? {
        val dx = wx - camX
        val dy = wy - camY
        val dz = wz - camZ

        val csz = dx * fwdX + dy * fwdY + dz * fwdZ
        if (csz < 0.05f) return null

        val csx = dx * rgtX + dy * rgtY
        val csy = dx * upX + dy * upY + dz * upZ

        val sx = screenW * 0.5f + csx / csz * fovScale
        val sy = screenH * 0.5f - csy / csz * fovScale

        return floatArrayOf(sx, sy, csz)
    }

    /** Depth of a world point, for painter's algorithm sorting. */
    fun depth(wx: Float, wy: Float, wz: Float = 0f): Float {
        return (wx - camX) * fwdX + (wy - camY) * fwdY + (wz - camZ) * fwdZ
    }

    /** World-space pixel scale at a given depth (pixels per tile unit). */
    fun scaleAt(depth: Float): Float = if (depth > 0.1f) fovScale / depth else 0f

    // ── Convenience: draw a filled quadrilateral from 4 projected points ──────
    fun quad(canvas: Canvas, p0: FloatArray, p1: FloatArray, p2: FloatArray, p3: FloatArray,
             paint: Paint) {
        val path = Path()
        path.moveTo(p0[0], p0[1])
        path.lineTo(p1[0], p1[1])
        path.lineTo(p2[0], p2[1])
        path.lineTo(p3[0], p3[1])
        path.close()
        canvas.drawPath(path, paint)
    }

    // ── Darken a color for side faces ─────────────────────────────────────────
    companion object {
        fun shade(color: Int, factor: Float): Int {
            val r = (Color.red(color) * factor).toInt().coerceIn(0, 255)
            val g = (Color.green(color) * factor).toInt().coerceIn(0, 255)
            val b = (Color.blue(color) * factor).toInt().coerceIn(0, 255)
            return Color.rgb(r, g, b)
        }
    }
}
