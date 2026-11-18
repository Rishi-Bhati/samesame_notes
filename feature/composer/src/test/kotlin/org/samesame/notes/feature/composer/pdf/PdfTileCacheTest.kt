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
    fun lruEvictionAt48MiBOffersBitmapsToPool() {
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
        assertThat(cache.poolSize).isEqualTo(2)

        // Oldest keys (0 and 1) should be evicted
        val key0 = PdfTileKey("media/doc.pdf", pageIndex = 0, lod = 1, col = 0, row = 0)
        val key1 = PdfTileKey("media/doc.pdf", pageIndex = 0, lod = 1, col = 1, row = 0)
        val key49 = PdfTileKey("media/doc.pdf", pageIndex = 0, lod = 1, col = 49, row = 0)

        assertThat(cache.get(key0)).isNull()
        assertThat(cache.get(key1)).isNull()
        assertThat(cache.get(key49)).isNotNull()
    }

    @Test
    fun obtainBitmapReusesBitmapsFromPool() {
        val cache = PdfTileCache(maxBytes = Limits.PDF_TILE_CACHE_MAX_BYTES.toInt())
        val tileSize = Limits.PDF_TILE_SIZE_PX

        // Insert 49 tiles to trigger 1 eviction into pool
        for (i in 0 until 49) {
            val key = PdfTileKey("media/doc.pdf", pageIndex = 0, lod = 1, col = i, row = 0)
            cache.put(key, Bitmap.createBitmap(tileSize, tileSize, Bitmap.Config.ARGB_8888))
        }

        assertThat(cache.poolSize).isEqualTo(1)

        val recycledBitmap = cache.obtainBitmap(tileSize)
        assertThat(recycledBitmap).isNotNull()
        assertThat(recycledBitmap.width).isEqualTo(tileSize)
        assertThat(recycledBitmap.height).isEqualTo(tileSize)
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

        assertThat(cache.poolSize).isEqualTo(2)

        cache.clear()
        assertThat(cache.byteCount).isEqualTo(0)
        assertThat(cache.poolSize).isEqualTo(0)
    }
}
