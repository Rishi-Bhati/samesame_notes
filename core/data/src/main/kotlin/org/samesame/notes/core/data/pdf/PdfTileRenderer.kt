package org.samesame.notes.core.data.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.samesame.notes.core.model.Limits
import java.io.Closeable
import java.io.File

/**
 * Thread-safe tiled PDF renderer using platform [PdfRenderer] on a single-threaded dispatcher.
 * [PdfRenderer] requires exclusive single-threaded access and only one page open at a time.
 */
class PdfTileRenderer(
    val file: File,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
) : Closeable {

    private val fd: ParcelFileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer: PdfRenderer = PdfRenderer(fd)

    @Volatile
    private var closed = false

    val pageCount: Int
        get() = if (closed) 0 else runCatching { renderer.pageCount }.getOrDefault(0)

    /**
     * Reports displayed page dimensions in PDF points (width to height).
     */
    fun pageSize(pageIndex: Int): Pair<Float, Float> {
        if (closed || pageIndex < 0 || pageIndex >= pageCount) {
            return PageDimensionsFallback
        }
        return runCatching {
            renderer.openPage(pageIndex).use { page ->
                page.width.toFloat() to page.height.toFloat()
            }
        }.getOrDefault(PageDimensionsFallback)
    }

    /**
     * Renders a single square tile of [tileSize] into [destination] at [scale] (view scale).
     */
    suspend fun renderTile(
        pageIndex: Int,
        col: Int,
        row: Int,
        scale: Float,
        widthPt: Float,
        tileSize: Int = Limits.PDF_TILE_SIZE_PX,
        destination: Bitmap,
    ): Boolean = withContext(dispatcher) {
        if (closed || destination.isRecycled || scale <= 0f || widthPt <= 0f) return@withContext false
        if (pageIndex < 0 || pageIndex >= pageCount) return@withContext false

        try {
            renderer.openPage(pageIndex).use { page ->
                val matrix = PdfCoordinates.tileMatrix(col, row, scale, widthPt, tileSize)
                destination.eraseColor(Color.WHITE)
                page.render(destination, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { renderer.close() }
        runCatching { fd.close() }
    }

    companion object {
        private val PageDimensionsFallback = 595f to 842f

        fun openOrNull(file: File): PdfTileRenderer? = runCatching { PdfTileRenderer(file) }.getOrNull()
    }
}
