package org.samesame.notes.core.model.files

/**
 * Gap ranks for the Files custom order (docs/architecture/files-feature.md, 1.2 Ordering). Each file and folder stores a
 * `sortIndex`; a drag changes only the moved row to a rank halfway between its new neighbours. When a pair has no integer
 * gap left, the whole section is renumbered to [STEP], 2 * [STEP], ... . New items always get `max + STEP` (the end).
 */
object OrderRanks {
    /** Rank distance between two items in a freshly numbered section. */
    const val STEP = 1024L

    /** An id and its current rank; [items] passed to [move] must be ordered by rank. */
    data class Ranked(val id: String, val rank: Long)

    /**
     * A rank strictly between [before] and [after]; nulls stand for the ends of the section. Null when there is no integer
     * gap left (the caller then renumbers).
     */
    fun between(before: Long?, after: Long?): Long? = when {
        before == null && after == null -> STEP
        before == null -> (after!! / 2).takeIf { it in 1 until after }
        after == null -> if (before > Long.MAX_VALUE - STEP) null else before + STEP
        else -> if (after - before > 1) before + (after - before) / 2 else null
    }

    /** The ranks of a section of [count] items, in order: [STEP], 2 * [STEP], ... */
    fun renumbered(count: Int): List<Long> = List(count) { (it + 1) * STEP }

    /**
     * Moves the item at [from] to [to] in [items] (ordered by rank) and returns the id -> rank changes. Empty for an
     * out-of-range or no-op move. When the neighbours have no gap left every changed row (normally all) is renumbered.
     */
    fun move(items: List<Ranked>, from: Int, to: Int): Map<String, Long> {
        if (from !in items.indices) return emptyMap()
        val target = to.coerceIn(0, items.lastIndex)
        if (target == from) return emptyMap()
        val order = items.toMutableList()
        val moved = order.removeAt(from)
        order.add(target, moved)
        val before = if (target > 0) order[target - 1].rank else null
        val after = if (target < order.lastIndex) order[target + 1].rank else null
        val rank = between(before, after)
        if (rank != null) return if (rank == moved.rank) emptyMap() else mapOf(moved.id to rank)
        val fresh = renumbered(order.size)
        return order.mapIndexedNotNull { i, item -> fresh[i].takeIf { it != item.rank }?.let { item.id to it } }.toMap()
    }
}
