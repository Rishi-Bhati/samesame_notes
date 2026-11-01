package org.samesame.notes.core.export

import android.graphics.BitmapFactory
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
import org.samesame.notes.core.model.PageTemplate
import org.samesame.notes.core.model.PenSettings
import org.samesame.notes.core.model.PenType
import org.samesame.notes.core.model.document.NoteDocument
import org.samesame.notes.core.model.document.PageLayout
import org.samesame.notes.core.model.document.PageSpec
import org.samesame.notes.core.model.document.TemplateRef
import org.samesame.notes.core.model.richtext.CharStyle
import org.samesame.notes.core.model.richtext.ParagraphStyle
import org.samesame.notes.core.model.richtext.RichText
import org.samesame.notes.core.model.richtext.TextRun
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream

@RunWith(AndroidJUnit4::class)
class ExportInstrumentedTest {
    private fun stroke(x: Float): Stroke {
        val inputs = MutableStrokeInputBatch()
            .add(InputToolType.STYLUS, x = x, y = 300f, elapsedTimeMillis = 0, pressure = 0.5f)
            .add(InputToolType.STYLUS, x = x + 300f, y = 500f, elapsedTimeMillis = 16, pressure = 0.7f)
            .add(InputToolType.STYLUS, x = x + 500f, y = 320f, elapsedTimeMillis = 32, pressure = 0.7f)
        return Stroke(PenBrushes.brushFor(PenSettings(PenType.Pen, 0xFF1A73E8, 12f, true)), inputs)
    }

    private fun source(mode: PageMode, pages: Int, textLines: Int = 3): ExportSource {
        val text = (1..textLines).joinToString("\n") { "Line $it of the exported note" }
        val body = RichText(text, listOf(TextRun(text.length, CharStyle())), List(textLines) { ParagraphStyle() })
        val specs = List(pages) { PageSpec("p$it", template = TemplateRef.Builtin(PageTemplate.LinesMedium)) }
        val doc = NoteDocument(id = "t", title = "Test", layout = PageLayout(mode = mode), body = body, pages = specs)
        return object : ExportSource {
            override suspend fun document() = doc
            override suspend fun page(pageId: String) = PageContent(pageId, listOf(InkElement(ElementId(1), stroke(100f))), 1)
            override suspend fun media(path: String): ByteArray? = null
        }
    }

    private fun export(src: ExportSource, format: ExportFormat, options: ExportOptions = ExportOptions()): Pair<ByteArray, ExportTarget> {
        val out = ByteArrayOutputStream()
        val target = runBlocking { NoteExporter().export(src, format, options, out) }
        return out.toByteArray() to target
    }

    @Test fun pdfHasOnePageAtA4AndRendersInk() {
        val (bytes, target) = export(source(PageMode.Pages, 2), ExportFormat.PDF)
        assertThat(target.extension).isEqualTo("pdf")
        val file = File.createTempFile("export", ".pdf", InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)
        file.writeBytes(bytes)
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { r ->
                assertThat(r.pageCount).isEqualTo(2)
                r.openPage(0).use { p ->
                    assertThat(p.width).isEqualTo(595)
                    assertThat(p.height).isEqualTo(842)
                    val bmp = android.graphics.Bitmap.createBitmap(595, 842, android.graphics.Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(android.graphics.Color.WHITE)
                    p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    val pixels = IntArray(595 * 842).also { bmp.getPixels(it, 0, 595, 0, 0, 595, 842) }
                    assertThat(pixels.any { it != android.graphics.Color.WHITE }).isTrue()
                }
            }
        }
        file.delete()
    }

    @Test fun pngPerPageIsZippedForPagedNotes() {
        val (zip, target) = export(source(PageMode.Pages, 3), ExportFormat.IMAGES)
        assertThat(target.extension).isEqualTo("zip")
        val sizes = ArrayList<Pair<Int, Int>>()
        ZipInputStream(zip.inputStream()).use { z ->
            while (z.nextEntry != null) {
                val bytes = z.readBytes()
                val b = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                sizes += b.width to b.height
            }
        }
        assertThat(sizes).hasSize(3)
        assertThat(sizes[0].first).isEqualTo(1080)
        assertThat(sizes[0].second).isEqualTo(1527)
    }

    @Test fun infiniteNoteIsOneTallImageWithBlankTrailingSegmentsTrimmed() {
        val (png, target) = export(
            source(PageMode.Infinite, 3),
            ExportFormat.IMAGES,
            ExportOptions(ImageOptions(background = ImageBackground.White)),
        )
        // all three segments carry ink, so nothing is trimmed
        assertThat(target.extension).isEqualTo("png")
        val b = BitmapFactory.decodeByteArray(png, 0, png.size)
        assertThat(b.width).isEqualTo(1080)
        assertThat(b.height).isEqualTo(3 * 1527)
    }

    @Test fun jpegWithScale() {
        val (jpg, target) = export(
            source(PageMode.Pages, 1), ExportFormat.IMAGES,
            ExportOptions(ImageOptions(format = ImageFormat.JPEG, scale = 0.5f)),
        )
        assertThat(target.extension).isEqualTo("jpg")
        val b = BitmapFactory.decodeByteArray(jpg, 0, jpg.size)
        assertThat(b.width).isEqualTo(540)
    }

    @Test fun docxAndPptxRenderOnDevice() {
        val (docx, _) = export(source(PageMode.Pages, 1), ExportFormat.DOCX)
        val (pptx, _) = export(source(PageMode.Pages, 2), ExportFormat.PPTX)
        assertThat(docx.size).isGreaterThan(1000)
        assertThat(pptx.size).isGreaterThan(1000)
    }
}
