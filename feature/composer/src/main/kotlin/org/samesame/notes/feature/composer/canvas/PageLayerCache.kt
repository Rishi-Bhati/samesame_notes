package org.samesame.notes.feature.composer.canvas

import androidx.compose.ui.graphics.GraphicsContext
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.ink.strokes.Stroke
import org.samesame.notes.core.ink.DarkModeInk
import org.samesame.notes.core.ink.page.ElementId
import org.samesame.notes.core.ink.page.ObjectElement
import org.samesame.notes.core.ink.page.PageContent
import org.samesame.notes.core.ink.page.floating
import org.samesame.notes.core.model.document.FloatingObject
import org.samesame.notes.core.model.document.TextWrap

/**
 * What a page layer was recorded from. Plain fields compared one by one, so checking a layer every frame allocates
 * nothing: the canvas fills the cache's scratch key ([PageLayerCache.key]) and asks whether a slot is stale.
 * Objects are compared with `==` (page contents and bitmaps have identity equality), numbers exactly.
 */
class LayerKey {
    var a: Any? = null
    var b: Any? = null
    var c: Any? = null
    var l1: Long = 0L
    var l2: Long = 0L
    var l3: Long = 0L
    var width: Int = -1
    var height: Int = -1

    fun set(a: Any? = null, b: Any? = null, c: Any? = null, l1: Long = 0L, l2: Long = 0L, l3: Long = 0L, width: Int, height: Int): LayerKey {
        this.a = a; this.b = b; this.c = c
        this.l1 = l1; this.l2 = l2; this.l3 = l3
        this.width = width; this.height = height
        return this
    }

    fun sameAs(o: LayerKey): Boolean =
        l1 == o.l1 && l2 == o.l2 && l3 == o.l3 && width == o.width && height == o.height && a == o.a && b == o.b && c == o.c

    fun copyFrom(o: LayerKey) {
        set(o.a, o.b, o.c, o.l1, o.l2, o.l3, o.width, o.height)
    }
}

/**
 * Slots of page layers by page id and kind, with the bookkeeping that needs no graphics: which key each slot was
 * recorded with, which pages are kept (the canvas keeps the visible pages +- 1, see [LayerRetention]) and a hard cap
 * on resident pages. [L] is the layer type ([GraphicsLayer] in the app, anything in JVM tests).
 */
class LayerSlots<L : Any>(
    private val create: () -> L,
    private val release: (L) -> Unit,
    private val kinds: Int = KINDS,
    private val maxPages: Int = MAX_PAGES,
) {
    class Slot<L : Any>(val layer: L) {
        /** The key the layer content was recorded with. */
        val key = LayerKey()

        fun isStale(k: LayerKey): Boolean = !key.sameAs(k)
    }

    // Access order: the eldest entry is the least recently drawn page.
    private val pages = LinkedHashMap<String, Array<Slot<L>?>>(16, 0.75f, true)

    /** Number of pages holding at least one slot. */
    val pageCount: Int get() = pages.size

    /** The slot for [pageId] / [kind], created on first use (its key never matches until recorded). */
    fun slot(pageId: String, kind: Int): Slot<L> {
        val row = pages[pageId] ?: arrayOfNulls<Slot<L>>(kinds).also {
            pages[pageId] = it
            trim()
        }
        return row[kind] ?: Slot(create()).also { row[kind] = it }
    }

    /** Releases every page not accepted by [keep]. Call when the kept window changes, not every frame. */
    fun retainOnly(keep: (String) -> Boolean) {
        val it = pages.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (keep(e.key)) continue
            e.value.forEach { s -> if (s != null) release(s.layer) }
            it.remove()
        }
    }

    fun clear() {
        pages.values.forEach { row -> row.forEach { s -> if (s != null) release(s.layer) } }
        pages.clear()
    }

    private fun trim() {
        while (pages.size > maxPages) {
            val eldest = pages.entries.iterator().next()
            eldest.value.forEach { s -> if (s != null) release(s.layer) }
            pages.remove(eldest.key)
        }
    }

    companion object {
        const val KIND_BACKGROUND = 0
        const val KIND_BEHIND = 1
        const val KIND_TEXT = 2
        const val KIND_INK = 3
        const val KINDS = 4
        /** Hard cap; normally the retention window keeps far fewer (visible pages + 2). */
        const val MAX_PAGES = 8
    }
}

/** Which pages keep their recorded layers, and which neighbour to record ahead of the viewport. Pure. */
object LayerRetention {
    /** Visible pages plus [margin] on each side, clamped to the document; empty when nothing is visible. */
    fun window(visible: IntRange, pageCount: Int, margin: Int = 1): IntRange {
        if (visible.isEmpty() || pageCount <= 0) return IntRange.EMPTY
        return (visible.first - margin).coerceAtLeast(0)..(visible.last + margin).coerceAtMost(pageCount - 1)
    }

    /**
     * The neighbour pages worth recording before they scroll in, best first: the next page in the scroll direction,
     * then the one behind. [which] = 0 or 1; returns -1 when there is none.
     */
    fun neighbour(visible: IntRange, pageCount: Int, forward: Boolean, which: Int): Int {
        if (visible.isEmpty()) return -1
        val ahead = if (forward) visible.last + 1 else visible.first - 1
        val behind = if (forward) visible.first - 1 else visible.last + 1
        val i = if (which == 0) ahead else behind
        return if (i in 0 until pageCount) i else -1
    }
}

/**
 * Page layers recorded once into Compose [GraphicsLayer]s (RenderNode-backed display lists) in page space and replayed
 * with the page-to-view matrix every frame, so scroll and zoom cost a matrix change instead of a re-draw (docs 2.3).
 * Display lists stay sharp at any zoom (HWUI rasterises them at the final scale), so zooming never re-records. A layer is
 * re-recorded only when its [LayerKey] changes; layers of pages outside the kept window are released. Main thread only.
 */
class PageLayerCache(private val context: GraphicsContext) {
    private val slots = LayerSlots(create = { context.createGraphicsLayer() }, release = { context.releaseGraphicsLayer(it) })
    private class Display(val source: Stroke, val shown: Stroke)
    private var displayStrokes = HashMap<String, HashMap<ElementId, Display>>()
    private var displayInverted = false

    /** Scratch key: fill it with [LayerKey.set], then pass it to [isStale] / [record]. */
    val key = LayerKey()

    /** Number of recordings so far (tests and the debug overlay read it to prove replays are cheap). */
    var recordCount = 0
        private set

    fun slot(pageId: String, kind: Int): LayerSlots.Slot<GraphicsLayer> = slots.slot(pageId, kind)

    /** Records [block] into [slot] when [k] differs from what it holds; returns the slot's layer. */
    inline fun layer(slot: LayerSlots.Slot<GraphicsLayer>, k: LayerKey, density: Density, crossinline block: DrawScope.() -> Unit): GraphicsLayer {
        if (slot.isStale(k)) {
            slot.layer.record(density, LayoutDirection.Ltr, IntSize(k.width, k.height)) { block() }
            slot.key.copyFrom(k)
            countRecord()
        }
        return slot.layer
    }

    @PublishedApi internal fun countRecord() { recordCount++ }

    /** Releases layers and display strokes of every page not accepted by [keep]. Call when the kept window changes. */
    fun retainOnly(keep: (String) -> Boolean) {
        slots.retainOnly(keep)
        val it = displayStrokes.keys.iterator()
        while (it.hasNext()) if (!keep(it.next())) it.remove()
        behindMemo.keys.retainAll { keep(it) }
        decoded.keys.retainAll { keep(it) }
    }

    private class BehindMemo(val content: PageContent, val any: Boolean)
    private val behindMemo = HashMap<String, BehindMemo>()

    /**
     * True when [content] has an object set to "wrap behind" the text. Decoding object JSON is expensive, so the answer
     * is memoised per content instance (every edit makes a new one): the canvas asks every frame.
     */
    fun hasBehindObjects(content: PageContent): Boolean {
        val m = behindMemo[content.pageId]
        if (m != null && m.content === content) return m.any
        val any = content.elements.any { it is ObjectElement && floating(content.pageId, it)?.wrap == TextWrap.Behind }
        behindMemo[content.pageId] = BehindMemo(content, any)
        return any
    }

    private class Decoded(val element: ObjectElement, val floating: FloatingObject?)
    private val decoded = HashMap<String, HashMap<ElementId, Decoded>>()

    /**
     * The decoded floating object of [e], memoised per element instance (elements are immutable; an edit replaces the
     * element), so re-recording a page after one stroke does not decode every object's JSON again.
     */
    fun floating(pageId: String, e: ObjectElement): FloatingObject? {
        val perPage = decoded.getOrPut(pageId) { HashMap() }
        val hit = perPage[e.id]
        if (hit != null && hit.element === e) return hit.floating
        return e.floating().also { perPage[e.id] = Decoded(e, it) }
    }

    /**
     * Display copy of a stored stroke (colour-inverted for adjusted dark pages), cached per element so a
     * re-record after adding one stroke does not copy all the others.
     */
    fun displayStroke(pageId: String, id: ElementId, stroke: Stroke, inverted: Boolean): Stroke {
        if (!inverted) return stroke
        if (inverted != displayInverted) { displayStrokes.clear(); displayInverted = inverted }
        // A transform/restyle keeps the element id but swaps the stroke instance: key the copy on the source identity,
        // otherwise the stale (old position/size) inverted copy is drawn after a move or resize on dark pages.
        val perPage = displayStrokes.getOrPut(pageId) { HashMap() }
        val hit = perPage[id]
        if (hit != null && hit.source === stroke) return hit.shown
        return DarkModeInk.invert(stroke).also { perPage[id] = Display(stroke, it) }
    }

    /** Drops cached display strokes of [pageId] that are no longer in [live]. */
    fun pruneDisplay(pageId: String, live: Set<ElementId>) {
        displayStrokes[pageId]?.keys?.retainAll(live)
        decoded[pageId]?.keys?.retainAll(live)
    }

    fun release() {
        slots.clear()
        displayStrokes.clear()
        behindMemo.clear()
        decoded.clear()
    }
}

internal fun DrawScope.replay(layer: GraphicsLayer) = drawLayer(layer)
