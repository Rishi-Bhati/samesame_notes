package org.samesame.notes.core.ink.tools

import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Input-level area erasing (docs 4.2). Only inputs are persisted, so cuts are made in the input list:
 * the erased parameter ranges of the polyline are computed exactly (line segment vs. capsule), the
 * complement becomes new strokes, and boundary inputs are interpolated so the cut is clean even when
 * the original inputs are far apart.
 */
object StrokeSplitter {
    /** Kept runs shorter than this (pu) are tangency slivers and are dropped. */
    private const val MIN_RUN_LENGTH = 0.2f
    private const val EPS = 1e-6f

    /**
     * Cuts [stroke] with the eraser path [path] (x0,y0,x1,y1,... in page units, at least one point; one
     * point is a tap) of [radius]. A stroke's own half width is added to the radius.
     * Returns null when nothing is erased, otherwise the surviving pieces (possibly empty), in order.
     */
    fun split(stroke: Stroke, path: FloatArray, radius: Float): List<Stroke>? {
        val inputs = stroke.inputs
        val n = inputs.size
        if (n == 0 || path.size < 2) return null
        val r = radius + stroke.brush.size / 2f
        val xs = FloatArray(n); val ys = FloatArray(n)
        val tmp = StrokeInput()
        for (i in 0 until n) { inputs.populate(i, tmp); xs[i] = tmp.x; ys[i] = tmp.y }

        val erased = ArrayList<FloatArray>() // [start, end] in input-index space
        val segCount = max(n - 1, 1)
        val last = (n - 1).toFloat()
        val pathPoints = path.size / 2
        for (i in 0 until segCount) {
            val i1 = min(i + 1, n - 1)
            val px = xs[i]; val py = ys[i]; val dx = xs[i1] - px; val dy = ys[i1] - py
            val sxMin = min(px, px + dx) - r; val sxMax = max(px, px + dx) + r
            val syMin = min(py, py + dy) - r; val syMax = max(py, py + dy) + r
            for (k in 0 until max(pathPoints - 1, 1)) {
                val ax = path[2 * k]; val ay = path[2 * k + 1]
                val bx = path[2 * min(k + 1, pathPoints - 1)]; val by = path[2 * min(k + 1, pathPoints - 1) + 1]
                if (min(ax, bx) > sxMax || max(ax, bx) < sxMin || min(ay, by) > syMax || max(ay, by) < syMin) continue
                val iv = capsuleInterval(px, py, dx, dy, ax, ay, bx, by, r) ?: continue
                erased += floatArrayOf(i + iv[0], i + min(iv[1], if (n == 1) 0f else 1f))
            }
        }
        if (erased.isEmpty()) return null
        erased.sortBy { it[0] }

        val kept = ArrayList<FloatArray>()
        var cursor = 0f
        for (iv in erased) {
            if (iv[0] > cursor + EPS) kept += floatArrayOf(cursor, min(iv[0], last))
            cursor = max(cursor, iv[1])
        }
        if (cursor < last - EPS) kept += floatArrayOf(cursor, last)

        val pieces = ArrayList<Stroke>(kept.size)
        for (run in kept) buildRun(stroke, run[0], run[1])?.let(pieces::add)
        return pieces
    }

    private fun buildRun(stroke: Stroke, a: Float, b: Float): Stroke? {
        val inputs = stroke.inputs
        val batch = MutableStrokeInputBatch()
        val pts = ArrayList<StrokeInput>()
        pts += lerpAt(stroke, a)
        var k = floor(a).toInt() + 1
        while (k < b - EPS) { if (k > a + EPS) pts += inputs.get(k); k++ }
        pts += lerpAt(stroke, b)
        var len = 0f
        for (i in 1 until pts.size) len += hypot(pts[i].x - pts[i - 1].x, pts[i].y - pts[i - 1].y)
        if (len < MIN_RUN_LENGTH) return null
        val t0 = pts[0].elapsedTimeMillis
        val tmp = StrokeInput()
        var prevT = 0L
        for (p in pts) {
            val t = max(p.elapsedTimeMillis - t0, prevT)
            prevT = t
            tmp.update(p.x, p.y, t, p.toolType, p.strokeUnitLengthCm, p.pressure, p.tiltRadians, p.orientationRadians)
            batch.add(tmp)
        }
        return Stroke(stroke.brush, batch)
    }

    private fun floor(v: Float) = kotlin.math.floor(v)

    private fun lerpAt(stroke: Stroke, u: Float): StrokeInput {
        val inputs = stroke.inputs
        val n = inputs.size
        val i = min(floor(u).toInt(), max(n - 2, 0)).coerceAtLeast(0)
        if (n == 1) return inputs.get(0)
        val t = (u - i).coerceIn(0f, 1f)
        val p = inputs.get(i); val q = inputs.get(i + 1)
        if (t <= 0f) return p
        if (t >= 1f) return q
        fun l(a: Float, b: Float) = a + (b - a) * t
        return StrokeInput().also {
            it.update(
                l(p.x, q.x), l(p.y, q.y), p.elapsedTimeMillis + ((q.elapsedTimeMillis - p.elapsedTimeMillis) * t).toLong(),
                p.toolType, p.strokeUnitLengthCm,
                if (p.hasPressure && q.hasPressure) l(p.pressure, q.pressure) else StrokeInput.NO_PRESSURE,
                if (p.hasTilt && q.hasTilt) l(p.tiltRadians, q.tiltRadians) else StrokeInput.NO_TILT,
                if (p.hasOrientation && q.hasOrientation) l(p.orientationRadians, q.orientationRadians) else StrokeInput.NO_ORIENTATION,
            )
        }
    }

    /**
     * Parameter interval t in [0,1] where P(t) = (px,py) + t (dx,dy) lies within [r] of segment AB, or null.
     * Capsule = rectangle + two end discs; their intersections with the line are overlapping intervals
     * whose hull is exact because the capsule is convex.
     */
    internal fun capsuleInterval(
        px: Float, py: Float, dx: Float, dy: Float,
        ax: Float, ay: Float, bx: Float, by: Float, r: Float,
    ): FloatArray? {
        var lo = Float.POSITIVE_INFINITY; var hi = Float.NEGATIVE_INFINITY
        fun add(iv: FloatArray?) { if (iv != null) { lo = min(lo, iv[0]); hi = max(hi, iv[1]) } }
        add(circleInterval(px, py, dx, dy, ax, ay, r))
        add(circleInterval(px, py, dx, dy, bx, by, r))
        val ux = bx - ax; val uy = by - ay
        val len = sqrt(ux * ux + uy * uy)
        if (len > EPS) {
            val nx = ux / len; val ny = uy / len
            // local coordinates: u along AB, v perpendicular
            val u0 = (px - ax) * nx + (py - ay) * ny; val du = dx * nx + dy * ny
            val v0 = -(px - ax) * ny + (py - ay) * nx; val dv = -dx * ny + dy * nx
            var t0 = 0f; var t1 = 1f
            val ok = slab(u0, du, 0f, len) { a, b -> t0 = max(t0, a); t1 = min(t1, b) } &&
                slab(v0, dv, -r, r) { a, b -> t0 = max(t0, a); t1 = min(t1, b) }
            if (ok && t0 <= t1) add(floatArrayOf(t0, t1))
        }
        return if (lo <= hi) floatArrayOf(max(lo, 0f), min(hi, 1f)) else null
    }

    private inline fun slab(p0: Float, d: Float, mn: Float, mx: Float, apply: (Float, Float) -> Unit): Boolean {
        if (kotlin.math.abs(d) < EPS) {
            if (p0 < mn || p0 > mx) return false
            apply(Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY)
            return true
        }
        val a = (mn - p0) / d; val b = (mx - p0) / d
        apply(min(a, b), max(a, b))
        return true
    }

    private fun circleInterval(px: Float, py: Float, dx: Float, dy: Float, cx: Float, cy: Float, r: Float): FloatArray? {
        val ox = px - cx; val oy = py - cy
        val a = dx * dx + dy * dy
        val c = ox * ox + oy * oy - r * r
        if (a < EPS) return if (c <= 0f) floatArrayOf(0f, 1f) else null
        val b = 2f * (dx * ox + dy * oy)
        val disc = b * b - 4f * a * c
        if (disc < 0f) return null
        val s = sqrt(disc)
        val t0 = (-b - s) / (2f * a); val t1 = (-b + s) / (2f * a)
        if (t1 < 0f || t0 > 1f) return null
        return floatArrayOf(max(t0, 0f), min(t1, 1f))
    }
}
