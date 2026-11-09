package org.samesame.notes.feature.composer.panels

import kotlin.math.roundToInt

/**
 * Press-and-glide on the font-size button: the mapping from the finger to the highlighted row of the popup.
 *
 * The mapping is purely positional and relative. When the glide starts, the highlight sits on the applied row (the
 * anchor) and the finger's y is remembered; from then on the highlight is the anchor plus one row per [STEP_DP] of
 * vertical travel (finger up = up the list), clamped to the list ([glideIndex]). Nothing depends on time, speed, the
 * number of move events, where the popup sits or how its list is scrolled, so the same displacement always gives the
 * same row. The list only follows the highlight ([centredScroll]); scrolling never changes it.
 *
 * All functions are pure (px), so the mapping is JVM-testable.
 */
internal object GlideScrollPad {
    /**
     * Finger travel per row. Smaller than the 44 dp row on purpose: the whole popup (30 rows) spans 812 dp at this
     * step, which still fits the screen height, while 44 dp would need 1276 dp. 28 dp is the floor for comfortable,
     * deliberate single steps.
     */
    const val STEP_DP = 28f

    /** Rows moved for a finger at [y] when the glide started at [anchorY]; rounds to the nearest step. */
    fun steps(y: Float, anchorY: Float, stepPx: Float): Int =
        if (stepPx <= 0f) 0 else ((y - anchorY) / stepPx).roundToInt()

    /**
     * Highlighted flat row for a finger at [y]: [anchorIndex] (the applied row) plus [steps], clamped to 0 until
     * [count]; -1 while the anchor or the list is unknown.
     */
    fun glideIndex(anchorIndex: Int, anchorY: Float, y: Float, stepPx: Float, count: Int): Int {
        if (count <= 0 || anchorIndex !in 0 until count) return -1
        return (anchorIndex + steps(y, anchorY, stepPx)).coerceIn(0, count - 1)
    }

    /**
     * Scroll value that centres row [listIndex] (rows of [rowHeight] stacked from the content top) in a viewport of
     * [viewport] px, clamped to 0..[maxScroll]; the highlighted row is then always fully visible.
     */
    fun centredScroll(listIndex: Int, rowHeight: Float, viewport: Float, maxScroll: Float): Float =
        (listIndex * rowHeight + rowHeight / 2f - viewport / 2f).coerceIn(0f, maxScroll.coerceAtLeast(0f))
}
