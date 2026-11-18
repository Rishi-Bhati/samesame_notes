package org.samesame.notes.feature.composer.search

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.samesame.notes.core.data.pdf.PdfTextExtractor
import org.samesame.notes.core.model.Limits
import org.samesame.notes.core.model.document.PdfBackground
import org.samesame.notes.core.model.pdf.PdfPageText
import org.samesame.notes.core.model.pdf.PdfSearchMatch

/** Compute on a worker, then publish in the UI scope. A new request cancels the preceding one. */
internal class NoteSearchRunner(
    private val scope: CoroutineScope,
    private val visitPages: suspend (List<Pair<Int, PdfBackground>>, suspend (PdfPageText) -> Boolean) -> Unit,
    private val worker: CoroutineDispatcher = Dispatchers.Default,
) {
    private var job: Job? = null

    fun cancel() { job?.cancel() }

    fun submit(text: String, query: String, pages: List<Pair<Int, PdfBackground>>, publish: (SearchNavigator.SearchResult) -> Unit) {
        cancel()
        job = scope.launch {
            val result = withContext(worker) {
                val context = currentCoroutineContext()
                val body = SearchNavigator.bodyResult(text, query) { context.ensureActive() }
                val hits = ArrayList<PdfSearchMatch>()
                if (body.query.isNotEmpty() && body.body.size < Limits.PDF_SEARCH_MAX_MATCHES) {
                    visitPages(pages) { page ->
                        context.ensureActive()
                        hits += PdfTextExtractor.search(page, body.query, Limits.PDF_SEARCH_MAX_MATCHES - body.body.size - hits.size)
                        body.body.size + hits.size < Limits.PDF_SEARCH_MAX_MATCHES
                    }
                }
                body.copy(pdf = hits)
            }
            ensureActive()
            publish(result)
        }
    }
}
