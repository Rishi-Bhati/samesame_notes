package org.samesame.notes.core.ink.tools

import androidx.ink.geometry.AffineTransform
import androidx.ink.geometry.ImmutableParallelogram
import androidx.ink.geometry.ImmutableVec
import androidx.ink.geometry.Intersection.intersects
import org.samesame.notes.core.ink.PenBrushes
import org.samesame.notes.core.ink.page.ElementId
import org.samesame.notes.core.ink.page.InkElement
import org.samesame.notes.core.ink.page.PageElement
import org.samesame.notes.core.model.PenType
import java.util.concurrent.ThreadLocalRandom
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Result of an eraser gesture, shaped for a `Replace` undo command: [elements] is the new page list
 * (z-order preserved; split pieces take the place of the stroke they came from), [removed] are the
 * original elements that disappeared and [added] the new ones.
 */
class EraseResult(val elements: List<PageElement>, val removed: List<PageElement>, val added: List<PageElement>) {
    val isEmpty: Boolean get() = removed.isEmpty() && added.isEmpty()
}

/** True for strokes drawn with the highlighter family (the "erase highlighter only" filter). */
fun InkElement.isHighlighter(): Boolean =
    PenBrushes.parseKey(stroke.brush.family.developerComment)?.first == PenType.Highlighter

private fun passes(e: PageElement, highlighterOnly: Boolean): Boolean =
    e is InkElement && (!highlighterOnly || e.isHighlighter())

fun randomElementId() = ElementId(ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE))

/**
 * Stroke eraser (docs 4.2): feed move segments in page units with [erase]; strokes touched by the
 * swept eraser (segment padded by [radius]) are removed whole. One session = one drag = one history entry.
 */
class StrokeEraser(
    private val original: List<PageElement>,
    private val radius: Float,
    private val highlighterOnly: Boolean = false,
) {
    private val index = SpatialIndex(original.filter { passes(it, highlighterOnly) })
    private val byId = original.associateBy { it.id }
    private val hit = LinkedHashSet<ElementId>()

    /** Ids removed so far, in hit order (hide these from the live page immediately). */
    val removedIds: Set<ElementId> get() = hit

    /** Erases along one segment (a tap is x0==x1,y0==y1); returns the ids newly hit by this call. */
    fun erase(x0: Float, y0: Float, x1: Float, y1: Float): List<ElementId> {
        val para = eraserShape(x0, y0, x1, y1, radius)
        val out = ArrayList<ElementId>()
        for (id in index.query(min(x0, x1) - radius, min(y0, y1) - radius, max(x0, x1) + radius, max(y0, y1) + radius)) {
            if (id in hit) continue
            val e = byId[id] as? InkElement ?: continue
            if (para.intersects(e.stroke.shape, AffineTransform.IDENTITY)) {
                hit += id; index.remove(id); out += id
            }
        }
        return out
    }

    fun result(): EraseResult {
        if (hit.isEmpty()) return EraseResult(original, emptyList(), emptyList())
        return EraseResult(original.filter { it.id !in hit }, original.filter { it.id in hit }, emptyList())
    }

    companion object {
        /** One-shot: erase along a polyline path (x,y pairs). */
        fun eraseAlong(elements: List<PageElement>, path: FloatArray, radius: Float, highlighterOnly: Boolean = false): EraseResult {
            val s = StrokeEraser(elements, radius, highlighterOnly)
            if (path.size >= 2) {
                if (path.size == 2) s.erase(path[0], path[1], path[0], path[1])
                for (i in 0 until path.size / 2 - 1) s.erase(path[2 * i], path[2 * i + 1], path[2 * i + 2], path[2 * i + 3])
            }
            return s.result()
        }

        internal fun eraserShape(x0: Float, y0: Float, x1: Float, y1: Float, r: Float): ImmutableParallelogram {
            val len = hypot(x1 - x0, y1 - y0)
            val c = ImmutableVec((x0 + x1) / 2f, (y0 + y1) / 2f)
            return if (len < 0.01f) {
                ImmutableParallelogram.fromCenterAndDimensions(c, 2 * r, 2 * r)
            } else {
                val deg = Math.toDegrees(atan2((y1 - y0).toDouble(), (x1 - x0).toDouble())).toFloat()
                ImmutableParallelogram.fromCenterDimensionsAndRotationInDegrees(c, len + 2 * r, 2 * r, deg)
            }
        }
    }
}

/**
 * Area eraser (docs 4.2): feed move segments with [erase]; every stroke within `radius + width/2`
 * of the path is split at input level by [StrokeSplitter]. Pieces get ids from [newId] and are
 * themselves erasable within the same drag.
 */
class AreaEraser(
    private val original: List<PageElement>,
    private val radius: Float,
    private val highlighterOnly: Boolean = false,
    private val newId: () -> ElementId = ::randomElementId,
) {
    private val index = SpatialIndex(original.filter { passes(it, highlighterOnly) })
    private val live = HashMap<ElementId, InkElement>().also { m -> original.forEach { if (it is InkElement) m[it.id] = it } }
    private val groups = HashMap<ElementId, MutableList<InkElement>>() // original id -> current pieces
    private val originOf = HashMap<ElementId, ElementId>()             // piece id -> original id

    /** Applies one move segment; returns true when any stroke changed (re-render the page layer once per frame). */
    fun erase(x0: Float, y0: Float, x1: Float, y1: Float): Boolean {
        val path = floatArrayOf(x0, y0, x1, y1)
        var changed = false
        for (id in index.query(min(x0, x1) - radius, min(y0, y1) - radius, max(x0, x1) + radius, max(y0, y1) + radius)) {
            val e = live[id] ?: continue
            val b = e.bounds
            if (b.xMax < min(x0, x1) - radius || b.xMin > max(x0, x1) + radius ||
                b.yMax < min(y0, y1) - radius || b.yMin > max(y0, y1) + radius) continue
            val pieces = StrokeSplitter.split(e.stroke, path, radius) ?: continue
            changed = true
            index.remove(id); live.remove(id)
            val origin = originOf.remove(id) ?: id
            val newPieces = pieces.map { InkElement(newId(), it) }
            val group = groups.getOrPut(origin) { mutableListOf(e) }
            val at = group.indexOfFirst { it.id == id }
            group.removeAt(at)
            group.addAll(at, newPieces)
            for (p in newPieces) { live[p.id] = p; originOf[p.id] = origin; index.insert(p) }
        }
        return changed
    }

    /** Elements currently alive that came from this session's splitting, for live rendering. */
    fun result(): EraseResult {
        if (groups.isEmpty()) return EraseResult(original, emptyList(), emptyList())
        val out = ArrayList<PageElement>(original.size + 8)
        val removed = ArrayList<PageElement>()
        val added = ArrayList<PageElement>()
        for (e in original) {
            val g = groups[e.id]
            if (g == null) out += e else { removed += e; out += g; added += g }
        }
        return EraseResult(out, removed, added)
    }

    companion object {
        fun eraseAlong(
            elements: List<PageElement>, path: FloatArray, radius: Float,
            highlighterOnly: Boolean = false, newId: () -> ElementId = ::randomElementId,
        ): EraseResult {
            val s = AreaEraser(elements, radius, highlighterOnly, newId)
            if (path.size == 2) s.erase(path[0], path[1], path[0], path[1])
            for (i in 0 until path.size / 2 - 1) s.erase(path[2 * i], path[2 * i + 1], path[2 * i + 2], path[2 * i + 3])
            return s.result()
        }
    }
}
