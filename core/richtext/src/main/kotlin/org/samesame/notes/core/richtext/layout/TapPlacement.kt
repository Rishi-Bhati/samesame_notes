package org.samesame.notes.core.richtext.layout

/**
 * Where a text-mode tap below the body text puts the caret.
 *
 * The reference does not create a text box there: it extends the flowing body with empty lines down to the tapped
 * line and puts the caret at the end of the body. This is pure so the page/line arithmetic is testable on the JVM.
 */
internal object TapPlacement {

    /** Safety guard for broken geometry; a real tap never needs this many lines. */
    private const val MAX_LINES = 100_000

    /** Smallest fraction of a page unit used for the exclusive bottom edge. */
    private const val EDGE = 0.01f

    /** Planned extension for one tap. */
    data class Plan(
        /** Page the caret must end on. */
        val targetPage: Int,
        /** Target y in document space, clamped to the page's content rect. */
        val targetY: Float,
        /** Empty lines to append at the end of the body. */
        val lineFeeds: Int,
    )

    /**
     * Plans the blank lines for a tap at ([tapX], [tapY]) when the tap hit-test would land on [targetPage].
     *
     * Returns null when no extension is needed: the tap is not inside a page, on an earlier page than the body end,
     * or on the body-end page at/above the end line. [bodyEndPage]/[bodyEndBottom] are the page and document-space
     * bottom of the caret at `body.length`; [lineHeight] is the height of the current last layout line (the height of
     * the blank lines that will be appended).
     */
    fun plan(
        geometry: TextPageGeometry,
        pagination: Pagination,
        lines: LineMetrics,
        layoutHeight: Float,
        targetPage: Int,
        tapX: Float,
        tapY: Float,
        bodyEndPage: Int,
        bodyEndBottom: Float,
        lineHeight: Float,
    ): Plan? {
        if (targetPage !in pagination.slices.indices) return null
        // Infinite notes are seamless segments, not pages: a tap after the text is not a page tap.
        if (geometry.continuous) return null
        if (targetPage < bodyEndPage) return null
        val page = geometry.pageRect(targetPage)
        if (!page.contains(tapX, tapY)) return null
        val content = geometry.contentRect(targetPage)
        if (content.height <= EDGE) return null
        val targetY = tapY.coerceIn(content.top, content.bottom - EDGE)
        if (targetPage == bodyEndPage && targetY <= bodyEndBottom) return null
        val feeds = lineFeeds(geometry, pagination, lines, layoutHeight, targetPage, targetY, lineHeight)
        return Plan(targetPage, targetY, feeds)
    }

    /**
     * Number of empty lines to append so the body's last line sits on [targetPage] and covers [targetY].
     *
     * Mirrors `Paginator.paged`: a line whose bottom would cross the page's content bottom starts the next page
     * (unless it already starts the page). Lines are simulated with [lineHeight]; the caller verifies the result on
     * the real layout and settles the last line.
     */
    internal fun lineFeeds(
        geometry: TextPageGeometry,
        pagination: Pagination,
        lines: LineMetrics,
        layoutHeight: Float,
        targetPage: Int,
        targetY: Float,
        lineHeight: Float,
    ): Int {
        if (lines.lineCount <= 0) return 0
        val end = pagination.sliceForLine(lines.lineCount - 1)
        var page = end.pageIndex
        var columnTop = end.columnTop
        var lineTop = lines.lineTop(lines.lineCount - 1)
        var lineBottom = maxOf(lines.lineBottom(lines.lineCount - 1), layoutHeight)
        val height = lineHeight.coerceAtLeast(EDGE)
        var feeds = 0
        while (feeds < MAX_LINES) {
            if (page == targetPage) {
                val top = geometry.contentRect(page).top - columnTop + lineTop
                if (targetY < top + (lineBottom - lineTop)) return feeds
            }
            // Append one empty line. It continues the column at the current bottom and is pushed to the next page
            // exactly like Paginator does.
            val nextTop = lineBottom
            val nextBottom = nextTop + height
            var nextPage = page
            var nextColumnTop = columnTop
            if (nextBottom - columnTop > contentHeight(geometry, page) && nextTop > columnTop) {
                nextPage = page + 1
                nextColumnTop = nextTop
            }
            // Keep the last line that fits on the tapped page instead of spilling onto the following page.
            if (nextPage > targetPage) return feeds
            page = nextPage
            columnTop = nextColumnTop
            lineTop = nextTop
            lineBottom = nextBottom
            feeds++
        }
        return feeds
    }

    private fun contentHeight(geometry: TextPageGeometry, page: Int): Float =
        if (page < geometry.pageCount) geometry.contentRect(page).height else geometry.nextPageContentHeight
}
