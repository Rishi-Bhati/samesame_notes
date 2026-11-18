package org.samesame.notes.feature.composer.pdf

import android.util.LruCache
import org.samesame.notes.core.model.Limits
import org.samesame.notes.core.model.pdf.PdfPageText

/** Conservative heap estimate includes glyphs, geometry, words, lines and their copied strings. */
internal class PdfTextCache(maxBytes: Int = Limits.PDF_TEXT_CACHE_MAX_BYTES) : LruCache<String, PdfPageText>(maxBytes) {
    override fun sizeOf(key: String, value: PdfPageText): Int {
        val words = value.lines.sumOf { line -> line.words.sumOf { 128L + it.text.length * 2L } }
        val lines = value.lines.sumOf { 96L + it.text.length * 2L }
        val estimate = 256L + value.text.length * 2L + value.glyphs.size * 96L + words + lines + value.links.size * 256L
        return maxOf(Limits.PDF_TEXT_CACHE_MAX_BYTES / Limits.PDF_TEXT_CACHE_MAX_PAGES, estimate.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
    }
}
