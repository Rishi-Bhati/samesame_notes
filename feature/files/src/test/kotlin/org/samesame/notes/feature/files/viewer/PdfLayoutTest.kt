package org.samesame.notes.feature.files.viewer

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PdfLayoutTest {
    @Test fun renderSizeKeepsTheAspectRatio() {
        assertThat(PdfLayout.renderSize(595, 842, 1080)).isEqualTo(1080 to 1528)
        assertThat(PdfLayout.renderSize(842, 595, 1000)).isEqualTo(1000 to 707)
    }

    @Test fun renderSizeStaysUnderThePixelBudget() {
        val (w, h) = PdfLayout.renderSize(595, 842, 4320)!!
        assertThat(w.toLong() * h).isAtMost(PdfLayout.MAX_PAGE_PIXELS + 4320)
        assertThat(w.toFloat() / h).isWithin(0.01f).of(595f / 842f)
        // A very tall page is shrunk too.
        val (tw, th) = PdfLayout.renderSize(100, 100_000, 1080)!!
        assertThat(tw.toLong() * th).isAtMost(PdfLayout.MAX_PAGE_PIXELS + 100_000)
        assertThat(tw).isAtLeast(1)
    }

    @Test fun pagesWithoutASizeAreNotRendered() {
        assertThat(PdfLayout.renderSize(0, 842, 1080)).isNull()
        assertThat(PdfLayout.renderSize(595, 842, 0)).isNull()
    }

    @Test fun renderSteps() {
        assertThat(PdfLayout.renderStep(1f)).isEqualTo(1)
        assertThat(PdfLayout.renderStep(1.3f)).isEqualTo(1)
        assertThat(PdfLayout.renderStep(2f)).isEqualTo(2)
        assertThat(PdfLayout.renderStep(4f)).isEqualTo(3)
    }

    @Test fun offsetIsClampedToTheZoomedWidth() {
        assertThat(PdfLayout.clampOffset(50f, 1000f, 2f)).isEqualTo(0f)
        assertThat(PdfLayout.clampOffset(-5000f, 1000f, 2f)).isEqualTo(-1000f)
        assertThat(PdfLayout.clampOffset(-300f, 1000f, 1f)).isEqualTo(0f)
    }

    @Test fun zoomKeepsThePointUnderTheFingers() {
        // Content x under screen x 600 at scale 2 with offset -400 is (600 + 400) / 2 = 500.
        val offset = PdfLayout.zoomOffset(-400f, 600f, 2f, 3f, 1000f)
        assertThat((600f - offset) / 3f).isWithin(0.01f).of(500f)
        // Zooming out to 1x always ends at offset 0.
        assertThat(PdfLayout.zoomOffset(-400f, 600f, 2f, 1f, 1000f)).isEqualTo(0f)
    }
}
