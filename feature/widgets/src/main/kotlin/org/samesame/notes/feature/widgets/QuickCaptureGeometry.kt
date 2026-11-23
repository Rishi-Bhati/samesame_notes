package org.samesame.notes.feature.widgets

import androidx.compose.ui.unit.DpSize
import dev.oneuiproject.oneui.R as Oui
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * The quick-capture layouts, chosen from the widget's size alone ([QuickCaptureLayout.forSize]). There is no card behind the
 * buttons: the widget is one rounded shape sliced into buttons by thin gaps, the main action in the accent.
 */
enum class CaptureShape {
    /** Small (1 x 1), or nothing else fits: a rounded tile with the accent coin of the main action. */
    Single,

    /** Low: one connected bar, the main action first in the accent ("+ Take a note" when there is room). */
    Row,

    /** Narrow and tall (1 x 2, 1 x 3, ...): a capsule of stacked buttons, the main action on top in the accent. */
    Column,

    /** About square and big enough (2 x 2, 3 x 3): four quadrant buttons around the accent coin of the main action. */
    Pad,

    /** Big and clearly oblong (4 x 2, 2 x 3, ...): a large main tile beside (or above) a grid of labelled action tiles. */
    Bento,
}

/** Corner radii of one slice in dp: top-left, top-right, bottom-right, bottom-left. */
data class Corners(val tl: Float, val tr: Float, val br: Float, val bl: Float) {
    val largest: Float get() = maxOf(tl, tr, br, bl)
    val smallest: Float get() = minOf(tl, tr, br, bl)

    companion object {
        fun all(r: Float) = Corners(r, r, r, r)
    }
}

/** An icon of [size] dp centred at ([cx], [cy]) in its slice's coordinates. */
data class Glyph(val cx: Float, val cy: Float, val size: Float)

/** A one-line text box in its slice's coordinates; the text is centred in it, or start-aligned when [start]. */
data class LabelBox(val x: Float, val y: Float, val w: Float, val h: Float, val start: Boolean = false)

/**
 * One button of the shape: a rounded rectangle at ([x], [y]) in the widget (dp), with its icon and optional label. [slot] is
 * [MAIN] for the main action, else the index of its corner slot in [CaptureButtons.corners].
 */
data class Slice(
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    val corners: Corners,
    val slot: Int,
    val glyph: Glyph,
    val label: LabelBox? = null,
) {
    val main: Boolean get() = slot == MAIN

    companion object {
        const val MAIN = -1
    }
}

/**
 * The solid accent disc of the main action at ([cx], [cy]) in the widget, radius [r], [glyph] the "+" size. On the pad it is
 * its own button and a [moat] of [moat] dp is cut out of the quadrants around it; elsewhere it sits inside the main slice.
 */
data class Coin(val cx: Float, val cy: Float, val r: Float, val moat: Float, val glyph: Float)

/** How the main slice is painted: in the accent (bar, column), surface with an accent wash (bento), or plain surface. */
enum class MainFill { Accent, Wash, Surface }

/** Everything the widget, its settings preview and the tests need to draw one size (dp, pure). */
data class CaptureSpec(
    val shape: CaptureShape,
    val width: Float,
    val height: Float,
    val slices: List<Slice>,
    val mainFill: MainFill,
    val coin: Coin?,
) {
    /** The main action's tap target: a slice, or the coin (pad). */
    val coinIsButton: Boolean get() = shape == CaptureShape.Pad
}

/** Geometry of the quick-capture widget (pure, dp). */
object QuickCaptureLayout {
    /** The thin gap that slices the shape into buttons. */
    const val GAP = 4f
    /** Corners where two slices meet in a bar or column. */
    const val INNER = 8f
    /** Corners where tiles meet in the bento. */
    const val TILE_INNER = 10f

    /** About square from this side up: the pad (smaller: the single tile). */
    const val PAD_FROM = 120f
    /** The pad stops growing here; a bigger widget centres it. */
    const val PAD_MAX = 300f
    const val SQUARE_MIN_ASPECT = 2f / 3f
    const val SQUARE_MAX_ASPECT = 3f / 2f
    private const val ASPECT_SLACK = 1e-4f
    /** Pad: the moat between the coin and the quadrants. */
    const val MOAT = 6f

    /** A low widget from this height (and not about square) is the bento; lower ones are the bar. */
    const val BENTO_FROM_HEIGHT = 120f
    /** Bento: least main tile and action tile widths; a low widget too narrow for both is the bar. */
    const val MAIN_TILE_MIN = 96f
    const val TILE_MIN = 64f
    const val BENTO_MIN_WIDTH = MAIN_TILE_MIN + GAP + TILE_MIN * 2 + GAP

    /** A tall widget narrower than this is the column; a wider one the bento. */
    const val COLUMN_BELOW = 160f

    /** Bar height (and column width) on a widget big enough; a smaller one shrinks it down to [BAR_MIN]. */
    const val BAR = 64f
    const val BAR_MIN = 40f
    /** Least room kept around the bar or the column across it. */
    const val BAR_MARGIN = 8f
    /** Least width of an action slice in the bar, and least height in the column. */
    const val ACTION_MIN = 52f
    /** Main slice of the bar with "+ Take a note" needs this width; it grows to [MAIN_MAX] at most. */
    const val LABEL_MAIN = 150f
    const val MAIN_MAX = 240f

    /** The least tap target. */
    const val TARGET = 48f

    /** Fallback outer radius of the bento (the launcher's own widget radius is used where the system has one). */
    const val OUTER_RADIUS = 24f

    fun nearSquare(width: Float, height: Float): Boolean = (width / height) in
        (SQUARE_MIN_ASPECT - ASPECT_SLACK)..(SQUARE_MAX_ASPECT + ASPECT_SLACK)

    /** The layout for [size] with the default buttons. */
    fun forSize(size: DpSize, buttons: CaptureButtons = CaptureButtons()): CaptureShape = spec(size, buttons).shape

    fun spec(size: DpSize, buttons: CaptureButtons, outerRadius: Float = OUTER_RADIUS): CaptureSpec =
        spec(size.width.value, size.height.value, buttons, outerRadius)

    /**
     * The layout for a widget of [w] x [h] dp. About square: the pad, or the single tile when small. Low: the bento when tall
     * enough, else the bar. Tall: the column when narrow, else the bento. A bar, column or pad with no action to show (or no
     * room for one) is the single tile.
     */
    fun spec(w: Float, h: Float, buttons: CaptureButtons, outerRadius: Float = OUTER_RADIUS): CaptureSpec {
        val shown = buttons.corners.indices.filter { buttons.corners[it] != CaptureButton.None }
        if (nearSquare(w, h)) {
            return if (min(w, h) >= PAD_FROM && shown.isNotEmpty()) pad(w, h, shown) else single(w, h)
        }
        return if (w > h) {
            if (h >= BENTO_FROM_HEIGHT && w >= BENTO_MIN_WIDTH) bento(w, h, shown, outerRadius) else row(w, h, shown) ?: single(w, h)
        } else {
            if (w >= COLUMN_BELOW) bento(w, h, shown, outerRadius) else column(w, h, shown) ?: single(w, h)
        }
    }

    // ---- Single ----

    fun single(w: Float, h: Float): CaptureSpec {
        val m = min(w, h)
        val side = min(m, max(m - 8f, TARGET)).coerceAtMost(96f)
        val x = (w - side) / 2
        val y = (h - side) / 2
        val r = side * 0.31f
        val glyph = (r * 0.95f).coerceIn(18f, 30f)
        val slice = Slice(x, y, side, side, Corners.all(side * 0.3f), Slice.MAIN, Glyph(side / 2, side / 2, glyph))
        return CaptureSpec(CaptureShape.Single, w, h, listOf(slice), MainFill.Surface, Coin(x + side / 2, y + side / 2, r, 0f, glyph))
    }

    // ---- Pad ----

    fun pad(w: Float, h: Float, slots: List<Int>): CaptureSpec {
        val s = minOf(w, h, PAD_MAX)
        val x0 = (w - s) / 2
        val y0 = (h - s) / 2
        val q = (s - GAP) / 2
        val out = q / 2
        val edge = (q * 0.17f).coerceIn(10f, 20f)
        val icon = (q * 0.3f).coerceIn(24f, 32f)
        val shift = q * 0.1f
        val slices = slots.map { slot ->
            val right = slot % 2 == 1
            val bottom = slot / 2 == 1
            val corners = when (slot) {
                CaptureButtons.TOP_LEFT -> Corners(out, edge, edge, edge)
                CaptureButtons.TOP_RIGHT -> Corners(edge, out, edge, edge)
                CaptureButtons.BOTTOM_LEFT -> Corners(edge, edge, edge, out)
                else -> Corners(edge, edge, out, edge)
            }
            Slice(
                x = x0 + if (right) q + GAP else 0f, y = y0 + if (bottom) q + GAP else 0f, w = q, h = q, corners = corners, slot = slot,
                glyph = Glyph(q / 2 + if (right) shift else -shift, q / 2 + if (bottom) shift else -shift, icon),
            )
        }
        val r = (s * 0.18f).coerceIn(26f, 50f)
        return CaptureSpec(CaptureShape.Pad, w, h, slices, MainFill.Surface, Coin(x0 + s / 2, y0 + s / 2, r, MOAT, (r * 0.85f).coerceIn(24f, 36f)))
    }

    // ---- Row (bar) ----

    /** Height of the bar on a widget [h] high: 64 dp, less on a low one. */
    fun barHeight(h: Float): Float = (h - BAR_MARGIN * 2).coerceIn(BAR_MIN, BAR).coerceAtMost(h)

    fun row(w: Float, h: Float, slots: List<Int>): CaptureSpec? {
        val b = barHeight(h)
        val aMin = max(ACTION_MIN, b * 0.8f)
        val aPref = max(aMin, b * 1.15f)
        val aMax = max(aPref, b * 1.6f)
        var n = 0
        while (n < slots.size && b + (n + 1) * (aMin + GAP) <= w) n++
        val icon = (b * 0.4f).coerceIn(20f, 26f)
        if (n == 0) {
            if (w < LABEL_MAIN) return null
            val mw = min(w, MAIN_MAX + aMax)
            val main = Slice((w - mw) / 2, (h - b) / 2, mw, b, Corners.all(b / 2), Slice.MAIN, Glyph(b / 2, b / 2, icon), barLabel(b, mw, icon))
            return CaptureSpec(CaptureShape.Row, w, h, listOf(main), MainFill.Accent, null)
        }
        val label = w - n * (aMin + GAP) >= LABEL_MAIN
        var a: Float
        var mw: Float
        if (label) {
            a = ((w - LABEL_MAIN) / n - GAP).coerceIn(aMin, aPref)
            mw = w - n * (a + GAP)
            if (mw > MAIN_MAX) {
                a = ((w - MAIN_MAX) / n - GAP).coerceIn(a, aMax)
                mw = (w - n * (a + GAP)).coerceAtMost(MAIN_MAX)
            }
        } else {
            a = ((w - b) / n - GAP).coerceIn(aMin, aMax)
            mw = (w - n * (a + GAP)).coerceIn(b, b * 1.6f)
        }
        val total = mw + n * (a + GAP)
        var x = (w - total) / 2
        val y = (h - b) / 2
        val end = b / 2
        val slices = mutableListOf(
            Slice(x, y, mw, b, Corners(end, INNER, INNER, end), Slice.MAIN,
                if (label) Glyph(b / 2, b / 2, icon) else Glyph(mw / 2, b / 2, icon), if (label) barLabel(b, mw, icon) else null),
        )
        x += mw + GAP
        slots.take(n).forEachIndexed { i, slot ->
            val last = i == n - 1
            slices += Slice(
                x, y, a, b, if (last) Corners(INNER, end, end, INNER) else Corners.all(INNER), slot,
                Glyph(a / 2 - if (last) (end - INNER) * 0.15f else 0f, b / 2, icon),
            )
            x += a + GAP
        }
        return CaptureSpec(CaptureShape.Row, w, h, slices, MainFill.Accent, null)
    }

    private fun barLabel(b: Float, mw: Float, icon: Float): LabelBox {
        val start = b / 2 + icon / 2 + 10f
        return LabelBox(start, 0f, (mw - start - 12f).coerceAtLeast(0f), b, start = true)
    }

    // ---- Column ----

    /** Width of the column on a widget [w] wide: 64 dp, less on a narrow one. */
    fun columnWidth(w: Float): Float = barHeight(w)

    fun column(w: Float, h: Float, slots: List<Int>): CaptureSpec? {
        val cw = columnWidth(w)
        val sMin = ACTION_MIN
        var n = 0
        while (n < slots.size && (n + 2) * sMin + (n + 1) * GAP <= h) n++
        if (n == 0) return null
        val s = ((h - n * GAP) / (n + 1)).coerceAtMost(cw * 1.25f)
        val total = (n + 1) * s + n * GAP
        val x = (w - cw) / 2
        var y = (h - total) / 2
        val end = min(cw / 2, s - INNER)
        val icon = (cw * 0.4f).coerceIn(20f, 26f)
        // The icon sits a little off the round end, towards the slice's optical middle.
        val nudge = (end - INNER) * 0.12f
        val slices = mutableListOf(Slice(x, y, cw, s, Corners(end, end, INNER, INNER), Slice.MAIN, Glyph(cw / 2, s / 2 + nudge, icon)))
        y += s + GAP
        slots.take(n).forEachIndexed { i, slot ->
            val last = i == n - 1
            slices += Slice(
                x, y, cw, s, if (last) Corners(INNER, INNER, end, end) else Corners.all(INNER), slot,
                Glyph(cw / 2, s / 2 - if (last) nudge else 0f, icon),
            )
            y += s + GAP
        }
        return CaptureSpec(CaptureShape.Column, w, h, slices, MainFill.Accent, null)
    }

    // ---- Bento ----

    /** Height of a tile label line, and the gap between a tile's icon and its label. */
    const val LABEL_H = 18f
    const val LABEL_GAP = 6f

    fun bento(w: Float, h: Float, slots: List<Int>, outerRadius: Float = OUTER_RADIUS): CaptureSpec {
        val big = outerRadius.coerceIn(TILE_INNER, min(w, h) / 4)
        fun corners(x: Float, y: Float, sw: Float, sh: Float): Corners {
            fun edge(v: Float, end: Float) = kotlin.math.abs(v) < 0.5f || kotlin.math.abs(v - end) < 0.5f
            fun at(cx: Float, cy: Float) = if (edge(cx, w) && edge(cy, h)) big else TILE_INNER
            return Corners(at(x, y), at(x + sw, y), at(x + sw, y + sh), at(x, y + sh))
        }
        val wide = w >= h
        val n = slots.size
        // The main tile; the rest of the widget is the action grid.
        val (mw, mh) = when {
            n == 0 -> w to h
            wide -> (w * 0.36f).coerceIn(120f, 220f).coerceAtMost(w - GAP - (if (n == 1) TILE_MIN else TILE_MIN * 2 + GAP)) to h
            else -> w to (h * 0.34f).coerceIn(104f, 200f)
        }
        val main = mainTile(mw, mh, corners(0f, 0f, mw, mh))
        val slices = mutableListOf(main)
        if (n > 0) {
            val gx = if (wide) mw + GAP else 0f
            val gy = if (wide) 0f else mh + GAP
            val gw = w - gx
            val gh = h - gy
            val cols = if (n == 1) 1 else 2
            val rows = ceil(n / cols.toFloat()).toInt()
            val tw = (gw - GAP * (cols - 1)) / cols
            val th = (gh - GAP * (rows - 1)) / rows
            slots.forEachIndexed { i, slot ->
                val c = i % cols
                val r = i / cols
                val span = i == n - 1 && n % cols != 0
                val sw = if (span) gw else tw
                val x = gx + c * (tw + GAP)
                val y = gy + r * (th + GAP)
                slices += tile(x, y, sw, th, corners(x, y, sw, th), slot)
            }
        }
        val coin = main.glyph.let { g -> Coin(main.x + g.cx, main.y + g.cy, coinRadius(mw, mh), 0f, g.size) }
        return CaptureSpec(CaptureShape.Bento, w, h, slices, MainFill.Wash, coin)
    }

    private fun coinRadius(mw: Float, mh: Float) = (min(mw, mh) * 0.2f).coerceIn(24f, 34f)

    private fun mainTile(mw: Float, mh: Float, corners: Corners): Slice {
        val r = coinRadius(mw, mh)
        val glyph = (r * 0.95f).coerceIn(22f, 32f)
        val block = 2 * r + 10f + LABEL_H + 2f
        return if (mh >= block + 16f && mw >= 96f) {
            val top = (mh - block) / 2
            Slice(0f, 0f, mw, mh, corners, Slice.MAIN, Glyph(mw / 2, top + r, glyph), LabelBox(6f, top + 2 * r + 10f, mw - 12f, LABEL_H + 2f))
        } else {
            Slice(0f, 0f, mw, mh, corners, Slice.MAIN, Glyph(mw / 2, mh / 2, glyph))
        }
    }

    private fun tile(x: Float, y: Float, sw: Float, sh: Float, corners: Corners, slot: Int): Slice {
        val icon = (min(sw, sh) * 0.3f).coerceIn(24f, 30f)
        val block = icon + LABEL_GAP + LABEL_H
        return if (sh >= block + 20f && sw >= 64f) {
            val top = (sh - block) / 2
            Slice(x, y, sw, sh, corners, slot, Glyph(sw / 2, top + icon / 2, icon), LabelBox(4f, top + icon + LABEL_GAP, sw - 8f, LABEL_H))
        } else {
            Slice(x, y, sw, sh, corners, slot, Glyph(sw / 2, sh / 2, icon))
        }
    }

    /** Icon of the main button: "+" for a new note, else the action's own icon. */
    fun mainIcon(action: CaptureButton): Int = if (action == CaptureButton.NewNote) Oui.drawable.ic_oui_add else action.icon
}
