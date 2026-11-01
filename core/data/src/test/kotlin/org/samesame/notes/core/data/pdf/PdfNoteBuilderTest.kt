package org.samesame.notes.core.data.pdf

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import org.samesame.notes.core.model.PageMode
import org.samesame.notes.core.model.PageRatio
import org.samesame.notes.core.model.document.NoteDocument
import org.samesame.notes.core.model.document.PageSize
import org.samesame.notes.core.model.document.PdfBackground
import org.samesame.notes.core.model.document.TemplateRef

class PdfNoteBuilderTest {
    private class FakeSource(val sizes: List<Pair<Float, Float>>) : PdfPageSource {
        val rendered = ArrayList<Triple<Int, Int, Int>>()
        override val pageCount get() = sizes.size
        override fun pageSize(index: Int) = sizes[index]
        override fun renderPng(index: Int, widthPx: Int, heightPx: Int): ByteArray {
            rendered += Triple(index, widthPx, heightPx)
            return byteArrayOf(index.toByte())
        }
        override fun close() {}
    }

    @Test
    fun noteRatioFollowsTheFirstPage() {
        assertThat(PdfNoteBuilder.ratioFor(595f, 842f)).isEqualTo(PageRatio.A4Portrait)
        assertThat(PdfNoteBuilder.ratioFor(612f, 792f)).isEqualTo(PageRatio.A4Portrait)
        assertThat(PdfNoteBuilder.ratioFor(842f, 595f)).isEqualTo(PageRatio.A4Landscape)
        assertThat(PdfNoteBuilder.ratioFor(300f, 1000f)).isEqualTo(PageRatio.Long)
    }

    @Test
    fun renderSizeFollowsEachPageShape() {
        assertThat(PdfNoteBuilder.renderSize(PageSize(595f, 842f))).isEqualTo(2160 to Math.round(2160 * 842f / 595f))
        assertThat(PdfNoteBuilder.renderSize(PageSize(842f, 595f))).isEqualTo(2160 to Math.round(2160 * 595f / 842f))
        assertThat(PdfNoteBuilder.renderSize(PageSize(612f, 792f))).isEqualTo(2160 to Math.round(2160 * 792f / 612f))
    }

    @Test
    fun veryTallPagesRenderNarrowerAndStayWithinThePixelCap() {
        // A 1:10 strip (clamped to the maximum aspect) would be 2160 x 21600 = 46.7 MP at full width.
        val (w, h) = PdfNoteBuilder.renderSize(PageSize(100f, 5000f))
        assertThat(w.toLong() * h).isAtMost(PdfNoteBuilder.MAX_RENDER_PIXELS.toLong() + 2 * (w + h))
        assertThat(h.toFloat() / w).isWithin(0.01f).of(PageSize.MAX_HEIGHT_OVER_WIDTH)
        assertThat(w).isLessThan(PdfNoteBuilder.RENDER_WIDTH_PX)
    }

    @Test
    fun nonsenseSizesFallBackToA4() {
        assertThat(PdfNoteBuilder.pageSize(0f, 842f)).isEqualTo(PageSize(PageSize.A4_WIDTH_PT, PageSize.A4_HEIGHT_PT))
        assertThat(PdfNoteBuilder.pageSize(Float.NaN, 1f)).isEqualTo(PageSize(PageSize.A4_WIDTH_PT, PageSize.A4_HEIGHT_PT))
        assertThat(PdfNoteBuilder.pageSize(842f, 595f)).isEqualTo(PageSize(842f, 595f))
    }

    @Test
    fun fitCentresThePageWithoutDistortion() {
        val (w, h) = PdfNoteBuilder.renderSize(PageSize(595f, 842f))
        // Letter (612x792) on an A4-shaped canvas: the width limits, so there is vertical slack split top and bottom.
        val (scale, dx, dy) = PdfNoteBuilder.fit(612f, 792f, w.toFloat(), h.toFloat())
        assertThat(612f * scale).isWithin(0.01f).of(w.toFloat())
        assertThat(dx).isWithin(0.01f).of(0f)
        assertThat(dy).isGreaterThan(0f)
        assertThat(dy * 2 + 792f * scale).isWithin(0.01f).of(h.toFloat())
        // A page rendered at its own shape fills the canvas: no letterbox.
        val (lw, lh) = PdfNoteBuilder.renderSize(PageSize(842f, 595f))
        val (s2, dx2, dy2) = PdfNoteBuilder.fit(842f, 595f, lw.toFloat(), lh.toFloat())
        assertThat(842f * s2).isWithin(1f).of(lw.toFloat())
        assertThat(dx2).isWithin(1f).of(0f)
        assertThat(dy2).isWithin(1f).of(0f)
    }

    @Test
    fun mixedSizePdfsKeepEveryPageShapeAndOrder() {
        val a = FakeSource(listOf(595f to 842f, 842f to 595f))
        val b = FakeSource(listOf(612f to 1008f, 300f to 300f))
        val stored = ArrayList<ByteArray>()
        val pages = PdfNoteBuilder.renderAll(listOf(a, b), listOf("media/a.pdf", "media/b.pdf")) { bytes ->
            stored += bytes; "media/p${stored.size}.png"
        }
        assertThat(pages.map { it.media }).containsExactly("media/p1.png", "media/p2.png", "media/p3.png", "media/p4.png").inOrder()
        assertThat(pages.map { it.size }).containsExactly(
            PageSize(595f, 842f), PageSize(842f, 595f), PageSize(612f, 1008f), PageSize(300f, 300f),
        ).inOrder()
        assertThat(pages.map { it.pdf }).containsExactly(
            PdfBackground("media/a.pdf", 0), PdfBackground("media/a.pdf", 1),
            PdfBackground("media/b.pdf", 0), PdfBackground("media/b.pdf", 1),
        ).inOrder()
        // Every page renders at its own shape.
        assertThat(a.rendered).containsExactly(
            Triple(0, 2160, Math.round(2160 * 842f / 595f)), Triple(1, 2160, Math.round(2160 * 595f / 842f)),
        ).inOrder()
        assertThat(b.rendered.last()).isEqualTo(Triple(1, 2160, 2160))

        val doc = PdfNoteBuilder.document("id", "Report", pages)
        assertThat(doc.title).isEqualTo("Report")
        assertThat(doc.layout.mode).isEqualTo(PageMode.Pages)
        assertThat(doc.layout.ratio).isEqualTo(PageRatio.A4Portrait)
        assertThat(doc.pages.map { (it.template as TemplateRef.Image).media }).isEqualTo(pages.map { it.media })
        assertThat(doc.pages.map { it.size }).isEqualTo(pages.map { it.size })
        assertThat(doc.pages.map { it.pdf }).isEqualTo(pages.map { it.pdf })
        assertThat(doc.pages.map { it.id }.toSet()).hasSize(4)
        assertThat(doc.pages[1].heightOverWidth(doc.layout)).isWithin(1e-5f).of(595f / 842f)
    }

    @Test
    fun aLandscapeFirstPageMakesALandscapeNote() {
        val pages = PdfNoteBuilder.renderAll(listOf(FakeSource(listOf(842f to 595f, 595f to 842f)))) { "media/x.png" }
        assertThat(PdfNoteBuilder.document("id", "t", pages).layout.ratio).isEqualTo(PageRatio.A4Landscape)
        assertThat(pages.all { it.pdf == null }).isTrue()
    }

    @Test
    fun emptyAndHugePdfsAreRefused() {
        val empty = assertThrows(PdfImportException::class.java) { PdfNoteBuilder.renderAll(listOf(FakeSource(emptyList()))) { "x" } }
        assertThat(empty.reason).isEqualTo(PdfImportError.Empty)
        val huge = FakeSource(List(PdfNoteBuilder.MAX_PAGES + 1) { 595f to 842f })
        val tooMany = assertThrows(PdfImportException::class.java) { PdfNoteBuilder.renderAll(listOf(huge)) { "x" } }
        assertThat(tooMany.reason).isEqualTo(PdfImportError.TooManyPages)
        assertThat(huge.rendered).isEmpty()
    }

    @Test
    fun insertedBackgroundPagesGetFreshIdsImagesSizesAndSources() {
        val pages = PdfNoteBuilder.backgroundPages(
            listOf(
                ImportedPdfPage("media/a.png", PdfBackground("media/s.pdf", 0), PageSize(595f, 842f)),
                ImportedPdfPage("media/b.png", PdfBackground("media/s.pdf", 1), PageSize(842f, 595f)),
            ),
        )
        assertThat(pages.map { (it.template as TemplateRef.Image).media }).containsExactly("media/a.png", "media/b.png").inOrder()
        assertThat(pages.map { it.id }.toSet()).hasSize(2)
        assertThat(pages.map { it.pdf?.pageIndex }).containsExactly(0, 1).inOrder()
        assertThat(pages.map { it.size }).containsExactly(PageSize(595f, 842f), PageSize(842f, 595f)).inOrder()
        assertThat(pages.none { it.isBookmarked }).isTrue()
    }

    @Test
    fun pageCountCheckMatchesTheImporter() {
        PdfNoteBuilder.requirePages(1)
        PdfNoteBuilder.requirePages(PdfNoteBuilder.MAX_PAGES)
        assertThat(assertThrows(PdfImportException::class.java) { PdfNoteBuilder.requirePages(0) }.reason)
            .isEqualTo(PdfImportError.Empty)
        assertThat(assertThrows(PdfImportException::class.java) { PdfNoteBuilder.requirePages(PdfNoteBuilder.MAX_PAGES + 1) }.reason)
            .isEqualTo(PdfImportError.TooManyPages)
    }

    @Test
    fun importedNoteRoundTripsWithPerPageSizes() {
        val doc = PdfNoteBuilder.document("id", "t", listOf(ImportedPdfPage("media/a.png", PdfBackground("media/s.pdf", 0), PageSize(842f, 595f))))
        val back = NoteDocument.fromJson(doc.toJson())
        assertThat(back.pages.single().template).isEqualTo(TemplateRef.Image("media/a.png"))
        assertThat(back.pages.single().size).isEqualTo(PageSize(842f, 595f))
        assertThat(back).isEqualTo(doc)
    }
}
