package org.samesame.notes.core.data.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.samesame.notes.core.model.Limits
import java.io.File

/**
 * Thread-safe tiled PDF renderer using platform [PdfRenderer] on a single-threaded dispatcher.
 * [PdfRenderer] requires exclusive single-threaded access and only one page open at a time.
 */
class PdfTileRenderer private constructor(
    val file: File,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
) {

    private val fd: ParcelFileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer: PdfRenderer = try { PdfRenderer(fd) } catch (e: Throwable) { fd.close(); throw e }
    private val dimensions: List<Pair<Float, Float>> = try {
        List(renderer.pageCount) { i -> renderer.openPage(i).use { it.width.toFloat() to it.height.toFloat() } }
    } catch (e: Throwable) {
        runCatching { renderer.close() }
        runCatching { fd.close() }
        throw e
    }

    @Volatile
    private var closed = false

    val pageCount: Int get() = if (closed) 0 else dimensions.size

    /** Cached during opening, so canvas lookups never touch the native renderer. */
    fun pageSize(pageIndex: Int): Pair<Float, Float> = dimensions.getOrNull(pageIndex) ?: PageDimensionsFallback

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

    /** Closing and rendering use the same serialized dispatcher, even during scope cancellation. */
    suspend fun close() {
        closed = true
        withContext(NonCancellable + dispatcher) {
            if (nativeClosed) return@withContext
            nativeClosed = true
            runCatching { renderer.close() }
            runCatching { fd.close() }
        }
    }

    private var nativeClosed = false // touched only on dispatcher

    companion object {
        private val PageDimensionsFallback = 595f to 842f

        suspend fun open(file: File, dispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)): PdfTileRenderer {
            var opened: PdfTileRenderer? = null
            try {
                return withContext(dispatcher) { PdfTileRenderer(file, dispatcher).also { opened = it } }
            } catch (e: CancellationException) {
                opened?.close()
                throw e
            }
        }
    }
}
