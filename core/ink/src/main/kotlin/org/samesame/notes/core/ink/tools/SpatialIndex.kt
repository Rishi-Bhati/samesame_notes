package org.samesame.notes.core.ink.tools

import androidx.ink.geometry.Box
import org.samesame.notes.core.ink.page.ElementId
import org.samesame.notes.core.ink.page.PageElement
import kotlin.math.floor

/**
 * Uniform grid over page units (docs 4.2). Cells are [CELL] pu wide and hashed, so the index is
 * unbounded. Mutable on purpose: eraser sessions insert split pieces and remove hit strokes while a
 * drag is in progress. Not thread-safe; one index per gesture.
 */
class SpatialIndex(elements: Iterable<PageElement> = emptyList()) {
    private val cells = HashMap<Long, ArrayList<ElementId>>()
    private val cellsOf = HashMap<ElementId, LongArray>()
    private val seen = HashSet<ElementId>()

    init {
        elements.forEach(::insert)
    }

    val size: Int get() = cellsOf.size

    fun insert(element: PageElement) {
        remove(element.id)
        val b = element.bounds
        val x0 = cell(b.xMin); val x1 = cell(b.xMax); val y0 = cell(b.yMin); val y1 = cell(b.yMax)
        val keys = LongArray((x1 - x0 + 1) * (y1 - y0 + 1))
        var n = 0
        for (cy in y0..y1) for (cx in x0..x1) {
            val key = key(cx, cy)
            keys[n++] = key
            cells.getOrPut(key) { ArrayList(4) }.add(element.id)
        }
        cellsOf[element.id] = keys
    }

    fun remove(id: ElementId) {
        val keys = cellsOf.remove(id) ?: return
        for (k in keys) cells[k]?.let { list -> list.remove(id); if (list.isEmpty()) cells.remove(k) }
    }

    /** Ids of elements whose grid cells overlap the box (a superset of true bounds overlaps). */
    fun query(xMin: Float, yMin: Float, xMax: Float, yMax: Float): List<ElementId> {
        val out = ArrayList<ElementId>()
        seen.clear()
        for (cy in cell(yMin)..cell(yMax)) for (cx in cell(xMin)..cell(xMax)) {
            val list = cells[key(cx, cy)] ?: continue
            for (id in list) if (seen.add(id)) out += id
        }
        return out
    }

    fun query(box: Box): List<ElementId> = query(box.xMin, box.yMin, box.xMax, box.yMax)

    companion object {
        const val CELL = 128f
        private fun cell(v: Float): Int = floor(v / CELL).toInt()
        private fun key(cx: Int, cy: Int): Long = (cx.toLong() shl 32) xor (cy.toLong() and 0xFFFFFFFFL)
    }
}
