package org.samesame.notes.feature.composer.panels

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue

/**
 * Shared state of the font-size button's press-and-slide gesture: the popup reports the root bounds of its rows, of
 * itself and of its scrolling list, the button turns the finger's root position into a highlighted row and shows it
 * (also in the popup), and scrolls the list through [scrollState] with the whole popup as a scroll pad ([GlideScrollPad]).
 */
class FontSizeHoldState {
    /** Flat row index under the finger, or -1. */
    var highlighted by mutableIntStateOf(-1)
        internal set

    private val rows = mutableStateMapOf<Int, RowBounds>()

    /** Extent of the popup in root coordinates (a finger outside it highlights nothing). */
    internal var popupLeft = Float.NEGATIVE_INFINITY
        private set
    internal var popupRight = Float.POSITIVE_INFINITY
        private set
    internal var popupTop = Float.NEGATIVE_INFINITY
        private set
    internal var popupBottom = Float.POSITIVE_INFINITY
        private set

    /** Rows in the scrolling size list (0 = unknown: list rows are then found through their reported bounds). */
    internal var listCount = 0

    /** Visible part of the scrolling size list in root coordinates. */
    internal var listTop = 0f
        private set
    internal var listBottom = 0f
        private set

    /** The size list's scroll state while the popup is shown. */
    internal var scrollState: ScrollState? = null

    fun reportRow(bounds: RowBounds) {
        rows[bounds.index] = bounds
    }

    internal fun reportPopup(left: Float, right: Float, top: Float = Float.NEGATIVE_INFINITY, bottom: Float = Float.POSITIVE_INFINITY) {
        popupLeft = left; popupRight = right; popupTop = top; popupBottom = bottom
    }

    internal fun reportList(top: Float, bottom: Float) {
        listTop = top; listBottom = bottom
    }

    fun clear() {
        rows.clear()
        highlighted = -1
        popupLeft = Float.NEGATIVE_INFINITY; popupRight = Float.POSITIVE_INFINITY
        popupTop = Float.NEGATIVE_INFINITY; popupBottom = Float.POSITIVE_INFINITY
        listTop = 0f; listBottom = 0f
        listCount = 0
        scrollState = null
    }

    /**
     * Row under [y]: inside the scrolling list it is computed from the live scroll value (exact on every animation
     * frame), elsewhere from the reported row bounds (headings and Body).
     */
    fun rowAt(y: Float): Int {
        val list = scrollState
        if (list != null && listCount > 0 && y >= listTop && y < listBottom) {
            val content = (listBottom - listTop) + list.maxValue
            val i = GlideScrollPad.listIndexAt(y, listTop, listBottom, list.value.toFloat(), content / listCount, listCount)
            return if (i < 0) -1 else FontSizeRows.SIZE_START + i
        }
        return FontSizeRows.rowAt(y, rows.values.sortedBy { it.index })
    }

    internal fun insidePopup(x: Float): Boolean = x in popupLeft..popupRight

    internal fun insidePopup(x: Float, y: Float): Boolean = insidePopup(x) && y in popupTop..popupBottom

    /** The finger moved to ([x], [y]); returns true when the highlighted row changed (caller gives haptic feedback). */
    fun moveTo(x: Float, y: Float): Boolean {
        val next = if (insidePopup(x, y)) rowAt(y) else -1
        if (next == highlighted) return false
        highlighted = next
        return true
    }
}
