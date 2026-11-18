package org.samesame.notes.feature.composer.search

import org.samesame.notes.core.data.pdf.PdfTextExtractor
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

    /** Runs a search over [text] and [pdfPages], and jumps to the first hit. Returns the focused hit range, or null. */
    fun search(text: CharSequence, query: String, pdfPages: List<PdfPageText> = emptyList()): IntRange? {
        this.query = query.take(TextSearch.MAX_QUERY_LENGTH)
        val bodyMatches = if (this.query.isNotEmpty() && text.isNotEmpty()) {
            TextSearch.find(text, this.query)
        } else {
            emptyList()
        }

        val pdfHits = if (this.query.isNotEmpty()) {
            pdfPages.flatMap { page -> PdfTextExtractor.search(page, this.query) }
        } else {
            emptyList()
        }

        return publishSearch(bodyMatches, pdfHits)
    }

    fun searchWithPdfHits(text: CharSequence, query: String, hits: List<PdfSearchMatch>): IntRange? {
        this.query = query.take(TextSearch.MAX_QUERY_LENGTH)
        val body = TextSearch.find(text, this.query)
        return publishSearch(body, hits.take(org.samesame.notes.core.model.Limits.PDF_SEARCH_MAX_MATCHES - minOf(body.size, org.samesame.notes.core.model.Limits.PDF_SEARCH_MAX_MATCHES)))
    }

    private fun publishSearch(bodyMatches: List<IntRange>, pdfHits: List<PdfSearchMatch>): IntRange? {
        matches = bodyMatches
        pdfMatches = pdfHits
        allHits = bodyMatches.map { SearchHit.Body(it) } + pdfHits.map { SearchHit.Pdf(it) }
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

    companion object {
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
