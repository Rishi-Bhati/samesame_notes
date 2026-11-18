package org.samesame.notes.feature.composer.pdf

import android.graphics.Bitmap
import androidx.collection.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.samesame.notes.core.data.pdf.PdfTileRenderer
import org.samesame.notes.core.model.Limits
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Key identifying a single 512x512 tile.
 * [lod] is the quantized level of detail / zoom tier.
 */
data class PdfTileKey(
    val media: String,
    val pageIndex: Int,
    val lod: Int,
    val col: Int,
    val row: Int,
)

/**
 * Bounded LRU cache with a pool only for destinations that never reached the canvas.
 *
 * Implements the no-flash policy: base 2160px PNG sits underneath; at zoom > 1.25x high-res tiles
 * render asynchronously and paint on top. Stale tile render jobs cancel on scroll.
 */
class PdfTileCache(
    maxBytes: Int = Limits.PDF_TILE_CACHE_MAX_BYTES.toInt(),
) {
    private val pool = ConcurrentLinkedQueue<Bitmap>()
    private val activeJobs = ConcurrentHashMap<PdfTileKey, Job>()

    /** Bumps whenever a new tile finishes loading, triggering a draw phase invalidation. */
    var version: Int by mutableIntStateOf(0)
        private set

    private val lru = object : LruCache<PdfTileKey, Bitmap>(maxBytes) {
        override fun sizeOf(key: PdfTileKey, value: Bitmap): Int = value.byteCount

    }

    val byteCount: Int get() = lru.size()
    val evictionCount: Int get() = lru.evictionCount()
    val hitCount: Int get() = lru.hitCount()
    val missCount: Int get() = lru.missCount()
    val poolSize: Int get() = pool.size

    /** Forces a redraw (a renderer became available). */
    fun touch() { version++ }

    fun get(key: PdfTileKey): Bitmap? = lru.get(key)

    /** Publishing transfers ownership to the canvas; eviction must let GC reclaim it. */
    fun put(key: PdfTileKey, bitmap: Bitmap) {
        lru.put(key, bitmap)
    }

    fun remove(key: PdfTileKey): Bitmap? = lru.remove(key)

    /**
     * Obtains a recyclable [tileSize]x[tileSize] ARGB_8888 bitmap from the pool or allocates a new one.
     */
    fun obtainBitmap(tileSize: Int = Limits.PDF_TILE_SIZE_PX): Bitmap {
        while (true) {
            val b = pool.poll() ?: break
            if (!b.isRecycled && b.width == tileSize && b.height == tileSize && b.config == Bitmap.Config.ARGB_8888) {
                return b
            }
        }
        return Bitmap.createBitmap(tileSize, tileSize, Bitmap.Config.ARGB_8888)
    }

    /**
     * Requests rendering of a missing tile if not already in-flight.
     */
    fun requestTile(
        scope: CoroutineScope,
        renderer: PdfTileRenderer,
        key: PdfTileKey,
        scale: Float,
        widthPt: Float,
        tileSize: Int = Limits.PDF_TILE_SIZE_PX,
        onRendered: () -> Unit = {},
    ) {
        if (lru.get(key) != null || activeJobs.containsKey(key)) return

        val job = scope.launch {
            val bitmap = obtainBitmap(tileSize)
            val success = renderer.renderTile(
                pageIndex = key.pageIndex,
                col = key.col,
                row = key.row,
                scale = scale,
                widthPt = widthPt,
                tileSize = tileSize,
                destination = bitmap,
            )
            if (success) {
                put(key, bitmap)
                version++
                onRendered()
            } else {
                if (!bitmap.isRecycled) pool.offer(bitmap)
            }
        }

        activeJobs[key] = job
        job.invokeOnCompletion { activeJobs.remove(key) }
    }

    /**
     * Cancels any pending tile rendering jobs that are not in [neededKeys] (e.g. scrolled out of view).
     */
    fun cancelStale(neededKeys: Set<PdfTileKey>) {
        val iter = activeJobs.entries.iterator()
        while (iter.hasNext()) {
            val (key, job) = iter.next()
            if (key !in neededKeys) {
                job.cancel(CancellationException("Tile no longer visible"))
                iter.remove()
            }
        }
    }

    /**
     * Clears all cached tiles and recycles pooled bitmaps.
     */
    fun clear() {
        for ((_, job) in activeJobs) {
            job.cancel(CancellationException("Cache cleared"))
        }
        activeJobs.clear()
        lru.evictAll()
        while (true) {
            val b = pool.poll() ?: break
            if (!b.isRecycled) runCatching { b.recycle() }
        }
        version++
    }
}
