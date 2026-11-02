package org.samesame.notes.core.richtext

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.samesame.notes.core.richtext.layout.LineMetrics
import org.samesame.notes.core.richtext.layout.Paginator
import org.samesame.notes.core.richtext.layout.SimplePageGeometry
import org.samesame.notes.core.richtext.layout.TapPlacement

class TapPlacementTest {

    /** [n] lines of equal [height], starting at y = 0. */
    private fun lines(n: Int, height: Float = LINE) = object : LineMetrics {
        override val lineCount = n
        override fun lineTop(line: Int) = line * height
        override fun lineBottom(line: Int) = (line + 1) * height
    }

    // Page 300 tall, margins 32/32 -> content height 236; five 50 px lines fit per page (4 pushed).
    private val pages = SimplePageGeometry(pageCount = 4, pageHeight = 300f, marginBottom = 32f)

    private fun plan(
        lineMetrics: LineMetrics,
        pagination: org.samesame.notes.core.richtext.layout.Pagination,
        targetPage: Int,
        tapX: Float,
        tapY: Float,
        bodyEndPage: Int,
        bodyEndBottom: Float,
        lineHeight: Float = LINE,
    ) = TapPlacement.plan(
        pages, pagination, lineMetrics, lineMetrics.lineBottom(lineMetrics.lineCount - 1),
        targetPage, tapX, tapY, bodyEndPage, bodyEndBottom, lineHeight,
    )

    @Test fun tapInsideTextNeedsNoExtension() {
        val l = lines(3)
        val p = Paginator.paginate(l, l.lineBottom(2), pages)
        val result = plan(l, p, targetPage = 0, tapX = 100f, tapY = 100f, bodyEndPage = 0, bodyEndBottom = 182f)
        assertThat(result).isNull()
    }

    @Test fun tapBelowTheBodyOnTheSamePageAddsOneLine() {
        val l = lines(3)
        val p = Paginator.paginate(l, l.lineBottom(2), pages)
        val result = plan(l, p, targetPage = 0, tapX = 100f, tapY = 200f, bodyEndPage = 0, bodyEndBottom = 182f)
        assertThat(result).isNotNull()
        assertThat(result!!.lineFeeds).isEqualTo(1)
        assertThat(result.targetPage).isEqualTo(0)
        assertThat(result.targetY).isEqualTo(200f)
    }

    @Test fun tapOnALaterPageReachesThatPage() {
        val l = lines(2)
        val p = Paginator.paginate(l, l.lineBottom(1), pages)
        val targetPage = 2
        val targetY = pages.contentRect(targetPage).top + 100f
        val result = plan(l, p, targetPage, tapX = 100f, tapY = targetY, bodyEndPage = 0, bodyEndBottom = 132f)
        assertThat(result).isNotNull()
        assertThat(result!!.lineFeeds).isGreaterThan(0)

        val extended = lines(2 + result.lineFeeds)
        val extendedPagination = Paginator.paginate(extended, extended.lineBottom(extended.lineCount - 1), pages)
        val last = extended.lineCount - 1
        val slice = extendedPagination.sliceForLine(last)
        assertThat(slice.pageIndex).isEqualTo(targetPage)
        val docBottom = pages.contentRect(targetPage).top - slice.columnTop + extended.lineBottom(last)
        assertThat(docBottom).isGreaterThan(targetY)
    }

    @Test fun tapAtTheBottomKeepsTheLastLineThatFits() {
        val l = lines(2)
        val p = Paginator.paginate(l, l.lineBottom(1), pages)
        val tapY = pages.pageRect(0).bottom - 1f
        val result = plan(l, p, targetPage = 0, tapX = 100f, tapY = tapY, bodyEndPage = 0, bodyEndBottom = 132f)
        assertThat(result).isNotNull()
        // 4 lines of 50 fit on the 236 px content; the body has 2, so 2 more fit and the next would start page 2.
        assertThat(result!!.lineFeeds).isEqualTo(2)

        val extended = lines(2 + result.lineFeeds)
        val extendedPagination = Paginator.paginate(extended, extended.lineBottom(extended.lineCount - 1), pages)
        val last = extended.lineCount - 1
        assertThat(extendedPagination.sliceForLine(last).pageIndex).isEqualTo(0)
        assertThat(result.targetY).isAtMost(pages.contentRect(0).bottom)
    }

    @Test fun tapOnAnEarlierPageThanTheBodyEndNeedsNoExtension() {
        val l = lines(11)
        val p = Paginator.paginate(l, l.lineBottom(10), pages)
        val endPage = p.sliceForLine(10).pageIndex
        assertThat(endPage).isGreaterThan(0)
        val result = plan(l, p, targetPage = 0, tapX = 100f, tapY = 50f, bodyEndPage = endPage, bodyEndBottom = 582f)
        assertThat(result).isNull()
    }

    @Test fun tapOutsideAPageRectNeedsNoExtension() {
        val l = lines(3)
        val p = Paginator.paginate(l, l.lineBottom(2), pages)
        val gapY = pages.pageRect(0).bottom + 10f
        val result = plan(l, p, targetPage = 0, tapX = 100f, tapY = gapY, bodyEndPage = 0, bodyEndBottom = 182f)
        assertThat(result).isNull()
    }

    @Test fun infiniteNotesDoNotAddBlankLines() {
        val infinite = pages.copy(continuous = true)
        val l = lines(2)
        val p = Paginator.paginate(l, l.lineBottom(1), infinite)
        val targetY = infinite.contentRect(2).top + 100f
        val result = TapPlacement.plan(
            infinite, p, l, l.lineBottom(1), 2, 100f, targetY, 0, 132f, LINE,
        )
        assertThat(result).isNull()
    }

    private companion object {
        const val LINE = 50f
    }
}
