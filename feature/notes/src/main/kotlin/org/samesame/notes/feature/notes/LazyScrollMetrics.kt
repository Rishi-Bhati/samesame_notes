package org.samesame.notes.feature.notes

import kotlin.math.roundToInt

/**
 * Scroll bar proportions for a lazy grid, measured in ROWS rather than items. The thumb advances one unit per
 * row whatever the heights of the cards, so a taller or shorter row scrolling off the top cannot make the thumb
 * jump, and the mapping is continuous at every row boundary. [lazyScrollTarget] is its exact inverse, so dragging
 * the thumb and the positions the list reports afterwards agree.
 */
internal data class LazyScrollMetrics(
    /** 0..1 position of the viewport in the estimated content. */
    val scrollFraction: Float = 0f,
    /** 0..1 share of the rows visible; 1 means the bar is not needed. */
    val thumbFraction: Float = 1f,
    val itemCount: Int = 0,
    val columns: Int = 1,
    /** Full-span items before the notes (folder row, sort row, trash hint): each one is a row of its own. */
    val headerItems: Int = 0,
    /** Total rows. */
    val rows: Int = 0,
    /** Row index of the first laid-out row. */
    val firstRow: Int = 0,
    /** Px the first row is scrolled past the viewport start, in `0..rowAdvance`. */
    val rowOffset: Float = 0f,
    /** Px from the first row's top to the next row's top (includes the row gap). */
    val rowAdvance: Float = 0f,
    /** Advances of the rows laid out right now, by row index: the drag uses the real heights where they are known. */
    val rowAdvances: Map<Int, Float> = emptyMap(),
    /** Stable advance used for rows that are not laid out yet. */
    val rowEstimate: Float = 0f,
    /** Rows that fit in the viewport (fractional), from the stable estimate. */
    val visibleRows: Float = 1f,
)

/** One laid-out grid item: its main-axis top (viewport-relative, negative once scrolled) and its height. */
internal data class GridItemPosition(val index: Int, val top: Int, val height: Int)

/** One laid-out grid row: its row index, viewport-relative top and advance to the next row. */
internal data class GridRow(val index: Int, val top: Int, val advance: Float)

/** Row index of a grid item: a full-span header item is a row of its own, notes sit [columns] per row. */
internal fun rowOfItem(index: Int, headerItems: Int, columns: Int): Int {
    val cols = columns.coerceAtLeast(1)
    return if (index < headerItems) index else headerItems + (index - headerItems) / cols
}

/** Total rows of a grid with [headerItems] full-span items and [columns] notes per row. */
internal fun totalRows(itemCount: Int, columns: Int, headerItems: Int): Int {
    if (itemCount <= 0) return 0
    val cols = columns.coerceAtLeast(1)
    val headers = headerItems.coerceIn(0, itemCount)
    return headers + (itemCount - headers + cols - 1) / cols
}

/**
 * Groups laid-out grid items into rows. The advance of a row is the distance from its top to the next row's top
 * (so the row gap is part of it); the last laid-out row uses its own item height, since its next row is unknown.
 */
internal fun gridRows(items: List<GridItemPosition>, columns: Int, headerItems: Int): List<GridRow> {
    if (items.isEmpty()) return emptyList()
    val byRow = items.groupBy { rowOfItem(it.index, headerItems, columns) }.toSortedMap()
    val indices = byRow.keys.toList()
    return indices.mapIndexed { i, row ->
        val group = byRow.getValue(row)
        val top = group.minOf { it.top }
        val own = group.maxOf { it.height }
        val advance = if (i + 1 < indices.size) byRow.getValue(indices[i + 1]).minOf { it.top } - top else own
        GridRow(row, top, advance.coerceAtLeast(1).toFloat())
    }
}

/** Measured advance of one row, averaged over the laid-out note rows (headers are shorter; the last row has no gap). */
internal fun measuredRowAdvance(rows: List<GridRow>, headerItems: Int = 0): Float {
    val notes = rows.filter { it.index >= headerItems }
    val use = if (notes.isEmpty()) rows else notes
    return if (use.isEmpty()) 0f else (use.sumOf { it.advance.toDouble() } / use.size).toFloat()
}

/** The stable row estimate: the measured value is adopted only for a new item count / column count / header count. */
internal data class ScrollEstimate(
    val itemCount: Int = -1,
    val columns: Int = 0,
    val headerItems: Int = 0,
    val rowAdvance: Float = 0f,
)

internal fun stableScrollEstimate(
    previous: ScrollEstimate,
    itemCount: Int,
    columns: Int,
    headerItems: Int,
    measuredRowAdvance: Float,
): ScrollEstimate = when {
    measuredRowAdvance <= 0f -> previous
    previous.rowAdvance <= 0f ||
        previous.itemCount != itemCount ||
        previous.columns != columns ||
        previous.headerItems != headerItems ->
        ScrollEstimate(itemCount, columns, headerItems, measuredRowAdvance)
    else -> previous
}

/** Keeps the row estimate across recompositions while the layout (item count, columns, headers) is unchanged. */
internal class ScrollEstimateHolder {
    private var estimate = ScrollEstimate()

    fun estimateFor(itemCount: Int, columns: Int, headerItems: Int, measuredRowAdvance: Float): Float {
        estimate = stableScrollEstimate(estimate, itemCount, columns, headerItems, measuredRowAdvance)
        return estimate.rowAdvance
    }
}

/**
 * @param rows the rows laid out right now (topmost first)
 * @param estimatedRowAdvance the stable advance from [stableScrollEstimate]; when positive it is used for the
 * unseen rows and for the viewport size so the thumb does not wobble while scrolling.
 */
internal fun lazyScrollMetrics(
    itemCount: Int,
    columns: Int,
    headerItems: Int,
    rows: List<GridRow>,
    viewportExtent: Float,
    estimatedRowAdvance: Float = 0f,
): LazyScrollMetrics {
    if (itemCount <= 0 || viewportExtent <= 0f || rows.isEmpty()) return LazyScrollMetrics()
    val cols = columns.coerceAtLeast(1)
    val headers = headerItems.coerceIn(0, itemCount)
    val total = totalRows(itemCount, cols, headers)
    if (total <= 0) return LazyScrollMetrics()
    val first = rows.first()
    val advance = first.advance.coerceAtLeast(1f)
    // LazyGrid item offsets are relative to the viewport, so a scrolled row has a negative top: -top is the scroll.
    val offset = (-first.top).toFloat().coerceIn(0f, advance)
    val estimate = (if (estimatedRowAdvance > 0f) estimatedRowAdvance else measuredRowAdvance(rows, headers)).coerceAtLeast(1f)
    val visible = (viewportExtent / estimate).coerceAtLeast(1f)
    val maxRow = (total - visible).coerceAtLeast(0f)
    val position = first.index + offset / advance
    val scroll = if (maxRow <= 0f) 0f else (position / maxRow).coerceIn(0f, 1f)
    val thumb = (visible / total).coerceIn(0f, 1f)
    return LazyScrollMetrics(
        scrollFraction = scroll,
        thumbFraction = thumb,
        itemCount = itemCount,
        columns = cols,
        headerItems = headers,
        rows = total,
        firstRow = first.index,
        rowOffset = offset,
        rowAdvance = advance,
        rowAdvances = rows.associate { it.index to it.advance },
        rowEstimate = estimate,
        visibleRows = visible,
    )
}

/** Item index and within-item offset to scroll to for a bar [fraction] (0..1): the inverse of [lazyScrollMetrics]. */
internal fun lazyScrollTarget(metrics: LazyScrollMetrics, fraction: Float): Pair<Int, Int> {
    if (metrics.rows <= 0 || metrics.itemCount <= 0 || metrics.columns <= 0) return 0 to 0
    val maxRow = (metrics.rows - metrics.visibleRows).coerceAtLeast(0f)
    if (maxRow <= 0f) return 0 to 0
    val position = fraction.coerceIn(0f, 1f) * maxRow
    val row = position.toInt().coerceIn(0, metrics.rows - 1)
    val within = (position - row).coerceIn(0f, 1f)
    val advance = metrics.rowAdvances[row] ?: metrics.rowEstimate
    val offset = (within * advance).roundToInt().coerceAtLeast(0)
    val index = if (row < metrics.headerItems) row
    else metrics.headerItems + (row - metrics.headerItems) * metrics.columns
    return index.coerceIn(0, metrics.itemCount - 1) to offset
}
