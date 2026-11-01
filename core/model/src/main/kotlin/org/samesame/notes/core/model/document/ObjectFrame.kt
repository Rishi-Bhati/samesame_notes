package org.samesame.notes.core.model.document

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sin

/**
 * A 2D affine map, laid out like Ink's AffineTransform: x' = m00 x + m10 y + m20, y' = m01 x + m11 y + m21.
 * Pure (no Android/Ink types) so the object geometry below is JVM testable.
 */
data class Affine2(val m00: Float, val m10: Float, val m20: Float, val m01: Float, val m11: Float, val m21: Float) {
    fun mapX(x: Float, y: Float) = m00 * x + m10 * y + m20
    fun mapY(x: Float, y: Float) = m01 * x + m11 * y + m21

    companion object {
        fun rotateAbout(deg: Float, px: Float, py: Float): Affine2 {
            val r = Math.toRadians(deg.toDouble()); val c = cos(r).toFloat(); val s = sin(r).toFloat()
            return Affine2(c, -s, px - c * px + s * py, s, c, py - s * px - c * py)
        }
    }
}

/** A point in a plane (page units or view px). */
data class Pt(val x: Float, val y: Float)

/**
 * The 8 resize handles of an object frame as normalised local coordinates: [nx], [ny] are -1 (left/top), 0 or +1
 * (right/bottom) of the half extents.
 */
enum class FrameHandle(val nx: Int, val ny: Int) {
    TL(-1, -1), T(0, -1), TR(1, -1), L(-1, 0), R(1, 0), BL(-1, 1), B(0, 1), BR(1, 1);

    val isCorner: Boolean get() = nx != 0 && ny != 0
}

/**
 * The frame of a floating object: centre, size and clockwise rotation (degrees, y down: the same sense as
 * `Canvas.rotate` and [FloatingObject.rotationDeg]). All chrome, hit-testing and resize math goes through this.
 */
data class ObjectFrame(val cx: Float, val cy: Float, val w: Float, val h: Float, val rotationDeg: Float = 0f) {
    private val cosT: Float get() = cos(Math.toRadians(rotationDeg.toDouble())).toFloat()
    private val sinT: Float get() = sin(Math.toRadians(rotationDeg.toDouble())).toFloat()

    /** Local offset from the centre (along the box's own axes) to a world point. */
    fun toWorld(lx: Float, ly: Float): Pt {
        val c = cosT; val s = sinT
        return Pt(cx + lx * c - ly * s, cy + lx * s + ly * c)
    }

    /** The world point [p] in the box's own axes, relative to the centre. */
    fun toLocal(p: Pt): Pt {
        val c = cosT; val s = sinT
        val dx = p.x - cx; val dy = p.y - cy
        return Pt(dx * c + dy * s, -dx * s + dy * c)
    }

    fun handlePoint(handle: FrameHandle): Pt = toWorld(handle.nx * w / 2f, handle.ny * h / 2f)

    /** TL, TR, BR, BL in world space. */
    fun corners(): List<Pt> = listOf(
        toWorld(-w / 2f, -h / 2f), toWorld(w / 2f, -h / 2f), toWorld(w / 2f, h / 2f), toWorld(-w / 2f, h / 2f),
    )

    /** The rotate handle: [gap] beyond the middle of the bottom edge, along the box's own "down". */
    fun rotateHandlePoint(gap: Float): Pt = toWorld(0f, h / 2f + gap)

    fun contains(p: Pt, inflate: Float = 0f): Boolean {
        val l = toLocal(p)
        return abs(l.x) <= w / 2f + inflate && abs(l.y) <= h / 2f + inflate
    }

    /** Axis-aligned bounds of the rotated frame: left, top, right, bottom. */
    fun aabb(): RectPu {
        val cs = corners()
        return RectPu(cs.minOf { it.x }, cs.minOf { it.y }, cs.maxOf { it.x }, cs.maxOf { it.y })
    }

    /** This frame carried by [m] (translation, rotation, and scale along the box's own axes; no shear). */
    fun transformedBy(m: Affine2): ObjectFrame {
        val c = cosT; val s = sinT
        // Images of the box's unit axes under the linear part of [m].
        val ux = m.m00 * c + m.m10 * s; val uy = m.m01 * c + m.m11 * s
        val vx = -m.m00 * s + m.m10 * c; val vy = -m.m01 * s + m.m11 * c
        val angle = if (hypot(ux, uy) < 1e-6f) rotationDeg else Math.toDegrees(atan2(uy, ux).toDouble()).toFloat()
        return ObjectFrame(m.mapX(cx, cy), m.mapY(cx, cy), w * hypot(ux, uy), h * hypot(vx, vy), normalizeAngle(angle))
    }

    fun toRect(): RectPu = RectPu(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
}

val FloatingObject.frame: ObjectFrame
    get() = ObjectFrame((rect.left + rect.right) / 2f, (rect.top + rect.bottom) / 2f, rect.width, rect.height, rotationDeg)

/** This object carried by [m]; rect and rotation follow the frame. */
fun FloatingObject.transformedBy(m: Affine2): FloatingObject {
    if (rotationDeg == 0f && m.m10 == 0f && m.m01 == 0f && m.m00 > 0f && m.m11 > 0f) {
        // Axis-aligned fast path: exactly the old rect mapping (no rounding drift).
        return copy(rect = rect.mapped(m.m00, m.m10, m.m20, m.m01, m.m11, m.m21))
    }
    val f = frame.transformedBy(m)
    return copy(rect = f.toRect(), rotationDeg = f.rotationDeg)
}

/** Page-space bounds of the rotated object (what selection, z-order and lasso use). */
val FloatingObject.rotatedBounds: RectPu get() = if (rotationDeg == 0f) rect else frame.aabb()

/** Can this object be rotated? (Voice pills are fixed.) */
val FloatingObject.isRotatable: Boolean get() = obj !is VoiceObject

/** Angle normalised to (-180, 180]. */
fun normalizeAngle(deg: Float): Float {
    var a = deg % 360f
    if (a > 180f) a -= 360f
    if (a <= -180f) a += 360f
    return a
}

/** Rotation and resize gestures of one object frame. Pure. */
object FrameGestures {
    /** The rotation snaps to multiples of [SNAP_STEP_DEG] when within [SNAP_TOLERANCE_DEG] of one. Our own choice (the reference's lives in native code). */
    const val SNAP_STEP_DEG = 45f
    const val SNAP_TOLERANCE_DEG = 3f
    const val MIN_SCALE = 0.05f

    fun snap(deg: Float): Float {
        val n = normalizeAngle(deg)
        val nearest = round(n / SNAP_STEP_DEG) * SNAP_STEP_DEG
        return if (abs(n - nearest) <= SNAP_TOLERANCE_DEG) normalizeAngle(nearest) else n
    }

    /** The absolute angle after dragging the rotate handle from [start] to [now] about the centre of [frame] (snapped). */
    fun rotatedAngle(frame: ObjectFrame, start: Pt, now: Pt): Float {
        val a0 = atan2(start.y - frame.cy, start.x - frame.cx)
        val a1 = atan2(now.y - frame.cy, now.x - frame.cx)
        val delta = Math.toDegrees((a1 - a0).toDouble()).toFloat()
        return snap(frame.rotationDeg + delta)
    }

    /** The map that turns [frame] to [angleDeg] about its centre. */
    fun rotation(frame: ObjectFrame, angleDeg: Float): Affine2 =
        Affine2.rotateAbout(angleDeg - frame.rotationDeg, frame.cx, frame.cy)

    /**
     * Resizes [frame] by dragging [handle] to the world point [now]: the opposite edge/corner stays fixed and the
     * dragged edge lands exactly under [now] (in the box's own axes) unless a minimum applies. [lockAspect] (corners
     * only) keeps the ratio by following the axis that moved most. [minSpan] is the least width/height.
     */
    fun resize(frame: ObjectFrame, handle: FrameHandle, now: Pt, lockAspect: Boolean = false, minSpan: Float = 0f): Affine2 {
        val l = frame.toLocal(now)
        fun scale(n: Int, local: Float, span: Float): Float {
            if (n == 0 || span < 1f) return 1f
            val newSpan = n * local + span / 2f // the opposite edge sits at -n * span / 2
            return max(max(newSpan / span, MIN_SCALE), minSpan / span)
        }
        var sx = scale(handle.nx, l.x, frame.w)
        var sy = scale(handle.ny, l.y, frame.h)
        if (lockAspect && handle.isCorner) { val u = max(sx, sy); sx = u; sy = u }
        // Fixed point: the opposite edge/corner (the centre line for an axis that isn't resized).
        val a = frame.toWorld(-handle.nx * frame.w / 2f, -handle.ny * frame.h / 2f)
        val c = cos(Math.toRadians(frame.rotationDeg.toDouble())).toFloat()
        val s = sin(Math.toRadians(frame.rotationDeg.toDouble())).toFloat()
        // M = R S R^-1 about a:  linear part L = R diag(sx, sy) R^T.
        val m00 = c * c * sx + s * s * sy
        val m10 = c * s * (sx - sy)
        val m01 = m10
        val m11 = s * s * sx + c * c * sy
        return Affine2(m00, m10, a.x - m00 * a.x - m10 * a.y, m01, m11, a.y - m01 * a.x - m11 * a.y)
    }
}
