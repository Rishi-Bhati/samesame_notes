package org.samesame.notes.feature.composer.session.tools

import org.samesame.notes.core.model.document.Affine2
import org.samesame.notes.core.model.document.FrameGestures
import org.samesame.notes.core.model.document.ObjectFrame
import org.samesame.notes.core.model.document.Pt
import org.samesame.notes.core.model.document.normalizeAngle
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sin

/**
 * Two-finger pinch / twist on one selected object (page space, pure). The object scales uniformly about its centre by
 * the change of the finger distance (images and drawings keep their ratio; a text box's frame scales, its font does
 * not) and, when [rotatable], turns by the change of the finger angle once the twist passes [ROTATE_SLOP_DEG] (so a
 * plain pinch never tilts the object). The resulting angle snaps like the rotate handle ([FrameGestures.snap]).
 * Limits are the handle-resize limits: neither side below [minSpan] (an object already smaller can't shrink further
 * but doesn't jump either), scale at least [FrameGestures.MIN_SCALE], no upper limit.
 */
class ObjectPinch(
    private val frame: ObjectFrame,
    a0: Pt,
    b0: Pt,
    private val rotatable: Boolean,
    private val minSpan: Float,
) {
    private val span0 = hypot(b0.x - a0.x, b0.y - a0.y)
    private val angle0 = angleOf(a0, b0)

    /** The part of the twist swallowed by the slop, fixed when rotation engages (null = not engaged yet). */
    private var rotateOffset: Float? = null

    /** Last result, for "did anything change". */
    var scale: Float = 1f
        private set
    var rotationDelta: Float = 0f
        private set

    val changed: Boolean get() = abs(scale - 1f) > 1e-3f || abs(rotationDelta) > 1e-3f

    /** The map from the frame at the gesture start to where the fingers [a], [b] put it now. */
    fun update(a: Pt, b: Pt): Affine2 {
        scale = clampScale(if (span0 < 1f) 1f else hypot(b.x - a.x, b.y - a.y) / span0, frame.w, frame.h, minSpan)
        rotationDelta = 0f
        if (rotatable) {
            val raw = normalizeAngle(angleOf(a, b) - angle0)
            val off = rotateOffset ?: if (abs(raw) >= ROTATE_SLOP_DEG) (sign(raw) * ROTATE_SLOP_DEG).also { rotateOffset = it } else null
            if (off != null) {
                val target = FrameGestures.snap(frame.rotationDeg + normalizeAngle(raw - off))
                rotationDelta = normalizeAngle(target - frame.rotationDeg)
            }
        }
        return matrix(frame.cx, frame.cy, scale, rotationDelta)
    }

    companion object {
        /** Twist needed before the object starts to turn. Our own choice: the reference's object control is native. */
        const val ROTATE_SLOP_DEG = 10f

        /** Clockwise (y down) angle of the line a -> b, degrees. */
        fun angleOf(a: Pt, b: Pt): Float = Math.toDegrees(atan2((b.y - a.y).toDouble(), (b.x - a.x).toDouble())).toFloat()

        fun clampScale(raw: Float, w: Float, h: Float, minSpan: Float): Float {
            val side = min(w, h)
            val floor = if (side < 1f) 1f else min(1f, max(FrameGestures.MIN_SCALE, minSpan / side))
            return max(raw, floor)
        }

        /** Uniform [scale] and clockwise [deltaDeg] about ([cx], [cy]). */
        fun matrix(cx: Float, cy: Float, scale: Float, deltaDeg: Float): Affine2 {
            val r = Math.toRadians(deltaDeg.toDouble())
            val c = (cos(r) * scale).toFloat()
            val s = (sin(r) * scale).toFloat()
            return Affine2(c, -s, cx - c * cx + s * cy, s, c, cy - s * cx - c * cy)
        }
    }
}
