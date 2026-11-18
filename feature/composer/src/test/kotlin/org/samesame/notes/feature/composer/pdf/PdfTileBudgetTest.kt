package org.samesame.notes.feature.composer.pdf

import android.graphics.Bitmap
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.samesame.notes.core.model.Limits

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PdfTileBudgetTest {
    @Test fun `queue and destinations stay bounded through cancellation`() = runTest {
        var allocations = 0
        val cache = PdfTileCache(allocate = { allocations++; Bitmap.createBitmap(it, it, Bitmap.Config.ARGB_8888) })
        repeat(1000) { col ->
            cache.requestTile(this, PdfTileKey("pdf", 0, 1, col, 0)) { awaitCancellation() }
        }
        assertThat(cache.pendingCount).isEqualTo(Limits.PDF_TILE_MAX_REQUESTS)
        runCurrent()
        assertThat(allocations).isEqualTo(4)
        assertThat(cache.reservedBytes).isEqualTo(Limits.PDF_TILE_IN_FLIGHT_MAX_BYTES)
        cache.clear()
        runCurrent()
        assertThat(cache.pendingCount).isEqualTo(0)
        assertThat(cache.reservedBytes).isEqualTo(0)
        assertThat(cache.poolSize).isEqualTo(0)
    }

    @Test fun `allocation failure releases capacity without escaping to UI`() = runTest {
        var fail = true
        val cache = PdfTileCache(allocate = {
            if (fail) throw OutOfMemoryError("test")
            Bitmap.createBitmap(it, it, Bitmap.Config.ARGB_8888)
        })
        val key = PdfTileKey("pdf", 0, 1, 0, 0)
        cache.requestTile(this, key) { true }
        runCurrent()
        assertThat(cache.get(key)).isNull()
        assertThat(cache.reservedBytes).isEqualTo(0)
        assertThat(cache.pendingCount).isEqualTo(0)
        fail = false
        cache.requestTile(this, key) { true }
        runCurrent()
        assertThat(cache.get(key)).isNotNull()
        assertThat(cache.reservedBytes).isEqualTo(0)
    }

    @Test fun `failed destinations can be pooled without publishing`() = runTest {
        val cache = PdfTileCache()
        var failed: Bitmap? = null
        cache.requestTile(this, PdfTileKey("pdf", 0, 1, 0, 0)) { failed = it; false }
        runCurrent()
        assertThat(cache.poolSize).isEqualTo(1)
        val obtained = cache.obtainBitmap()
        assertThat(obtained).isSameInstanceAs(failed)
        assertThat(cache.byteCount).isEqualTo(0)
    }
}
