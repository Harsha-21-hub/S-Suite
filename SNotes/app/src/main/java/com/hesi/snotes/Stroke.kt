package com.hesi.snotes

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One drawn stroke. Points are stored as a flat interleaved primitive list
 * [x0,y0,x1,y1,...] (world coordinates) - no per-point objects, no boxing.
 *
 * Performance notes:
 *
 *  1. INCREMENTAL BUILD. extend() only appends the segments that are new.
 *
 *  2. ONE DRAW CALL PER FINISHED STROKE. Finished pressure strokes are baked
 *     once into a closed outline polygon that is filled with a single drawPath().
 *
 *  3. CHUNKED LIVE STROKE. While a stroke is being drawn, everything except the
 *     last few dozen points is "frozen" into a path that no longer changes. Each
 *     frame then only re-draws a short tail, so the cost of one frame no longer
 *     grows with the length of the stroke (a long scribble used to get slower
 *     and slower the longer you kept the pen down).
 */
class Stroke(
    val points: FloatList,
    var color: Int,
    var width: Float,
    val eraser: Boolean,
    val straight: Boolean = false,
    val pressures: FloatList? = null
) {
    /** Cached centre-line (live: the frozen prefix; finished: the whole line). */
    val path = Path()

    /** Ink bounds in world space, already padded by half the stroke width. */
    val bounds = RectF()

    /** Filled outline for finished variable-width strokes (live: frozen prefix). */
    private val outline = Path()
    private var outlineValid = false

    /** Live only: the not-yet-frozen end of the centre line. */
    private var tail: Path? = null
    private var frozenUpTo = 0     // point index where the live tail starts

    private var sealedUp = false
    private var built = 0          // centre index already folded into the line
    private var boundsDone = 0     // points already folded into `raw`
    private var endX = 0f          // last midpoint written into the line
    private var endY = 0f
    private var maxPr = 1f
    private val raw = RectF()

    val isSealed get() = sealedUp

    fun addPoint(x: Float, y: Float, pressure: Float = 1f) {
        points.add(x, y)
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

        if (pressures != null) {
            // Variable width: the tail is drawn segment by segment; freeze the
            // older part into a filled outline once it is long enough.
            growBounds()
            if (n - 1 - frozenUpTo >= CHUNK) {
                val upTo = n - 2
                appendOutline(outline, frozenUpTo, upTo, capStart = true, capEnd = true)
                outlineValid = false        // the live outline is not "final"
                frozenUpTo = upTo
            }
            return
        }

        val t = tail ?: Path().also { tail = it }
        if (built == 0) {
            path.rewind()
            t.rewind()
            t.moveTo(points[0], points[1])
            endX = points[0]; endY = points[1]
            built = 1
        }
        var i = built
        while (i <= n - 2) {
            val cx = points[i * 2]
            val cy = points[i * 2 + 1]
            val mx = (cx + points[(i + 1) * 2]) * 0.5f
            val my = (cy + points[(i + 1) * 2 + 1]) * 0.5f
            t.quadTo(cx, cy, mx, my)
            endX = mx; endY = my
            i++
        }
        built = i
        if (built - frozenUpTo >= CHUNK) {
            // Freeze: move the tail into the static prefix path and restart it
            // at the current end point (round caps make the joint invisible).
            path.addPath(t)
            t.rewind()
            t.moveTo(endX, endY)
            frozenUpTo = built
        }
        growBounds()
    }

    /** Marks the stroke finished: closes the centre-line and bakes the outline. */
    fun seal() {
        sealedUp = true
        tail = null
        frozenUpTo = 0
        points.trimToSize()
        pressures?.trimToSize()
        rebuild()
    }

    /** Full rebuild. Used on load, after transforms, and when sealing. */
    fun rebuild() {
        path.rewind()
        outline.rewind()
        outlineValid = false
        built = 0
        boundsDone = 0
        val n = points.size / 2
        if (n == 0) { bounds.setEmpty(); return }
        // strokes loaded from disk never went through addPoint()
        pressures?.let { pr -> for (i in 0 until pr.size) if (pr[i] > maxPr) maxPr = pr[i] }

        val usesOutline = sealedUp && hasPressure() && !straight
        if (!usesOutline) {
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
        }
        built = if (straight || n <= 2) n else n - 1
        endX = points[(n - 1) * 2]
        endY = points[(n - 1) * 2 + 1]

        growBounds()
        if (usesOutline) {
            appendOutline(outline, 0, n - 1, capStart = true, capEnd = true)
            outlineValid = true
            // the centre line of a finished pressure stroke is never drawn -
            // free its native memory instead of keeping a second copy around
            path.reset()
        }
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
     * Appends the variable-width outline of points [from]..[to] as one closed
     * contour: left offsets forward -> round end cap -> right offsets backward
     * -> round start cap. Always a single contour, so the fill rule can never
     * punch holes. Tangents use the neighbours outside the range too, so two
     * adjacent ranges meet without a visible seam.
     */
    private fun appendOutline(out: Path, from: Int, to: Int, capStart: Boolean, capEnd: Boolean) {
        val pr = pressures ?: return
        val n = points.size / 2
        if (n < 2 || to <= from) return
        val half = width * 0.5f

        var nx: Float
        var ny: Float
        var first = true
        var lastAngle = 0.0
        var i = from
        while (i <= to) {
            tangent(i, n)
            nx = -tmpT[1]; ny = tmpT[0]
            val r = half * pr[i]
            val x = points[i * 2] + nx * r
            val y = points[i * 2 + 1] + ny * r
            if (first) { out.moveTo(x, y); first = false } else out.lineTo(x, y)
            lastAngle = atan2(ny.toDouble(), nx.toDouble())
            i++
        }
        if (capEnd) cap(out, points[to * 2], points[to * 2 + 1], lastAngle, half * pr[to])

        i = to
        while (i >= from) {
            tangent(i, n)
            nx = tmpT[1]; ny = -tmpT[0]
            val r = half * pr[i]
            out.lineTo(points[i * 2] + nx * r, points[i * 2 + 1] + ny * r)
            lastAngle = atan2(ny.toDouble(), nx.toDouble())
            i--
        }
        if (capStart) cap(out, points[from * 2], points[from * 2 + 1], lastAngle, half * pr[from])
        out.close()
    }

    private val tmpT = FloatArray(2)

    private fun tangent(i: Int, n: Int) {
        val a = if (i > 0) i - 1 else i
        val b = if (i < n - 1) i + 1 else i
        var tx = points[b * 2] - points[a * 2]
        var ty = points[b * 2 + 1] - points[a * 2 + 1]
        val len = hypot(tx, ty)
        if (len < 1e-4f) { tx = 1f; ty = 0f } else { tx /= len; ty /= len }
        tmpT[0] = tx; tmpT[1] = ty
    }

    private fun cap(out: Path, px: Float, py: Float, fromAngle: Double, r: Float) {
        val steps = 8
        for (k in 1..steps) {
            val a = fromAngle - Math.PI * k / steps
            out.lineTo(px + (r * cos(a)).toFloat(), py + (r * sin(a)).toFloat())
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
            tail?.let { if (!sealedUp) canvas.drawPath(it, paint) }
            drawTail(canvas, paint)
            return
        }
        // Live variable-width stroke (only ever one of these at a time):
        // the frozen prefix as one filled path + the short live tail.
        val pr = pressures!!
        val n = points.size / 2
        if (frozenUpTo > 0) {
            paint.style = Paint.Style.FILL
            canvas.drawPath(outline, paint)
            paint.style = Paint.Style.STROKE
        }
        var i = frozenUpTo
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
        val a = points.rawArray()
        var i = 0
        val n = points.size
        while (i < n) {
            a[i] += dx
            a[i + 1] += dy
            i += 2
        }
        path.offset(dx, dy)
        if (outlineValid) outline.offset(dx, dy)
        raw.offset(dx, dy)
        bounds.offset(dx, dy)
        endX += dx; endY += dy
    }

    /** Moves every point through [map] (x, y, out) and rebuilds the geometry. */
    fun mapPoints(map: (Float, Float, FloatArray) -> Unit) {
        val tmp = FloatArray(2)
        val a = points.rawArray()
        var i = 0
        while (i < points.size) {
            map(a[i], a[i + 1], tmp)
            a[i] = tmp[0]
            a[i + 1] = tmp[1]
            i += 2
        }
        rebuild()
    }

    fun scaleAround(f: Float, px: Float, py: Float) {
        val a = points.rawArray()
        var i = 0
        while (i < points.size) {
            a[i] = px + (a[i] - px) * f
            a[i + 1] = py + (a[i + 1] - py) * f
            i += 2
        }
        width *= f
        rebuild()
    }

    /** Is (x,y) within r of this stroke's ink? Cheap bounds reject first. */
    fun hits(x: Float, y: Float, r: Float): Boolean {
        if (x < bounds.left - r || x > bounds.right + r ||
            y < bounds.top - r || y > bounds.bottom + r) return false
        return distanceTo(x, y) <= width * (if (pressures != null) maxPr else 1f) / 2f + r
    }

    /** Shortest distance from (x,y) to this stroke's polyline (Float.MAX if empty). */
    fun distanceTo(x: Float, y: Float): Float {
        val n = points.size / 2
        if (n == 0) return Float.MAX_VALUE
        val p = points.rawArray()
        if (n == 1) {
            val dx = p[0] - x; val dy = p[1] - y
            return sqrt(dx * dx + dy * dy)
        }
        var best = Float.MAX_VALUE
        var i = 0
        while (i < n - 1) {
            val x1 = p[i * 2]; val y1 = p[i * 2 + 1]
            val x2 = p[(i + 1) * 2]; val y2 = p[(i + 1) * 2 + 1]
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
        return sqrt(best)
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

    /** A sealed deep copy (own point buffers). */
    fun copy(color: Int = this.color): Stroke =
        Stroke(FloatList(points), color, width, eraser, straight, pressures?.let { FloatList(it) })
            .also { it.seal() }

    private companion object {
        /** Points per frozen chunk of a live stroke. */
        const val CHUNK = 48
    }
}
