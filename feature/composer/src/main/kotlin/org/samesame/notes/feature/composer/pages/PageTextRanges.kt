package org.samesame.notes.feature.composer.pages

/** The body text that goes with deleted pages (pure, so it is unit tested). */
object PageTextRanges {
    /**
     * Clamps [ranges] (`[start, end]` inclusive body offsets) to [text], merges overlapping or touching ones and returns
     * them last first. A range that runs to the end of the body also takes the line break before it: otherwise the
     * empty paragraph left behind could still sit on the deleted page's slot and make the editor append a page again.
     */
    fun normalise(ranges: List<IntRange>, text: String): List<IntRange> {
        val len = text.length
        val sorted = ranges.asSequence()
            .map { maxOf(0, it.first)..minOf(len - 1, it.last) }
            .filter { !it.isEmpty() }
            .sortedBy { it.first }
            .toList()
        val merged = ArrayList<IntRange>()
        for (r in sorted) {
            val last = merged.lastOrNull()
            if (last != null && r.first <= last.last + 1) merged[merged.lastIndex] = last.first..maxOf(last.last, r.last)
            else merged += r
        }
        if (merged.isNotEmpty()) {
            val tail = merged.last()
            if (tail.last == len - 1 && tail.first > 0 && text[tail.first - 1] == '\n') {
                val grown = (tail.first - 1)..tail.last
                merged[merged.lastIndex] = grown
                if (merged.size > 1 && merged[merged.lastIndex - 1].last + 1 >= grown.first) {
                    val prev = merged.removeAt(merged.lastIndex - 1)
                    merged[merged.lastIndex] = prev.first..grown.last
                }
            }
        }
        return merged.asReversed()
    }
}
