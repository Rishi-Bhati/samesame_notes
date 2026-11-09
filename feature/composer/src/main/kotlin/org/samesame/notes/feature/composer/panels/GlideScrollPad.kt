package org.samesame.notes.feature.composer.panels

import kotlin.math.floor

/**
 * Press-and-glide on the font-size button: once the finger glides into the popup, the popup's size list follows it.
 *
 * The model is deterministic: the highlighted row is always the row under the finger in the list as it is currently
 * scrolled ([listIndexAt]), so the same finger position over the same scroll value always gives the same size, no
 * matter how fast the finger got there. There is no velocity term and no smoothing. The list itself moves only while
 * the finger is in the top or bottom edge zone of the list ([edgeBias]): the scroll step is proportional to the depth
 * in the zone and capped at [MAX_EDGE_SCROLL_DP_PER_S], so holding at an edge travels steadily and every size is
 * reachable in one glide ([edgeScrollStep]). Between the zones the list stays put and the highlight steps row by row
 * as the finger moves over the visible rows.
 *
 * All functions are pure (px in one coordinate space), so the mapping is JVM-testable.
 */
internal object GlideScrollPad {
    /** Fraction of the visible list height that is the top/bottom auto-scroll edge zone. */
    const val EDGE_ZONE = 0.15f
    /** Fastest auto-scroll, at the very edge of the list. */
    const val MAX_EDGE_SCROLL_DP_PER_S = 900f

    /**
     * Index within the scrolling list of the row under [y] (rows of [rowHeight] stacked from the content top, the
     * viewport spanning [listTop]..[listBottom] and scrolled by [scroll]), or -1 outside the viewport / past [count].
     */
    fun listIndexAt(y: Float, listTop: Float, listBottom: Float, scroll: Float, rowHeight: Float, count: Int): Int {
        if (rowHeight <= 0f || y < listTop || y >= listBottom) return -1
        val i = floor((y - listTop + scroll) / rowHeight).toInt()
        return if (i in 0 until count) i else -1
    }

    /**
     * Where [y] sits in the list's auto-scroll edge zones: -1 at the top edge, +1 at the bottom edge, 0 between them
     * and outside the viewport; linear in between. [zoneFraction] is the share of the list height that is a zone.
     */
    fun edgeBias(y: Float, listTop: Float, listBottom: Float, zoneFraction: Float = EDGE_ZONE): Float {
        val height = listBottom - listTop
        if (height <= 0f || y < listTop || y > listBottom) return 0f
        val zone = height * zoneFraction.coerceIn(0f, 0.5f)
        if (zone <= 0f) return 0f
        val fromTop = y - listTop
        val fromBottom = listBottom - y
        return when {
            fromTop < zone -> -((zone - fromTop) / zone)
            fromBottom < zone -> (zone - fromBottom) / zone
            else -> 0f
        }
    }

    /**
     * Scroll delta (px, positive = toward the list end) for one frame of [dtMs] with the finger at [y], proportional
     * to the edge depth and capped at [maxPxPerS]; 0 between the edge zones.
     */
    fun edgeScrollStep(
        y: Float,
        listTop: Float,
        listBottom: Float,
        dtMs: Float,
        maxPxPerS: Float,
        zoneFraction: Float = EDGE_ZONE,
    ): Float {
        if (maxPxPerS <= 0f || dtMs <= 0f) return 0f
        return edgeBias(y, listTop, listBottom, zoneFraction) * maxPxPerS * (dtMs / 1000f)
    }
}
