package org.samesame.notes.feature.composer.pdf

import android.graphics.Bitmap
import androidx.collection.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.samesame.notes.core.data.pdf.PdfTileRenderer
import org.samesame.notes.core.data.pdf.PdfCoordinates
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
    val transform: PdfCoordinates.PageTransform? = null,
)

/**
 * Bounded LRU cache with a pool only for destinations that never reached the canvas.
 *
 * Implements the no-flash policy: base 2160px PNG sits underneath; at zoom > 1.25x high-res tiles
 * render asynchronously and paint on top. Stale tile render jobs cancel on scroll.
 */
class PdfTileCache(
    maxBytes: Int = Limits.PDF_TILE_CACHE_MAX_BYTES.toInt(),
    private val allocate: (Int) -> Bitmap = { Bitmap.createBitmap(it, it, Bitmap.Config.ARGB_8888) },
) {
    private val pool = ConcurrentLinkedQueue<Bitmap>()
    private val tileBytes = Limits.PDF_TILE_SIZE_PX * Limits.PDF_TILE_SIZE_PX * 4L
    private val destinations = Semaphore((Limits.PDF_TILE_IN_FLIGHT_MAX_BYTES / tileBytes).toInt())
    private var generation = 0
    val pendingCount: Int get() = activeJobs.size
    val reservedBytes: Long get() = (Limits.PDF_TILE_IN_FLIGHT_MAX_BYTES / tileBytes - destinations.availablePermits) * tileBytes
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
        return allocate(tileSize)
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
        requestTile(scope, key, tileSize, onRendered) { bitmap ->
            renderer.renderTile(key.pageIndex, key.col, key.row, scale, widthPt, tileSize, bitmap,
                key.transform ?: PdfCoordinates.PageTransform(PdfCoordinates.puPerPt(widthPt)))
        }
    }

    internal fun requestTile(
        scope: CoroutineScope,
        key: PdfTileKey,
        tileSize: Int = Limits.PDF_TILE_SIZE_PX,
        onRendered: () -> Unit = {},
        render: suspend (Bitmap) -> Boolean,
    ) {
        // One permit reserves a maximum-size destination before any allocation.
        if (tileSize !in 1..Limits.PDF_TILE_SIZE_PX) return
        val job: Job
        synchronized(activeJobs) {
            if (lru.get(key) != null || activeJobs.containsKey(key) || activeJobs.size >= Limits.PDF_TILE_MAX_REQUESTS) return
            val requestedGeneration = generation
            job = scope.launch(start = CoroutineStart.LAZY) {
                destinations.withPermit {
                    var bitmap: Bitmap? = null
                    var published = false
                    try {
                        bitmap = obtainBitmap(tileSize)
                        val success = render(bitmap)
                        ensureActive()
                        if (success && generation == requestedGeneration) {
                            put(key, bitmap)
                            published = true
                            version++
                            onRendered()
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: OutOfMemoryError) {
                        // Leave the base page visible when a tile cannot be allocated.
                    } catch (_: Exception) {
                        // A failed tile can be retried on the next draw.
                    } finally {
                        bitmap?.takeIf { !published && !it.isRecycled }?.let {
                            if (generation == requestedGeneration && pool.size * tileBytes < Limits.PDF_TILE_POOL_MAX_BYTES) pool.offer(it)
                            else it.recycle()
                        }
                    }
                }
            }
            activeJobs[key] = job
            job.invokeOnCompletion { activeJobs.remove(key, job) }
        }
        job.start()
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
            }
        }
    }

    /**
     * Clears all cached tiles and recycles pooled bitmaps.
     */
    fun clear() {
        generation++
        for ((_, job) in activeJobs) {
            job.cancel(CancellationException("Cache cleared"))
        }
        lru.evictAll()
        while (true) {
            val b = pool.poll() ?: break
            if (!b.isRecycled) runCatching { b.recycle() }
        }
        version++
    }
}
