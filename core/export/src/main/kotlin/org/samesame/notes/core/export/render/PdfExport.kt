package org.samesame.notes.core.export.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import org.samesame.notes.core.model.Limits
import java.io.File
import java.io.OutputStream

/**
 * PDF through [PdfDocument]. Text and ink are drawn as vectors by [NoteRenderer]; every page is written at its own size.
 *
 * Pages imported from a PDF are written at the ORIGINAL page size (as displayed, rotation applied): the original page is
 * rendered from the bytes stored with the note at [PDF_RASTER_DPI] and drawn first, then the annotations are mapped from
 * note space back onto it (the inverse of the importer's fit, so pages imported before format v6, letterboxed into the
 * note's ratio, map correctly too).
 *
 * NOTE: the original page is a raster, not vector content: the platform [PdfDocument] cannot embed another PDF's pages,
 * and a PDF library was not added in this round (owner: finish quickly). Original text is therefore not selectable.
 */
internal object PdfExport {
    const val PDF_RASTER_DPI = 300f

    /** Pixel cap of one original-page raster (about 48 MB while drawn); A4 at 300 dpi is 8.7 MP. */
    const val MAX_RASTER_PIXELS = 12_000_000L

    fun write(renderer: NoteRenderer, out: OutputStream) {
        val sources = PdfSources(renderer)
        val pdf = PdfDocument()
        try {
            for (i in 0 until renderer.pageCount) {
                val spec = renderer.note.doc.pages.getOrNull(i)
                val pdfRef = spec?.pdf
                val background = pdfRef?.let { sources.open(it.media) }
                val original = if (background != null && pdfRef != null) runCatching { background.pageSize(pdfRef.pageIndex) }.getOrNull() else null
                val noteH = renderer.pageH(i)
                if (original != null && background != null && pdfRef != null) {
                    val (srcW, srcH) = original
                    val (pageWidth, pageHeight) = PdfPageGeometry.pageBox(srcW, srcH)
                    val page = pdf.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, i + 1).create())
                    val c = page.canvas
                    c.clipRect(0f, 0f, pageWidth.toFloat(), pageHeight.toFloat())
                    c.drawColor(Color.WHITE)
                    background.render(c, pdfRef.pageIndex, pageWidth.toFloat(), pageHeight.toFloat())
                    // Inverse of the importer's "fit original into note page": map note space onto the original page.
                    val t = PdfPageGeometry.fit(srcW, srcH, Limits.PAGE_WIDTH_PU, noteH)
                    c.save()
                    c.scale(pageWidth / srcW, pageHeight / srcH) // whole-point page box vs the exact original size
                    c.scale(1f / t.scale, 1f / t.scale)
                    c.translate(-t.dx, -t.dy)
                    renderer.drawPage(c, i, Layers(background = false, template = false))
                    c.restore()
                    pdf.finishPage(page)
                } else {
                    val ratio = noteH / renderer.pageW
                    val (pageWidth, pageHeight) = PdfPageGeometry.nativePageBox(spec?.size, ratio)
                    val page = pdf.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, i + 1).create())
                    val c = page.canvas
                    c.clipRect(0f, 0f, pageWidth.toFloat(), pageHeight.toFloat())
                    val scale = PdfPageGeometry.pointsPerPu(pageWidth)
                    c.scale(scale, pageHeight / noteH)
                    c.clipRect(0f, 0f, renderer.pageW, noteH)
                    renderer.drawPage(c, i)
                    pdf.finishPage(page)
                }
            }
            pdf.writeTo(out)
            out.flush()
        } finally {
            sources.close()
            pdf.close()
        }
    }

    /** Keeps one `PdfRenderer` per stored PDF media path for the duration of an export. */
    private class PdfSources(private val renderer: NoteRenderer) : AutoCloseable {
        private val open = HashMap<String, Source?>()

        fun open(media: String): Source? = open.getOrPut(media) {
            val bytes = renderer.note.media[media] ?: return@getOrPut null
            var file: File? = null
            try {
                file = File.createTempFile("pdf-export", ".pdf")
                file.writeBytes(bytes)
                Source(file)
            } catch (e: Exception) {
                file?.delete()
                null
            }
        }

        override fun close() {
            open.values.forEach { runCatching { it?.close() } }
            open.clear()
        }
    }

    private class Source(private val file: File) : AutoCloseable {
        private val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        private val renderer = try { PdfRenderer(fd) } catch (e: Exception) { fd.close(); throw e }

        fun pageSize(index: Int): Pair<Float, Float> = renderer.openPage(index).use { it.width.toFloat() to it.height.toFloat() }

        fun render(canvas: Canvas, index: Int, widthPt: Float, heightPt: Float) {
            val (w, h) = PdfPageGeometry.rasterSize(widthPt, heightPt, PDF_RASTER_DPI, MAX_RASTER_PIXELS)
            val bitmap = try { Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) } catch (e: OutOfMemoryError) { return }
            try {
                Canvas(bitmap).drawColor(Color.WHITE)
                renderer.openPage(index).use { page ->
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }
                canvas.drawBitmap(bitmap, null, RectF(0f, 0f, widthPt, heightPt), Paint(Paint.FILTER_BITMAP_FLAG))
            } finally {
                bitmap.recycle()
            }
        }

        override fun close() {
            runCatching { renderer.close() }
            runCatching { fd.close() }
            file.delete()
        }
    }
}
