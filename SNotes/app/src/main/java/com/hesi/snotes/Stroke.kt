package com.hesi.snotes

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * One drawn stroke. Points are stored as a flat interleaved list [x0,y0,x1,y1,...]
 * (world coordinates) to keep RAM usage low - no per-point objects.
 *
 * Requirement 6 (performance) changed two things here:
 *
 *  1. INCREMENTAL BUILD. The old code called rebuild() on every MOVE event, which
 *     re-created the whole Path from scratch -> O(n^2) work over one stroke and a
 *     GC storm on long strokes. extend() now only appends the segments that are
 *     actually new.
 *
 *  2. ONE DRAW CALL PER STROKE. Pressure strokes used to be rendered as N-1
 *     separate canvas.drawLine() calls. Since pressure is now always on
 *     (requirement 7) that would have meant tens of thousands of draw calls per
 *     frame. Finished strokes are instead converted once into a closed outline
 *     polygon that is filled with a single drawPath() - far less CPU, far less
 *     GPU overdraw, and it removes the dark "beads" where segments overlapped.
 */
class Stroke(
    val points: ArrayList<Float>,
    var color: Int,
    var width: Float,
    val eraser: Boolean,
    val straight: Boolean = false,
    val pressures: ArrayList<Float>? = null
) {
    /** Cached centre-line (used live, and for constant-width strokes). */
    val path = Path()

    /** Ink bounds in world space, already padded by half the stroke width. */
    val bounds = RectF()

    /** Filled outline for finished variable-width strokes. */
    private val outline = Path()
    private var outlineValid = false

    private var sealedUp = false
    private var built = 0          // centre index already folded into `path`
    private var boundsDone = 0     // points already folded into `raw`
    private var endX = 0f          // last midpoint written into `path`
    private var endY = 0f
    private var maxPr = 1f
    private val raw = RectF()

    fun addPoint(x: Float, y: Float, pressure: Float = 1f) {
        points.add(x)
        points.add(y)
        pressures?.add(pressure)
        if (pressure > maxPr) maxPr = pressure
    }

    fun hasPressure(): Boolean {
        val pr = pressures ?: return false
        return pr.size * 2 == points.size && pr.size >= 2
    }

    // =====================================================================
    //  Building
    // =====================================================================

    /** Cheap append-only update. Call after adding points while drawing. */
    fun extend() {
        val n = points.size / 2
        if (n == 0) return
        if (straight || sealedUp) { rebuild(); return }

        if (built == 0) {
            path.rewind()
            path.moveTo(points[0], points[1])
            endX = points[0]; endY = points[1]
            built = 1
        }
        var i = built
        while (i <= n - 2) {
            val cx = points[i * 2]
            val cy = points[i * 2 + 1]
            val mx = (cx + points[(i + 1) * 2]) * 0.5f
            val my = (cy + points[(i + 1) * 2 + 1]) * 0.5f
            path.quadTo(cx, cy, mx, my)
            endX = mx; endY = my
            i++
        }
        built = i
        growBounds()
    }

    /** Marks the stroke finished: closes the centre-line and bakes the outline. */
    fun seal() {
        sealedUp = true
        rebuild()
    }

    /** Full rebuild. Used on load, after transforms, and when sealing. */
    fun rebuild() {
        path.rewind()
        outlineValid = false
        built = 0
        boundsDone = 0
        val n = points.size / 2
        if (n == 0) { bounds.setEmpty(); return }

        path.moveTo(points[0], points[1])
        if (n == 1) {
            path.lineTo(points[0] + 0.01f, points[1])
        } else if (straight || n == 2) {
            var i = 1
            while (i < n) {
                path.lineTo(points[i * 2], points[i * 2 + 1])
                i++
            }
        } else {
            var i = 1
            while (i < n - 1) {
                val cx = points[i * 2]
                val cy = points[i * 2 + 1]
                val mx = (cx + points[(i + 1) * 2]) * 0.5f
                val my = (cy + points[(i + 1) * 2 + 1]) * 0.5f
                path.quadTo(cx, cy, mx, my)
                i++
            }
            path.lineTo(points[(n - 1) * 2], points[(n - 1) * 2 + 1])
        }
        built = if (straight || n <= 2) n else n - 1
        endX = points[(n - 1) * 2]
        endY = points[(n - 1) * 2 + 1]

        growBounds()
        if (sealedUp && hasPressure() && !straight) buildOutline()
    }

    private fun growBounds() {
        val n = points.size / 2
        if (n == 0) { bounds.setEmpty(); return }
        if (boundsDone == 0) {
            raw.set(points[0], points[1], points[0], points[1])
            boundsDone = 1
        }
        var i = boundsDone
        while (i < n) {
            val x = points[i * 2]
            val y = points[i * 2 + 1]
            if (x < raw.left) raw.left = x
            if (x > raw.right) raw.right = x
            if (y < raw.top) raw.top = y
            if (y > raw.bottom) raw.bottom = y
            i++
        }
        boundsDone = n
        val half = width * (if (pressures != null) maxPr else 1f) * 0.5f + 1f
        bounds.set(raw.left - half, raw.top - half, raw.right + half, raw.bottom + half)
    }

    /**
     * Turns the centre line into a closed variable-width polygon:
     * left offsets forward -> round end cap -> right offsets backward -> round
     * start cap. Always a single contour, so the fill rule can never punch holes.
     */
    private fun buildOutline() {
        val pr = pressures ?: return
        val n = points.size / 2
        if (n < 2) return
        outline.rewind()
        val half = width * 0.5f

        var nx: Float
        var ny: Float
        var first = true
        var lastAngle = 0.0
        var i = 0
        while (i < n) {
            val t = tangent(i, n)
            nx = -t[1]; ny = t[0]
            val r = half * pr[i]
            val x = points[i * 2] + nx * r
            val y = points[i * 2 + 1] + ny * r
            if (first) { outline.moveTo(x, y); first = false } else outline.lineTo(x, y)
            lastAngle = atan2(ny.toDouble(), nx.toDouble())
            i++
        }
        // end cap around the last point
        cap(points[(n - 1) * 2], points[(n - 1) * 2 + 1], lastAngle, half * pr[n - 1])

        i = n - 1
        while (i >= 0) {
            val t = tangent(i, n)
            nx = t[1]; ny = -t[0]
            val r = half * pr[i]
            outline.lineTo(points[i * 2] + nx * r, points[i * 2 + 1] + ny * r)
            lastAngle = atan2(ny.toDouble(), nx.toDouble())
            i--
        }
        // start cap
        cap(points[0], points[1], lastAngle, half * pr[0])
        outline.close()
        outlineValid = true
    }

    private val tmpT = FloatArray(2)

    private fun tangent(i: Int, n: Int): FloatArray {
        val a = if (i > 0) i - 1 else i
        val b = if (i < n - 1) i + 1 else i
        var tx = points[b * 2] - points[a * 2]
        var ty = points[b * 2 + 1] - points[a * 2 + 1]
        val len = hypot(tx, ty)
        if (len < 1e-4f) { tx = 1f; ty = 0f } else { tx /= len; ty /= len }
        tmpT[0] = tx; tmpT[1] = ty
        return tmpT
    }

    private fun cap(px: Float, py: Float, fromAngle: Double, r: Float) {
        val steps = 8
        for (k in 1..steps) {
            val a = fromAngle - Math.PI * k / steps
            outline.lineTo(px + (r * cos(a)).toFloat(), py + (r * sin(a)).toFloat())
        }
    }

    // =====================================================================
    //  Drawing
    // =====================================================================

    /**
     * The caller must have set paint.color. Style/strokeWidth are managed here
     * and always restored to STROKE before returning.
     */
    fun draw(canvas: Canvas, paint: Paint) {
        if (outlineValid) {
            paint.style = Paint.Style.FILL
            canvas.drawPath(outline, paint)
            paint.style = Paint.Style.STROKE
            return
        }
        if (!hasPressure()) {
            paint.strokeWidth = width
            canvas.drawPath(path, paint)
            drawTail(canvas, paint)
            return
        }
        // Live variable-width stroke (only ever one of these at a time).
        val pr = pressures!!
        val n = points.size / 2
        var i = 0
        while (i < n - 1) {
            paint.strokeWidth = width * (pr[i] + pr[i + 1]) * 0.5f
            canvas.drawLine(
                points[i * 2], points[i * 2 + 1],
                points[(i + 1) * 2], points[(i + 1) * 2 + 1], paint
            )
            i++
        }
        if (n == 1) {
            paint.strokeWidth = width * pr[0]
            canvas.drawPoint(points[0], points[1], paint)
        }
    }

    /** The un-sealed centre line stops at the last midpoint; close the gap. */
    private fun drawTail(canvas: Canvas, paint: Paint) {
        if (sealedUp || straight) return
        val n = points.size / 2
        if (n < 2) return
        canvas.drawLine(endX, endY, points[(n - 1) * 2], points[(n - 1) * 2 + 1], paint)
    }

    // =====================================================================
    //  Transforms / hit tests
    // =====================================================================

    fun translate(dx: Float, dy: Float) {
        var i = 0
        while (i < points.size) {
            points[i] = points[i] + dx
            points[i + 1] = points[i + 1] + dy
            i += 2
        }
        path.offset(dx, dy)
        if (outlineValid) outline.offset(dx, dy)
        raw.offset(dx, dy)
        bounds.offset(dx, dy)
        endX += dx; endY += dy
    }

    fun scaleAround(f: Float, px: Float, py: Float) {
        var i = 0
        while (i < points.size) {
            points[i] = px + (points[i] - px) * f
            points[i + 1] = py + (points[i + 1] - py) * f
            i += 2
        }
        width *= f
        rebuild()
    }

    /** Cheap hit test: is (x,y) within r of this stroke's ink? */
    fun hits(x: Float, y: Float, r: Float): Boolean = distanceTo(x, y) <= width / 2f + r

    /** Shortest distance from (x,y) to this stroke's polyline (Float.MAX if empty). */
    fun distanceTo(x: Float, y: Float): Float {
        val n = points.size / 2
        if (n == 0) return Float.MAX_VALUE
        if (n == 1) {
            val dx = points[0] - x; val dy = points[1] - y
            return kotlin.math.sqrt(dx * dx + dy * dy)
        }
        var best = Float.MAX_VALUE
        var i = 0
        while (i < n - 1) {
            val x1 = points[i * 2]; val y1 = points[i * 2 + 1]
            val x2 = points[(i + 1) * 2]; val y2 = points[(i + 1) * 2 + 1]
            val vx = x2 - x1; val vy = y2 - y1
            val wx = x - x1; val wy = y - y1
            val len2 = vx * vx + vy * vy
            val t = if (len2 <= 0f) 0f else ((wx * vx + wy * vy) / len2).coerceIn(0f, 1f)
            val dx = wx - vx * t
            val dy = wy - vy * t
            val d = dx * dx + dy * dy
            if (d < best) best = d
            i++
        }
        return kotlin.math.sqrt(best)
    }


    /** True if any point falls inside the rect (used by the selection tool). */
    fun anyPointIn(r: RectF): Boolean {
        var i = 0
        while (i < points.size) {
            if (r.contains(points[i], points[i + 1])) return true
            i += 2
        }
        return false
    }
}
