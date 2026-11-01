package org.samesame.notes.core.ink.tools

import androidx.ink.brush.InputToolType
import androidx.ink.geometry.AffineTransform
import androidx.ink.geometry.Box
import androidx.ink.geometry.ImmutableVec
import androidx.ink.geometry.Intersection.intersects
import androidx.ink.geometry.PartitionedMesh
import androidx.ink.strokes.createClosedShape
import androidx.ink.strokes.MutableStrokeInputBatch
import org.samesame.notes.core.ink.page.InkElement
import org.samesame.notes.core.ink.page.ObjectElement
import org.samesame.notes.core.ink.page.PageElement
import org.samesame.notes.core.ink.page.box
import kotlin.math.max
import kotlin.math.min

/**
 * Lasso and rectangle selection (docs 4.3). All coordinates are page units of one page. Results keep
 * z-order. [includePartial] = the "include partially selected objects" setting: any overlap selects;
 * without it an element needs at least [SELECT_COVERAGE] of its area inside the selection.
 */
object SelectionHitTester {
    /** Provisional (docs 4.3). */
    const val SELECT_COVERAGE = 0.9f
    private const val OBJECT_SAMPLES = 8

    fun lasso(elements: List<PageElement>, path: FloatArray, includePartial: Boolean, index: SpatialIndex? = null): List<PageElement> {
        val n = path.size / 2
        if (n < 3) return emptyList()
        val batch = MutableStrokeInputBatch()
        var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
        for (i in 0 until n) {
            val x = path[2 * i]; val y = path[2 * i + 1]
            batch.add(InputToolType.STYLUS, x = x, y = y, elapsedTimeMillis = i.toLong())
            x0 = min(x0, x); x1 = max(x1, x); y0 = min(y0, y); y1 = max(y1, y)
        }
        val mesh = try {
            batch.createClosedShape()
        } catch (e: IllegalArgumentException) {
            return emptyList() // degenerate lasso (collinear / zero area)
        }
        val t = if (includePartial) 0f else SELECT_COVERAGE
        return select(elements, index, x0, y0, x1, y1) { e ->
            when (e) {
                is InkElement -> e.stroke.shape.computeCoverageIsGreaterThan(mesh, t)
                is ObjectElement -> objectInLasso(e.bounds, mesh, includePartial)
            }
        }
    }

    fun rectangle(elements: List<PageElement>, xMin: Float, yMin: Float, xMax: Float, yMax: Float, includePartial: Boolean, index: SpatialIndex? = null): List<PageElement> {
        val sel = box(min(xMin, xMax), min(yMin, yMax), max(xMin, xMax), max(yMin, yMax))
        val t = if (includePartial) 0f else SELECT_COVERAGE
        return select(elements, index, sel.xMin, sel.yMin, sel.xMax, sel.yMax) { e ->
            when (e) {
                is InkElement -> {
                    val b = e.bounds
                    if (b.xMin >= sel.xMin && b.xMax <= sel.xMax && b.yMin >= sel.yMin && b.yMax <= sel.yMax) true // fully inside
                    else e.stroke.shape.computeCoverageIsGreaterThan(sel, t, AffineTransform.IDENTITY)
                }
                is ObjectElement -> {
                    val b = e.bounds
                    val ix = min(b.xMax, sel.xMax) - max(b.xMin, sel.xMin)
                    val iy = min(b.yMax, sel.yMax) - max(b.yMin, sel.yMin)
                    if (ix < 0f || iy < 0f) false
                    else if (includePartial) true
                    else ix * iy >= SELECT_COVERAGE * b.width * b.height
                }
            }
        }
    }

    /** Union of the bounds of [elements], or null when empty (the transform box). */
    fun unionBounds(elements: List<PageElement>): Box? {
        if (elements.isEmpty()) return null
        var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
        for (e in elements) { val b = e.bounds; x0 = min(x0, b.xMin); y0 = min(y0, b.yMin); x1 = max(x1, b.xMax); y1 = max(y1, b.yMax) }
        return box(x0, y0, x1, y1)
    }

    private inline fun select(
        elements: List<PageElement>, index: SpatialIndex?,
        x0: Float, y0: Float, x1: Float, y1: Float, test: (PageElement) -> Boolean,
    ): List<PageElement> {
        val candidates = index?.query(x0, y0, x1, y1)?.toHashSet()
        val out = ArrayList<PageElement>()
        for (e in elements) {
            if (candidates != null && e.id !in candidates) continue
            val b = e.bounds
            if (b.xMax < x0 || b.xMin > x1 || b.yMax < y0 || b.yMin > y1) continue
            if (test(e)) out += e
        }
        return out
    }

    // NOTE(WP-E): Ink only offers coverage of a mesh *by* a shape, not of a box by a mesh, so object
    // boxes are sampled on an 8x8 grid against the lasso for the strict threshold.
    private fun objectInLasso(b: Box, lasso: PartitionedMesh, includePartial: Boolean): Boolean {
        if (includePartial) return b.intersects(lasso, AffineTransform.IDENTITY)
        var inside = 0
        for (i in 0 until OBJECT_SAMPLES) for (j in 0 until OBJECT_SAMPLES) {
            val p = ImmutableVec(
                b.xMin + b.width * (i + 0.5f) / OBJECT_SAMPLES, b.yMin + b.height * (j + 0.5f) / OBJECT_SAMPLES,
            )
            if (p.intersects(lasso, AffineTransform.IDENTITY)) inside++
        }
        return inside >= SELECT_COVERAGE * OBJECT_SAMPLES * OBJECT_SAMPLES
    }
}
