package org.samesame.notes.core.richtext.layout

import org.samesame.notes.core.model.Limits
import org.samesame.notes.core.richtext.BodyTextMetrics

/** Axis-aligned rectangle in page units (document or page space, as documented at each use). */
data class PuRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    fun contains(x: Float, y: Float): Boolean = x >= left && x < right && y >= top && y < bottom
    fun translate(dx: Float, dy: Float) = PuRect(left + dx, top + dy, right + dx, bottom + dy)

    /** Squared distance from (x, y) to this rect (0 inside). */
    fun distanceSq(x: Float, y: Float): Float {
        val dx = if (x < left) left - x else if (x > right) x - right else 0f
        val dy = if (y < top) top - y else if (y > bottom) y - bottom else 0f
        return dx * dx + dy * dy
    }
}

/** A point in document space (pu). */
data class PuPoint(val x: Float, val y: Float)

/**
 * Document (pu) → view (px) transform for zoom and scroll: `view = doc * scale + translate`.
 * WP-G's `Viewport` maps onto this (no rotation is ever needed).
 */
data class ViewTransform(val scale: Float = 1f, val translateX: Float = 0f, val translateY: Float = 0f) {
    fun docToViewX(x: Float) = x * scale + translateX
    fun docToViewY(y: Float) = y * scale + translateY
    fun viewToDocX(x: Float) = (x - translateX) / scale
    fun viewToDocY(y: Float) = (y - translateY) / scale
    fun viewToDoc(x: Float, y: Float) = PuPoint(viewToDocX(x), viewToDocY(y))
}

/**
 * The minimum the text engine needs to know about pages (doc §2.1/§2.2). WP-G's `DocumentLayout`
 * implements or adapts to this; [SimplePageGeometry] is a standalone implementation.
 *
 * All content rects must have the same width (the text column width); their positions are free,
 * so vertical and horizontal page flows both work. [pageRect]/[contentRect] must also answer for
 * indices ≥ [pageCount]: virtual pages the text already needs before the session appends them.
 */
interface TextPageGeometry {
    val pageCount: Int

    /** Infinite mode: pages are seamless segments and text is not pushed at page boundaries. */
    val continuous: Boolean

    /** Page rect in document space. */
    fun pageRect(index: Int): PuRect

    /** Rect the body text may occupy on page [index], in document space. */
    fun contentRect(index: Int): PuRect

    /** Content height of a page that would be appended after the last one. */
    val nextPageContentHeight: Float

    val columnWidth: Float get() = if (pageCount > 0) contentRect(0).width else BodyTextMetrics.COLUMN_WIDTH

    /**
     * Distance between the horizontal rules of the press template of page [index], in pu, or null when the page
     * has no ruled template (blank page, image/PDF background, table, ...). Body text on such a page is laid out so
     * its baselines sit on those rules (see `TemplateLineAlignment`).
     */
    fun linePitch(index: Int): Float? = null

    /** Distance from the page's top edge down to its first template rule, in pu. Defaults to one pitch. */
    fun firstRuleOffset(index: Int): Float = linePitch(index) ?: 0f
}

enum class PageFlow { Vertical, Horizontal }

/**
 * Pages of one size laid out in a row or column. In [continuous] mode the gap is ignored, the top
 * margin applies only to the first segment and there is no bottom margin (doc §1.2).
 */
data class SimplePageGeometry(
    override val pageCount: Int,
    val pageHeight: Float = Limits.PAGE_WIDTH_PU * 1.4142857f,
    val gap: Float = DEFAULT_PAGE_GAP,
    override val continuous: Boolean = false,
    val flow: PageFlow = PageFlow.Vertical,
    val marginLeft: Float = BodyTextMetrics.MARGIN_LEFT,
    val marginTop: Float = BodyTextMetrics.MARGIN_TOP,
    val marginRight: Float = BodyTextMetrics.MARGIN_RIGHT,
    val marginBottom: Float = BodyTextMetrics.MARGIN_BOTTOM,
) : TextPageGeometry {
    private val pageWidth = Limits.PAGE_WIDTH_PU
    private val effectiveGap get() = if (continuous) 0f else gap

    override fun pageRect(index: Int): PuRect = when (flow) {
        PageFlow.Vertical -> {
            val top = index * (pageHeight + effectiveGap)
            PuRect(0f, top, pageWidth, top + pageHeight)
        }
        PageFlow.Horizontal -> {
            val left = index * (pageWidth + effectiveGap)
            PuRect(left, 0f, left + pageWidth, pageHeight)
        }
    }

    override fun contentRect(index: Int): PuRect {
        val p = pageRect(index)
        return if (continuous) {
            PuRect(p.left + marginLeft, p.top + if (index == 0) marginTop else 0f, p.right - marginRight, p.bottom)
        } else {
            PuRect(p.left + marginLeft, p.top + marginTop, p.right - marginRight, p.bottom - marginBottom)
        }
    }

    override val nextPageContentHeight: Float
        get() = if (continuous) pageHeight else pageHeight - marginTop - marginBottom

    companion object {
        /** NOTE(WP-F): PAGE_GAP_PU is provisional in the doc (not captured); WP-G owns the real value. */
        const val DEFAULT_PAGE_GAP = 26f
    }
}
