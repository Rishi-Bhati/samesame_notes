package org.samesame.notes.feature.composer.session

import org.samesame.notes.core.model.document.Pt
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/**
 * Straighten (stroke context menu). The reference's item straightens recognised handwriting lines (it needs its
 * handwriting engine and says it found none otherwise); we have no recogniser, so this works on the shapes the
 * straight-line tools already make: every selected stroke that is close to a straight line becomes the exact segment
 * from its first to its last point (like `StraightLine.snap`), turned to horizontal / vertical when it is within
 * [AXIS_SNAP_DEG] of an axis (about its midpoint). Curvy strokes (writing, circles) are left alone. Pure, unit-tested.
 */
object Straighten {
    /** A stroke counts as a line when no point is further than this share of its length from the chord... */
    const val MAX_DEVIATION_RATIO = 0.08f
    /** ...or than this many page units (short dashes). */
    const val MAX_DEVIATION_PU = 6f
    /** Shorter strokes (dots, taps) are left alone. */
    const val MIN_LENGTH_PU = 24f
    /** Lines this close to horizontal / vertical are snapped onto the axis. */
    const val AXIS_SNAP_DEG = 5f

    data class Segment(val a: Pt, val b: Pt)

    /** The segment a stroke through [points] straightens to, or null when it is not line-like. */
    fun segmentFor(points: List<Pt>): Segment? {
        if (points.size < 2) return null
        val a = points.first(); val b = points.last()
        val len = hypot(b.x - a.x, b.y - a.y)
        if (len < MIN_LENGTH_PU) return null
        var maxDev = 0f
        for (p in points) maxDev = max(maxDev, abs((b.x - a.x) * (a.y - p.y) - (a.x - p.x) * (b.y - a.y)) / len)
        // A stroke that doubles back (its path is much longer than the chord) is not a line either.
        var path = 0f
        for (i in 1 until points.size) path += hypot(points[i].x - points[i - 1].x, points[i].y - points[i - 1].y)
        if (path > len * 1.25f) return null
        if (maxDev > max(MAX_DEVIATION_PU, len * MAX_DEVIATION_RATIO)) return null
        return snapToAxis(Segment(a, b))
    }

    /** [s] rotated about its midpoint onto the nearest axis when within [AXIS_SNAP_DEG]; otherwise unchanged. */
    fun snapToAxis(s: Segment): Segment {
        val dx = s.b.x - s.a.x; val dy = s.b.y - s.a.y
        val angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
        val nearest = Math.round(angle / 90f) * 90f
        if (abs(angle - nearest) > AXIS_SNAP_DEG || abs(angle - nearest) < 1e-4f) return s
        val half = hypot(dx, dy) / 2f
        val cx = (s.a.x + s.b.x) / 2f; val cy = (s.a.y + s.b.y) / 2f
        val r = Math.toRadians(nearest.toDouble())
        val ux = (cos(r) * half).toFloat(); val uy = (sin(r) * half).toFloat()
        return Segment(Pt(cx - ux, cy - uy), Pt(cx + ux, cy + uy))
    }
}
