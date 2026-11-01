package org.samesame.notes.core.model.document

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.samesame.notes.core.model.PageMode
import org.samesame.notes.core.model.PageRatio
import org.samesame.notes.core.model.PageTemplate

class PageSizeTest {
    private val a4 = PageLayout(PageMode.Pages, PageRatio.A4Portrait)

    @Test fun `a page without a size follows the note ratio`() {
        assertThat(PageSpec("p").heightOverWidth(a4)).isEqualTo(PageRatio.A4Portrait.heightOverWidth)
        assertThat(PageSpec("p").heightOverWidth(PageLayout(ratio = PageRatio.Long))).isEqualTo(2f)
    }

    @Test fun `a sized page keeps its own aspect`() {
        assertThat(PageSpec("p", size = PageSize(792f, 612f)).heightOverWidth(a4)).isWithin(1e-6f).of(612f / 792f)
        assertThat(PageSpec("p", size = PageSize(612f, 792f)).heightOverWidth(a4)).isWithin(1e-6f).of(792f / 612f)
    }

    @Test fun `extreme aspects are clamped and invalid sizes fall back to the note ratio`() {
        assertThat(PageSize(1000f, 10f).heightOverWidth).isEqualTo(PageSize.MIN_HEIGHT_OVER_WIDTH)
        assertThat(PageSize(10f, 1000f).heightOverWidth).isEqualTo(PageSize.MAX_HEIGHT_OVER_WIDTH)
        assertThat(PageSpec("p", size = PageSize(0f, 10f)).heightOverWidth(a4)).isEqualTo(PageRatio.A4Portrait.heightOverWidth)
        assertThat(PageSpec("p", size = PageSize(Float.NaN, 10f)).heightOverWidth(a4)).isEqualTo(PageRatio.A4Portrait.heightOverWidth)
        assertThat(PageSize.clampHeightOverWidth(Float.POSITIVE_INFINITY)).isEqualTo(1f)
    }

    @Test fun `document page aspect by index`() {
        val doc = NoteDocument(
            id = "n", layout = a4,
            pages = listOf(PageSpec("a"), PageSpec("b", size = PageSize(842f, 595f))),
        )
        assertThat(doc.pageHeightOverWidth(0)).isEqualTo(PageRatio.A4Portrait.heightOverWidth)
        assertThat(doc.pageHeightOverWidth(1)).isWithin(1e-6f).of(595f / 842f)
        assertThat(doc.pageHeightOverWidth(5)).isEqualTo(PageRatio.A4Portrait.heightOverWidth)
    }

    @Test fun `a follower keeps size and template but not the pdf background or bookmark`() {
        val pdfPage = PageSpec(
            "p", TemplateRef.Image("media/page.png"), backgroundColor = 7L, isBookmarked = true,
            pdf = PdfBackground("media/src.pdf", 3), size = PageSize(842f, 595f),
        )
        val next = pdfPage.follower("q")
        assertThat(next.id).isEqualTo("q")
        assertThat(next.size).isEqualTo(PageSize(842f, 595f))
        assertThat(next.pdf).isNull()
        assertThat(next.isBookmarked).isFalse()
        assertThat(next.backgroundColor).isEqualTo(7L)
        assertThat(next.template).isEqualTo(TemplateRef.Builtin(PageTemplate.Blank))

        val imagePage = PageSpec("i", TemplateRef.Image("media/own.png"))
        assertThat(imagePage.follower("j").template).isEqualTo(TemplateRef.Image("media/own.png"))
        val lined = PageSpec("l", TemplateRef.Builtin(PageTemplate.LinesWide))
        assertThat(lined.follower("m").template).isEqualTo(TemplateRef.Builtin(PageTemplate.LinesWide))
    }

    @Test fun `v5 json without sizes reads with null sizes`() {
        val doc = NoteDocument.fromJson("""{"formatVersion":5,"id":"n","pages":[{"id":"p","pdf":{"media":"media/a.pdf","pageIndex":0}}]}""")
        assertThat(doc.pages.single().size).isNull()
        assertThat(doc.pages.single().pdf).isEqualTo(PdfBackground("media/a.pdf", 0))
    }

    @Test fun `size round trips`() {
        val doc = NoteDocument(id = "n", pages = listOf(PageSpec("p", size = PageSize(595.28f, 841.89f))))
        assertThat(NoteDocument.fromJson(doc.toJson())).isEqualTo(doc)
    }
}
