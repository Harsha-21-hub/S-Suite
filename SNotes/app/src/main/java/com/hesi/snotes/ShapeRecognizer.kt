package com.hesi.snotes

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * On-device shape detector (no Android dependencies, so it is unit-testable on
 * the JVM).
 *
 * Two stages, like the shape tools in GoodNotes / Notability:
 *
 *  1. CLASSIFY - the stroke is resampled and described by ~40 scale- and
 *     rotation-invariant features (closure, turning, convex-hull ratios,
 *     ellipse/circle/polygon fit errors, ShortStraw corner count, k-gon area
 *     ratios, self-intersections, cusps ...). A small neural network
 *     (ShapeModel - a 2-hidden-layer MLP trained offline on ~156k synthetic
 *     hand-drawn strokes with wobble, rounded corners, overshoot, hooks,
 *     uneven sizes and rotations, plus handwriting/scribble negatives) turns
 *     them into class probabilities.
 *
 *  2. FIT - the winning class is fitted geometrically to the actual ink
 *     (least-squares lines intersected at the corners, region-moment ellipse,
 *     algebraic circle fit for arcs ...), tidied up (near-90° quads become
 *     rectangles, near-equal rectangles squares, near-axis lines/edges snap
 *     straight, near-regular polygons become regular) and verified. If the
 *     fit is poor or the network is unsure, nothing is changed.
 */
object ShapeRecognizer {

    enum class Kind { NONE, LINE, ARROW, ELLIPSE, TRIANGLE, QUAD, PENTAGON, HEXAGON, STAR, ARC, POLYLINE }

    /** [points] = flat x,y list of the perfect shape (an open or closed polyline). */
    class Result(val kind: Kind, val label: String, val points: FloatArray, val confidence: Float)

    /** Network output below this = "not sure" = leave the ink alone. */
    private const val MIN_CONFIDENCE = 0.55f

    // =====================================================================
    //  Public entry points
    // =====================================================================

    /** Recognises the stroke in [xy] (n points as x0,y0,x1,y1...). */
    fun recognize(xy: FloatArray, n: Int): Result? {
        val sk = Sketch.of(xy, n) ?: return null
        val f = features(sk)
        val probs = ShapeModel.predict(f)
        // try the classes in order of probability; the first one whose fit
        // passes verification wins (a confident-but-wrong guess falls back)
        val order = probs.indices.sortedByDescending { probs[it] }
        for (ci in order.take(2)) {
            val p = probs[ci]
            if (p < MIN_CONFIDENCE && ci == order[0]) return null
            if (p < 0.25f) break
            val kind = Kind.values()[ci]
            if (kind == Kind.NONE) return null
            // open zig-zags are the most handwriting-like class: demand more certainty
            if (kind == Kind.POLYLINE && p < 0.8f) continue
            val r = fit(kind, sk) ?: continue
            return Result(kind, r.first, r.second, p)
        }
        return null
    }

    /** Classification only (used by tests / training tools). */
    fun classify(xy: FloatArray, n: Int): FloatArray? {
        val sk = Sketch.of(xy, n) ?: return null
        return ShapeModel.predict(features(sk))
    }

    /** Feature vector only (used by the offline training pipeline). */
    fun featureVector(xy: FloatArray, n: Int): FloatArray? {
        val sk = Sketch.of(xy, n) ?: return null
        return features(sk)
    }

    // =====================================================================
    //  Preprocessing
    // =====================================================================

    /**
     * A stroke prepared for analysis:
     *  [rx]/[ry]  - the whole stroke resampled at equal spacing [step]
     *  [lx]/[ly]  - the closed loop: overshoot past the start trimmed off and
     *               the closing gap filled in (meaningful for closed shapes)
     */
    internal class Sketch(
        val rx: DoubleArray, val ry: DoubleArray, val m: Int,
        val lx: DoubleArray, val ly: DoubleArray, val k: Int,
        val diag: Double, val len: Double, val step: Double,
        val trimEnd: Int, val gap: Double, val trimmedLen: Double
    ) {
        companion object {
            fun of(xy: FloatArray, n: Int): Sketch? {
                if (n < 5) return null
                // drop consecutive duplicates
                val px = DoubleArray(n); val py = DoubleArray(n)
                var c = 0
                for (i in 0 until n) {
                    val x = xy[2 * i].toDouble(); val y = xy[2 * i + 1].toDouble()
                    if (c > 0 && x == px[c - 1] && y == py[c - 1]) continue
                    px[c] = x; py[c] = y; c++
                }
                if (c < 5) return null
                var minX = px[0]; var maxX = px[0]; var minY = py[0]; var maxY = py[0]
                var len = 0.0
                for (i in 1 until c) {
                    minX = min(minX, px[i]); maxX = max(maxX, px[i])
                    minY = min(minY, py[i]); maxY = max(maxY, py[i])
                    len += hypot(px[i] - px[i - 1], py[i] - py[i - 1])
                }
                val diag = hypot(maxX - minX, maxY - minY)
                if (diag < 1e-3 || len < 1e-3) return null

                // ShortStraw spacing: diag/40, but never more than 400 points
                val step = max(diag / 40.0, len / 400.0)
                val (rx, ry) = resample(px, py, c, step)
                val m = rx.size
                if (m < 6) return null

                // ---- closure: trim overshoot past the start point ----
                var trimEnd = m - 1
                var best = Double.MAX_VALUE
                val from = (m * 0.55).toInt().coerceAtLeast(3)
                for (i in m - 1 downTo from) {
                    val d = hypot(rx[i] - rx[0], ry[i] - ry[0])
                    if (d < best) { best = d; trimEnd = i }
                }
                if (best > diag * 0.14) trimEnd = m - 1
                // only trim a genuine overshoot (the tail must not be most of the stroke)
                if (m - 1 - trimEnd > m * 0.3) trimEnd = m - 1
                val gap = hypot(rx[trimEnd] - rx[0], ry[trimEnd] - ry[0])
                var tLen = 0.0
                for (i in 1..trimEnd) tLen += hypot(rx[i] - rx[i - 1], ry[i] - ry[i - 1])

                // ---- the loop: trimmed points + the closing gap filled in ----
                val fill = max(0, (gap / step).toInt() - 1)
                val k = trimEnd + 1 + fill
                val lx = DoubleArray(k); val ly = DoubleArray(k)
                for (i in 0..trimEnd) { lx[i] = rx[i]; ly[i] = ry[i] }
                for (j in 1..fill) {
                    val t = j.toDouble() / (fill + 1)
                    lx[trimEnd + j] = rx[trimEnd] + (rx[0] - rx[trimEnd]) * t
                    ly[trimEnd + j] = ry[trimEnd] + (ry[0] - ry[trimEnd]) * t
                }
                return Sketch(rx, ry, m, lx, ly, k, diag, len, step, trimEnd, gap, max(tLen, 1e-6))
            }

            /** Equal arc-length resampling (the $1-recogniser way). */
            fun resample(px: DoubleArray, py: DoubleArray, n: Int, step: Double): Pair<DoubleArray, DoubleArray> {
                val ox = ArrayList<Double>(); val oy = ArrayList<Double>()
                ox.add(px[0]); oy.add(py[0])
                var acc = 0.0
                var qx = px[0]; var qy = py[0]
                var i = 1
                while (i < n) {
                    val d = hypot(px[i] - qx, py[i] - qy)
                    if (acc + d >= step && d > 0) {
                        val t = (step - acc) / d
                        val nx = qx + t * (px[i] - qx)
                        val ny = qy + t * (py[i] - qy)
                        ox.add(nx); oy.add(ny)
                        qx = nx; qy = ny
                        acc = 0.0
                    } else {
                        acc += d
                        qx = px[i]; qy = py[i]
                        i++
                    }
                }
                if (hypot(px[n - 1] - ox.last(), py[n - 1] - oy.last()) > step * 0.3) {
                    ox.add(px[n - 1]); oy.add(py[n - 1])
                }
                return ox.toDoubleArray() to oy.toDoubleArray()
            }
        }
    }

    // =====================================================================
    //  Features
    // =====================================================================

    const val NUM_FEATURES = 38

    internal fun features(s: Sketch): FloatArray {
        val f = FloatArray(NUM_FEATURES)
        val d = s.diag
        val gapD = s.gap / d
        f[0] = gapD.coerceAtMost(2.0).toFloat()
        f[1] = (s.gap / s.trimmedLen).coerceAtMost(1.0).toFloat()
        f[2] = ((s.len - s.trimmedLen) / s.len).toFloat()
        f[3] = (hypot(s.rx[s.m - 1] - s.rx[0], s.ry[s.m - 1] - s.ry[0]) / s.len).toFloat()

        // PCA of the whole stroke
        val pca = pca(s.rx, s.ry, s.m)
        f[4] = sqrt(max(0.0, pca[1]) / max(1e-12, pca[0])).toFloat()
        f[5] = lineDeviation(s.rx, s.ry, s.m, pca).toFloat()

        // turning
        val turn = turning(s.rx, s.ry, s.m)
        f[6] = (turn[0] / (2 * PI)).coerceAtMost(8.0).toFloat()
        f[7] = (abs(turn[1]) / (2 * PI)).coerceAtMost(4.0).toFloat()

        // convex hull of the loop
        val hull = convexHull(s.lx, s.ly, s.k)
        val hA = polyArea(hull.first, hull.second).let { abs(it) }
        val hP = polyPerimeter(hull.first, hull.second)
        f[8] = if (hP > 0) (4 * PI * hA / (hP * hP)).toFloat() else 0f
        val mr = minAreaRect(hull.first, hull.second)
        f[9] = if (mr[0] > 0) (hA / mr[0]).toFloat().coerceAtMost(1.2f) else 0f
        val loopA = abs(polyArea(s.lx, s.ly, s.k))
        f[10] = if (hA > 0) (loopA / hA).toFloat().coerceAtMost(1.5f) else 0f
        f[11] = if (mr[2] > 0) (mr[1] / mr[2]).toFloat() else 0f

        // ellipse fit (region moments) of the loop
        val ell = fitEllipse(s.lx, s.ly, s.k)
        f[12] = (ell?.meanErr ?: 1.0).coerceAtMost(1.0).toFloat()
        f[13] = (ell?.maxErr ?: 2.0).coerceAtMost(2.0).toFloat()

        // circle fit of the open stroke (arcs)
        val circ = fitCircle(s.rx, s.ry, s.m)
        if (circ != null && circ[2] < d * 20) {
            f[14] = circleError(s.rx, s.ry, s.m, circ).coerceAtMost(1.0).toFloat()
            f[15] = (abs(sweep(s.rx, s.ry, s.m, circ)) / (2 * PI)).coerceAtMost(2.0).toFloat()
        } else { f[14] = 1f; f[15] = 0f }

        // ShortStraw corners on the closed loop
        val cc = corners(s.lx, s.ly, s.k, cyclic = true)
        val nc = cc.size
        f[16] = nc.coerceAtMost(12).toFloat()
        val pr = if (nc >= 3) polyResidual(s.lx, s.ly, s.k, cc, cyclic = true) else doubleArrayOf(d, d)
        f[17] = (pr[0] / d).coerceAtMost(1.0).toFloat()
        f[18] = (pr[1] / d).coerceAtMost(1.0).toFloat()

        // k-gon area ratios: hull reduced to k vertices / hull area
        for (kk in 3..6) {
            val a = reducedArea(hull.first, hull.second, kk)
            f[19 + kk - 3] = if (hA > 0) (a / hA).toFloat() else 0f
        }

        f[23] = selfIntersections(s.rx, s.ry, s.trimEnd + 1).coerceAtMost(12).toFloat()
        f[24] = cusps(s.rx, s.ry, s.m).coerceAtMost(8).toFloat()

        // ShortStraw corners on the open stroke
        val oc = corners(s.rx, s.ry, s.m, cyclic = false)
        f[25] = (oc.size - 2).coerceIn(0, 12).toFloat()
        val opr = polyResidual(s.rx, s.ry, s.m, oc, cyclic = false)
        f[26] = (opr[0] / d).coerceAtMost(1.0).toFloat()
        var longest = 0.0
        for (i in 1 until oc.size) longest = max(longest, chord(s.rx, s.ry, oc[i - 1], oc[i]))
        f[27] = (longest / s.len).toFloat()
        f[28] = (hP / s.trimmedLen).coerceAtMost(2.0).toFloat()
        f[29] = if (gapD < 0.22) 1f else 0f

        // one-hot of the closed corner count (the strongest polygon cue)
        f[30] = if (nc <= 2) 1f else 0f
        f[31] = if (nc == 3) 1f else 0f
        f[32] = if (nc == 4) 1f else 0f
        f[33] = if (nc == 5) 1f else 0f
        f[34] = if (nc == 6) 1f else 0f
        f[35] = if (nc >= 7) 1f else 0f
        // ellipse axis ratio + open-corner residual max
        f[36] = (ell?.let { it.b / it.a } ?: 0.0).toFloat()
        f[37] = (opr[1] / d).coerceAtMost(1.0).toFloat()
        return f
    }

    // =====================================================================
    //  Geometry helpers
    // =====================================================================

    /** [λmax, λmin, cx, cy, ux, uy] - covariance eigenvalues + major axis. */
    private fun pca(x: DoubleArray, y: DoubleArray, n: Int): DoubleArray {
        var mx = 0.0; var my = 0.0
        for (i in 0 until n) { mx += x[i]; my += y[i] }
        mx /= n; my /= n
        var sxx = 0.0; var syy = 0.0; var sxy = 0.0
        for (i in 0 until n) {
            val dx = x[i] - mx; val dy = y[i] - my
            sxx += dx * dx; syy += dy * dy; sxy += dx * dy
        }
        sxx /= n; syy /= n; sxy /= n
        val tr = sxx + syy
        val det = sxx * syy - sxy * sxy
        val disc = sqrt(max(0.0, tr * tr / 4 - det))
        val l1 = tr / 2 + disc
        val l2 = tr / 2 - disc
        val ang = 0.5 * atan2(2 * sxy, sxx - syy)
        return doubleArrayOf(l1, l2, mx, my, cos(ang), sin(ang))
    }

    /** Max distance from the PCA line, relative to the extent along it. */
    private fun lineDeviation(x: DoubleArray, y: DoubleArray, n: Int, p: DoubleArray): Double {
        var maxD = 0.0; var lo = Double.MAX_VALUE; var hi = -Double.MAX_VALUE
        for (i in 0 until n) {
            val dx = x[i] - p[2]; val dy = y[i] - p[3]
            val along = dx * p[4] + dy * p[5]
            val perp = abs(-dx * p[5] + dy * p[4])
            maxD = max(maxD, perp); lo = min(lo, along); hi = max(hi, along)
        }
        return (maxD / max(1e-9, hi - lo)).coerceAtMost(1.0)
    }

    private fun wrap(a: Double): Double {
        var v = a
        while (v > PI) v -= 2 * PI
        while (v < -PI) v += 2 * PI
        return v
    }

    /** [sum |dθ|, sum dθ] over 2-step chords (robust to jitter). */
    private fun turning(x: DoubleArray, y: DoubleArray, n: Int): DoubleArray {
        if (n < 5) return doubleArrayOf(0.0, 0.0)
        var absSum = 0.0; var sum = 0.0
        var prev = atan2(y[2] - y[0], x[2] - x[0])
        var i = 2
        while (i + 2 < n) {
            val a = atan2(y[i + 2] - y[i], x[i + 2] - x[i])
            val dth = wrap(a - prev)
            absSum += abs(dth); sum += dth
            prev = a
            i += 2
        }
        return doubleArrayOf(absSum, sum)
    }

    private fun cusps(x: DoubleArray, y: DoubleArray, n: Int): Int {
        val w = 2
        var count = 0
        var inRun = false
        for (i in w until n - w) {
            val ax = x[i] - x[i - w]; val ay = y[i] - y[i - w]
            val bx = x[i + w] - x[i]; val by = y[i + w] - y[i]
            val la = hypot(ax, ay); val lb = hypot(bx, by)
            if (la < 1e-9 || lb < 1e-9) continue
            val cosT = (ax * bx + ay * by) / (la * lb)
            val sharp = cosT < -0.7   // turned by more than ~135°
            if (sharp && !inRun) count++
            inRun = sharp
        }
        return count
    }

    private fun chord(x: DoubleArray, y: DoubleArray, a: Int, b: Int) = hypot(x[b] - x[a], y[b] - y[a])

    private fun pathLen(x: DoubleArray, y: DoubleArray, n: Int, a: Int, b: Int, cyclic: Boolean): Double {
        var s = 0.0
        var i = a
        while (i != b) {
            val j = if (cyclic) (i + 1) % n else i + 1
            s += hypot(x[j] - x[i], y[j] - y[i])
            i = j
            if (!cyclic && i >= n - 1 && i != b) break
        }
        return s
    }

    /** Andrew's monotone chain; counter-clockwise hull. */
    private fun convexHull(x: DoubleArray, y: DoubleArray, n: Int): Pair<DoubleArray, DoubleArray> {
        val idx = (0 until n).sortedWith(compareBy({ x[it] }, { y[it] }))
        val h = IntArray(2 * n + 2)
        var k = 0
        fun cross(o: Int, a: Int, b: Int) = (x[a] - x[o]) * (y[b] - y[o]) - (y[a] - y[o]) * (x[b] - x[o])
        for (i in idx) {
            while (k >= 2 && cross(h[k - 2], h[k - 1], i) <= 0) k--
            h[k++] = i
        }
        val lower = k + 1
        for (j in idx.indices.reversed()) {
            val i = idx[j]
            while (k >= lower && cross(h[k - 2], h[k - 1], i) <= 0) k--
            h[k++] = i
        }
        val cnt = max(1, k - 1)
        return DoubleArray(cnt) { x[h[it]] } to DoubleArray(cnt) { y[h[it]] }
    }

    private fun polyArea(x: DoubleArray, y: DoubleArray, n: Int = x.size): Double {
        var a = 0.0
        for (i in 0 until n) {
            val j = (i + 1) % n
            a += x[i] * y[j] - x[j] * y[i]
        }
        return a / 2
    }

    private fun polyPerimeter(x: DoubleArray, y: DoubleArray): Double {
        var p = 0.0
        val n = x.size
        for (i in 0 until n) { val j = (i + 1) % n; p += hypot(x[j] - x[i], y[j] - y[i]) }
        return p
    }

    /** Minimum-area bounding rectangle of a convex polygon: [area, short, long, angle]. */
    private fun minAreaRect(x: DoubleArray, y: DoubleArray): DoubleArray {
        val n = x.size
        var best = doubleArrayOf(0.0, 0.0, 0.0, 0.0)
        var bestA = Double.MAX_VALUE
        if (n < 3) return best
        for (i in 0 until n) {
            val j = (i + 1) % n
            val ex = x[j] - x[i]; val ey = y[j] - y[i]
            val l = hypot(ex, ey)
            if (l < 1e-9) continue
            val ux = ex / l; val uy = ey / l
            var a0 = Double.MAX_VALUE; var a1 = -Double.MAX_VALUE
            var b0 = Double.MAX_VALUE; var b1 = -Double.MAX_VALUE
            for (k in 0 until n) {
                val a = x[k] * ux + y[k] * uy
                val b = -x[k] * uy + y[k] * ux
                a0 = min(a0, a); a1 = max(a1, a); b0 = min(b0, b); b1 = max(b1, b)
            }
            val area = (a1 - a0) * (b1 - b0)
            if (area < bestA) {
                bestA = area
                best = doubleArrayOf(area, min(a1 - a0, b1 - b0), max(a1 - a0, b1 - b0), atan2(uy, ux))
            }
        }
        return best
    }

    /** Visvalingam: drop the hull vertex with the smallest triangle until k remain. */
    private fun reduceHull(x: DoubleArray, y: DoubleArray, k: Int): Pair<DoubleArray, DoubleArray> {
        val px = x.toMutableList(); val py = y.toMutableList()
        while (px.size > k) {
            var bi = 0; var ba = Double.MAX_VALUE
            val n = px.size
            for (i in 0 until n) {
                val a = (i + n - 1) % n; val b = (i + 1) % n
                val area = abs((px[i] - px[a]) * (py[b] - py[a]) - (py[i] - py[a]) * (px[b] - px[a]))
                if (area < ba) { ba = area; bi = i }
            }
            px.removeAt(bi); py.removeAt(bi)
        }
        return px.toDoubleArray() to py.toDoubleArray()
    }

    private fun reducedArea(x: DoubleArray, y: DoubleArray, k: Int): Double {
        if (x.size <= k) return abs(polyArea(x, y))
        val r = reduceHull(x, y, k)
        return abs(polyArea(r.first, r.second))
    }

    private fun segIntersect(
        ax: Double, ay: Double, bx: Double, by: Double,
        cx: Double, cy: Double, dx: Double, dy: Double
    ): Boolean {
        val d1 = (dx - cx) * (ay - cy) - (dy - cy) * (ax - cx)
        val d2 = (dx - cx) * (by - cy) - (dy - cy) * (bx - cx)
        val d3 = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax)
        val d4 = (bx - ax) * (dy - ay) - (by - ay) * (dx - ax)
        return ((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))
    }

    private fun selfIntersections(x: DoubleArray, y: DoubleArray, n: Int): Int {
        var c = 0
        for (i in 0 until n - 1) {
            for (j in i + 2 until n - 1) {
                if (i == 0 && j == n - 2) continue
                if (segIntersect(x[i], y[i], x[i + 1], y[i + 1], x[j], y[j], x[j + 1], y[j + 1])) {
                    c++
                    if (c > 12) return c
                }
            }
        }
        return c
    }

    // ---------- ShortStraw corner finder (Wolin, Eoff & Hammond 2008) ----------

    private const val W = 3

    private fun corners(x: DoubleArray, y: DoubleArray, n: Int, cyclic: Boolean): IntArray {
        if (n < 2 * W + 2) return if (cyclic) IntArray(0) else intArrayOf(0, n - 1)
        val straw = DoubleArray(n) { Double.MAX_VALUE }
        val vals = ArrayList<Double>(n)
        for (i in 0 until n) {
            if (!cyclic && (i < W || i >= n - W)) continue
            val a = if (cyclic) (i - W + n) % n else i - W
            val b = if (cyclic) (i + W) % n else i + W
            straw[i] = hypot(x[b] - x[a], y[b] - y[a])
            vals.add(straw[i])
        }
        vals.sort()
        val t = vals[vals.size / 2] * 0.95

        val out = ArrayList<Int>()
        if (!cyclic) out.add(0)
        // start the scan where the straw is long (never inside a corner run)
        var start = 0
        if (cyclic) { var mx = -1.0; for (i in 0 until n) if (straw[i] > mx) { mx = straw[i]; start = i } }
        var c = 0
        while (c < n) {
            val i = (start + c) % n
            if (straw[i] < t) {
                var lm = i; var lmv = straw[i]
                while (c + 1 < n && straw[(start + c + 1) % n] < t) {
                    c++
                    val j = (start + c) % n
                    if (straw[j] < lmv) { lmv = straw[j]; lm = j }
                }
                out.add(lm)
            }
            c++
        }
        if (!cyclic) out.add(n - 1)
        if (cyclic) out.sort()

        fun isLine(a: Int, b: Int): Boolean {
            val pl = pathLen(x, y, n, a, b, cyclic)
            if (pl <= 1e-9) return true
            return chord(x, y, a, b) / pl > 0.95
        }

        // higher-level: split segments that are not straight
        var changed = true
        var guard = 0
        while (changed && guard++ < 24 && out.size < 40) {
            changed = false
            val cnt = if (cyclic) out.size else out.size - 1
            if (cyclic && out.size < 2) break
            for (s in 0 until cnt) {
                val a = out[s]; val b = out[(s + 1) % out.size]
                if (isLine(a, b)) continue
                val span = if (b > a) b - a else b + n - a
                if (span < 2 * W + 2) continue
                val lo = span / 4; val hi = span - span / 4
                var bi = -1; var bv = Double.MAX_VALUE
                for (o in lo..hi) {
                    val j = (a + o) % n
                    if (straw[j] < bv) { bv = straw[j]; bi = j }
                }
                if (bi >= 0 && bi != a && bi != b) {
                    out.add(s + 1, bi)
                    if (cyclic) out.sort()
                    changed = true
                    break
                }
            }
        }
        // remove collinear corners
        var i = if (cyclic) 0 else 1
        while (out.size > (if (cyclic) 3 else 2) && i < out.size - (if (cyclic) 0 else 1)) {
            val p = out[(i - 1 + out.size) % out.size]
            val q = out[(i + 1) % out.size]
            if (isLine(p, q) && chordAngleFlat(x, y, p, out[i], q)) out.removeAt(i) else i++
        }
        // merge corners that are too close
        val merged = ArrayList<Int>()
        for (v in out) {
            if (merged.isNotEmpty()) {
                val prev = merged.last()
                val dist = if (cyclic) min(abs(v - prev), n - abs(v - prev)) else v - prev
                if (dist <= W) {
                    // open strokes always keep their real end point
                    if (!cyclic && v == n - 1 && merged.size > 1) merged[merged.size - 1] = v
                    continue
                }
            }
            merged.add(v)
        }
        if (cyclic && merged.size > 1) {
            val dist = merged[0] + n - merged.last()
            if (dist <= W) merged.removeAt(merged.size - 1)
        }
        return merged.toIntArray()
    }

    /** True when the corner at [m] turns by less than ~22° (a flat "corner"). */
    private fun chordAngleFlat(x: DoubleArray, y: DoubleArray, a: Int, m: Int, b: Int): Boolean {
        val ax = x[m] - x[a]; val ay = y[m] - y[a]
        val bx = x[b] - x[m]; val by = y[b] - y[m]
        val la = hypot(ax, ay); val lb = hypot(bx, by)
        if (la < 1e-9 || lb < 1e-9) return true
        return (ax * bx + ay * by) / (la * lb) > 0.93
    }

    /** Mean / max distance of the points to the polyline through the corners. */
    private fun polyResidual(x: DoubleArray, y: DoubleArray, n: Int, cs: IntArray, cyclic: Boolean): DoubleArray {
        if (cs.size < 2) return doubleArrayOf(1e9, 1e9)
        var sum = 0.0; var mx = 0.0; var cnt = 0
        val segs = if (cyclic) cs.size else cs.size - 1
        for (s in 0 until segs) {
            val a = cs[s]; val b = cs[(s + 1) % cs.size]
            var i = a
            while (true) {
                val dd = segDist(x[i], y[i], x[a], y[a], x[b], y[b])
                sum += dd; mx = max(mx, dd); cnt++
                if (i == b) break
                i = if (cyclic) (i + 1) % n else i + 1
                if (i >= n) break
            }
        }
        return doubleArrayOf(sum / max(1, cnt), mx)
    }

    private fun segDist(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
        val vx = bx - ax; val vy = by - ay
        val l2 = vx * vx + vy * vy
        val t = if (l2 <= 0) 0.0 else (((px - ax) * vx + (py - ay) * vy) / l2).coerceIn(0.0, 1.0)
        return hypot(px - ax - vx * t, py - ay - vy * t)
    }

    // ---------- ellipse (region moments) ----------

    internal class Ellipse(val cx: Double, val cy: Double, val a: Double, val b: Double, val theta: Double,
                           val meanErr: Double, val maxErr: Double)

    private fun fitEllipse(x: DoubleArray, y: DoubleArray, n: Int): Ellipse? {
        // shift to the mean for numerical stability
        var mx = 0.0; var my = 0.0
        for (i in 0 until n) { mx += x[i]; my += y[i] }
        mx /= n; my /= n
        var A = 0.0; var cxs = 0.0; var cys = 0.0; var ixx = 0.0; var iyy = 0.0; var ixy = 0.0
        for (i in 0 until n) {
            val j = (i + 1) % n
            val x0 = x[i] - mx; val y0 = y[i] - my; val x1 = x[j] - mx; val y1 = y[j] - my
            val cr = x0 * y1 - x1 * y0
            A += cr
            cxs += (x0 + x1) * cr; cys += (y0 + y1) * cr
            iyy += (x0 * x0 + x0 * x1 + x1 * x1) * cr
            ixx += (y0 * y0 + y0 * y1 + y1 * y1) * cr
            ixy += (x0 * y1 + 2 * x0 * y0 + 2 * x1 * y1 + x1 * y0) * cr
        }
        A /= 2
        if (abs(A) < 1e-9) return null
        val cx = cxs / (6 * A); val cy = cys / (6 * A)
        val vxx = iyy / (12 * A) - cx * cx
        val vyy = ixx / (12 * A) - cy * cy
        val vxy = ixy / (24 * A) - cx * cy
        val tr = vxx + vyy
        val det = vxx * vyy - vxy * vxy
        val disc = sqrt(max(0.0, tr * tr / 4 - det))
        val l1 = tr / 2 + disc; val l2 = tr / 2 - disc
        if (l1 <= 0 || l2 <= 0) return null
        val th = 0.5 * atan2(2 * vxy, vxx - vyy)
        var a = 2 * sqrt(l1); var b = 2 * sqrt(l2)
        val c = cos(th); val s = sin(th)
        // scale so the median point sits on the ellipse
        val rho = DoubleArray(n)
        for (i in 0 until n) {
            val dx = x[i] - mx - cx; val dy = y[i] - my - cy
            val u = dx * c + dy * s; val v = -dx * s + dy * c
            rho[i] = sqrt((u / a) * (u / a) + (v / b) * (v / b))
        }
        val sorted = rho.copyOf(); sorted.sort()
        val med = sorted[n / 2]
        if (med <= 0) return null
        a *= med; b *= med
        var sum = 0.0; var mxe = 0.0
        for (i in 0 until n) {
            val e = abs(rho[i] / med - 1)
            sum += e; mxe = max(mxe, e)
        }
        return Ellipse(cx + mx, cy + my, a, b, th, sum / n, mxe)
    }

    // ---------- circle (Kasa algebraic fit) ----------

    /** [cx, cy, r] or null. */
    private fun fitCircle(x: DoubleArray, y: DoubleArray, n: Int): DoubleArray? {
        var mx = 0.0; var my = 0.0
        for (i in 0 until n) { mx += x[i]; my += y[i] }
        mx /= n; my /= n
        var suu = 0.0; var svv = 0.0; var suv = 0.0
        var suuu = 0.0; var svvv = 0.0; var suvv = 0.0; var svuu = 0.0
        for (i in 0 until n) {
            val u = x[i] - mx; val v = y[i] - my
            suu += u * u; svv += v * v; suv += u * v
            suuu += u * u * u; svvv += v * v * v; suvv += u * v * v; svuu += v * u * u
        }
        val det = suu * svv - suv * suv
        if (abs(det) < 1e-12) return null
        val r1 = 0.5 * (suuu + suvv); val r2 = 0.5 * (svvv + svuu)
        val uc = (r1 * svv - r2 * suv) / det
        val vc = (suu * r2 - suv * r1) / det
        val r = sqrt(uc * uc + vc * vc + (suu + svv) / n)
        return doubleArrayOf(uc + mx, vc + my, r)
    }

    private fun circleError(x: DoubleArray, y: DoubleArray, n: Int, c: DoubleArray): Double {
        var s = 0.0
        for (i in 0 until n) s += abs(hypot(x[i] - c[0], y[i] - c[1]) - c[2])
        return s / n / max(1e-9, c[2])
    }

    private fun sweep(x: DoubleArray, y: DoubleArray, n: Int, c: DoubleArray): Double {
        var prev = atan2(y[0] - c[1], x[0] - c[0])
        var total = 0.0
        for (i in 1 until n) {
            val a = atan2(y[i] - c[1], x[i] - c[0])
            total += wrap(a - prev)
            prev = a
        }
        return total
    }

    // =====================================================================
    //  Fitting the perfect shape
    // =====================================================================

    private fun fit(kind: Kind, s: Sketch): Pair<String, FloatArray>? = when (kind) {
        Kind.LINE -> fitLine(s)
        Kind.ARROW -> fitArrow(s)
        Kind.ELLIPSE -> fitEllipseShape(s)
        Kind.TRIANGLE -> fitPolygon(s, 3)
        Kind.QUAD -> fitPolygon(s, 4)
        Kind.PENTAGON -> fitPolygon(s, 5)
        Kind.HEXAGON -> fitPolygon(s, 6)
        Kind.STAR -> fitStar(s)
        Kind.ARC -> fitArc(s)
        Kind.POLYLINE -> fitPolyline(s)
        Kind.NONE -> null
    }

    private fun out(vararg v: Double): FloatArray = FloatArray(v.size) { v[it].toFloat() }

    private fun closedOut(x: DoubleArray, y: DoubleArray): FloatArray {
        val n = x.size
        val o = FloatArray((n + 1) * 2)
        for (i in 0..n) { o[2 * i] = x[i % n].toFloat(); o[2 * i + 1] = y[i % n].toFloat() }
        return o
    }

    /** Snaps an angle to the nearest multiple of [stepDeg] if within [tolDeg]. */
    private fun snapAngle(a: Double, stepDeg: Double, tolDeg: Double): Double {
        val step = Math.toRadians(stepDeg)
        val nearest = round(a / step) * step
        return if (abs(a - nearest) <= Math.toRadians(tolDeg)) nearest else a
    }

    private fun fitLine(s: Sketch): Pair<String, FloatArray>? {
        val p = pca(s.rx, s.ry, s.m)
        if (lineDeviation(s.rx, s.ry, s.m, p) > 0.09) return null
        var ux = p[4]; var uy = p[5]
        // orient from the first to the last point
        val t0 = (s.rx[0] - p[2]) * ux + (s.ry[0] - p[3]) * uy
        val t1 = (s.rx[s.m - 1] - p[2]) * ux + (s.ry[s.m - 1] - p[3]) * uy
        val half = abs(t1 - t0) / 2
        val mid = (t0 + t1) / 2
        val cx = p[2] + ux * mid; val cy = p[3] + uy * mid
        var ang = atan2(if (t1 >= t0) uy else -uy, if (t1 >= t0) ux else -ux)
        ang = snapAngle(ang, 45.0, 4.0)
        ux = cos(ang); uy = sin(ang)
        if (half * 2 < s.diag * 0.5) return null
        return "LINE" to out(cx - ux * half, cy - uy * half, cx + ux * half, cy + uy * half)
    }

    private fun fitArrow(s: Sketch): Pair<String, FloatArray>? {
        val oc = corners(s.rx, s.ry, s.m, cyclic = false)
        if (oc.size < 3) return null
        // the shaft is the longest straight run
        var bi = 0; var bl = -1.0
        for (i in 1 until oc.size) {
            val l = chord(s.rx, s.ry, oc[i - 1], oc[i])
            if (l > bl) { bl = l; bi = i }
        }
        val a = oc[bi - 1]; val b = oc[bi]
        val tailIdx: Int; val tipIdx: Int; val headFrom: Int; val headTo: Int
        when {
            bi == 1 -> { tailIdx = a; tipIdx = b; headFrom = b; headTo = s.m - 1 }        // shaft first
            bi == oc.size - 1 -> { tailIdx = b; tipIdx = a; headFrom = 0; headTo = a }     // head first
            else -> return null
        }
        if (pathLen(s.rx, s.ry, s.m, a, b, false) <= 0) return null
        if (bl / pathLen(s.rx, s.ry, s.m, a, b, false) < 0.93) return null
        val tx = s.rx[tipIdx]; val ty = s.ry[tipIdx]
        val sx = s.rx[tailIdx]; val sy = s.ry[tailIdx]
        // head size = how far the head strokes reach from the tip
        var reach = 0.0
        for (i in headFrom..headTo) reach = max(reach, hypot(s.rx[i] - tx, s.ry[i] - ty))
        if (reach < bl * 0.06 || reach > bl * 0.9) return null
        var ang = atan2(ty - sy, tx - sx)
        ang = snapAngle(ang, 45.0, 4.0)
        val len = bl
        val ex = sx + cos(ang) * len; val ey = sy + sin(ang) * len
        val head = reach.coerceIn(len * 0.12, len * 0.45)
        val wing = head * 0.6
        val ux = cos(ang); val uy = sin(ang)
        val bx = ex - ux * head; val by = ey - uy * head
        return "ARROW" to out(
            sx, sy, ex, ey,
            bx - uy * wing, by + ux * wing, ex, ey,
            bx + uy * wing, by - ux * wing
        )
    }

    private fun fitEllipseShape(s: Sketch): Pair<String, FloatArray>? {
        if (s.gap / s.diag > 0.35) return null
        val e = fitEllipse(s.lx, s.ly, s.k) ?: return null
        if (e.meanErr > 0.11) return null
        var a = e.a; var b = e.b; var th = e.theta
        val circle = b / a >= 0.86
        if (circle) {
            val r = (a + b) / 2; a = r; b = r; th = 0.0
        } else {
            th = snapAngle(th, 90.0, 10.0)
        }
        val n = 72
        val o = FloatArray((n + 1) * 2)
        val c = cos(th); val sn = sin(th)
        for (i in 0..n) {
            val t = 2 * PI * i / n
            val u = a * cos(t); val v = b * sin(t)
            o[2 * i] = (e.cx + u * c - v * sn).toFloat()
            o[2 * i + 1] = (e.cy + u * sn + v * c).toFloat()
        }
        return (if (circle) "CIRCLE" else "ELLIPSE") to o
    }

    /** Total-least-squares line through points: [cx, cy, ux, uy]. */
    private fun tls(xs: List<Double>, ys: List<Double>): DoubleArray {
        val n = xs.size
        val p = pca(xs.toDoubleArray(), ys.toDoubleArray(), n)
        return doubleArrayOf(p[2], p[3], p[4], p[5])
    }

    private fun intersect(l1: DoubleArray, l2: DoubleArray): DoubleArray? {
        val den = l1[2] * l2[3] - l1[3] * l2[2]
        if (abs(den) < 1e-6) return null
        val t = ((l2[0] - l1[0]) * l2[3] - (l2[1] - l1[1]) * l2[2]) / den
        return doubleArrayOf(l1[0] + l1[2] * t, l1[1] + l1[3] * t)
    }

    /**
     * Convex k-gon: hull reduced to k vertices, then every edge re-fitted to
     * the ink (ignoring the rounded corner zones) and neighbouring edges
     * intersected - so corners come out crisp where the user "meant" them.
     */
    private fun refinedPolygon(s: Sketch, k: Int): Pair<DoubleArray, DoubleArray>? {
        val hull = convexHull(s.lx, s.ly, s.k)
        if (hull.first.size < k) return null
        val (vx, vy) = reduceHull(hull.first, hull.second, k)
        val lines = ArrayList<DoubleArray>(k)
        for (e in 0 until k) {
            val ax = vx[e]; val ay = vy[e]; val bx = vx[(e + 1) % k]; val by = vy[(e + 1) % k]
            val ex = bx - ax; val ey = by - ay
            val l2 = ex * ex + ey * ey
            if (l2 < 1e-9) return null
            val xs = ArrayList<Double>(); val ys = ArrayList<Double>()
            for (i in 0 until s.k) {
                val t = ((s.lx[i] - ax) * ex + (s.ly[i] - ay) * ey) / l2
                if (t < 0.18 || t > 0.82) continue
                // must be closest to this edge
                val dd = segDist(s.lx[i], s.ly[i], ax, ay, bx, by)
                var closest = true
                for (o in 0 until k) {
                    if (o == e) continue
                    if (segDist(s.lx[i], s.ly[i], vx[o], vy[o], vx[(o + 1) % k], vy[(o + 1) % k]) < dd) { closest = false; break }
                }
                if (closest) { xs.add(s.lx[i]); ys.add(s.ly[i]) }
            }
            lines.add(if (xs.size >= 3) tls(xs, ys) else {
                val l = sqrt(l2); doubleArrayOf((ax + bx) / 2, (ay + by) / 2, ex / l, ey / l)
            })
        }
        val ox = DoubleArray(k); val oy = DoubleArray(k)
        for (i in 0 until k) {
            val p = intersect(lines[(i + k - 1) % k], lines[i])
            if (p == null || hypot(p[0] - vx[i], p[1] - vy[i]) > s.diag * 0.25) {
                ox[i] = vx[i]; oy[i] = vy[i]
            } else { ox[i] = p[0]; oy[i] = p[1] }
        }
        return ox to oy
    }

    private fun interiorAngles(x: DoubleArray, y: DoubleArray): DoubleArray {
        val k = x.size
        return DoubleArray(k) { i ->
            val a = (i + k - 1) % k; val b = (i + 1) % k
            val ux = x[a] - x[i]; val uy = y[a] - y[i]
            val vx = x[b] - x[i]; val vy = y[b] - y[i]
            val c = (ux * vx + uy * vy) / max(1e-9, hypot(ux, uy) * hypot(vx, vy))
            Math.toDegrees(kotlin.math.acos(c.coerceIn(-1.0, 1.0)))
        }
    }

    private fun sides(x: DoubleArray, y: DoubleArray): DoubleArray {
        val k = x.size
        return DoubleArray(k) { i -> hypot(x[(i + 1) % k] - x[i], y[(i + 1) % k] - y[i]) }
    }

    private fun median(v: ArrayList<Double>): Double {
        v.sort()
        return if (v.size % 2 == 1) v[v.size / 2] else (v[v.size / 2 - 1] + v[v.size / 2]) / 2
    }

    private fun cv(v: DoubleArray): Double {
        val m = v.average()
        if (m <= 0) return 1.0
        return sqrt(v.sumOf { (it - m) * (it - m) } / v.size) / m
    }

    private fun rotateAll(x: DoubleArray, y: DoubleArray, cx: Double, cy: Double, a: Double) {
        val c = cos(a); val s = sin(a)
        for (i in x.indices) {
            val dx = x[i] - cx; val dy = y[i] - cy
            x[i] = cx + dx * c - dy * s
            y[i] = cy + dx * s + dy * c
        }
    }

    /** Rotates the polygon so its most horizontal/vertical edge is exact, if close. */
    private fun snapEdges(x: DoubleArray, y: DoubleArray, tolDeg: Double) {
        val k = x.size
        var best = Double.MAX_VALUE; var corr = 0.0
        for (i in 0 until k) {
            val a = atan2(y[(i + 1) % k] - y[i], x[(i + 1) % k] - x[i])
            val sn = snapAngle(a, 90.0, tolDeg)
            val d = abs(sn - a)
            if (sn != a && d < best) { best = d; corr = sn - a }
            if (sn == a && abs(a - round(a / (PI / 2)) * (PI / 2)) < 1e-9) return
        }
        if (best == Double.MAX_VALUE) return
        rotateAll(x, y, x.average(), y.average(), corr)
    }

    private fun regularPolygon(x: DoubleArray, y: DoubleArray, k: Int): Pair<DoubleArray, DoubleArray> {
        val cx = x.average(); val cy = y.average()
        var r = 0.0
        for (i in 0 until k) r += hypot(x[i] - cx, y[i] - cy)
        r /= k
        // circular mean of k * vertex angle gives the polygon's rotation
        var sc = 0.0; var ss = 0.0
        for (i in 0 until k) {
            val a = atan2(y[i] - cy, x[i] - cx) * k
            sc += cos(a); ss += sin(a)
        }
        var rot = atan2(ss, sc) / k
        // prefer a vertex pointing straight up (-90°) when close
        val up = -PI / 2
        val step = 2 * PI / k
        val rel = rot - up
        val snapped = round(rel / step) * step
        if (abs(rel - snapped) < Math.toRadians(7.0)) rot = up + snapped
        // ... or a flat bottom edge
        val flat = up + step / 2
        val rel2 = rot - flat
        val sn2 = round(rel2 / step) * step
        if (abs(rel2 - sn2) < Math.toRadians(7.0)) rot = flat + sn2
        // keep the drawing direction
        val ccw = polyArea(x, y) > 0
        val ox = DoubleArray(k); val oy = DoubleArray(k)
        for (i in 0 until k) {
            val a = rot + (if (ccw) 1 else -1) * step * i
            ox[i] = cx + r * cos(a); oy[i] = cy + r * sin(a)
        }
        return ox to oy
    }

    private fun fitPolygon(s: Sketch, k: Int): Pair<String, FloatArray>? {
        if (s.gap / s.diag > 0.35) return null
        val (px, py) = refinedPolygon(s, k) ?: return null
        // verify: the ink must follow the polygon closely
        var sum = 0.0
        for (i in 0 until s.k) {
            var best = Double.MAX_VALUE
            for (e in 0 until k) best = min(best, segDist(s.lx[i], s.ly[i], px[e], py[e], px[(e + 1) % k], py[(e + 1) % k]))
            sum += best
        }
        if (sum / s.k / s.diag > 0.05) return null
        val ang = interiorAngles(px, py)
        val sd = sides(px, py)
        if (sd.any { it < s.diag * 0.06 }) return null

        when (k) {
            3 -> {
                if (cv(sd) < 0.07) {
                    val (rx, ry) = regularPolygon(px, py, 3)
                    return "TRIANGLE" to closedOut(rx, ry)
                }
                snapEdges(px, py, 6.0)
                return "TRIANGLE" to closedOut(px, py)
            }
            4 -> {
                if (ang.all { abs(it - 90) < 14 }) {
                    // rectangle: orientation = circular mean of 4 x edge angle
                    var sc = 0.0; var ss = 0.0
                    for (i in 0 until 4) {
                        val a = atan2(py[(i + 1) % 4] - py[i], px[(i + 1) % 4] - px[i]) * 4
                        sc += cos(a); ss += sin(a)
                    }
                    var th = atan2(ss, sc) / 4
                    th = snapAngle(th, 90.0, 7.0)
                    val c = cos(th); val sn = sin(th)
                    // centre = corner centroid; half sizes = mean corner offsets
                    // (robust to one sloppy corner)
                    val cx = px.average(); val cy = py.average()
                    var hw = 0.0; var hh = 0.0
                    for (i in 0 until 4) {
                        val dx = px[i] - cx; val dy = py[i] - cy
                        hw += abs(dx * c + dy * sn); hh += abs(-dx * sn + dy * c)
                    }
                    hw /= 4; hh /= 4
                    // refine each side from the ink itself: the median position
                    // of the points along the middle of that side (corners and
                    // overshoot ignored)
                    val left = ArrayList<Double>(); val right = ArrayList<Double>()
                    val top = ArrayList<Double>(); val bottom = ArrayList<Double>()
                    for (i in 0 until s.k) {
                        val dx = s.lx[i] - cx; val dy = s.ly[i] - cy
                        val u = dx * c + dy * sn; val v = -dx * sn + dy * c
                        if (abs(v) < hh * 0.6) { if (u < 0) left.add(u) else right.add(u) }
                        if (abs(u) < hw * 0.6) { if (v < 0) top.add(v) else bottom.add(v) }
                    }
                    var ou = 0.0; var ov = 0.0
                    if (left.size >= 3 && right.size >= 3) {
                        val l = median(left); val r = median(right)
                        hw = (r - l) / 2; ou = (r + l) / 2
                    }
                    if (top.size >= 3 && bottom.size >= 3) {
                        val t = median(top); val b = median(bottom)
                        hh = (b - t) / 2; ov = (b + t) / 2
                    }
                    if (hw <= 0 || hh <= 0) return null
                    val square = min(hw, hh) / max(hw, hh) > 0.9
                    if (square) { val m = (hw + hh) / 2; hw = m; hh = m }
                    val ccx = cx + ou * c - ov * sn
                    val ccy = cy + ou * sn + ov * c
                    val ox = DoubleArray(4); val oy = DoubleArray(4)
                    val corners = arrayOf(doubleArrayOf(-hw, -hh), doubleArrayOf(hw, -hh), doubleArrayOf(hw, hh), doubleArrayOf(-hw, hh))
                    for (i in 0 until 4) {
                        val (u, v) = corners[i].let { it[0] to it[1] }
                        ox[i] = ccx + u * c - v * sn; oy[i] = ccy + u * sn + v * c
                    }
                    val name = if (square) "SQUARE" else "RECTANGLE"
                    return name to closedOut(ox, oy)
                }
                if (cv(sd) < 0.1) {
                    // rhombus / diamond: keep it symmetric about its centre
                    val cx = px.average(); val cy = py.average()
                    val d1x = (px[0] - px[2]) / 2; val d1y = (py[0] - py[2]) / 2
                    val d2x = (px[1] - px[3]) / 2; val d2y = (py[1] - py[3]) / 2
                    val ox = doubleArrayOf(cx + d1x, cx + d2x, cx - d1x, cx - d2x)
                    val oy = doubleArrayOf(cy + d1y, cy + d2y, cy - d1y, cy - d2y)
                    // a diamond standing on a corner: make the diagonals axis-aligned if close
                    val a1 = atan2(d1y, d1x)
                    val sa = snapAngle(a1, 90.0, 8.0)
                    if (sa != a1) rotateAll(ox, oy, cx, cy, sa - a1)
                    return "DIAMOND" to closedOut(ox, oy)
                }
                snapEdges(px, py, 5.0)
                return "QUADRILATERAL" to closedOut(px, py)
            }
            else -> {
                val angDev = ang.map { abs(it - (180.0 * (k - 2) / k)) }.maxOrNull() ?: 99.0
                val name = if (k == 5) "PENTAGON" else "HEXAGON"
                if (cv(sd) < 0.2 && angDev < 18) {
                    val (rx, ry) = regularPolygon(px, py, k)
                    return name to closedOut(rx, ry)
                }
                return name to closedOut(px, py)
            }
        }
    }

    private fun fitStar(s: Sketch): Pair<String, FloatArray>? {
        val hull = convexHull(s.lx, s.ly, s.k)
        if (hull.first.size < 5) return null
        val (tx, ty) = reduceHull(hull.first, hull.second, 5)
        val cx = tx.average(); val cy = ty.average()
        val rs = DoubleArray(5) { hypot(tx[it] - cx, ty[it] - cy) }
        if (cv(rs) > 0.25) return null
        val (rx, ry) = regularPolygon(tx, ty, 5)
        // pentagram path through every second tip (how a star is drawn)
        val order = intArrayOf(0, 2, 4, 1, 3)
        val ox = DoubleArray(5) { rx[order[it]] }
        val oy = DoubleArray(5) { ry[order[it]] }
        return "STAR" to closedOut(ox, oy)
    }

    private fun fitArc(s: Sketch): Pair<String, FloatArray>? {
        val c = fitCircle(s.rx, s.ry, s.m) ?: return null
        if (c[2] > s.diag * 6) return null
        if (circleError(s.rx, s.ry, s.m, c) > 0.07) return null
        val sw = sweep(s.rx, s.ry, s.m, c)
        if (abs(sw) < Math.toRadians(35.0) || abs(sw) > Math.toRadians(335.0)) return null
        val a0 = atan2(s.ry[0] - c[1], s.rx[0] - c[0])
        val steps = max(12, (abs(sw) / Math.toRadians(4.0)).toInt())
        val o = FloatArray((steps + 1) * 2)
        for (i in 0..steps) {
            val a = a0 + sw * i / steps
            o[2 * i] = (c[0] + c[2] * cos(a)).toFloat()
            o[2 * i + 1] = (c[1] + c[2] * sin(a)).toFloat()
        }
        return "ARC" to o
    }

    private fun fitPolyline(s: Sketch): Pair<String, FloatArray>? {
        val oc = corners(s.rx, s.ry, s.m, cyclic = false)
        val segs = oc.size - 1
        if (segs < 2 || segs > 4) return null
        val res = polyResidual(s.rx, s.ry, s.m, oc, cyclic = false)
        if (res[0] / s.diag > 0.03) return null
        // fit each segment, ignoring the ends near the corners
        val lines = ArrayList<DoubleArray>()
        for (i in 0 until segs) {
            val a = oc[i]; val b = oc[i + 1]
            val span = b - a
            val lo = a + (span * 0.15).toInt(); val hi = b - (span * 0.15).toInt()
            val xs = ArrayList<Double>(); val ys = ArrayList<Double>()
            for (j in lo..hi) { xs.add(s.rx[j]); ys.add(s.ry[j]) }
            if (xs.size < 2 || chord(s.rx, s.ry, a, b) < s.diag * 0.08) return null
            lines.add(tls(xs, ys))
        }
        val o = ArrayList<Double>()
        fun proj(l: DoubleArray, x: Double, y: Double): DoubleArray {
            val t = (x - l[0]) * l[2] + (y - l[1]) * l[3]
            return doubleArrayOf(l[0] + l[2] * t, l[1] + l[3] * t)
        }
        proj(lines[0], s.rx[0], s.ry[0]).let { o.add(it[0]); o.add(it[1]) }
        for (i in 1 until segs) {
            val p = intersect(lines[i - 1], lines[i]) ?: doubleArrayOf(s.rx[oc[i]], s.ry[oc[i]])
            if (hypot(p[0] - s.rx[oc[i]], p[1] - s.ry[oc[i]]) > s.diag * 0.2) {
                o.add(s.rx[oc[i]]); o.add(s.ry[oc[i]])
            } else { o.add(p[0]); o.add(p[1]) }
        }
        proj(lines[segs - 1], s.rx[s.m - 1], s.ry[s.m - 1]).let { o.add(it[0]); o.add(it[1]) }
        return "LINES" to FloatArray(o.size) { o[it].toFloat() }
    }
}
