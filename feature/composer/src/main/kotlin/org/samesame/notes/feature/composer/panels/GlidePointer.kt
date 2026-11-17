package org.samesame.notes.feature.composer.panels

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min

/**
 * Press-and-glide on the font-size button, direct pointing: the highlighted row is the row physically under the
 * finger (1:1, no gain, no velocity), with a little hysteresis at row borders. Only at the popup's top and bottom edge
 * bands the list auto-scrolls, faster the deeper the finger is in the band; leaving the band stops it at once and the
 * row under the finger is highlighted again.
 *
 * - Until the finger has moved [ARM_DP] from the press point nothing happens: the highlight stays on the anchor (the
 *   current row) and the bands are inert, so a stationary hold never scrolls.
 * - A band that contains the press point (the finger starts at the bottom of the popup) has its inner border moved to
 *   the press point plus [DEAD_DP]: "below where I started = larger sizes, faster the further down".
 * - [suppressed] (finger far off the popup sideways) hides the highlight; coming back shows the row under the finger.
 *
 * Plain Kotlin, no allocation per frame; distances in px, built from dp with [pxPerDp]. Rows are indexed over the whole
 * list; the viewport shows [visible] rows from [scroll] (float rows) and starts at root y [listTop].
 */
internal class GlidePointer(
    val count: Int,
    val visible: Int,
    scroll: Int,
    val rowPx: Float,
    val listTop: Float,
    val anchor: Int,
    private val pressY: Float,
    pxPerDp: Float,
) {
    private val armPx = ARM_DP * pxPerDp
    private val hysteresisRows = if (rowPx <= 0f) 0f else HYSTERESIS_DP * pxPerDp / rowPx
    private val bandPx = BAND_ROWS * rowPx
    private val viewportPx = visible * rowPx
    private val topInner: Float
    private val bottomInner: Float

    init {
        val deadPx = DEAD_DP * pxPerDp
        val pressLocal = pressY - listTop
        topInner = if (pressLocal < bandPx) pressLocal - deadPx else bandPx
        bottomInner = if (pressLocal > viewportPx - bandPx) pressLocal + deadPx else viewportPx - bandPx
    }

    /** First visible row, fractional while the list auto-scrolls. */
    var scroll: Float = scroll.toFloat().coerceIn(0f, maxScroll)
        private set

    /** Highlighted row (over the whole list), -1 while [suppressed]. */
    var highlighted: Int = anchor.coerceIn(0, count - 1)
        private set

    /** True once the finger moved [ARM_DP] from the press point: the highlight follows the finger and the bands work. */
    var armed: Boolean = false
        private set

    var suppressed: Boolean = false
        private set

    private var fingerY = pressY
    private var lastRow = highlighted

    val maxScroll: Float get() = (count - visible).coerceAtLeast(0).toFloat()

    /** The finger is at root [y]; [suppressed] while it is far off the popup sideways. */
    fun pointer(y: Float, suppressed: Boolean = false) {
        fingerY = y
        this.suppressed = suppressed
        if (!armed && abs(y - pressY) > armPx) armed = true
        update()
    }

    /** Signed auto-scroll speed (rows/s, positive = towards the end of the list) for the finger's current depth in a band. */
    fun velocity(): Float {
        if (!armed) return 0f
        val local = fingerY - listTop
        return when {
            local > bottomInner -> speed((local - bottomInner) / bandPx)
            local < topInner -> -speed((topInner - local) / bandPx)
            else -> 0f
        }
    }

    /** One frame of [dtSeconds]: integrates the band scroll. Returns true when the list moved. */
    fun advance(dtSeconds: Float): Boolean {
        if (suppressed) return false
        val v = velocity()
        if (v == 0f) return false
        val next = (scroll + v * dtSeconds.coerceIn(0f, MAX_FRAME_S)).coerceIn(0f, maxScroll)
        if (next == scroll) return false
        scroll = next
        update()
        return true
    }

    /** Row under root [y] for the current scroll (clamped to the list). */
    fun rowAt(y: Float): Int = floor((y - listTop) / rowPx + scroll).toInt().coerceIn(0, count - 1)

    /** Root y of the centre of [row] for the current scroll. */
    fun rowCentreY(row: Int): Float = listTop + (row - scroll + 0.5f) * rowPx

    private fun update() {
        if (suppressed) { highlighted = -1; return }
        if (!armed) { highlighted = lastRow; return }
        val rowF = (fingerY - listTop) / rowPx + scroll
        val h = lastRow
        // Hysteresis: the finger has to be a few dp inside a neighbouring row before the highlight moves.
        if (rowF < h - hysteresisRows || rowF > h + 1 + hysteresisRows) lastRow = floor(rowF).toInt().coerceIn(0, count - 1)
        highlighted = lastRow
    }

    private fun speed(depth: Float): Float {
        if (depth <= 0f) return 0f
        val u = min(depth, 1f)
        return MIN_SPEED + (MAX_SPEED - MIN_SPEED) * u * u
    }

    companion object {
        /** Movement from the press point after which the highlight follows the finger and the bands work. */
        const val ARM_DP = 8f

        /** Extra travel past a row border before the highlight moves to the neighbouring row (no flicker on the border). */
        const val HYSTERESIS_DP = 3f

        /** Edge band height in rows. */
        const val BAND_ROWS = 1f

        /** Dead zone past the press point when the finger starts inside a band. */
        const val DEAD_DP = 4f

        /** Rows per second at the band's inner border: one size step in about 0.7 s, easy to stop. */
        const val MIN_SPEED = 1.5f

        /** Rows per second at the band's outer edge and beyond: 17 -> 96 (12 rows) in under a second. */
        const val MAX_SPEED = 14f

        /** Frame gaps longer than this (a stall) are integrated as this, so the list never leaps. */
        const val MAX_FRAME_S = 0.05f
    }
}
