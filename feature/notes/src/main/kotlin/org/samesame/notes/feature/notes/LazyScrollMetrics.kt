package org.samesame.notes.feature.notes

import kotlin.math.roundToInt

/**
 * Scroll bar proportions for a lazy grid, measured in PIXELS of the content. Every row counts with its own height
 * (a short header row is worth its few dp, a note row its card height), so the thumb moves in proportion to the
 * distance scrolled whatever row is first. Heights of rows that are not laid out come from [rowAdvances] (rows seen
 * earlier) or from the frozen estimates. [lazyScrollTarget] is its exact inverse, so dragging the thumb and the
 * positions the list reports afterwards agree.
 */
internal data class LazyScrollMetrics(
    /** 0..1 position of the viewport in the estimated content. */
    val scrollFraction: Float = 0f,
    /** 0..1 share of the content that is visible; 1 means the bar is not needed. */
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
    /** Advances (px, row top to next row top) of every row measured so far, by row index. */
    val rowAdvances: Map<Int, Float> = emptyMap(),
    /** Stable advance used for note rows that were never measured. */
    val rowEstimate: Float = 0f,
    /** Advance used for header rows that were never measured. */
    val headerEstimate: Float = 0f,
    /** Viewport extent in px. */
    val viewport: Float = 0f,
    /** Estimated height of all the content in px. */
    val contentExtent: Float = 0f,
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

/**
 * Keeps the row estimate across recompositions while the layout (item count, columns, headers) is unchanged, and
 * the advance of every row seen so far (cleared when the layout changes).
 */
internal class ScrollEstimateHolder {
    private var estimate = ScrollEstimate()
    private val known = HashMap<Int, Float>()

    fun estimateFor(itemCount: Int, columns: Int, headerItems: Int, measuredRowAdvance: Float): Float {
        val next = stableScrollEstimate(estimate, itemCount, columns, headerItems, measuredRowAdvance)
        if (next != estimate) known.clear()
        estimate = next
        return estimate.rowAdvance
    }

    /**
     * Remembers the advances of the laid-out [rows] and returns everything known (the live map, not a copy: the caller
     * runs every scroll frame). The last laid-out row only has its own height (the next row's top is unknown, so no
     * gap): it is recorded only when nothing better is known, and replaced once it is no longer the last.
     */
    fun advancesWith(rows: List<GridRow>): Map<Int, Float> {
        for (i in 0 until rows.size - 1) known[rows[i].index] = rows[i].advance
        rows.lastOrNull()?.let { if (it.index !in known) known[it.index] = it.advance }
        return known
    }
}

/**
 * @param rows the rows laid out right now (topmost first)
 * @param estimatedRowAdvance the stable advance from [stableScrollEstimate]; when positive it is used for the
 * note rows never seen, so the thumb does not wobble while scrolling.
 * @param knownAdvances advances of rows seen earlier (the laid-out rows take precedence).
 */
internal fun lazyScrollMetrics(
    itemCount: Int,
    columns: Int,
    headerItems: Int,
    rows: List<GridRow>,
    viewportExtent: Float,
    estimatedRowAdvance: Float = 0f,
    knownAdvances: Map<Int, Float> = emptyMap(),
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
    val advances = withLaidOutRows(knownAdvances, rows)
    var headerSum = 0f
    var headerSeen = 0
    for (r in 0 until headers) advances[r]?.let { headerSum += it; headerSeen++ }
    val headerEstimate = if (headerSeen == 0) 0f else headerSum / headerSeen
    // One pass over the rows (this runs every scroll frame): start of the first visible row and the whole extent.
    var content = 0f
    var above = 0f
    for (r in 0 until total) {
        if (r == first.index) above = content
        content += advanceOf(r, headers, advances, estimate, headerEstimate)
    }
    val position = above + offset
    val maxScroll = (content - viewportExtent).coerceAtLeast(0f)
    val scroll = if (maxScroll <= 0f) 0f else (position / maxScroll).coerceIn(0f, 1f)
    val thumb = (viewportExtent / content).coerceIn(0f, 1f)
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
        rowAdvances = advances,
        rowEstimate = estimate,
        headerEstimate = headerEstimate,
        viewport = viewportExtent,
        contentExtent = content,
    )
}

private fun advanceOf(row: Int, headers: Int, advances: Map<Int, Float>, estimate: Float, headerEstimate: Float): Float =
    advances[row] ?: if (row < headers) headerEstimate else estimate

private fun startOfRow(row: Int, headers: Int, advances: Map<Int, Float>, estimate: Float, headerEstimate: Float): Float {
    var sum = 0f
    for (r in 0 until row) sum += advanceOf(r, headers, advances, estimate, headerEstimate)
    return sum
}

/**
 * [known] with the laid-out [rows] applied (rows take precedence; the last laid-out row only has its own height, so a
 * better value measured earlier is kept). Returns [known] itself, no copy, when it already says the same (the holder's
 * map does: this runs every scroll frame), else a copy.
 */
internal fun withLaidOutRows(known: Map<Int, Float>, rows: List<GridRow>): Map<Int, Float> {
    var copy: HashMap<Int, Float>? = null
    rows.forEachIndexed { i, row ->
        val current = copy ?: known
        val old = current[row.index]
        val wants = i < rows.size - 1 || old == null
        if (wants && old != row.advance) {
            val target = copy ?: HashMap(known).also { copy = it }
            target[row.index] = row.advance
        }
    }
    return copy ?: known
}

/** Item index and within-item offset to scroll to for a bar [fraction] (0..1): the inverse of [lazyScrollMetrics]. */
internal fun lazyScrollTarget(metrics: LazyScrollMetrics, fraction: Float): Pair<Int, Int> {
    if (metrics.rows <= 0 || metrics.itemCount <= 0 || metrics.columns <= 0) return 0 to 0
    val maxScroll = (metrics.contentExtent - metrics.viewport).coerceAtLeast(0f)
    if (maxScroll <= 0f) return 0 to 0
    val position = fraction.coerceIn(0f, 1f) * maxScroll
    var row = 0
    var start = 0f
    while (row < metrics.rows - 1) {
        val advance = advanceOf(row, metrics.headerItems, metrics.rowAdvances, metrics.rowEstimate, metrics.headerEstimate)
        if (position < start + advance) break
        start += advance
        row++
    }
    val advance = advanceOf(row, metrics.headerItems, metrics.rowAdvances, metrics.rowEstimate, metrics.headerEstimate)
    val offset = (position - start).coerceIn(0f, advance).roundToInt()
    val index = if (row < metrics.headerItems) row
    else metrics.headerItems + (row - metrics.headerItems) * metrics.columns
    return index.coerceIn(0, metrics.itemCount - 1) to offset
}
