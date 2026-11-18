package org.samesame.notes.feature.composer.search

import org.samesame.notes.core.data.pdf.PdfTextExtractor
import org.samesame.notes.core.model.Limits
import org.samesame.notes.core.model.pdf.PdfPageText
import org.samesame.notes.core.model.pdf.PdfSearchMatch
import org.samesame.notes.core.richtext.TextSearch

/** One hit in a unified in-note search (note body text or PDF text layer). */
sealed interface SearchHit {
    data class Body(val range: IntRange) : SearchHit
    data class Pdf(val match: PdfSearchMatch) : SearchHit
}

/**
 * Hit list and cursor of the in-note search bar (R1). Behaviour from the reference app: a new search starts with no
 * focused hit and the view jumps to the first one ([forward] from -1); "next" and "previous" do not wrap, they are
 * disabled at the last / first hit.
 *
 * Supports searching both note body text and extracted PDF page text.
 */
class SearchNavigator {
    var query: String = ""
        private set
    var matches: List<IntRange> = emptyList()
        private set
    var pdfMatches: List<PdfSearchMatch> = emptyList()
        private set
    var allHits: List<SearchHit> = emptyList()
        private set

    /** Index of the focused hit in [allHits], or -1 before the first jump. */
    var current: Int = -1
        private set

    val count: Int get() = allHits.size
    val canForward: Boolean get() = count > 0 && current < count - 1
    val canBackward: Boolean get() = count > 0 && current > 0

    /** Runs a bounded search and jumps to the first hit. */
    fun search(text: CharSequence, query: String, pdfPages: List<PdfPageText> = emptyList()): IntRange? {
        val result = bodyResult(text.toString(), query)
        val hits = ArrayList<PdfSearchMatch>()
        for (page in pdfPages) {
            val remaining = Limits.PDF_SEARCH_MAX_MATCHES - result.body.size - hits.size
            if (remaining <= 0) break
            hits += PdfTextExtractor.search(page, result.query, remaining)
        }
        return publish(result.copy(pdf = hits))
    }

    /** Only publication mutates the navigator; computation can run on a worker dispatcher. */
    fun publish(result: SearchResult): IntRange? {
        query = result.query
        matches = result.body.take(Limits.PDF_SEARCH_MAX_MATCHES)
        pdfMatches = result.pdf.take(Limits.PDF_SEARCH_MAX_MATCHES - matches.size)
        allHits = matches.map { SearchHit.Body(it) } + pdfMatches.map { SearchHit.Pdf(it) }
        current = -1
        return forward()
    }

    fun clear() {
        query = ""
        matches = emptyList()
        pdfMatches = emptyList()
        allHits = emptyList()
        current = -1
    }

    fun currentHit(): SearchHit? = allHits.getOrNull(current)

    fun forwardHit(): SearchHit? {
        if (!canForward) return currentHit()
        current++
        return currentHit()
    }

    fun backwardHit(): SearchHit? {
        if (!canBackward) return currentHit()
        current--
        return currentHit()
    }

    fun forward(): IntRange? {
        val hit = forwardHit() ?: return null
        return when (hit) {
            is SearchHit.Body -> hit.range
            is SearchHit.Pdf -> hit.match.start until hit.match.endExclusive
        }
    }

    fun backward(): IntRange? {
        val hit = backwardHit() ?: return null
        return when (hit) {
            is SearchHit.Body -> hit.range
            is SearchHit.Pdf -> hit.match.start until hit.match.endExclusive
        }
    }

    data class SearchResult(val query: String, val body: List<IntRange>, val pdf: List<PdfSearchMatch> = emptyList())

    companion object {
        fun bodyResult(text: String, query: String, checkActive: () -> Unit = {}): SearchResult {
            val q = query.take(TextSearch.MAX_QUERY_LENGTH)
            val matches = ArrayList<IntRange>()
            if (q.isNotEmpty()) {
                var start = 0
                while (start <= text.length - q.length && matches.size < Limits.PDF_SEARCH_MAX_MATCHES) {
                    checkActive()
                    val index = text.indexOf(q, start, ignoreCase = true)
                    if (index < 0) break
                    matches += index until index + q.length
                    start = index + q.length
                }
            }
            return SearchResult(q, matches)
        }

        /**
         * Pages (0-based, ascending, distinct) that hold at least one of [matches]; [pageOf] maps a body offset to its
         * page. Used by the page sorter's search, which lists those pages.
         */
        fun pagesWithHits(matches: List<IntRange>, pageOf: (Int) -> Int): List<Int> =
            matches.map { pageOf(it.first) }.distinct().sorted()

        /** Number of hits that start on [page]; handy for tests and the no-result state. */
        fun hitsOnPage(matches: List<IntRange>, page: Int, pageOf: (Int) -> Int): Int =
            matches.count { pageOf(it.first) == page }
    }
}
