package org.samesame.notes.feature.composer.pages

import org.samesame.notes.core.model.document.PageSpec

/** The page sorter's four modes (reference: 0 base, 1 edit, 2 search, 3 bookmark). */
enum class SorterMode { Normal, Edit, Search, Bookmark }

/**
 * What the sorter grid shows per mode, as indices into the note's page list. Normal and Edit list every page (only
 * Normal ends with the "add page" tile); Bookmark lists the bookmarked pages; Search lists the pages with a hit.
 */
object PageSorterModel {
    fun visibleIndices(mode: SorterMode, pages: List<PageSpec>, searchHits: List<Int>): List<Int> = when (mode) {
        SorterMode.Normal, SorterMode.Edit -> pages.indices.toList()
        SorterMode.Bookmark -> pages.indices.filter { pages[it].isBookmarked }
        SorterMode.Search -> searchHits.filter { it in pages.indices }
    }

    fun showsAddTile(mode: SorterMode) = mode == SorterMode.Normal

    /** Selection changes: a page toggles in or out; "select all" covers the visible pages only. */
    fun toggle(selected: Set<String>, id: String): Set<String> = if (id in selected) selected - id else selected + id

    fun selectAll(visibleIds: List<String>, selected: Set<String>): Set<String> =
        if (selected.containsAll(visibleIds)) emptySet() else visibleIds.toSet()

    /** Selected ids that still exist (pages deleted or reordered meanwhile drop out). */
    fun prune(selected: Set<String>, pages: List<PageSpec>): Set<String> {
        val ids = pages.mapTo(HashSet()) { it.id }
        return selected.filterTo(HashSet()) { it in ids }
    }

    /** Recent page-search keywords: newest first, no duplicates (case-insensitive), at most [max]. */
    fun addRecent(recent: List<String>, keyword: String, max: Int = 10): List<String> {
        val k = keyword.trim()
        if (k.isEmpty()) return recent
        return (listOf(k) + recent.filterNot { it.equals(k, ignoreCase = true) }).take(max)
    }
}
