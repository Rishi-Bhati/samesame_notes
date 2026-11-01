package org.samesame.notes.core.export

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.samesame.notes.core.ink.PenBrushes
import org.samesame.notes.core.ink.page.ElementId
import org.samesame.notes.core.ink.page.InkElement
import org.samesame.notes.core.ink.page.PageContent
import org.samesame.notes.core.model.PageMode
import org.samesame.notes.core.model.PenSettings
import org.samesame.notes.core.model.PenType
import org.samesame.notes.core.model.document.NoteDocument
import org.samesame.notes.core.model.document.PageLayout
import org.samesame.notes.core.model.document.PageSize
import org.samesame.notes.core.model.document.PageSpec
import org.samesame.notes.core.model.document.PdfBackground
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Mixed-size PDF -> note with per-page sizes -> PDF export: page count, per-page sizes and an annotation at a known spot.
 * Written for the device agent (androidx.ink is native), not run by the build agent.
 */
@RunWith(AndroidJUnit4::class)
class PdfRoundTripInstrumentedTest {
    private val sizes = listOf(595 to 842, 842 to 595, 612 to 1008)

    /** A source PDF whose pages are white with a 40 pt black square at the top-left corner. */
    private fun sourcePdf(): ByteArray {
        val doc = PdfDocument()
        sizes.forEachIndexed { i, (w, h) ->
            val page = doc.startPage(PdfDocument.PageInfo.Builder(w, h, i + 1).create())
            page.canvas.drawRect(0f, 0f, 40f, 40f, Paint().apply { color = Color.BLACK })
            doc.finishPage(page)
        }
        return ByteArrayOutputStream().also { doc.writeTo(it); doc.close() }.toByteArray()
    }

    /** A thick red stroke across the middle of the page (page units). */
    private fun stroke(y: Float): Stroke {
        val inputs = MutableStrokeInputBatch()
            .add(InputToolType.STYLUS, x = 300f, y = y, elapsedTimeMillis = 0, pressure = 1f)
            .add(InputToolType.STYLUS, x = 780f, y = y, elapsedTimeMillis = 16, pressure = 1f)
        return Stroke(PenBrushes.brushFor(PenSettings(PenType.Pen, 0xFFFF0000, 40f, false)), inputs)
    }

    @Test
    fun mixedSizePdfExportsEveryPageAtItsOwnSizeWithAnnotations() {
        val pdfBytes = sourcePdf()
        val pages = sizes.mapIndexed { i, (w, h) ->
            PageSpec("p$i", pdf = PdfBackground("media/src.pdf", i), size = PageSize(w.toFloat(), h.toFloat()))
        }
        val doc = NoteDocument(id = "n", title = "Mixed", layout = PageLayout(PageMode.Pages), pages = pages)
        val source = object : ExportSource {
            override suspend fun document() = doc
            override suspend fun page(pageId: String): PageContent {
                val i = pages.indexOfFirst { it.id == pageId }
                val hPu = 1080f * sizes[i].second / sizes[i].first
                return PageContent(pageId, listOf(InkElement(ElementId(1), stroke(hPu / 2f))), 1)
            }
            override suspend fun media(path: String): ByteArray? = if (path == "media/src.pdf") pdfBytes else null
        }
        val out = ByteArrayOutputStream()
        runBlocking { NoteExporter().export(source, ExportFormat.PDF, ExportOptions(), out) }

        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "mixed-export.pdf")
        file.writeBytes(out.toByteArray())
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { r ->
                assertThat(r.pageCount).isEqualTo(sizes.size)
                sizes.forEachIndexed { i, (w, h) ->
                    r.openPage(i).use { page ->
                        assertThat(page.width).isEqualTo(w)
                        assertThat(page.height).isEqualTo(h)
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        // Original content (the corner square) is kept ...
                        assertThat(Color.red(bmp.getPixel(10, 10))).isLessThan(80)
                        // ... and the annotation sits in the middle of the page, red.
                        val mid = bmp.getPixel(w / 2, h / 2)
                        assertThat(Color.red(mid)).isGreaterThan(180)
                        assertThat(Color.green(mid)).isLessThan(100)
                        // A blank spot stays white.
                        assertThat(Color.green(bmp.getPixel(w - 10, h - 10))).isGreaterThan(200)
                        bmp.recycle()
                    }
                }
            }
        }
        file.delete()
    }
}
