package org.samesame.notes.feature.composer.panels

import kotlin.math.ceil
import kotlin.math.floor

/**
 * Where the glide popup goes when a press-and-glide on the font-size button starts: the popup shows as many rows as
 * fit on screen and is placed (and its one scrolling list pre-scrolled) so that the CURRENT row sits exactly under the
 * finger. Pure, all values in px of the same (root) coordinate system, y grows downwards; JVM-tested.
 */
internal object GlideLayout {

    /**
     * @property top root y of the popup's top edge (above the top padding).
     * @property visible rows the popup's viewport shows.
     * @property scroll first visible row (whole rows; the glide scrolls it further as a float).
     */
    data class Placement(val top: Float, val visible: Int, val scroll: Int) {
        /** Root y of the viewport's top for a popup with [topPad] px of top padding. */
        fun listTop(topPad: Float): Float = top + topPad
    }

    /**
     * Places a popup of [count] rows of [rowPx] each, with [topPad] / [bottomPad] px of padding, for a finger at
     * [fingerY] on the current row [anchor]. The popup's top stays at or below [minTop]; its viewport ends at or above
     * [maxBottom] (only the bottom padding may overhang, it is hidden behind the toolbar / keyboard).
     *
     * The list is not scrolled when the anchor row can sit under the finger without scrolling; otherwise it is scrolled
     * just enough. When even that does not fit (the anchor is one of the first rows and the finger is at the bottom of
     * the screen) the popup is clamped to the screen and the anchor ends up above the finger.
     */
    fun place(
        count: Int,
        anchor: Int,
        rowPx: Float,
        topPad: Float,
        bottomPad: Float,
        fingerY: Float,
        minTop: Float,
        maxBottom: Float,
    ): Placement {
        val n = count.coerceAtLeast(1)
        val a = anchor.coerceIn(0, n - 1)
        val available = maxBottom - minTop - topPad
        val visible = if (rowPx <= 0f) n else floor(available / rowPx).toInt().coerceIn(1, n)
        // k = the anchor's index inside the viewport: bounded by the screen above and below, and by the list ends.
        val kMaxScreen = floor((fingerY - minTop - topPad) / rowPx - 0.5f).toInt()
        val kMinScreen = ceil((fingerY - maxBottom) / rowPx + visible - 0.5f).toInt()
        var k = a.coerceAtMost(kMaxScreen).coerceAtLeast(kMinScreen).coerceIn(0, visible - 1)
        val scroll = (a - k).coerceIn(0, n - visible)
        k = a - scroll
        val listTop = fingerY - (k + 0.5f) * rowPx
        val top = (listTop - topPad).coerceIn(minTop, (maxBottom - visible * rowPx - topPad).coerceAtLeast(minTop))
        return Placement(top, visible, scroll)
    }

    /** Pixel height of a glide popup showing [visible] rows. */
    fun height(visible: Int, rowPx: Float, topPad: Float, bottomPad: Float): Float = topPad + visible * rowPx + bottomPad
}
