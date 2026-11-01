package org.samesame.notes.feature.notes

import org.samesame.notes.core.designsystem.component.ScrollBarGeometry

/**
 * Scroll bar proportions for a lazy grid. The content extent is only fully known once the last item has been
 * reached, so the per-item extent is measured once and kept stable while scrolling: the thumb must not change
 * length just because differently sized items come into view.
 */
internal data class LazyScrollMetrics(
    /** 0..1 position of the viewport in the estimated content. */
    val scrollFraction: Float = 0f,
    /** 0..1 share of the content visible; 1 means the bar is not needed. */
    val thumbFraction: Float = 1f,
    /** Estimated extent of one item index, used to turn a scrub fraction into `scrollToItem`. */
    val itemExtent: Float = 0f,
    val contentExtent: Float = 0f,
)

/** Estimated per-item extent (in item indexes) measured from the items currently laid out. */
internal fun measuredItemExtent(firstIndex: Int, firstOffset: Int, lastIndex: Int, lastEndOffset: Int): Float {
    val span = (lastIndex - firstIndex + 1).coerceAtLeast(1)
    return ((lastEndOffset - firstOffset).toFloat() / span).coerceAtLeast(1f)
}

/** The stable per-item estimate: the measured value is adopted only for a new item count or before any estimate. */
internal data class ScrollEstimate(val itemCount: Int = -1, val itemExtent: Float = 0f)

internal fun stableScrollEstimate(previous: ScrollEstimate, itemCount: Int, measuredItemExtent: Float): ScrollEstimate =
    when {
        measuredItemExtent <= 0f -> previous
        previous.itemExtent <= 0f || previous.itemCount != itemCount ->
            ScrollEstimate(itemCount, measuredItemExtent)
        else -> previous
    }

/** Keeps the estimate across recompositions while the item count is unchanged. */
internal class ScrollEstimateHolder {
    private var estimate = ScrollEstimate()

    fun estimateFor(itemCount: Int, measuredItemExtent: Float): Float {
        estimate = stableScrollEstimate(estimate, itemCount, measuredItemExtent)
        return estimate.itemExtent
    }
}

/**
 * @param estimatedItemExtent the stable per-item extent from [stableScrollEstimate]; when positive it is used for
 * both the content extent and the scroll offset so the thumb length stays put while scrolling.
 */
internal fun lazyScrollMetrics(
    itemCount: Int,
    firstIndex: Int,
    firstOffset: Int,
    lastIndex: Int,
    lastEndOffset: Int,
    viewportExtent: Float,
    estimatedItemExtent: Float = 0f,
): LazyScrollMetrics {
    if (itemCount <= 0 || viewportExtent <= 0f) return LazyScrollMetrics()
    val first = firstIndex.coerceIn(0, itemCount - 1)
    val last = lastIndex.coerceIn(first, itemCount - 1)
    val span = (last - first + 1).coerceAtLeast(1)
    val visibleExtent = (lastEndOffset - firstOffset).toFloat().coerceAtLeast(1f)
    val measuredPerItem = visibleExtent / span
    val perItem = if (estimatedItemExtent > 0f) estimatedItemExtent else measuredPerItem
    val content = perItem * itemCount
    val travel = (content - viewportExtent).coerceAtLeast(0f)
    // The measured average can be a little off; the end of the list must still put the thumb at the bottom.
    val offset = if (last >= itemCount - 1) travel else (perItem * first + firstOffset).coerceIn(0f, travel)
    val thumb = ScrollBarGeometry.thumbFraction(content, viewportExtent)
    val scroll = if (travel <= 0f) 0f else (offset / travel).coerceIn(0f, 1f)
    return LazyScrollMetrics(scrollFraction = scroll, thumbFraction = thumb, itemExtent = perItem, contentExtent = content)
}

/** Index and within-item offset to scroll to for a bar [fraction] (0..1). */
internal fun lazyScrollTarget(metrics: LazyScrollMetrics, fraction: Float): Pair<Int, Int> {
    if (metrics.itemExtent <= 0f) return 0 to 0
    // The viewport length is implied by the thumb share: content * (1 - thumb) is the travel.
    val max = metrics.contentExtent * (1f - metrics.thumbFraction.coerceIn(0f, 1f))
    val target = (fraction.coerceIn(0f, 1f) * max).coerceAtLeast(0f)
    val index = (target / metrics.itemExtent).toInt()
    val within = target - index * metrics.itemExtent
    return index to within.toInt()
}
