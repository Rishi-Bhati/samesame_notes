package org.samesame.notes.feature.composer.session

import androidx.compose.ui.geometry.Rect
import org.samesame.notes.core.ink.page.ElementId
import org.samesame.notes.core.ink.page.PageElement

/**
 * "Move forwards/backwards" (doc 4.3): each selected element moves past the next (previous) non-selected element
 * whose bounds overlap the selection's bounds. Pure list permutation; returns the same list when nothing moves.
 */
object ZOrder {
    fun forward(elements: List<PageElement>, selected: Set<ElementId>, selection: Rect): List<PageElement> {
        val list = elements.toMutableList()
        for (i in list.indices.reversed()) {
            if (list[i].id !in selected) continue
            var j = i + 1
            while (j < list.size && (list[j].id in selected || !overlaps(list[j], selection))) j++
            if (j < list.size) list.add(j, list.removeAt(i))
        }
        return list
    }

    fun backward(elements: List<PageElement>, selected: Set<ElementId>, selection: Rect): List<PageElement> {
        val list = elements.toMutableList()
        for (i in list.indices) {
            if (list[i].id !in selected) continue
            var j = i - 1
            while (j >= 0 && (list[j].id in selected || !overlaps(list[j], selection))) j--
            if (j >= 0) list.add(j, list.removeAt(i))
        }
        return list
    }

    /** Moves the [selected] elements (keeping their relative order) to the start ([toBack]) or the end of the list. */
    fun toEdge(elements: List<PageElement>, selected: Set<ElementId>, toBack: Boolean): List<PageElement> {
        val moving = elements.filter { it.id in selected }
        val rest = elements.filter { it.id !in selected }
        return if (toBack) moving + rest else rest + moving
    }

    private fun overlaps(e: PageElement, r: Rect): Boolean {
        val b = e.bounds
        return b.xMax >= r.left && b.xMin <= r.right && b.yMax >= r.top && b.yMin <= r.bottom
    }
}
