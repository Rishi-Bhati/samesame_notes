package org.samesame.notes.feature.composer.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import org.samesame.notes.core.model.Limits
import org.samesame.notes.core.model.PageMode
import org.samesame.notes.core.model.document.PageLayout
import org.samesame.notes.core.model.document.PageSpec
import org.samesame.notes.core.model.document.TemplateRef
import org.samesame.notes.core.model.document.ScrollDirection
import org.samesame.notes.core.richtext.layout.PuRect
import org.samesame.notes.core.richtext.layout.StackedPageGeometry
import org.samesame.notes.core.richtext.layout.TextPageGeometry
import kotlin.math.ceil

/**
 * Places the pages of a note in document space (page units, origin = top-left of the first page).
 * Pure and cheap: O(pages) to build, O(log n) to query. See docs/editor-architecture.md 2.1.
 *
 * Every page is [Limits.PAGE_WIDTH_PU] wide; its height is its own (format v6 `PageSpec.size`, e.g. an imported PDF
 * page) or the note's ratio. Vertical: page i sits at (0, sum of the heights before it + i * gap). Horizontal (Pages
 * mode only, Infinite is always vertical): (i * (width + gap), 0), pages top-aligned. Infinite mode: gap 0 (seamless
 * segments). The text engine sees the same geometry through [StackedPageGeometry], which the export renderer shares.
 */
class DocumentLayout(
    override val pageCount: Int,
    val layout: PageLayout,
    gapPu: Float = PAGE_GAP_PU,
    /** Height over width of each page; missing entries (or an empty list) use the note's ratio. */
    pageRatios: List<Float> = emptyList(),
    /** Rule spacing of each page's built-in line/grid/dot template (pu); null for unruled pages. */
    private val lineSpacings: List<Float?> = emptyList(),
) : TextPageGeometry {
    /** The layout of [pages] with their own sizes and template line spacings. */
    constructor(pages: List<PageSpec>, layout: PageLayout, gapPu: Float = PAGE_GAP_PU) :
        this(
            pages.size,
            layout,
            gapPu,
            pages.map { it.heightOverWidth(layout) },
            pages.map(::pageLineSpacing).let { spacings -> if (spacings.any { it != null }) spacings else emptyList() },
        )

    val isInfinite: Boolean = layout.mode == PageMode.Infinite
    val horizontal: Boolean = !isInfinite && layout.scroll == ScrollDirection.Horizontal
    val gapPu: Float = if (isInfinite) 0f else gapPu

    /** Size of a page without its own size (pages appended later, virtual text pages). */
    val defaultPageSize: Size = Size(Limits.PAGE_WIDTH_PU, Limits.PAGE_WIDTH_PU * layout.ratio.heightOverWidth)

    private val geometry = StackedPageGeometry(
        heights = List(pageCount) { i -> Limits.PAGE_WIDTH_PU * (pageRatios.getOrNull(i) ?: layout.ratio.heightOverWidth) },
        defaultHeight = defaultPageSize.height,
        gap = this.gapPu,
        continuous = isInfinite,
        horizontal = horizontal,
    )

    val pageRects: List<Rect> = List(pageCount) { i -> geometry.pageRect(i).toRect() }

    /** Size of page [index] (the default size past the end). */
    fun pageSize(index: Int): Size = pageRects.getOrNull(index)?.size ?: defaultPageSize

    /** Uniform-page notes: the size every page has. Mixed sizes: the size of the first page. Prefer [pageSize]. */
    val pageSize: Size get() = pageSize(0)

    val contentSize: Size = when {
        pageCount == 0 -> Size.Zero
        horizontal -> Size(geometry.totalExtent, geometry.maxHeight)
        else -> Size(Limits.PAGE_WIDTH_PU, geometry.totalExtent)
    }

    private fun axis(doc: Offset): Float = if (horizontal) doc.x else doc.y

    /** Index of the page containing [doc], or null in a gap / outside the document. */
    fun pageAt(doc: Offset): Int? {
        if (pageCount == 0) return null
        val i = geometry.indexAtOrBefore(axis(doc))
        for (c in intArrayOf(i, i - 1, i + 1)) if (c in 0 until pageCount && pageRects[c].contains(doc)) return c
        return null
    }

    /** Page nearest to [doc] along the scroll axis (never null unless empty): used to attribute ink that starts in a gap. */
    fun nearestPage(doc: Offset): Int? {
        if (pageCount == 0) return null
        val coord = axis(doc)
        val i = geometry.indexAtOrBefore(coord)
        val end = if (horizontal) pageRects[i].right else pageRects[i].bottom
        // In the gap after page i: the half nearer the next page belongs to it.
        return if (i + 1 < pageCount && coord >= end + gapPu / 2f) i + 1 else i
    }

    /** Pages intersecting [viewportDoc]; an empty range when none. */
    fun visiblePages(viewportDoc: Rect): IntRange {
        if (pageCount == 0) return IntRange.EMPTY
        val (lo, hi) = if (horizontal) viewportDoc.left to viewportDoc.right else viewportDoc.top to viewportDoc.bottom
        var first = geometry.indexAtOrBefore(lo)
        val firstEnd = if (horizontal) pageRects[first].right else pageRects[first].bottom
        if (firstEnd <= lo) first++ // lo is in the gap after `first` (or past the end)
        val lastStart = geometry.indexAtOrBefore(hi)
        val last = if ((if (horizontal) pageRects[lastStart].left else pageRects[lastStart].top) < hi) lastStart else lastStart - 1
        val r = first.coerceAtLeast(0)..last.coerceAtMost(pageCount - 1)
        return if (r.isEmpty()) IntRange.EMPTY else r
    }

    fun pageOrigin(index: Int): Offset = pageRects[index].topLeft

    fun docToPage(index: Int, doc: Offset): Offset = doc - pageRects[index].topLeft

    fun pageToDoc(index: Int, page: Offset): Offset = page + pageRects[index].topLeft

    // ---- TextPageGeometry (the text engine's view of the pages; answers for virtual pages past pageCount too)

    override val continuous: Boolean get() = isInfinite

    override fun pageRect(index: Int): PuRect = geometry.pageRect(index)

    override fun contentRect(index: Int): PuRect = geometry.contentRect(index)

    override fun linePitch(index: Int): Float? = lineSpacings.getOrNull(index)

    /**
     * First rule of the page. A page on its own starts one pitch below the paper edge (the template's top
     * margin); in seamless mode the pattern continues across pages, so the first rule can sit anywhere in
     * (0, pitch].
     */
    override fun firstRuleOffset(index: Int): Float {
        val p = lineSpacings.getOrNull(index) ?: return 0f
        if (layout.mode != PageMode.Infinite) return p
        val top = geometry.pageRect(index).top
        return if (top <= 0f) p else ceil(top / p) * p - top
    }

    override val nextPageContentHeight: Float get() = geometry.nextPageContentHeight

    // Value equality so the text engine does not re-paginate when an equal layout is rebuilt.
    override fun equals(other: Any?): Boolean =
        other is DocumentLayout && other.pageCount == pageCount && other.layout == layout && other.geometry == geometry &&
            other.lineSpacings == lineSpacings

    override fun hashCode(): Int =
        ((pageCount * 31 + layout.hashCode()) * 31 + geometry.hashCode()) * 31 + lineSpacings.hashCode()

    companion object {
        /** Measured from #107 (1080 px wide): 26 px grey gap between pages (about 10 dp). */
        const val PAGE_GAP_PU = 26f
    }
}

private fun PuRect.toRect(): Rect = Rect(left, top, right, bottom)

/** Rule spacing of a page's built-in template; null for blank pages and custom image/PDF templates. */
private fun pageLineSpacing(spec: PageSpec): Float? =
    ((spec.template as? TemplateRef.Builtin)?.template)?.let(TemplatePainter::spacing)
