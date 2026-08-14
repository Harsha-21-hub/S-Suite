package com.hesi.snotes

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Manual shapes - drawn the natural, predictable way people expect when marking
 * up or lecturing (requirement 3):
 *
 *  - LINE / ARROW / ARC follow the drag vector (start -> end), any direction.
 *  - Every closed shape (rect, ellipse, triangle, diamond, star, square, circle)
 *    fills the axis-aligned box between the two drag corners. Because the box is
 *    built from min/max, dragging in ANY direction (up-left, down-right, ...) works
 *    and nothing rotates or gets a forced aspect ratio. Drag a wide box -> a wide
 *    shape; a tall box -> a tall shape.
 *  - SQUARE / CIRCLE stay regular, sized from the larger drag dimension and
 *    anchored at the start corner so they never collapse on an axis-aligned drag.
 */
object Shapes {

    enum class Kind { LINE, ARROW, RECT, SQUARE, ELLIPSE, CIRCLE, TRIANGLE, DIAMOND, STAR, ARC }

    val ALL = Kind.values()

    fun label(k: Kind): String = k.name

    fun build(kind: Kind, x0: Float, y0: Float, x1: Float, y1: Float): ArrayList<Float> {
        val out = ArrayList<Float>(96)
        buildInto(kind, x0, y0, x1, y1, out)
        return out
    }

    fun buildInto(
        kind: Kind, x0: Float, y0: Float, x1: Float, y1: Float, out: ArrayList<Float>
    ) {
        out.clear()

        // Directional shapes follow the drag exactly.
        when (kind) {
            Kind.LINE -> { push(out, x0, y0, x1, y1); return }
            Kind.ARROW -> { arrow(x0, y0, x1, y1, out); return }
            Kind.ARC -> { arc(x0, y0, x1, y1, out); return }
            else -> {}
        }

        // Closed shapes fill the axis-aligned box between the two drag corners.
        var l = min(x0, x1); var r = max(x0, x1)
        var t = min(y0, y1); var b = max(y0, y1)

        if (kind == Kind.SQUARE || kind == Kind.CIRCLE) {
            // Regular: use the larger side, anchored at the start corner so the
            // shape follows the drag and never collapses on a straight drag.
            val side = max(abs(x1 - x0), abs(y1 - y0)).coerceAtLeast(1f)
            val sx = if (x1 >= x0) 1f else -1f
            val sy = if (y1 >= y0) 1f else -1f
            l = min(x0, x0 + sx * side); r = max(x0, x0 + sx * side)
            t = min(y0, y0 + sy * side); b = max(y0, y0 + sy * side)
        }

        val cx = (l + r) * 0.5f
        val cy = (t + b) * 0.5f

        when (kind) {
            Kind.RECT, Kind.SQUARE ->
                push(out, l, t, r, t, r, b, l, b, l, t)

            Kind.ELLIPSE, Kind.CIRCLE ->
                oval(cx, cy, (r - l) * 0.5f, (b - t) * 0.5f, 64, out)

            // Triangle is deliberately built from a stable drag-direction layout.
            // The previous implementation rotated a wide/tall bounding triangle a
            // second time, which could make it cross itself and look messy during
            // fast or mostly-horizontal/mostly-vertical drags.  Here the dominant
            // drag axis chooses the pointing direction, while diagonal drags use
            // that same stable orientation without any secondary rotation.
            Kind.TRIANGLE -> {
                val dx = x1 - x0
                val dy = y1 - y0
                val w = (r - l).coerceAtLeast(1f)
                val h = (b - t).coerceAtLeast(1f)

                if (abs(dx) >= abs(dy)) {
                    // Horizontal drag -> point left/right.
                    val apexX = if (dx >= 0f) r else l
                    val baseX = if (dx >= 0f) l else r
                    push(
                        out,
                        apexX, cy,
                        baseX, t,
                        baseX, b,
                        apexX, cy
                    )
                } else {
                    // Vertical drag -> point up/down.
                    val apexY = if (dy >= 0f) b else t
                    val baseY = if (dy >= 0f) t else b
                    push(
                        out,
                        cx, apexY,
                        l, baseY,
                        r, baseY,
                        cx, apexY
                    )
                }
            }

            Kind.DIAMOND ->
                push(out, cx, t, r, cy, cx, b, l, cy, cx, t)

            Kind.STAR ->
                star(cx, cy, (r - l) * 0.5f, (b - t) * 0.5f, out)

            else -> {}
        }
    }

    private fun push(out: ArrayList<Float>, vararg v: Float) {
        for (f in v) out.add(f)
    }

    private fun oval(
        cx: Float, cy: Float, rx: Float, ry: Float, steps: Int, out: ArrayList<Float>
    ) {
        val sx = rx.coerceAtLeast(1f)
        val sy = ry.coerceAtLeast(1f)
        for (i in 0..steps) {
            val a = 2.0 * PI * i / steps
            out.add(cx + sx * cos(a).toFloat())
            out.add(cy + sy * sin(a).toFloat())
        }
    }

    private fun arrow(x0: Float, y0: Float, x1: Float, y1: Float, out: ArrayList<Float>) {
        val dx = x1 - x0
        val dy = y1 - y0
        val len = max(1f, hypot(dx, dy))
        val ux = dx / len
        val uy = dy / len
        val head = min(len * 0.28f, 60f)
        val wing = head * 0.55f
        val bx = x1 - ux * head
        val by = y1 - uy * head
        push(
            out,
            x0, y0, x1, y1,
            bx - uy * wing, by + ux * wing, x1, y1,
            bx + uy * wing, by - ux * wing
        )
    }

    private fun arc(x0: Float, y0: Float, x1: Float, y1: Float, out: ArrayList<Float>) {
        val cx = (x0 + x1) * 0.5f
        val cy = (y0 + y1) * 0.5f
        val r = max(1f, hypot(x1 - x0, y1 - y0) * 0.5f)
        val a0 = atan2((y0 - cy).toDouble(), (x0 - cx).toDouble())
        for (i in 0..40) {
            val a = a0 + PI * i / 40
            out.add(cx + r * cos(a).toFloat())
            out.add(cy + r * sin(a).toFloat())
        }
    }

    /** Upright 5-point star inscribed in the box (radii rx, ry). */
    private fun star(cx: Float, cy: Float, rx: Float, ry: Float, out: ArrayList<Float>) {
        val sx = rx.coerceAtLeast(1f)
        val sy = ry.coerceAtLeast(1f)
        for (i in 0..10) {
            val a = -PI / 2 + i * PI / 5
            val f = if (i % 2 == 0) 1f else 0.42f
            out.add(cx + sx * f * cos(a).toFloat())
            out.add(cy + sy * f * sin(a).toFloat())
        }
    }
}
