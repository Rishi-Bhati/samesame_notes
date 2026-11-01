package org.samesame.notes.core.richtext

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.samesame.notes.core.richtext.layout.LineMetrics
import org.samesame.notes.core.richtext.layout.PageFlow
import org.samesame.notes.core.richtext.layout.Paginator
import org.samesame.notes.core.richtext.layout.SimplePageGeometry

class PaginatorTest {

    /** [n] lines of equal [height]. */
    private fun lines(n: Int, height: Float) = object : LineMetrics {
        override val lineCount = n
        override fun lineTop(line: Int) = line * height
        override fun lineBottom(line: Int) = (line + 1) * height
    }

    private fun custom(vararg heights: Float) = object : LineMetrics {
        val tops = heights.runningFold(0f) { a, h -> a + h }
        override val lineCount = heights.size
        override fun lineTop(line: Int) = tops[line]
        override fun lineBottom(line: Int) = tops[line + 1]
    }

    // Page 300 tall, margins 32/32 → content height 236.
    private val pages = SimplePageGeometry(pageCount = 1, pageHeight = 300f, marginBottom = 32f)

    @Test fun shortTextFitsOnFirstPage() {
        val p = Paginator.paginate(lines(3, 50f), 150f, pages)
        assertThat(p.requiredPageCount).isEqualTo(1)
        assertThat(p.slices).hasSize(1)
        assertThat(p.slices[0].columnTop).isEqualTo(0f)
        assertThat(p.slices[0].firstLine).isEqualTo(0)
        assertThat(p.slices[0].lastLine).isEqualTo(2)
    }

    @Test fun lineCrossingContentBottomMovesToNextPage() {
        // 236 / 50 → 4 lines fit (200), line 4 (200..250) crosses → page 2 starts at column 200.
        val p = Paginator.paginate(lines(10, 50f), 500f, pages)
        assertThat(p.requiredPageCount).isEqualTo(3)
        assertThat(p.slices.map { it.firstLine to it.lastLine }).containsExactly(0 to 3, 4 to 7, 8 to 9).inOrder()
        assertThat(p.slices[1].columnTop).isEqualTo(200f)
        assertThat(p.slices[0].columnBottom).isEqualTo(200f)
        assertThat(p.sliceForLine(5).pageIndex).isEqualTo(1)
        assertThat(p.sliceForLine(9).pageIndex).isEqualTo(2)
    }

    @Test fun overTallLineIsKeptAloneOnItsPage() {
        val p = Paginator.paginate(custom(50f, 400f, 50f), 500f, pages)
        assertThat(p.slices.map { it.firstLine to it.lastLine }).containsExactly(0 to 0, 1 to 1, 2 to 2).inOrder()
    }

    @Test fun fortyReferenceLinesMakeTwoA4Pages() {
        // #107: 20 lines of ~68.7 px per 1527 px page, 40 lines -> 2 pages (not 3 or 4).
        val a4 = SimplePageGeometry(pageCount = 1)
        for (pitch in listOf(66.8f, 68.7f, 69.9f)) {
            val p = Paginator.paginate(lines(40, pitch), 40 * pitch, a4)
            assertThat(p.requiredPageCount).isEqualTo(2)
            assertThat(p.slices[0].lastLine).isEqualTo(19)
        }
    }

    @Test fun existingPagesBeyondTextGetEmptySlices() {
        val p = Paginator.paginate(lines(2, 50f), 100f, pages.copy(pageCount = 3))
        assertThat(p.requiredPageCount).isEqualTo(1)
        assertThat(p.slices).hasSize(3)
        assertThat(p.slices[2].isEmpty).isTrue()
        assertThat(p.sliceForLine(1).pageIndex).isEqualTo(0)
    }

    @Test fun horizontalFlowUsesSameColumnMapping() {
        val h = pages.copy(flow = PageFlow.Horizontal)
        val p = Paginator.paginate(lines(10, 50f), 500f, h)
        assertThat(p.slices[1].columnTop).isEqualTo(200f)
        assertThat(h.contentRect(1).left).isEqualTo(1080f + SimplePageGeometry.DEFAULT_PAGE_GAP + BodyTextMetrics.MARGIN_LEFT)
    }

    @Test fun continuousModeNeverPushesLinesAndSharesStraddlingLine() {
        // Segments 300 tall; first segment content = 300 - 32 = 268, then 300 each.
        val inf = SimplePageGeometry(pageCount = 1, pageHeight = 300f, continuous = true)
        val p = Paginator.paginate(lines(12, 50f), 600f, inf)
        assertThat(p.requiredPageCount).isEqualTo(3) // 268 + 300 = 568 < 600
        assertThat(p.slices[0].columnTop).isEqualTo(0f)
        assertThat(p.slices[1].columnTop).isEqualTo(268f)
        // Line 5 (250..300) straddles the seam at 268: on both pages, owned by page 0.
        assertThat(p.slices[0].lastLine).isEqualTo(5)
        assertThat(p.slices[1].firstLine).isEqualTo(5)
        assertThat(p.sliceForLine(5).pageIndex).isEqualTo(0)
        assertThat(p.sliceForLine(6).pageIndex).isEqualTo(1)
        assertThat(inf.contentRect(1).top).isEqualTo(inf.pageRect(1).top)
        assertThat(inf.pageRect(1).top).isEqualTo(300f) // no gap in infinite mode
    }
}
