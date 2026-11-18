package org.samesame.notes.feature.composer.pdf

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.samesame.notes.core.data.pdf.PdfTextExtractor
import org.samesame.notes.core.data.pdf.PdfCoordinates
import org.samesame.notes.core.model.document.PageSpec
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
    scope: CoroutineScope,
    private val resolveFile: suspend (media: String) -> File?,
) {
    private val closed = AtomicBoolean(false)
    private val workScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val appContext = context.applicationContext
    val tileCache = PdfTileCache()
    val selection = PdfSelectionState()

    /** Bumps when a text page arrived (redraw, re-run a pending search). */
    var version: Int by mutableIntStateOf(0)
        private set

    private val sourceSizes = ConcurrentHashMap<String, Pair<Float, Float>>()
    private val renderers = ConcurrentHashMap<String, PdfTileRenderer>()
    private val opening = ConcurrentHashMap.newKeySet<String>()
    private val failed = ConcurrentHashMap.newKeySet<String>()
    private val textCache = PdfTextCache()
    private val loadingText = ConcurrentHashMap.newKeySet<String>()
    private val docs = HashMap<String, PdfTextSource>() // touched only on the extraction thread
    private val extractThread = Dispatchers.IO.limitedParallelism(1)
    private val extractor by lazy { PdfTextExtractor(appContext) }
    private var memo: Pair<PdfPageText, PdfPageText>? = null

    /** The renderer of [media], or null while it is still opening (the call starts opening it). */
    fun renderer(media: String): PdfTileRenderer? {
        if (closed.get()) return null
        renderers[media]?.let { return it }
        if (media in failed || !opening.add(media)) return null
        workScope.launch {
            var r: PdfTileRenderer? = null
            var published = false
            try {
                val file = withContext(Dispatchers.IO) { resolveFile(media) }
                if (file != null) r = PdfTileRenderer.open(file)
                ensureActive()
                if (!closed.get() && r != null) {
                    renderers[media] = r
                    published = true
                    tileCache.touch()
                } else failed.add(media)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                failed.add(media)
            } finally {
                opening.remove(media)
                if (!published) r?.close()
            }
        }
        return null
    }

    private fun key(pdf: PdfBackground) = "${pdf.media}#${pdf.pageIndex}"

    /** Effective import fit; a legacy page's source size comes from PDF metadata, never an A4 guess. */
    fun pageFit(spec: PageSpec, heightPu: Float): Pair<Float, PdfCoordinates.PageTransform>? {
        val pdf = spec.pdf ?: return null
        val size = spec.size?.takeIf { it.isValid }?.let { it.widthPt to it.heightPt }
            ?: sourceSizes[key(pdf)] ?: renderer(pdf.media)?.pageSize(pdf.pageIndex) ?: return null
        return size.first to PdfCoordinates.fit(size.first, size.second, heightPu)
    }

    /** Text layer of one PDF page, raw (its pageIndex is the PDF's). Null until extracted off the main thread. */
    fun rawText(pdf: PdfBackground): PdfPageText? {
        if (closed.get()) return null
        val k = key(pdf)
        textCache.get(k)?.let { return it }
        if (pdf.media in failed || !loadingText.add(k)) return null
        workScope.launch {
            try {
                val t = withContext(extractThread) { extract(pdf) }
                if (t != null && !closed.get()) { textCache.put(k, t); version++ }
            } finally { loadingText.remove(k) }
        }
        return null
    }

    /** Like [rawText] but numbered as note page [noteIndex], the index selection and search matches use. */
    fun pageText(pdf: PdfBackground, noteIndex: Int): PdfPageText? {
        val raw = rawText(pdf) ?: return null
        memo?.let { (r, c) -> if (r === raw && c.pageIndex == noteIndex) return c }
        return raw.copy(pageIndex = noteIndex).also { memo = raw to it }
    }

    /** Visit one extracted page at a time; stop as soon as the search budget is filled. */
    suspend fun forEachSearchPage(pages: List<Pair<Int, PdfBackground>>, visit: suspend (PdfPageText) -> Boolean) {
        for ((noteIndex, pdf) in pages) {
            if (closed.get()) break
            currentCoroutineContext().ensureActive()
            val raw = withContext(extractThread) {
                val k = key(pdf)
                textCache.get(k) ?: extract(pdf)?.also { textCache.put(k, it) }
            }
            if (raw != null && !visit(raw.copy(pageIndex = noteIndex))) break
        }
    }

    private suspend fun extract(pdf: PdfBackground): PdfPageText? {
        if (closed.get()) return null
        return try {
            val doc = docs[pdf.media] ?: run {
                val f = resolveFile(pdf.media) ?: return null
                currentCoroutineContext().ensureActive()
                if (closed.get()) return null
                PdfTextSource(f, extractor).also { docs[pdf.media] = it }
            }
            sourceSizes[key(pdf)] = doc.pageSize(pdf.pageIndex)
            doc.page(pdf.pageIndex)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        workScope.cancel()
        tileCache.clear()
        memo = null
        cleanupScope.launch {
            try {
                withContext(NonCancellable) {
                    renderers.values.forEach { it.close() }
                    renderers.clear()
                    sourceSizes.clear()
                    withContext(extractThread) {
                        docs.values.forEach { it.close() }
                        docs.clear()
                        textCache.evictAll()
                    }
                }
            } finally { cleanupScope.cancel() }
        }
    }
}
