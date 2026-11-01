package org.samesame.notes.core.richtext.layout

/**
 * The part of the text column shown on one page: layout y [columnTop] maps to the top of the page's
 * content rect, and only layout y in `[columnTop, columnBottom)` is drawn there. Lines
 * `firstLine..lastLine` (empty when `lastLine < firstLine`) belong to the page.
 */
data class ColumnSlice(
    val pageIndex: Int,
    val columnTop: Float,
    val columnBottom: Float,
    val firstLine: Int,
    val lastLine: Int,
) {
    val isEmpty: Boolean get() = lastLine < firstLine
}

/** Line geometry of a laid-out column (abstracted so pagination is testable on the JVM). */
interface LineMetrics {
    val lineCount: Int
    fun lineTop(line: Int): Float
    fun lineBottom(line: Int): Float
}

data class Pagination(
    /** One slice per page, `max(pageCount, requiredPageCount)` of them. */
    val slices: List<ColumnSlice>,
    /** Pages the text needs. More than the geometry's page count means pages must be appended. */
    val requiredPageCount: Int,
) {
    /** Slice holding [line] (lines past the end map to the last non-empty slice). */
    fun sliceForLine(line: Int): ColumnSlice {
        // firstLine is non-decreasing (empty trailing slices have firstLine = lineCount).
        var lo = 0
        var hi = slices.lastIndex
        var ans = 0
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (slices[mid].firstLine <= line) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        // A line straddling a seam (infinite mode) belongs to the page where it starts.
        if (ans > 0 && slices[ans - 1].lastLine >= line) ans--
        return slices[ans]
    }
}

/**
 * Maps the body's single text column onto pages (doc §2.2).
 *
 * NOTE(WP-F): the doc puts the page push in a `PaginationSpan : LineHeightSpan`. That does not work
 * with `DynamicLayout`: it only re-measures the edited block and shifts the lines below by a delta,
 * so a line that moves across a page boundary keeps its old push. Instead the column is laid out
 * without breaks and this pure pass assigns whole lines to pages (a line that would cross a page's
 * content bottom starts the next page). Every line on a page then shares one offset, so drawing a
 * page is still "clip + translate by `contentTop − slice.columnTop`", exactly as in the doc.
 */
object Paginator {

    fun paginate(lines: LineMetrics, layoutHeight: Float, geometry: TextPageGeometry): Pagination =
        if (geometry.continuous) continuous(lines, layoutHeight, geometry) else paged(lines, layoutHeight, geometry)

    private fun contentHeight(geometry: TextPageGeometry, page: Int): Float =
        if (page < geometry.pageCount) geometry.contentRect(page).height else geometry.nextPageContentHeight

    private fun paged(lines: LineMetrics, layoutHeight: Float, geometry: TextPageGeometry): Pagination {
        val slices = ArrayList<ColumnSlice>()
        var page = 0
        var colTop = 0f
        var first = 0
        for (i in 0 until lines.lineCount) {
            val top = lines.lineTop(i)
            val bottom = lines.lineBottom(i)
            // A line that doesn't fit moves to the next page, unless it already starts the page
            // (an over-tall line is kept and clipped rather than looping forever).
            if (bottom - colTop > contentHeight(geometry, page) && top > colTop) {
                slices += ColumnSlice(page, colTop, top, first, i - 1)
                page++
                colTop = top
                first = i
            }
        }
        val end = maxOf(layoutHeight, colTop)
        slices += ColumnSlice(page, colTop, end, first, lines.lineCount - 1)
        val required = page + 1
        for (p in required until maxOf(geometry.pageCount, required)) {
            slices += ColumnSlice(p, end, end, lines.lineCount, lines.lineCount - 1)
        }
        return Pagination(slices, required)
    }

    private fun continuous(lines: LineMetrics, layoutHeight: Float, geometry: TextPageGeometry): Pagination {
        // Pages needed to hold the whole column.
        var required = 1
        var acc = contentHeight(geometry, 0)
        while (acc < layoutHeight) {
            acc += contentHeight(geometry, required)
            required++
        }
        val count = maxOf(geometry.pageCount, required)
        val slices = ArrayList<ColumnSlice>(count)
        var colTop = 0f
        var line = 0
        for (p in 0 until count) {
            val colBottom = colTop + contentHeight(geometry, p)
            // Lines overlapping [colTop, colBottom): a line straddling a seam belongs to both pages.
            while (line < lines.lineCount && lines.lineBottom(line) <= colTop) line++
            var last = line - 1
            var j = line
            while (j < lines.lineCount && lines.lineTop(j) < colBottom) { last = j; j++ }
            slices += ColumnSlice(p, colTop, colBottom, line, last)
            colTop = colBottom
        }
        return Pagination(slices, required)
    }
}
