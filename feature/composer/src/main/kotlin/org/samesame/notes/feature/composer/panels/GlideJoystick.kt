package org.samesame.notes.feature.composer.panels

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Press-and-glide on the font-size button, rate controlled (a joystick): the finger's vertical offset from a neutral
 * line (the vertical centre of the size popup) sets how fast the highlight travels through the rows; holding the finger
 * still keeps it travelling, bringing it back to the centre stops it.
 *
 * - The glide starts on the button, far below the centre. Until the finger first reaches the dead zone (or crosses the
 *   neutral line) the joystick is not [armed]: nothing moves and the highlight stays on the applied row.
 * - Inside the dead zone, and while [advance] is told the finger is held back (over the toolbar), the position eases
 *   to the nearest row, so it always comes to rest exactly on a row.
 * - Finger above the centre = up the list (towards Heading 1), below = down the list (larger sizes).
 *
 * Plain Kotlin, no allocation per frame; all distances in px (built from dp with [pxPerDp]).
 */
internal class GlideJoystick(pxPerDp: Float) {

    private val deadZonePx = DEAD_ZONE_DP * pxPerDp
    private val rampPx = RAMP_DP * pxPerDp
    private val fullSpeedPx = FULL_SPEED_DP * pxPerDp

    /** Fractional row under the highlight (0 = first row), -1 before [start]. */
    var position: Float = -1f
        private set

    /** Rows in the list (0 before [start]). */
    var count: Int = 0
        private set

    /** True once the finger has reached the centre; before that the joystick does nothing. */
    var armed: Boolean = false
        private set

    private var startSign = 0

    val started: Boolean get() = count > 0

    /** Highlighted row: [position] rounded, or -1 before [start]. */
    val row: Int get() = if (count <= 0) -1 else position.roundToInt().coerceIn(0, count - 1)

    /** A glide begins on [anchorRow] (the applied row) of a list of [count] rows. */
    fun start(anchorRow: Int, count: Int) {
        this.count = count.coerceAtLeast(0)
        position = if (this.count == 0) -1f else anchorRow.coerceIn(0, this.count - 1).toFloat()
        armed = false
        startSign = 0
    }

    fun reset() {
        position = -1f; count = 0; armed = false; startSign = 0
    }

    /** Signed speed in rows per second for a finger [offsetPx] below (positive) or above (negative) the neutral line. */
    fun velocity(offsetPx: Float): Float {
        val d = abs(offsetPx) - deadZonePx
        if (d <= 0f) return 0f
        val ramp = if (rampPx <= 0f) 1f else min(d / rampPx, 1f)
        val u = if (fullSpeedPx <= 0f) 1f else min(d / fullSpeedPx, 1f)
        val speed = MIN_SPEED * ramp + (MAX_SPEED - MIN_SPEED) * u * u
        return if (offsetPx > 0f) speed else -speed
    }

    /**
     * One frame of [dtSeconds] with the finger [offsetPx] from the neutral line (NaN while the neutral line is not
     * known yet). [held] stops the travel (finger back over the toolbar). Returns the highlighted [row].
     */
    fun advance(offsetPx: Float, dtSeconds: Float, held: Boolean = false): Int {
        if (count <= 0) return -1
        if (offsetPx.isNaN()) return row
        if (!armed) {
            val sign = if (offsetPx > 0f) 1 else if (offsetPx < 0f) -1 else 0
            if (startSign == 0) startSign = sign
            if (abs(offsetPx) <= deadZonePx || sign != startSign) armed = true else return row
        }
        val dt = dtSeconds.coerceIn(0f, MAX_FRAME_S)
        val v = if (held) 0f else velocity(offsetPx)
        val last = (count - 1).toFloat()
        position = if (v != 0f) {
            (position + v * dt).coerceIn(0f, last)
        } else {
            // At rest: ease onto the nearest row.
            val target = position.roundToInt().toFloat().coerceIn(0f, last)
            val next = position + (target - position) * (1f - exp(-SNAP_RATE * dt))
            if (abs(target - next) < SNAP_EPSILON) target else next
        }
        return row
    }

    companion object {
        /** Half width of the neutral band around the popup's centre where nothing moves. */
        const val DEAD_ZONE_DP = 16f

        /** The slowest speed fades in over this much travel past the dead zone (no jolt on leaving it). */
        const val RAMP_DP = 8f

        /** Travel past the dead zone at which the top speed is reached (the popup's half height is about 214 dp). */
        const val FULL_SPEED_DP = 136f

        /** Rows per second just outside the dead zone: one size step in about 0.4 s, easy to stop on. */
        const val MIN_SPEED = 2.5f

        /** Top speed, rows per second: the whole list (29 steps) in about 1.5 s. */
        const val MAX_SPEED = 20f

        /** Rate (1/s) of the ease onto the nearest row when the finger rests in the dead zone (about 0.2 s). */
        const val SNAP_RATE = 16f

        /** Frame gaps longer than this (a stall) are integrated as this, so the list never leaps. */
        const val MAX_FRAME_S = 0.05f

        private const val SNAP_EPSILON = 0.002f
    }
}
