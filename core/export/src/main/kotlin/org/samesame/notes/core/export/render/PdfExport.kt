package org.samesame.notes.core.export.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import org.samesame.notes.core.model.Limits
import java.io.File
import java.io.OutputStream
import java.io.IOException
import org.samesame.notes.core.model.document.PdfMarkupKind
import org.samesame.notes.core.model.document.PdfTextMarkup

/**
 * PDF through [PdfDocument]. Text and ink are drawn as vectors by [NoteRenderer]; every page is written at its own size.
 *
 * Pages imported from a PDF are written at the ORIGINAL page size (as displayed, rotation applied): the original page is
 * rendered from the bytes stored with the note at [PDF_RASTER_DPI] and drawn first, then the annotations are mapped from
 * note space back onto it (the inverse of the importer's fit, so pages imported before format v6, letterboxed into the
 * note's ratio, map correctly too).
 *
 * This is the FALLBACK path: the original page is a raster here and its text is not selectable. [PdfVectorExport] is tried first
 * and keeps the original page content.
 */
internal object PdfExport {
    const val PDF_RASTER_DPI = 300f

    /** Pixel cap of one original-page raster (about 48 MB while drawn); A4 at 300 dpi is 8.7 MP. */
    const val MAX_RASTER_PIXELS = 12_000_000L

    /** Choose a complete staged PDF before touching the caller's output. */
    fun write(renderer: NoteRenderer, out: OutputStream) = writeStaged(
        out,
        vector = { PdfVectorExport.write(renderer, it) },
        fallback = { file -> file.outputStream().use { writeRaster(renderer, it) } },
    )

    internal fun writeStaged(out: OutputStream, vector: (File) -> Unit, fallback: (File) -> Unit) {
        val staged = File.createTempFile("pdf-export", ".pdf")
        try {
            try {
                vector(staged)
            } catch (e: Exception) {
                Log.w("PdfExport", "vector export failed, using the raster fallback", e)
                fallback(staged)
            } catch (e: OutOfMemoryError) {
                Log.w("PdfExport", "vector export ran out of memory, using the raster fallback", e)
                fallback(staged)
            } catch (e: LinkageError) {
                Log.w("PdfExport", "vector export unavailable, using the raster fallback", e)
                fallback(staged)
            }
            staged.inputStream().use { it.copyTo(out) }
            out.flush()
        } finally {
            staged.delete()
        }
    }

    /** The 0.4 export: original pages as 300 dpi pictures under vector ink. Kept as the fallback. */
    fun writeRaster(renderer: NoteRenderer, out: OutputStream) {
        val sources = PdfSources(renderer)
        val pdf = PdfDocument()
        try {
            for (i in 0 until renderer.pageCount) {
                val spec = renderer.note.doc.pages.getOrNull(i)
                val pdfRef = spec?.pdf
                val background = pdfRef?.let { sources.open(it.media) }
                val original = background?.pageSize(pdfRef!!.pageIndex)
                val noteH = renderer.unrotatedPageH(i)
                val rotation = spec?.normalizedRotation ?: 0
                if (original != null && background != null && pdfRef != null) {
                    val (srcW, srcH) = original
                    val (pageWidth, pageHeight) = PdfPageGeometry.pageBox(srcW, srcH)
                    val page = pdf.startPage(PdfDocument.PageInfo.Builder(
                        if (rotation % 180 != 0) pageHeight else pageWidth,
                        if (rotation % 180 != 0) pageWidth else pageHeight, i + 1,
                    ).create())
                    val c = page.canvas
                    rotatePage(c, rotation, pageWidth.toFloat(), pageHeight.toFloat())
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
                    drawMarkup(c, spec.textMarkup)
                    c.restore()
                    pdf.finishPage(page)
                } else {
                    val ratio = noteH / renderer.pageW
                    val (pageWidth, pageHeight) = PdfPageGeometry.nativePageBox(spec?.size, ratio)
                    val page = pdf.startPage(PdfDocument.PageInfo.Builder(
                        if (rotation % 180 != 0) pageHeight else pageWidth,
                        if (rotation % 180 != 0) pageWidth else pageHeight, i + 1,
                    ).create())
                    val c = page.canvas
                    rotatePage(c, rotation, pageWidth.toFloat(), pageHeight.toFloat())
                    c.clipRect(0f, 0f, pageWidth.toFloat(), pageHeight.toFloat())
                    val scale = PdfPageGeometry.pointsPerPu(pageWidth)
                    c.scale(scale, pageHeight / noteH)
                    c.clipRect(0f, 0f, renderer.pageW, noteH)
                    renderer.drawPage(c, i)
                    drawMarkup(c, spec?.textMarkup.orEmpty())
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

    internal fun rotatePage(canvas: Canvas, rotation: Int, width: Float, height: Float) {
        when (rotation) {
            90 -> { canvas.translate(height, 0f); canvas.rotate(90f) }
            180 -> { canvas.translate(width, height); canvas.rotate(180f) }
            270 -> { canvas.translate(0f, width); canvas.rotate(270f) }
        }
    }

    internal fun drawMarkup(canvas: Canvas, markups: List<PdfTextMarkup>) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        for (markup in markups.take(Limits.MAX_MARKUPS_PER_PAGE)) {
            paint.color = markup.color.toInt()
            if (markup.kind == PdfMarkupKind.Highlight) {
                paint.alpha = minOf(paint.alpha, 90)
                paint.style = Paint.Style.FILL
            } else {
                paint.style = Paint.Style.STROKE
            }
            for (q in markup.quads.take(Limits.MAX_QUADS_PER_MARKUP)) {
                paint.strokeWidth = maxOf(1f, (q.y3 - q.y1) * 0.07f)
                val path = Path()
                when (markup.kind) {
                    PdfMarkupKind.Highlight -> {
                        path.moveTo(q.x1, q.y1); path.lineTo(q.x2, q.y2)
                        path.lineTo(q.x4, q.y4); path.lineTo(q.x3, q.y3); path.close()
                    }
                    PdfMarkupKind.Underline -> { path.moveTo(q.x3, q.y3); path.lineTo(q.x4, q.y4) }
                    PdfMarkupKind.Strike -> {
                        path.moveTo((q.x1 + q.x3) / 2, (q.y1 + q.y3) / 2)
                        path.lineTo((q.x2 + q.x4) / 2, (q.y2 + q.y4) / 2)
                    }
                }
                canvas.drawPath(path, paint)
            }
        }
    }

    /** Keeps one `PdfRenderer` per stored PDF media path for the duration of an export. */
    private class PdfSources(private val renderer: NoteRenderer) : AutoCloseable {
        private val open = HashMap<String, Source?>()

        fun open(media: String): Source = open.getOrPut(media) {
            val bytes = renderer.note.media[media] ?: throw IOException("Missing PDF source: $media")
            val file = File.createTempFile("pdf-export", ".pdf")
            try {
                file.writeBytes(bytes)
                Source(file)
            } catch (e: Exception) {
                file.delete()
                throw IOException("Cannot render PDF source: $media", e)
            }
        }!!

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
            val bitmap = try { Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) } catch (e: OutOfMemoryError) { throw IOException("Not enough memory to render PDF page ${index + 1}", e) }
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
