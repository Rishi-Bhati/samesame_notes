package org.samesame.notes.core.data.transfer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.samesame.notes.core.export.ExportFormat
import org.samesame.notes.core.export.ExportOptions
import org.samesame.notes.core.export.ExportSource
import org.samesame.notes.core.export.ExportTarget
import org.samesame.notes.core.export.NoteExporter
import org.samesame.notes.core.ink.media.BoundedBitmaps
import org.samesame.notes.core.ink.page.PageContent
import org.samesame.notes.core.model.Limits
import org.samesame.notes.core.model.document.MarkdownBody
import org.samesame.notes.core.model.document.NoteDocument
import org.samesame.notes.core.model.document.PageSpec
import org.samesame.notes.core.model.io.InputTooLargeException
import org.samesame.notes.core.model.richtext.RichText
import java.io.IOException
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Renders files opened from other apps without making them notes first: a text or Markdown document to PDF, HTML or
 * Word through the note exporters (an in-memory document, nothing is written to the catalog), and pictures to a PDF.
 */
@Singleton
class FileConversionService @Inject constructor(private val exporter: NoteExporter) {

    /**
     * Writes [text] as [format] (PDF, HTML or DOCX) to [out]. [markdown] renders it as Markdown (headings, lists, tables;
     * HTML is escaped and link URLs sanitised by the Markdown exporter); otherwise it is plain text, one paragraph per line.
     * Text over [Limits.MAX_TEXT_IMPORT_BYTES] is refused with [InputTooLargeException]; Markdown over
     * [Limits.MAX_MARKDOWN_CHARS] is rendered as plain text, like the editor shows it.
     */
    suspend fun exportText(text: String, title: String, markdown: Boolean, format: ExportFormat, out: OutputStream): ExportTarget {
        require(format in TEXT_FORMATS) { "$format is not a text rendering" }
        if (utf8Length(text) > Limits.MAX_TEXT_IMPORT_BYTES) throw InputTooLargeException("Text is larger than ${Limits.MAX_TEXT_IMPORT_BYTES} bytes")
        val asMarkdown = markdown && text.length <= Limits.MAX_MARKDOWN_CHARS
        val document = NoteDocument(
            id = DOCUMENT_ID,
            title = title.trim(),
            body = if (asMarkdown) RichText() else RichText.of(text),
            pages = listOf(PageSpec(PAGE_ID)),
            markdown = if (asMarkdown) MarkdownBody(content = text) else null,
        )
        val source = object : ExportSource {
            override suspend fun document(): NoteDocument = document
            override suspend fun page(pageId: String): PageContent = PageContent(pageId, emptyList(), 0)
            override suspend fun media(path: String): ByteArray? = null
        }
        return exporter.export(source, format, ExportOptions(), out)
    }

    /**
     * Writes one PDF with a page per picture to [out], in order. [count] pictures are read one at a time through [image]
     * (null skips one that can't be read); each is downsampled to [Limits.MAX_IMAGE_TO_PDF_PIXELS] and fitted on an A4
     * page turned to match it. Returns the number of pages; throws [IOException] when no picture could be decoded.
     */
    suspend fun imagesToPdf(count: Int, image: suspend (Int) -> ByteArray?, out: OutputStream): Int = withContext(Dispatchers.Default) {
        val pdf = PdfDocument()
        try {
            var pages = 0
            for (i in 0 until count) {
                ensureActive()
                val bytes = image(i) ?: continue
                val bitmap = BoundedBitmaps.decode(bytes, Limits.MAX_IMAGE_TO_PDF_PIXELS) ?: continue
                try {
                    val landscape = bitmap.width > bitmap.height
                    val w = if (landscape) A4_LONG_PT else A4_SHORT_PT
                    val h = if (landscape) A4_SHORT_PT else A4_LONG_PT
                    val page = pdf.startPage(PdfDocument.PageInfo.Builder(w, h, pages + 1).create())
                    drawFitted(page.canvas, bitmap, w.toFloat(), h.toFloat())
                    pdf.finishPage(page)
                    pages++
                } finally {
                    bitmap.recycle()
                }
            }
            if (pages == 0) throw IOException("No picture could be read")
            withContext(Dispatchers.IO) { pdf.writeTo(out); out.flush() }
            pages
        } finally {
            pdf.close()
        }
    }

    private fun drawFitted(canvas: Canvas, bitmap: Bitmap, w: Float, h: Float) {
        canvas.drawColor(Color.WHITE)
        val scale = minOf(w / bitmap.width, h / bitmap.height)
        val dw = bitmap.width * scale
        val dh = bitmap.height * scale
        val left = (w - dw) / 2f
        val top = (h - dh) / 2f
        canvas.drawBitmap(bitmap, null, RectF(left, top, left + dw, top + dh), Paint(Paint.FILTER_BITMAP_FLAG))
    }

    private fun utf8Length(text: String): Long {
        var n = 0L
        var i = 0
        while (i < text.length) {
            val c = text[i]
            n += when {
                c.code < 0x80 -> 1
                c.code < 0x800 -> 2
                Character.isHighSurrogate(c) && i + 1 < text.length && Character.isLowSurrogate(text[i + 1]) -> { i++; 4 }
                else -> 3
            }
            i++
        }
        return n
    }

    companion object {
        /** What a text document can be rendered to. */
        val TEXT_FORMATS: Set<ExportFormat> = setOf(ExportFormat.PDF, ExportFormat.HTML, ExportFormat.DOCX)
        private const val DOCUMENT_ID = "converted"
        private const val PAGE_ID = "p1"
        private const val A4_SHORT_PT = 595
        private const val A4_LONG_PT = 842
    }
}
