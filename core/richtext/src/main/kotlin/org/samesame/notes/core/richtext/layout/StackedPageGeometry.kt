package org.samesame.notes.core.richtext.layout

import org.samesame.notes.core.model.Limits
import org.samesame.notes.core.richtext.BodyTextMetrics

/**
 * Pages of one width ([Limits.PAGE_WIDTH_PU]) and individual heights, stacked in a column (or a row) with [gap] between
 * them. Page `i` is [heights]`[i]` tall; pages past the end (virtual pages the text needs before the session appends
 * them) are [defaultHeight] tall. In [continuous] mode the gap is 0, the top margin applies only to the first page and
 * there is no bottom margin (doc §1.2).
 *
 * The editor's `DocumentLayout` and the export renderer both answer the text engine through this class, so body text
 * paginates identically on screen and in every export.
 */
class StackedPageGeometry(
    heights: List<Float>,
    val defaultHeight: Float,
    gap: Float = SimplePageGeometry.DEFAULT_PAGE_GAP,
    override val continuous: Boolean = false,
    val horizontal: Boolean = false,
) : TextPageGeometry {
    val gap: Float = if (continuous) 0f else gap
    private val heights: FloatArray = heights.toFloatArray()
    private val width = Limits.PAGE_WIDTH_PU

    /** Start of page i along the flow axis; `starts[pageCount]` is the end of the last page plus one gap. */
    private val starts: FloatArray = FloatArray(this.heights.size + 1).also { s ->
        for (i in this.heights.indices) s[i + 1] = s[i] + extent(i) + this.gap
    }

    override val pageCount: Int get() = heights.size

    /** Height of page [index] (virtual pages included). */
    fun pageHeight(index: Int): Float = if (index in heights.indices) heights[index] else defaultHeight

    /** Size of a page along the flow axis. */
    private fun extent(index: Int): Float = if (horizontal) width else pageHeight(index)

    /** Where page [index] starts along the flow axis (virtual pages continue with [defaultHeight] steps). */
    fun start(index: Int): Float {
        if (index <= 0) return 0f
        if (index <= pageCount) return starts[index]
        val step = (if (horizontal) width else defaultHeight) + gap
        return starts[pageCount] + (index - pageCount) * step
    }

    /** Total extent along the flow axis of the real pages (no trailing gap). */
    val totalExtent: Float get() = if (pageCount == 0) 0f else starts[pageCount] - gap

    /** Largest page height (the cross-axis size of a horizontal flow). */
    val maxHeight: Float get() = heights.maxOrNull() ?: 0f

    /** Index of the last real page starting at or before [coord] on the flow axis, clamped to the pages; -1 when empty. */
    fun indexAtOrBefore(coord: Float): Int {
        if (pageCount == 0) return -1
        var lo = 0
        var hi = pageCount - 1
        var ans = 0
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (starts[mid] <= coord) { ans = mid; lo = mid + 1 } else hi = mid - 1
        }
        return ans
    }

    override fun pageRect(index: Int): PuRect {
        val s = start(index)
        return if (horizontal) PuRect(s, 0f, s + width, pageHeight(index))
        else PuRect(0f, s, width, s + pageHeight(index))
    }

    override fun contentRect(index: Int): PuRect {
        val p = pageRect(index)
        return if (continuous) {
            PuRect(p.left + BodyTextMetrics.MARGIN_LEFT, p.top + if (index == 0) BodyTextMetrics.MARGIN_TOP else 0f, p.right - BodyTextMetrics.MARGIN_RIGHT, p.bottom)
        } else {
            PuRect(p.left + BodyTextMetrics.MARGIN_LEFT, p.top + BodyTextMetrics.MARGIN_TOP, p.right - BodyTextMetrics.MARGIN_RIGHT, p.bottom - BodyTextMetrics.MARGIN_BOTTOM)
        }
    }

    override val nextPageContentHeight: Float
        get() = if (continuous) defaultHeight else defaultHeight - BodyTextMetrics.MARGIN_TOP - BodyTextMetrics.MARGIN_BOTTOM

    override fun equals(other: Any?): Boolean =
        other is StackedPageGeometry && other.defaultHeight == defaultHeight && other.gap == gap &&
            other.continuous == continuous && other.horizontal == horizontal && other.heights.contentEquals(heights)

    override fun hashCode(): Int {
        var h = heights.contentHashCode()
        h = h * 31 + defaultHeight.hashCode()
        h = h * 31 + gap.hashCode()
        h = h * 31 + continuous.hashCode()
        return h * 31 + horizontal.hashCode()
    }
}
