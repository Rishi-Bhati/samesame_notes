package org.samesame.notes.feature.files.viewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Closeable
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Pure sizing rules of the PDF viewer (JVM-tested). */
internal object PdfLayout {
    /** One rendered page stays under about 32 MB as ARGB. */
    const val MAX_PAGE_PIXELS = 8_000_000L
    const val MIN_SCALE = 1f
    const val MAX_SCALE = 4f
    const val DOUBLE_TAP_SCALE = 2f

    /**
     * The bitmap size for a [pageW] x [pageH] page shown [targetWidth] px wide: the page's aspect ratio, shrunk when it
     * would go over [maxPixels]. Null for a page without a size.
     */
    fun renderSize(pageW: Int, pageH: Int, targetWidth: Int, maxPixels: Long = MAX_PAGE_PIXELS): Pair<Int, Int>? {
        if (pageW <= 0 || pageH <= 0 || targetWidth <= 0) return null
        var w = targetWidth.toDouble()
        var h = w * pageH / pageW
        if (w * h > maxPixels) {
            val k = sqrt(maxPixels / (w * h))
            w *= k; h *= k
        }
        return w.roundToInt().coerceAtLeast(1) to h.roundToInt().coerceAtLeast(1)
    }

    /** Pages are re-rendered sharper in whole steps once the zoom settles: 1x up to 1.3, then 2x, then 3x. */
    fun renderStep(scale: Float): Int = when {
        scale <= 1.3f -> 1
        scale <= 2.3f -> 2
        else -> 3
    }

    /** The horizontal offset range of content [scale] times the viewport [width]: from -(extra width) to 0. */
    fun clampOffset(offset: Float, width: Float, scale: Float): Float {
        val extra = width * scale - width
        return if (extra <= 0f) 0f else offset.coerceIn(-extra, 0f)
    }

    /** The new horizontal offset when zooming from [scale] to [newScale] around screen x [cx], keeping that point still. */
    fun zoomOffset(offset: Float, cx: Float, scale: Float, newScale: Float, width: Float): Float =
        clampOffset(cx - (cx - offset) * newScale / scale, width, newScale)
}

internal sealed interface PdfOpen {
    data class Opened(val document: PdfPages) : PdfOpen
    data object Protected : PdfOpen
    data object Failed : PdfOpen
}

/**
 * Read-only pages of a stored PDF over the platform renderer. The renderer allows one open page at a time, so every
 * call runs on one thread. A damaged page renders to null instead of failing the viewer.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class PdfPages private constructor(private val fd: ParcelFileDescriptor, private val renderer: PdfRenderer) : Closeable {
    private val thread = Dispatchers.IO.limitedParallelism(1)
    @Volatile private var closed = false
    val pageCount: Int = renderer.pageCount

    /** The page's displayed size in points (its own rotation applied), or null when it can't be read. */
    suspend fun pageSize(index: Int): Pair<Int, Int>? = withContext(thread) {
        if (closed) return@withContext null
        try { renderer.openPage(index).use { it.width to it.height } } catch (e: Exception) { null }
    }

    suspend fun render(index: Int, widthPx: Int): Bitmap? = withContext(thread) {
        if (closed) return@withContext null
        try {
            renderer.openPage(index).use { page ->
                val (w, h) = PdfLayout.renderSize(page.width, page.height, widthPx) ?: return@withContext null
                val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap
            }
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    override fun close() {
        closed = true
        // After any render in flight: closing the renderer under an open page throws.
        CoroutineScope(thread).launch {
            runCatching { renderer.close() }
            runCatching { fd.close() }
        }
    }

    companion object {
        /** Opens the stored copy read-only through the library provider. A password-protected file is reported, not opened. */
        fun open(context: Context, uri: Uri): PdfOpen {
            val fd = try {
                context.contentResolver.openFileDescriptor(uri, "r") ?: return PdfOpen.Failed
            } catch (e: Exception) {
                return PdfOpen.Failed
            }
            return try {
                PdfOpen.Opened(PdfPages(fd, PdfRenderer(fd)))
            } catch (e: SecurityException) {
                runCatching { fd.close() }; PdfOpen.Protected
            } catch (e: Exception) {
                runCatching { fd.close() }; PdfOpen.Failed
            }
        }
    }
}
