package org.samesame.notes.core.richtext

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.samesame.notes.core.model.Limits
import org.samesame.notes.core.model.richtext.ListKind
import org.samesame.notes.core.model.richtext.ParagraphStyle
import org.samesame.notes.core.model.richtext.RichText
import org.samesame.notes.core.richtext.layout.SimplePageGeometry

@RunWith(AndroidJUnit4::class)
class EngineLayoutTest {

    private val text = (1..40).joinToString("\n") { "Line $it" }
    private val pages = SimplePageGeometry(pageCount = 1, pageHeight = 400f)

    @Test fun textFlowsAcrossPagesWithWholeLines() {
        val e = BodyTextEngine(BodyText(RichText.of(text)), pages)
        val pag = e.pagination()
        assertThat(pag.requiredPageCount).isGreaterThan(1)
        val content = pages.contentRect(0).height
        for (s in pag.slices.filter { !it.isEmpty }) {
            // Every line of a page lies inside its content rect.
            assertThat(e.layout.getLineBottom(s.lastLine) - s.columnTop).isAtMost(content)
            assertThat(e.layout.getLineTop(s.firstLine).toFloat()).isEqualTo(s.columnTop)
        }
    }

    @Test fun caretAndHitTestAgreeOnSecondPage() {
        val body = BodyText(RichText.of(text))
        val e = BodyTextEngine(body, pages)
        val slice1 = e.pagination().slices[1]
        val offset = e.layout.getLineStart(slice1.firstLine) + 2
        val c = e.caret(offset)
        assertThat(c.pageIndex).isEqualTo(1)
        // The first line of page 2 starts at its content top (pushed past the page break).
        assertThat(c.top).isEqualTo(pages.contentRect(1).top)
        assertThat(e.hitTest(c.x + 1f, (c.top + c.bottom) / 2f)).isEqualTo(offset)
        // A tap in the gap between pages snaps to the nearest page.
        val gapY = pages.pageRect(0).bottom + 1f
        assertThat(e.hitTest(200f, gapY)).isAtLeast(0)
    }

    @Test fun selectionAcrossPageBreakIsDrawnOnBothPages() {
        val body = BodyText(RichText.of(text))
        val e = BodyTextEngine(body, pages)
        e.colors = RichTextColors.Light.copy(selection = Color.RED)
        val s0 = e.pagination().slices[0]
        val s1 = e.pagination().slices[1]
        body.setSelection(e.layout.getLineStart(s0.lastLine), e.layout.getLineEnd(s1.firstLine))
        for (page in 0..1) {
            val bmp = Bitmap.createBitmap(Limits.PAGE_WIDTH_PU.toInt(), 400, Bitmap.Config.ARGB_8888)
            e.drawPage(Canvas(bmp), page, TextDecorations(selectionVisible = true))
            var red = 0
            for (y in 0 until bmp.height step 2) for (x in 0 until bmp.width step 4) if (bmp.getPixel(x, y) == Color.RED) red++
            assertThat(red).isGreaterThan(50)
        }
        val top = e.caret(body.selection.min)
        val bottom = e.caret(body.selection.max)
        assertThat(top.pageIndex).isEqualTo(0)
        assertThat(bottom.pageIndex).isEqualTo(1)
    }

    @Test fun infiniteModeGrowsByWholeSegments() {
        val inf = SimplePageGeometry(pageCount = 1, pageHeight = 400f, continuous = true)
        val e = BodyTextEngine(BodyText(RichText.of(text)), inf)
        val required = e.requiredPageCount
        assertThat(required).isGreaterThan(1)
        assertThat(required * 400f - BodyTextMetrics.MARGIN_TOP).isAtLeast(e.layout.height.toFloat())
    }

    @Test fun emptyStyledLastParagraphCaretSitsAfterMarker() {
        val todo = ParagraphStyle(list = ListKind.Checklist)
        val body = BodyText(RichText.of("a\n").copy(paragraphs = listOf(todo, todo)))
        val e = BodyTextEngine(body, pages)
        val c = e.caret(body.length)
        assertThat(c.x).isEqualTo(pages.contentRect(0).left + BodyTextMetrics.MARKER_WIDTH)
        // Checkbox hit on the first item toggles via the paragraph index.
        val first = e.caret(0)
        assertThat(e.checkboxAt(pages.contentRect(0).left + 30f, (first.top + first.bottom) / 2f)).isEqualTo(0)
        assertThat(e.checkboxAt(pages.contentRect(0).left + 400f, (first.top + first.bottom) / 2f)).isNull()
    }

    @Test fun defaultTextSizeMatchesReferenceScale() {
        val e = BodyTextEngine(BodyText(RichText.of("Line 1\nLine 2")), pages)
        assertThat(e.paint.textSize).isEqualTo(BodyTextMetrics.DEFAULT_TEXT_SIZE_PU)
        // Line height of a default line (#74 caret ≈ 70 px), and the last line's caret matches it.
        assertThat(e.layout.getLineBottom(0) - e.layout.getLineTop(0)).isIn(com.google.common.collect.Range.closed(60, 75))
        val c0 = e.caret(0)
        val c1 = e.caret(8)
        assertThat(c1.bottom - c1.top).isWithin(2f).of(c0.bottom - c0.top)
    }
}
