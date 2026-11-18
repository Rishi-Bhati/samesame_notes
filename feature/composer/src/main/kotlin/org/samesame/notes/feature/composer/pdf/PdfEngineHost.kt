package org.samesame.notes.feature.composer.pdf

import android.content.Context
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.samesame.notes.core.data.pdf.PdfTextExtractor
import org.samesame.notes.core.data.pdf.PdfTextSource
import org.samesame.notes.core.data.pdf.PdfTileRenderer
import org.samesame.notes.core.model.Limits
import org.samesame.notes.core.model.document.PdfBackground
import org.samesame.notes.core.model.pdf.PdfPageText
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns everything the PDF engine needs for one open note: one [PdfTileRenderer] per source PDF (platform renderer, a
 * single thread each), the tile cache, a bounded per-page text cache filled off the main thread, and the selection.
 * Draw-phase lookups never block: they return null and ask for the work, then [version] bumps and the canvas redraws.
 */
class PdfEngineHost(
    context: Context,
    private val scope: CoroutineScope,
    private val resolveFile: suspend (media: String) -> File?,
) {
    private val appContext = context.applicationContext
    val tileCache = PdfTileCache()
    val selection = PdfSelectionState()

    /** Bumps when a text page arrived (redraw, re-run a pending search). */
    var version: Int by mutableIntStateOf(0)
        private set

    private val renderers = ConcurrentHashMap<String, PdfTileRenderer>()
    private val opening = ConcurrentHashMap.newKeySet<String>()
    private val failed = ConcurrentHashMap.newKeySet<String>()
    private val textCache = LruCache<String, PdfPageText>(Limits.PDF_TEXT_CACHE_MAX_PAGES)
    private val loadingText = ConcurrentHashMap.newKeySet<String>()
    private val docs = HashMap<String, PdfTextSource>() // touched only on the extraction thread
    private val extractThread = Dispatchers.IO.limitedParallelism(1)
    private val extractor by lazy { PdfTextExtractor(appContext) }
    private var memo: Pair<PdfPageText, PdfPageText>? = null

    /** The renderer of [media], or null while it is still opening (the call starts opening it). */
    fun renderer(media: String): PdfTileRenderer? {
        renderers[media]?.let { return it }
        if (media in failed || !opening.add(media)) return null
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { resolveFile(media)?.let { PdfTileRenderer(it) } }.getOrNull()
            }
            if (r != null) renderers[media] = r else failed.add(media)
            opening.remove(media)
            tileCache.touch()
        }
        return null
    }

    private fun key(pdf: PdfBackground) = "${pdf.media}#${pdf.pageIndex}"

    /** Text layer of one PDF page, raw (its pageIndex is the PDF's). Null until extracted off the main thread. */
    fun rawText(pdf: PdfBackground): PdfPageText? {
        val k = key(pdf)
        textCache.get(k)?.let { return it }
        if (pdf.media in failed || !loadingText.add(k)) return null
        scope.launch {
            val t = withContext(extractThread) { extract(pdf) }
            if (t != null) textCache.put(k, t)
            loadingText.remove(k)
            if (t != null) version++
        }
        return null
    }

    /** Like [rawText] but numbered as note page [noteIndex], the index selection and search matches use. */
    fun pageText(pdf: PdfBackground, noteIndex: Int): PdfPageText? {
        val raw = rawText(pdf) ?: return null
        memo?.let { (r, c) -> if (r === raw && c.pageIndex == noteIndex) return c }
        return raw.copy(pageIndex = noteIndex).also { memo = raw to it }
    }

    /** Extracts every PDF page of [pages] (note index to background) for a search; one document load per source. */
    suspend fun textForSearch(pages: List<Pair<Int, PdfBackground>>): List<PdfPageText> = withContext(extractThread) {
        pages.mapNotNull { (noteIndex, pdf) ->
            val k = key(pdf)
            val raw = textCache.get(k) ?: extract(pdf)?.also { textCache.put(k, it) }
            raw?.copy(pageIndex = noteIndex)
        }
    }

    private suspend fun extract(pdf: PdfBackground): PdfPageText? = runCatching {
        val doc = docs[pdf.media] ?: run {
            val f = resolveFile(pdf.media) ?: return null
            PdfTextSource(f, extractor).also { docs[pdf.media] = it }
        }
        doc.page(pdf.pageIndex)
    }.getOrNull()

    fun close() {
        tileCache.clear()
        renderers.values.forEach { it.close() }
        renderers.clear()
        val toClose = docs.values.toList()
        docs.clear()
        scope.launch(extractThread) { toClose.forEach { runCatching { it.close() } } }
    }
}
