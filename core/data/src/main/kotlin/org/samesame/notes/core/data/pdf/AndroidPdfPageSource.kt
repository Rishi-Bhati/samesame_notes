package org.samesame.notes.core.data.pdf

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/** [PdfPageSource] over the platform `PdfRenderer`. Password-protected files are refused. */
class AndroidPdfPageSource private constructor(
    private val fd: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
) : PdfPageSource {
    override val pageCount: Int get() = renderer.pageCount

    override fun pageSize(index: Int): Pair<Float, Float> = guarded {
        // PdfRenderer reports the displayed size: a page with /Rotate 90 or 270 comes back with its sides swapped.
        renderer.openPage(index).use { it.width.toFloat() to it.height.toFloat() }
    }

    override fun renderPng(index: Int, widthPx: Int, heightPx: Int): ByteArray = guarded {
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        try {
            Canvas(bitmap).drawColor(Color.WHITE)
            renderer.openPage(index).use { page ->
                val (scale, dx, dy) = PdfNoteBuilder.fit(page.width.toFloat(), page.height.toFloat(), widthPx.toFloat(), heightPx.toFloat())
                val matrix = Matrix().apply { setScale(scale, scale); postTranslate(dx, dy) }
                // The page's own rotation is applied by the renderer.
                page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            }
            val out = ByteArrayOutputStream(256 * 1024)
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    /** A damaged page (or one too large to render) fails the import cleanly instead of crashing the app. */
    private inline fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (e: PdfImportException) {
        throw e
    } catch (e: SecurityException) {
        throw PdfImportException(PdfImportError.PasswordProtected, e)
    } catch (e: RuntimeException) {
        throw PdfImportException(PdfImportError.Corrupt, e)
    } catch (e: OutOfMemoryError) {
        throw PdfImportException(PdfImportError.Corrupt, e)
    }

    override fun close() {
        runCatching { renderer.close() }
        runCatching { fd.close() }
    }

    companion object {
        fun open(file: File): AndroidPdfPageSource {
            val fd = try {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            } catch (e: IOException) {
                throw PdfImportException(PdfImportError.Corrupt, e)
            }
            return try {
                AndroidPdfPageSource(fd, PdfRenderer(fd))
            } catch (e: SecurityException) {
                fd.close(); throw PdfImportException(PdfImportError.PasswordProtected, e)
            } catch (e: IOException) {
                fd.close(); throw PdfImportException(PdfImportError.Corrupt, e)
            } catch (e: RuntimeException) {
                fd.close(); throw PdfImportException(PdfImportError.Corrupt, e)
            }
        }
    }
}
