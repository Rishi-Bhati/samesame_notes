package org.samesame.notes.feature.textdoc

import org.samesame.notes.core.model.Limits

/** Find and replace over plain text. Hits are case-insensitive and capped at [Limits.MAX_TEXTDOC_FIND_MATCHES]. */
internal object FindReplace {
    fun matches(text: String, query: String, limit: Int = Limits.MAX_TEXTDOC_FIND_MATCHES): List<IntRange> {
        if (query.isEmpty() || text.isEmpty()) return emptyList()
        val out = ArrayList<IntRange>()
        var from = 0
        while (out.size < limit) {
            val at = text.indexOf(query, from, ignoreCase = true)
            if (at < 0) break
            out += at until at + query.length
            from = at + query.length
        }
        return out
    }

    /** The next (or previous) hit, wrapping around; -1 when there are none. */
    fun step(current: Int, count: Int, forward: Boolean): Int = when {
        count <= 0 -> -1
        current !in 0 until count -> if (forward) 0 else count - 1
        forward -> (current + 1) % count
        else -> (current - 1 + count) % count
    }

    fun replaceCurrent(text: String, match: IntRange, replacement: String): String =
        text.replaceRange(match.first, match.last + 1, replacement)

    fun replaceAll(text: String, query: String, replacement: String): String {
        if (query.isEmpty() || text.isEmpty()) return text
        val out = StringBuilder(text.length)
        var from = 0
        while (true) {
            val at = text.indexOf(query, from, ignoreCase = true)
            if (at < 0) {
                out.append(text, from, text.length)
                break
            }
            out.append(text, from, at).append(replacement)
            from = at + query.length
        }
        return out.toString()
    }
}
