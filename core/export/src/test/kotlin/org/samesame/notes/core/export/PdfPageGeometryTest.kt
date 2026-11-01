package org.samesame.notes.core.export

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.samesame.notes.core.export.render.PdfPageGeometry
import org.samesame.notes.core.model.Limits

class PdfPageGeometryTest {
    private val noteW = Limits.PAGE_WIDTH_PU.toFloat()
    private val noteH = noteW * 1.4142f

    @Test
    fun inverseFitMapsTheOriginalPageBackOntoItself() {
        val (srcW, srcH) = 612f to 792f
        val t = PdfPageGeometry.fit(srcW, srcH, noteW, noteH)
        // Corners of the original page land on the letterboxed rect, and the inverse returns them exactly.
        val left = t.dx
        val top = t.dy
        val right = t.dx + srcW * t.scale
        val bottom = t.dy + srcH * t.scale
        assertThat(PdfPageGeometry.toPdfX(left, t)).isWithin(0.01f).of(0f)
        assertThat(PdfPageGeometry.toPdfY(top, t)).isWithin(0.01f).of(0f)
        assertThat(PdfPageGeometry.toPdfX(right, t)).isWithin(0.01f).of(srcW)
        assertThat(PdfPageGeometry.toPdfY(bottom, t)).isWithin(0.01f).of(srcH)
        // The note page centre stays the page centre.
        assertThat(PdfPageGeometry.toPdfX(noteW / 2f, t)).isWithin(0.01f).of(srcW / 2f)
        assertThat(PdfPageGeometry.toPdfY(noteH / 2f, t)).isWithin(0.01f).of(srcH / 2f)
    }

    @Test
    fun rasterIsAtLeastTwoHundredDpiAndNeverEmpty() {
        assertThat(PdfPageGeometry.rasterSize(595f, 842f, 200f)).isEqualTo(1653 to 2339)
        assertThat(PdfPageGeometry.rasterSize(0f, 0f, 200f)).isEqualTo(1 to 1)
        assertThat(PdfPageGeometry.pageBox(595.4f, 841.6f)).isEqualTo(595 to 842)
        assertThat(PdfPageGeometry.pageBox(0f, 0f)).isEqualTo(1 to 1)
    }

    @Test
    fun aPageImportedAtItsOwnShapeMapsWithoutLetterbox() {
        // v6: a landscape page is 1080 x 1080*595/842 pu, so the inverse fit is a pure scale.
        val h = noteW * 595f / 842f
        val t = PdfPageGeometry.fit(842f, 595f, noteW, h)
        assertThat(t.dx).isWithin(0.01f).of(0f)
        assertThat(t.dy).isWithin(0.01f).of(0f)
        assertThat(PdfPageGeometry.toPdfX(noteW, t)).isWithin(0.05f).of(842f)
        assertThat(PdfPageGeometry.toPdfY(h, t)).isWithin(0.05f).of(595f)
    }

    @Test
    fun nativePagesUseTheirOwnSizeOrA4ByShape() {
        assertThat(PdfPageGeometry.nativePageBox(null, 1.4142857f)).isEqualTo(595 to 841)
        assertThat(PdfPageGeometry.nativePageBox(null, 1f / 1.4142857f)).isEqualTo(842 to 595)
        assertThat(PdfPageGeometry.nativePageBox(null, 2f)).isEqualTo(595 to 1190)
        assertThat(PdfPageGeometry.nativePageBox(org.samesame.notes.core.model.document.PageSize(612f, 792f), 1.41f)).isEqualTo(612 to 792)
        assertThat(PdfPageGeometry.pointsPerPu(595)).isWithin(1e-4f).of(595f / 1080f)
    }

    @Test
    fun rasterIs300DpiAndCappedForHugePages() {
        assertThat(PdfPageGeometry.rasterSize(595f, 842f, 300f)).isEqualTo(2479 to 3508)
        val (w, h) = PdfPageGeometry.rasterSize(2000f, 2000f, 300f, 12_000_000L)
        assertThat(w.toLong() * h).isAtMost(12_010_000L)
        assertThat(w).isEqualTo(h)
    }
}
