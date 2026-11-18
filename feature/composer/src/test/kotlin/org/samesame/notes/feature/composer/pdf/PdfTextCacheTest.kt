package org.samesame.notes.feature.composer.pdf

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.samesame.notes.core.model.pdf.PdfGlyph
import org.samesame.notes.core.model.pdf.PdfPageText
import org.samesame.notes.core.model.pdf.RectPt

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PdfTextCacheTest {
    @Test fun largePagesEvictByEstimatedBytes() {
        val cache = PdfTextCache(maxBytes = 1024 * 1024)
        fun page(index: Int) = PdfPageText(index, "a".repeat(8000),
            List(8000) { PdfGlyph('a', RectPt(0f, 0f, 1f, 1f), it) }, emptyList(), emptyList())
        cache.put("one", page(0))
        cache.put("two", page(1))
        assertThat(cache.size()).isAtMost(1024 * 1024)
        assertThat(cache.get("one")).isNull()
        assertThat(cache.get("two")).isNotNull()
    }
}
