package org.samesame.notes.feature.composer.pdf

import android.graphics.Bitmap
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.samesame.notes.core.model.Limits

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PdfTileCacheTest {

    @Test
    fun lruEvictionDoesNotPoolPublishedBitmaps() {
        val cache = PdfTileCache(maxBytes = Limits.PDF_TILE_CACHE_MAX_BYTES.toInt())
        val tileSize = Limits.PDF_TILE_SIZE_PX // 512

        // Each 512x512 ARGB_8888 bitmap is 512 * 512 * 4 = 1,048,576 bytes (1 MiB).
        // 48 MiB fits 48 tiles.
        for (i in 0 until 50) {
            val key = PdfTileKey("media/doc.pdf", pageIndex = 0, lod = 1, col = i, row = 0)
            val bmp = Bitmap.createBitmap(tileSize, tileSize, Bitmap.Config.ARGB_8888)
            cache.put(key, bmp)
        }

        // 50 inserted, 48 max capacity -> 2 evictions
        assertThat(cache.evictionCount).isEqualTo(2)
        assertThat(cache.poolSize).isEqualTo(0)

        // Oldest keys (0 and 1) should be evicted
        val key0 = PdfTileKey("media/doc.pdf", pageIndex = 0, lod = 1, col = 0, row = 0)
        val key1 = PdfTileKey("media/doc.pdf", pageIndex = 0, lod = 1, col = 1, row = 0)
        val key49 = PdfTileKey("media/doc.pdf", pageIndex = 0, lod = 1, col = 49, row = 0)

        assertThat(cache.get(key0)).isNull()
        assertThat(cache.get(key1)).isNull()
        assertThat(cache.get(key49)).isNotNull()
    }

    @Test
    fun evictedAndClearedBitmapRemainsSafeForCanvas() {
        val cache = PdfTileCache(maxBytes = 512 * 512 * 4)
        val key = PdfTileKey("media/doc.pdf", 0, 1, 0, 0)
        val published = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        published.eraseColor(android.graphics.Color.RED)
        cache.put(key, published)
        val canvasReference = cache.get(key)!!
        cache.put(key.copy(col = 1), Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888))
        val destination = cache.obtainBitmap()
        destination.eraseColor(android.graphics.Color.BLUE)
        cache.clear()
        assertThat(destination).isNotSameInstanceAs(canvasReference)
        assertThat(canvasReference.isRecycled).isFalse()
        assertThat(canvasReference.getPixel(0, 0)).isEqualTo(android.graphics.Color.RED)
        assertThat(cache.poolSize).isEqualTo(0)
    }

    @Test
    fun clearEvictsAllAndRecyclesPooledBitmaps() {
        val cache = PdfTileCache(maxBytes = Limits.PDF_TILE_CACHE_MAX_BYTES.toInt())
        val tileSize = Limits.PDF_TILE_SIZE_PX

        for (i in 0 until 50) {
            val key = PdfTileKey("media/doc.pdf", pageIndex = 0, lod = 1, col = i, row = 0)
            cache.put(key, Bitmap.createBitmap(tileSize, tileSize, Bitmap.Config.ARGB_8888))
        }

        assertThat(cache.poolSize).isEqualTo(0)

        cache.clear()
        assertThat(cache.byteCount).isEqualTo(0)
        assertThat(cache.poolSize).isEqualTo(0)
    }
}
