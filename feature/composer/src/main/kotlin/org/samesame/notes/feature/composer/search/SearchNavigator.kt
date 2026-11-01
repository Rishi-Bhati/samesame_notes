package org.samesame.notes.feature.composer.search

import org.samesame.notes.core.richtext.TextSearch

/**
 * Hit list and cursor of the in-note search bar (R1). Behaviour from the reference app: a new search starts with no
 * focused hit and the view jumps to the first one ([forward] from -1); "next" and "previous" do not wrap, they are
 * disabled at the last / first hit.
 */
class SearchNavigator {
    var query: String = ""
        private set
    var matches: List<IntRange> = emptyList()
        private set

    /** Index of the focused hit, or -1 before the first jump. */
    var current: Int = -1
        private set

    val count: Int get() = matches.size
    val canForward: Boolean get() = count > 0 && current < count - 1
    val canBackward: Boolean get() = count > 0 && current > 0

    /** Runs a search over [text] and jumps to the first hit. Returns the focused hit, or null. */
    fun search(text: CharSequence, query: String): IntRange? {
        this.query = query.take(TextSearch.MAX_QUERY_LENGTH)
        matches = TextSearch.find(text, this.query)
        current = -1
        return forward()
    }

    fun clear() {
        query = ""
        matches = emptyList()
        current = -1
    }

    fun forward(): IntRange? {
        if (!canForward) return matches.getOrNull(current)
        current++
        return matches[current]
    }

    fun backward(): IntRange? {
        if (!canBackward) return matches.getOrNull(current)
        current--
        return matches[current]
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
