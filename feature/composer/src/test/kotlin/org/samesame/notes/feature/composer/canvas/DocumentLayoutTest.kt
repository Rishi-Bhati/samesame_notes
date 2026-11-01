package org.samesame.notes.feature.composer.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntSize
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.samesame.notes.core.model.PageMode
import org.samesame.notes.core.model.PageRatio
import org.samesame.notes.core.model.document.PageLayout
import org.samesame.notes.core.model.document.ScrollDirection

class DocumentLayoutTest {
    private val a4 = 1080f * PageRatio.A4Portrait.heightOverWidth

    @Test fun verticalPagesAreStackedWithGap() {
        val l = DocumentLayout(3, PageLayout(PageMode.Pages))
        assertThat(l.pageRects[0].top).isEqualTo(0f)
        assertThat(l.pageRects[1].top).isWithin(0.01f).of(a4 + DocumentLayout.PAGE_GAP_PU)
        assertThat(l.contentSize.height).isWithin(0.01f).of(3 * a4 + 2 * DocumentLayout.PAGE_GAP_PU)
        assertThat(l.contentSize.width).isEqualTo(1080f)
    }

    @Test fun infiniteSegmentsAreSeamless() {
        val l = DocumentLayout(3, PageLayout(PageMode.Infinite))
        assertThat(l.pageRects[1].top).isWithin(0.01f).of(a4)
        assertThat(l.pageRects[1].top).isWithin(0.001f).of(l.pageRects[0].bottom)
        assertThat(l.pageAt(Offset(10f, l.pageRects[1].top))).isEqualTo(1)
    }

    @Test fun longRatio() {
        val l = DocumentLayout(1, PageLayout(PageMode.Pages, PageRatio.Long))
        assertThat(l.pageSize).isEqualTo(Size(1080f, 2160f))
    }

    @Test fun horizontalPagesGoSideways() {
        val l = DocumentLayout(2, PageLayout(PageMode.Pages, scroll = ScrollDirection.Horizontal))
        assertThat(l.pageRects[1].left).isWithin(0.01f).of(1080f + DocumentLayout.PAGE_GAP_PU)
        assertThat(l.pageRects[1].top).isEqualTo(0f)
        assertThat(l.contentSize.width).isWithin(0.01f).of(2 * 1080f + DocumentLayout.PAGE_GAP_PU)
    }

    @Test fun infiniteIgnoresHorizontalScroll() {
        val l = DocumentLayout(2, PageLayout(PageMode.Infinite, scroll = ScrollDirection.Horizontal))
        assertThat(l.horizontal).isFalse()
    }

    @Test fun pageAtFindsPagesAndGaps() {
        val l = DocumentLayout(3, PageLayout(PageMode.Pages))
        assertThat(l.pageAt(Offset(500f, 10f))).isEqualTo(0)
        assertThat(l.pageAt(Offset(500f, a4 + 5f))).isNull() // inside the gap
        assertThat(l.pageAt(Offset(500f, a4 + DocumentLayout.PAGE_GAP_PU + 1f))).isEqualTo(1)
        assertThat(l.pageAt(Offset(1100f, 10f))).isNull()
        assertThat(l.pageAt(Offset(500f, -1f))).isNull()
        assertThat(l.nearestPage(Offset(500f, a4 + 5f))).isEqualTo(0)
        assertThat(l.nearestPage(Offset(500f, a4 + 20f))).isEqualTo(1)
    }

    @Test fun visiblePages() {
        val l = DocumentLayout(5, PageLayout(PageMode.Pages))
        assertThat(l.visiblePages(Rect(0f, 0f, 1080f, 100f))).isEqualTo(0..0)
        val y = l.pageRects[2].top
        assertThat(l.visiblePages(Rect(0f, y - 30f, 1080f, y + 10f))).isEqualTo(1..2)
        assertThat(l.visiblePages(Rect(0f, -500f, 1080f, -10f)).isEmpty()).isTrue()
        assertThat(l.visiblePages(Rect(0f, 0f, 1080f, 1e7f))).isEqualTo(0..4)
        // exactly in the gap
        val gap = l.pageRects[0].bottom + 1f
        assertThat(l.visiblePages(Rect(0f, gap, 1080f, gap + 2f)).isEmpty()).isTrue()
    }

    @Test fun docToPageRoundTrip() {
        val l = DocumentLayout(3, PageLayout(PageMode.Pages))
        val doc = Offset(100f, l.pageRects[2].top + 40f)
        assertThat(l.docToPage(2, doc)).isEqualTo(Offset(100f, 40f))
        assertThat(l.pageToDoc(2, Offset(100f, 40f))).isEqualTo(doc)
    }

    // --- Viewport, on the reference phone: canvas 1080 x 1911 (#26), 1080 x 2167 (#61) ---

    private fun viewport(h: Int, layout: DocumentLayout): Viewport = Viewport().also {
        it.updateSize(IntSize(1080, h)); it.updateContent(layout.contentSize)
    }

    @Test fun singleA4PageIsCentredLikeReference26And61() {
        val l = DocumentLayout(1, PageLayout(PageMode.Pages))
        // #26: viewport y 256..2167, page 448..1975 (first row 448, 1527 rows)
        val v26 = viewport(2167 - 256, l)
        val top26 = 256 + v26.docToView(Offset.Zero).y
        assertThat(top26).isWithin(1.0f).of(448f)
        assertThat(256 + v26.docToView(Offset(0f, l.pageSize.height)).y).isWithin(1.5f).of(1975f)
        assertThat(v26.docToView(Offset.Zero).x).isEqualTo(0f)
        assertThat(v26.scale).isEqualTo(1f)
        // #61: viewport y 109..2277, page 429..1956
        val v61 = viewport(2277 - 109, l)
        assertThat(109 + v61.docToView(Offset.Zero).y).isWithin(1.0f).of(429f)
        assertThat(109 + v61.docToView(Offset(0f, l.pageSize.height)).y).isWithin(1.5f).of(1956f)
    }

    @Test fun zoomIsClampedAndKeepsFocus() {
        val l = DocumentLayout(3, PageLayout(PageMode.Pages))
        val v = viewport(1911, l)
        v.zoomBy(0.5f, Offset(540f, 900f))
        assertThat(v.zoom).isEqualTo(1f)
        val focus = Offset(300f, 700f)
        val docBefore = v.viewToDoc(focus)
        v.zoomBy(2f, focus)
        assertThat(v.zoom).isEqualTo(2f)
        assertThat(v.viewToDoc(focus).x).isWithin(0.01f).of(docBefore.x)
        assertThat(v.viewToDoc(focus).y).isWithin(0.01f).of(docBefore.y)
        v.zoomBy(10f, focus)
        assertThat(v.zoom).isEqualTo(3f)
    }

    @Test fun scrollIsClampedToContent() {
        val l = DocumentLayout(3, PageLayout(PageMode.Pages))
        val v = viewport(1911, l)
        v.scrollBy(Offset(0f, 5000f))
        assertThat(v.scroll.y).isEqualTo(0f)
        v.scrollBy(Offset(0f, -1e6f))
        assertThat(v.scroll.y).isWithin(0.01f).of(l.contentSize.height - 1911f)
        assertThat(v.scroll.x).isEqualTo(0f) // fit width: no horizontal travel at 1x
        v.zoomBy(2f, Offset(540f, 900f))
        v.scrollBy(Offset(1e6f, 0f))
        assertThat(v.scroll.x).isEqualTo(0f)
        v.scrollBy(Offset(-1e6f, 0f))
        assertThat(v.scroll.x).isWithin(0.01f).of(1080f - 540f)
    }

    @Test fun viewDocRoundTripAfterScrollAndZoom() {
        val l = DocumentLayout(3, PageLayout(PageMode.Pages))
        val v = viewport(1911, l)
        v.zoomBy(2f, Offset(200f, 300f))
        v.scrollBy(Offset(-120f, -800f))
        val p = Offset(333f, 777f)
        val back = v.docToView(v.viewToDoc(p))
        assertThat(back.x).isWithin(0.01f).of(p.x)
        assertThat(back.y).isWithin(0.01f).of(p.y)
        // a point drawn under the pen at 2x lands where the pen is
        assertThat(v.viewToDoc(p).x * v.scale + v.origin.x).isWithin(0.01f).of(p.x)
    }

    @Test fun contentSmallerThanViewportIgnoresScroll() {
        val l = DocumentLayout(1, PageLayout(PageMode.Pages))
        val v = viewport(2000, l)
        v.scrollBy(Offset(0f, -300f))
        assertThat(v.scroll.y).isEqualTo(0f)
    }

    @Test fun `mixed page sizes stack with their own heights`() {
        val pages = listOf(
            org.samesame.notes.core.model.document.PageSpec("a"),
            org.samesame.notes.core.model.document.PageSpec("b", size = org.samesame.notes.core.model.document.PageSize(842f, 595f)),
            org.samesame.notes.core.model.document.PageSpec("c"),
        )
        val l = DocumentLayout(pages, PageLayout(PageMode.Pages))
        val a4 = 1080f * PageRatio.A4Portrait.heightOverWidth
        val land = 1080f * 595f / 842f
        assertThat(l.pageSize(1).height).isWithin(0.01f).of(land)
        assertThat(l.pageRects[2].top).isWithin(0.01f).of(a4 + land + 2 * DocumentLayout.PAGE_GAP_PU)
        assertThat(l.contentSize.height).isWithin(0.01f).of(2 * a4 + land + 2 * DocumentLayout.PAGE_GAP_PU)
        assertThat(l.pageAt(Offset(10f, a4 + DocumentLayout.PAGE_GAP_PU + land - 1f))).isEqualTo(1)
        assertThat(l.pageAt(Offset(10f, a4 + DocumentLayout.PAGE_GAP_PU + land + 1f))).isNull()
        assertThat(l.nearestPage(Offset(10f, a4 + DocumentLayout.PAGE_GAP_PU + land + 20f))).isEqualTo(2)
        assertThat(l.visiblePages(androidx.compose.ui.geometry.Rect(0f, a4 + 30f, 1080f, a4 + land + 60f))).isEqualTo(1..2)
        // The text engine sees the same rects; virtual pages after the last one use the note ratio.
        assertThat(l.contentRect(1).bottom).isWithin(0.01f).of(l.pageRects[1].bottom - org.samesame.notes.core.richtext.BodyTextMetrics.MARGIN_BOTTOM)
        assertThat(l.pageRect(3).height).isWithin(0.01f).of(a4)
    }

    @Test fun `pages without a size lay out exactly as before`() {
        val specs = List(3) { org.samesame.notes.core.model.document.PageSpec("p$it") }
        assertThat(DocumentLayout(specs, PageLayout(PageMode.Pages))).isEqualTo(DocumentLayout(3, PageLayout(PageMode.Pages)))
        val old = DocumentLayout(3, PageLayout(PageMode.Pages, PageRatio.A4Landscape))
        val new = DocumentLayout(specs, PageLayout(PageMode.Pages, PageRatio.A4Landscape))
        assertThat(new.pageRects).isEqualTo(old.pageRects)
    }
}

class DocumentLayoutTemplatePitchTest {
    @Test fun ruledTemplatesExposeTheirRuleSpacing() {
        val pages = listOf(
            org.samesame.notes.core.model.document.PageSpec(
                "a", template = org.samesame.notes.core.model.document.TemplateRef.Builtin(org.samesame.notes.core.model.PageTemplate.LinesMedium),
            ),
            org.samesame.notes.core.model.document.PageSpec(
                "b", template = org.samesame.notes.core.model.document.TemplateRef.Builtin(org.samesame.notes.core.model.PageTemplate.GridNarrow),
            ),
            org.samesame.notes.core.model.document.PageSpec(
                "c", template = org.samesame.notes.core.model.document.TemplateRef.Builtin(org.samesame.notes.core.model.PageTemplate.Blank),
            ),
        )
        val l = DocumentLayout(pages, PageLayout(PageMode.Pages))
        assertThat(l.linePitch(0)).isEqualTo(TemplateMetrics.LINE_MEDIUM)
        assertThat(l.linePitch(1)).isEqualTo(TemplateMetrics.GRID_NARROW)
        assertThat(l.linePitch(2)).isNull()
        assertThat(l.linePitch(9)).isNull()
    }

    @Test fun templateChangeChangesTheLayoutValue() {
        fun layout(template: org.samesame.notes.core.model.PageTemplate) = DocumentLayout(
            listOf(
                org.samesame.notes.core.model.document.PageSpec(
                    "a", template = org.samesame.notes.core.model.document.TemplateRef.Builtin(template),
                ),
            ),
            PageLayout(PageMode.Pages),
        )
        assertThat(layout(org.samesame.notes.core.model.PageTemplate.LinesMedium))
            .isNotEqualTo(layout(org.samesame.notes.core.model.PageTemplate.Blank))
    }
}

class DocumentLayoutRuleOffsetTest {
    private fun layout(mode: PageMode) = DocumentLayout(
        listOf(
            org.samesame.notes.core.model.document.PageSpec(
                "a", template = org.samesame.notes.core.model.document.TemplateRef.Builtin(org.samesame.notes.core.model.PageTemplate.LinesMedium),
            ),
            org.samesame.notes.core.model.document.PageSpec(
                "b", template = org.samesame.notes.core.model.document.TemplateRef.Builtin(org.samesame.notes.core.model.PageTemplate.LinesMedium),
            ),
        ),
        PageLayout(mode),
    )

    @Test fun everyPageStartsOneRuleDown() {
        val l = layout(PageMode.Pages)
        org.junit.Assert.assertEquals(TemplateMetrics.LINE_MEDIUM, l.firstRuleOffset(0), 0.01f)
        org.junit.Assert.assertEquals(TemplateMetrics.LINE_MEDIUM, l.firstRuleOffset(1), 0.01f)
    }

    @Test fun seamlessPagesContinueTheRulePattern() {
        val l = layout(PageMode.Infinite)
        val p = TemplateMetrics.LINE_MEDIUM
        val top = l.pageRects[1].top
        // The first rule of page 2 is the first multiple of the pitch past the page edge.
        org.junit.Assert.assertEquals(kotlin.math.ceil(top / p) * p - top, l.firstRuleOffset(1), 0.01f)
        org.junit.Assert.assertTrue(l.firstRuleOffset(1) in 0.01f..p)
    }

    @Test fun unruledPagesHaveNoOffset() {
        val l = DocumentLayout(2, PageLayout(PageMode.Infinite))
        org.junit.Assert.assertEquals(0f, l.firstRuleOffset(1), 0f)
    }
}
