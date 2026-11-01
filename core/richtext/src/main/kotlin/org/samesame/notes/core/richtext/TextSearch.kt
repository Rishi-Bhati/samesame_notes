package org.samesame.notes.core.richtext

/**
 * Find-in-text used by in-note search (R1) and the page sorter's search (R4). Case-insensitive, matches
 * do not overlap (the scan resumes after each hit) and the query is used as typed (no trimming).
 */
object TextSearch {
    /** The search field takes at most this many characters (the reference app: 199). */
    const val MAX_QUERY_LENGTH = 199

    /** Hits of [query] in [text] as inclusive `[first, last]` offset ranges, in order. Empty query: no hits. */
    fun find(text: CharSequence, query: String): List<IntRange> {
        if (query.isEmpty() || text.length < query.length) return emptyList()
        val s = text.toString()
        val out = ArrayList<IntRange>()
        var i = 0
        val last = s.length - query.length
        while (i <= last) {
            if (s.regionMatches(i, query, 0, query.length, ignoreCase = true)) {
                out += i until i + query.length
                i += query.length
            } else i++
        }
        return out
    }
}
