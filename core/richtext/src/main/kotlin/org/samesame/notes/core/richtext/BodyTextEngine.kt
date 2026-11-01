package org.samesame.notes.core.richtext

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Build
import android.text.DynamicLayout
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import org.samesame.notes.core.model.richtext.ListKind
import org.samesame.notes.core.model.richtext.ParagraphStyle
import org.samesame.notes.core.model.richtext.TextAlign
import org.samesame.notes.core.richtext.layout.LineMetrics
import org.samesame.notes.core.richtext.layout.Pagination
import org.samesame.notes.core.richtext.layout.Paginator
import org.samesame.notes.core.richtext.layout.PuRect
import org.samesame.notes.core.richtext.layout.TextPageGeometry
import org.samesame.notes.core.richtext.spans.ChecklistSpan
import org.samesame.notes.core.richtext.spans.ParagraphSpan
import org.samesame.notes.core.richtext.spans.fontSizePu
import java.text.BreakIterator

/** Colours for body text. Explicit run colours go through `TextRenderEnv.colorTransform` instead. */
data class RichTextColors(
    /** Automatic text colour (also list markers and checkboxes). */
    val text: Int,
    val hint: Int,
    val caret: Int,
    val selection: Int,
    val handle: Int,
    /** Page colour, used inside checked checkboxes. */
    val page: Int,
    /** In-note search: every match / the focused match (R1). */
    val searchMatch: Int = 0x4DFC7F66,
    val searchCurrent: Int = 0xA6FC7F66.toInt(),
) {
    companion object {
        /** #74 (dark): text #DADADA, caret #FF5E36 (4 px), page near-black; #27: handle blue. */
        val Dark = RichTextColors(
            text = 0xFFDADADA.toInt(), hint = 0xFF8A8A8A.toInt(), caret = 0xFFFF5E36.toInt(),
            selection = 0x664297FF, handle = 0xFF4297FF.toInt(), page = 0xFF030303.toInt(),
        )

        /** NOTE(WP-F): light-mode values not captured; mirrored from the dark ones. */
        val Light = RichTextColors(
            text = 0xFF252525.toInt(), hint = 0xFF8A8A8A.toInt(), caret = 0xFFF0532D.toInt(),
            selection = 0x552F7FE8, handle = 0xFF2F7FE8.toInt(), page = Color.WHITE,
        )
    }
}

/** A caret position in document space (pu). */
data class CaretGeometry(val pageIndex: Int, val x: Float, val top: Float, val bottom: Float)

/** What to draw on top of the text. */
data class TextDecorations(
    val caretVisible: Boolean = false,
    val selectionVisible: Boolean = true,
    val hint: String? = null,
    /** Underline links ("Show links in notes"). */
    val links: Boolean = false,
    /** In-note search hits as `[start, end)` body offsets, painted under the glyphs (R1). */
    val searchMatches: List<IntRange> = emptyList(),
    /** Index into [searchMatches] of the focused hit (painted stronger), or -1. */
    val searchCurrent: Int = -1,
)

/**
 * Lays the body out as one `DynamicLayout` column (pu, incremental on edits), maps it onto pages
 * ([pagination]), answers hit tests and caret/selection geometry in document space and draws page
 * slices (doc §2.2). Main thread only.
 */
class BodyTextEngine(val body: BodyText, geometry: TextPageGeometry) {

    val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = BodyTextMetrics.DEFAULT_TEXT_SIZE_PU
        isSubpixelText = true
        @Suppress("DEPRECATION")
        isLinearText = true // glyphs are scaled by the canvas zoom, not hinted at 1×
        typeface = body.env.typeface
    }

    var colors: RichTextColors = RichTextColors.Light
        set(value) {
            field = value
            paint.color = value.text
            body.env.pageColor = value.page
            drawRevision++
            body.invalidateDrawing()
        }

    var geometry: TextPageGeometry = geometry
        set(value) {
            val pitchBefore = templateLinePitch()
            if (value == field) return
            val widthChanged = value.columnWidth != field.columnWidth
            field = value
            if (widthChanged || templateLinePitch() != pitchBefore) layout = createLayout()
            cached = null
            drawRevision++
            body.invalidateDrawing()
        }

    var layout: Layout = createLayout()
        private set

    private var cached: Pagination? = null
    private var cachedVersion = -1L
    private val path = Path()
    private val tmpPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tmpRect = RectF()

    init {
        paint.color = colors.text
        body.env.maxObjectWidth = geometry.columnWidth
    }

    private fun createLayout(): Layout {
        val width = geometry.columnWidth.toInt()
        body.env.maxObjectWidth = width.toFloat()
        // Ruled pages: the line height is a whole number of template rules (LinePitchSpan), so leave the line
        // spacing multiplier at 1 and let the span do the work.
        body.setLinePitch(templateLinePitch())
        val mult = if (templateLinePitch() != null) 1f else BodyTextMetrics.LINE_SPACING_MULT
        return if (Build.VERSION.SDK_INT >= 28) {
            DynamicLayout.Builder.obtain(body.editable, paint, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, mult)
                .setIncludePad(false)
                .setUseLineSpacingFromFallbacks(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            DynamicLayout(body.editable, paint, width, Layout.Alignment.ALIGN_NORMAL, mult, 0f, false)
        }
    }

    /**
     * The ruled-template spacing shared by every ruled page (null when no page has one, or when pages mix
     * different templates: one column layout can only use one pitch). Blank/image/PDF pages are not counted.
     */
    fun templateLinePitch(): Float? {
        if (geometry.pageCount <= 0) return null
        var found: Float? = null
        for (i in 0 until geometry.pageCount) {
            val p = geometry.linePitch(i) ?: continue
            if (found == null) found = p else if (found != p) return null
        }
        return found
    }

    /** Extra shift (doc pu) that puts the first line of page [pageIndex] on the page's first template rule. */
    private fun templateShift(pageIndex: Int): Float {
        val pag = pagination()
        val slice = pag.slices.getOrNull(pageIndex) ?: return 0f
        if (slice.isEmpty) return 0f
        val pitch = geometry.linePitch(pageIndex) ?: return 0f
        if (pitch != templateLinePitch()) return 0f
        return TemplateLineAlignment.pageShift(
            firstRule = geometry.firstRuleOffset(pageIndex),
            pageTop = geometry.pageRect(pageIndex).top,
            contentTop = geometry.contentRect(pageIndex).top,
            columnTop = slice.columnTop,
            firstBaseline = layout.getLineBaseline(slice.firstLine).toFloat(),
        )
    }

    // ---------------------------------------------------------------- pagination

    private val lineMetrics = object : LineMetrics {
        override val lineCount: Int get() = layout.lineCount
        override fun lineTop(line: Int) = layout.getLineTop(line).toFloat()
        override fun lineBottom(line: Int) = layout.getLineBottom(line).toFloat()
    }

    fun pagination(): Pagination {
        val c = cached
        if (c != null && cachedVersion == body.layoutVersion) return c
        return Paginator.paginate(lineMetrics, layout.height.toFloat(), geometry).also {
            cached = it
            cachedVersion = body.layoutVersion
        }
    }

    /** Pages the text needs; when larger than `geometry.pageCount` the session appends pages. */
    val requiredPageCount: Int get() = pagination().requiredPageCount

    fun pageSlices() = pagination().slices

    // ---------------------------------------------------------------- geometry

    /** Index of the page at (or nearest to) a document point, among existing and required pages. */
    fun pageIndexAt(x: Float, y: Float): Int {
        val n = pagination().slices.size
        var best = 0
        var bestD = Float.MAX_VALUE
        for (i in 0 until n) {
            val d = geometry.pageRect(i).distanceSq(x, y)
            if (d < bestD) {
                bestD = d
                best = i
                if (d == 0f) break
            }
        }
        return best
    }

    /** Document point → (layout x, layout y) in the column, clamped to the page's slice. */
    private fun docToLayout(x: Float, y: Float): Triple<Int, Float, Float> {
        val page = pageIndexAt(x, y)
        val slice = pagination().slices[page]
        val content = geometry.contentRect(page)
        val ly = (slice.columnTop + (y - content.top - templateShift(page)))
            .coerceIn(slice.columnTop, maxOf(slice.columnTop, slice.columnBottom - 0.5f))
        return Triple(page, x - content.left, ly)
    }

    /** Text offset at a document point. */
    fun hitTest(x: Float, y: Float): Int {
        val (page, lx, ly) = docToLayout(x, y)
        val slice = pagination().slices[page]
        if (slice.isEmpty) return body.length
        val line = layout.getLineForVertical(ly.toInt()).coerceIn(slice.firstLine, slice.lastLine)
        return layout.getOffsetForHorizontal(line, lx)
    }

    /**
     * Index of the checklist paragraph whose checkbox is at a document point, or null. The hit area is the box plus about a
     * fifth of its size on the left and a quarter on the right, over the full height of the item's first line.
     */
    fun checkboxAt(x: Float, y: Float): Int? {
        val (_, lx, ly) = docToLayout(x, y)
        val offset = layout.getOffsetForHorizontal(layout.getLineForVertical(ly.toInt()), lx)
        val p = body.paragraphIndexAt(offset)
        val style = body.paragraphStyleOrNull(p) ?: return null
        if (style.list != ListKind.Checklist) return null
        val start = body.paragraphRange(p).first
        val line = layout.getLineForOffset(start)
        val box = ChecklistSpan.boxRect(style.indent * BodyTextMetrics.INDENT_STEP, layout.getLineBaseline(line).toFloat(), markerEm(start), tmpRect)
        val size = box.width()
        val hit = RectF(box.left - size * 0.22f, layout.getLineTop(line).toFloat(), box.right + size * 0.28f, layout.getLineBottom(line).toFloat())
        return if (hit.contains(lx, ly)) p else null
    }

    /** True when the document point lies on a line of body text (the line's own extent, not the whole page width). */
    fun isOnText(x: Float, y: Float): Boolean {
        if (body.length == 0 || layout.lineCount == 0) return false
        val (_, lx, ly) = docToLayout(x, y)
        if (ly < 0f || ly > layout.height) return false
        val line = layout.getLineForVertical(ly.toInt())
        if (layout.getLineVisibleEnd(line) <= layout.getLineStart(line)) return false
        val left = layout.getLineLeft(line)
        return lx >= left && lx <= layout.getLineMax(line).coerceAtLeast(left)
    }

    /** Caret rectangle for [offset] in document space. */
    fun caret(offset: Int): CaretGeometry {
        val o = offset.coerceIn(0, body.length)
        val line = layout.getLineForOffset(o)
        val slice = pagination().sliceForLine(line)
        val content = geometry.contentRect(slice.pageIndex)
        val dy = content.top - slice.columnTop + templateShift(slice.pageIndex)
        return CaretGeometry(
            pageIndex = slice.pageIndex,
            x = content.left + caretX(o, line),
            top = layout.getLineTop(line) + dy,
            bottom = lineBottomForCaret(line) + dy,
        )
    }

    /**
     * NOTE(WP-F): DynamicLayout adds no line spacing to the last line, so its caret would be shorter
     * than every other line's; extend it to the spaced height.
     */
    private fun lineBottomForCaret(line: Int): Float {
        val top = layout.getLineTop(line)
        val bottom = layout.getLineBottom(line)
        return if (line == layout.lineCount - 1) top + (bottom - top) * BodyTextMetrics.LINE_SPACING_MULT else bottom.toFloat()
    }

    private fun caretX(offset: Int, line: Int): Float {
        val empty = emptyLastParagraph()
        if (empty != null && offset == body.length) return emptyLineX(empty)
        return layout.getPrimaryHorizontal(offset)
    }

    /** Style of the last paragraph when it is empty and styled (Android gives it no paragraph spans). */
    private fun emptyLastParagraph(): ParagraphStyle? {
        val n = body.length
        if (n > 0 && body.editable[n - 1] != '\n') return null
        val style = body.lastParagraphStyle()
        return if (style == DEFAULT_PARAGRAPH) null else style
    }

    private fun emptyLineX(style: ParagraphStyle): Float {
        val margin = ParagraphSpan.leadingMargin(style)
        return when (style.align) {
            TextAlign.Left -> margin
            TextAlign.Center -> margin + (layout.width - margin) / 2f
            TextAlign.Right -> layout.width.toFloat()
        }
    }

    private fun markerEm(paragraphStart: Int): Float {
        val size = if (paragraphStart < body.length) RichTextBridge.charStyleAt(body.editable, paragraphStart).fontSize else null
        return fontSizePu(size ?: body.typingStyle?.fontSize)
    }

    /** Word around [offset] (for long-press / double-tap); empty range when not on a word. */
    fun wordAt(offset: Int): IntRange {
        val text = body.editable.toString()
        if (text.isEmpty()) return 0 until 0
        val o = offset.coerceIn(0, text.length)
        val it = BreakIterator.getWordInstance()
        it.setText(text)
        var end = it.following(minOf(o, text.length - 1).coerceAtLeast(0))
        if (end == BreakIterator.DONE) end = text.length
        var start = it.previous()
        if (start == BreakIterator.DONE) start = 0
        // At the end of a word (caret after the last letter), take that word.
        if ((start until end).none { i -> Character.isLetterOrDigit(text[i]) } && o > 0) {
            val e2 = it.preceding(o).let { s -> if (s == BreakIterator.DONE) 0 else s }
            val f = it.following(e2).let { e -> if (e == BreakIterator.DONE) text.length else e }
            if ((e2 until f).any { i -> Character.isLetterOrDigit(text[i]) }) return e2 until f
            return o until o
        }
        return start until end
    }

    /** Bounding box of the selection in document space (for the floating toolbar), or the caret. */
    fun selectionBounds(start: Int, end: Int): PuRect {
        val a = caret(minOf(start, end))
        val b = caret(maxOf(start, end))
        if (a.pageIndex != b.pageIndex || a.top != b.top) {
            val content = geometry.contentRect(a.pageIndex)
            return PuRect(content.left, a.top, content.right, if (a.pageIndex == b.pageIndex) b.bottom else geometry.contentRect(a.pageIndex).bottom)
        }
        return PuRect(minOf(a.x, b.x), a.top, maxOf(a.x, b.x), a.bottom)
    }

    // ---------------------------------------------------------------- drawing

    /**
     * Draws the body slice of page [pageIndex]. With [inPageSpace] the canvas origin is the page's
     * top-left in pu (NoteCanvas layer 4); otherwise it is the document origin.
     */
    fun drawPage(canvas: Canvas, pageIndex: Int, decorations: TextDecorations = TextDecorations(), inPageSpace: Boolean = true) {
        drawPageUnder(canvas, pageIndex, decorations, inPageSpace)
        drawPageStatic(canvas, pageIndex, decorations, inPageSpace)
        drawPageOver(canvas, pageIndex, decorations, inPageSpace)
    }

    /**
     * Saves [canvas] and sets the clip and translation of page [pageIndex]'s slice (layout coordinates from then on).
     * Returns the save count to restore, or -1 when the page has no slice (nothing was saved).
     */
    private fun enterPage(canvas: Canvas, pageIndex: Int, inPageSpace: Boolean): Int {
        val pag = pagination()
        if (pageIndex !in pag.slices.indices) return -1
        val slice = pag.slices[pageIndex]
        val page = geometry.pageRect(pageIndex)
        val content = geometry.contentRect(pageIndex)
        val shiftX = if (inPageSpace) -page.left else 0f
        val shiftY = if (inPageSpace) -page.top else 0f
        val ruled = templateShift(pageIndex)
        val save = canvas.save()
        // Horizontally the whole page (italic overhang), vertically the content rect. On a ruled page the first
        // baseline moves above the top margin, so the clip starts at the paper edge there.
        canvas.clipRect(
            page.left + shiftX,
            (if (ruled != 0f) page.top else content.top) + shiftY,
            page.right + shiftX,
            content.bottom + shiftY,
        )
        canvas.translate(content.left + shiftX, content.top + shiftY - slice.columnTop + ruled)
        canvas.clipRect(-content.left + page.left, slice.columnTop, page.right - content.left, maxOf(slice.columnBottom, slice.columnTop))
        return save
    }

    /** Live part under the glyphs: the selection and the in-note search hits (paths cached per layout and range). */
    fun drawPageUnder(canvas: Canvas, pageIndex: Int, decorations: TextDecorations, inPageSpace: Boolean = true) {
        val sel = body.selection
        val selection = decorations.selectionVisible && !sel.collapsed
        if (!selection && decorations.searchMatches.isEmpty()) return
        val save = enterPage(canvas, pageIndex, inPageSpace)
        if (save < 0) return
        if (selection) {
            tmpPaint.style = Paint.Style.FILL
            tmpPaint.color = colors.selection
            canvas.drawPath(selectionPath(sel.min, sel.max), tmpPaint)
        }
        if (decorations.searchMatches.isNotEmpty()) drawSearchMatches(canvas, decorations)
        canvas.restoreToCount(save)
    }

    /**
     * The static part (hint, body glyphs with link and checked-item colouring, link underlines): changes only with the
     * text, its layout, colours, [TextDecorations.links] and the hint, as tracked by [staticVersion].
     */
    fun drawPageStatic(canvas: Canvas, pageIndex: Int, decorations: TextDecorations, inPageSpace: Boolean = true) {
        val save = enterPage(canvas, pageIndex, inPageSpace)
        if (save < 0) return
        val slice = pagination().slices[pageIndex]
        if (body.length == 0 && decorations.hint != null && pageIndex == 0) {
            drawHint(canvas, decorations.hint)
        }
        drawBodyText(canvas, slice.firstLine, slice.lastLine, decorations.links)
        if (decorations.links) drawLinks(canvas)
        canvas.restoreToCount(save)
    }

    /** Live part over the glyphs: the marker of an empty styled last paragraph (follows the typing style) and the caret. */
    fun drawPageOver(canvas: Canvas, pageIndex: Int, decorations: TextDecorations, inPageSpace: Boolean = true) {
        val sel = body.selection
        val empty = emptyLastParagraph()
        val caret = decorations.caretVisible && sel.collapsed
        if (empty == null && !caret) return
        val save = enterPage(canvas, pageIndex, inPageSpace)
        if (save < 0) return
        val pag = pagination()
        val slice = pag.slices[pageIndex]
        if (empty != null) {
            val line = layout.lineCount - 1
            if (line in slice.firstLine..slice.lastLine) {
                ParagraphSpan.drawMarker(
                    canvas, paint, empty.indent * BodyTextMetrics.INDENT_STEP, layout.getLineBaseline(line).toFloat(),
                    fontSizePu(body.typingStyle?.fontSize), empty, body.numbers.lastOrNull() ?: 0, body.env,
                )
            }
        }
        if (caret) {
            val line = layout.getLineForOffset(sel.min)
            if (pag.sliceForLine(line).pageIndex == pageIndex) {
                val x = caretX(sel.min, line)
                val w = BodyTextMetrics.CARET_WIDTH
                tmpPaint.color = colors.caret
                tmpPaint.style = Paint.Style.FILL
                canvas.drawRect(x - w / 2f, layout.getLineTop(line).toFloat(), x + w / 2f, lineBottomForCaret(line), tmpPaint)
            }
        }
        canvas.restoreToCount(save)
    }

    // ---------------------------------------------------------------- static version (page layer caching)

    /** Bumped by redraw-only changes made through the engine (colours, geometry). */
    private var drawRevision = 0L
    private val staticTracker = StaticTextVersion()

    /**
     * Version of what [drawPageStatic] paints with [decorations]; a page layer recorded at another version is stale.
     * Selection, caret and search changes do not bump it.
     */
    fun staticVersion(decorations: TextDecorations): Long =
        staticTracker.update(body.layoutVersion, drawRevision, layout, decorations.links, decorations.hint.takeIf { body.length == 0 })

    // ---------------------------------------------------------------- cached highlight paths

    private var selPathLayoutVersion = -1L
    private var selPathLayout: Layout? = null
    private var selPathStart = -1
    private var selPathEnd = -1

    /** The selection path of `[start, end)` over the whole column, rebuilt only when the range or the layout changes. */
    private fun selectionPath(start: Int, end: Int): Path {
        if (start != selPathStart || end != selPathEnd || body.layoutVersion != selPathLayoutVersion || layout !== selPathLayout) {
            path.reset()
            layout.getSelectionPath(start, end, path)
            selPathStart = start
            selPathEnd = end
            selPathLayoutVersion = body.layoutVersion
            selPathLayout = layout
        }
        return path
    }

    private val matchPath = Path()
    private val currentMatchPath = Path()
    private var matchList: List<IntRange>? = null
    private var matchCurrent = -2
    private var matchLayoutVersion = -1L
    private var matchLayout: Layout? = null

    /**
     * Paints the search hits; the focused one last, in the stronger colour. Both paths cover the whole column and are
     * rebuilt only when the hits, the focused hit or the layout change; the page clip limits what is painted.
     */
    private fun drawSearchMatches(canvas: Canvas, decorations: TextDecorations) {
        if (layout.lineCount == 0) return
        val matches = decorations.searchMatches
        if (matches !== matchList || decorations.searchCurrent != matchCurrent || body.layoutVersion != matchLayoutVersion || layout !== matchLayout) {
            matchPath.reset()
            currentMatchPath.reset()
            val n = body.length
            for (i in matches.indices) {
                val m = matches[i]
                val s = m.first.coerceIn(0, n)
                val e = (m.last + 1).coerceIn(s, n)
                if (i == decorations.searchCurrent) {
                    layout.getSelectionPath(s, e, currentMatchPath)
                } else {
                    selPath.reset()
                    layout.getSelectionPath(s, e, selPath)
                    matchPath.addPath(selPath)
                }
            }
            matchList = matches
            matchCurrent = decorations.searchCurrent
            matchLayoutVersion = body.layoutVersion
            matchLayout = layout
        }
        tmpPaint.style = Paint.Style.FILL
        if (!matchPath.isEmpty) {
            tmpPaint.color = colors.searchMatch
            canvas.drawPath(matchPath, tmpPaint)
        }
        if (!currentMatchPath.isEmpty) {
            tmpPaint.color = colors.searchCurrent
            canvas.drawPath(currentMatchPath, tmpPaint)
        }
    }

    private var linkVersion = -1L
    private var linkCache: List<TextLink> = emptyList()

    /** Links in the body text, recomputed only when the text changed. */
    fun links(): List<TextLink> {
        if (linkVersion != body.layoutVersion) {
            linkCache = LinkDetector.find(body.editable)
            linkVersion = body.layoutVersion
        }
        return linkCache
    }

    /** The link under a document point, or null. */
    fun linkAt(x: Float, y: Float): TextLink? {
        val list = links()
        if (list.isEmpty() || body.length == 0) return null
        val o = hitTest(x, y)
        return list.firstOrNull { o >= it.start && o < it.end }
    }

    private val checkedPath = Path()
    private val linkPath = Path()
    private val unionPath = Path()
    private val selPath = Path()
    private val selBounds = RectF()

    /** Horizontal extent of `[s, e)` within one layout line (handles a wrapped line's end offset). */
    private fun segmentX(s: Int, e: Int): Pair<Float, Float>? {
        selPath.reset()
        layout.getSelectionPath(s, e, selPath)
        if (selPath.isEmpty) return null
        selPath.computeBounds(selBounds, true)
        return selBounds.left to selBounds.right
    }

    private var decoVersion = -1L
    private var decoLayout: Layout? = null
    private var decoLinks = false
    private var hasDecoration = false
    private val checkedLineList = ArrayList<Int>()
    /** x1, y, x2, y per link line segment, for the underline pass. */
    private var underlines = FloatArray(0)
    private var underlineCount = 0

    /**
     * Link and checked-item regions of the whole text, rebuilt only when the text, its paragraph styles or the layout change
     * (not per frame): the page's own clip limits what is actually painted.
     */
    private fun ensureDecorations(withLinks: Boolean) {
        val v = body.layoutVersion
        if (v == decoVersion && layout === decoLayout && withLinks == decoLinks) return
        decoVersion = v
        decoLayout = layout
        decoLinks = withLinks
        checkedLineList.clear()
        checkedPath.reset()
        linkPath.reset()
        underlineCount = 0
        if (layout.lineCount > 0) {
            if (body.hasCheckedItems()) {
                val text = body.editable
                var p = 0
                for (line in 0 until layout.lineCount) {
                    val start = layout.getLineStart(line)
                    if (line > 0 && start > 0 && text[start - 1] == '\n') p++
                    val st = body.paragraphStyleOrNull(p) ?: continue
                    if (st.list == ListKind.Checklist && st.checked) {
                        checkedLineList += line
                        checkedPath.addRect(-1000f, layout.getLineTop(line).toFloat(), layout.width + 1000f, layout.getLineBottom(line).toFloat(), Path.Direction.CW)
                    }
                }
            }
            if (withLinks) {
                for (l in links()) {
                    val a = layout.getLineForOffset(l.start)
                    val b = layout.getLineForOffset(l.end)
                    for (line in a..b) {
                        val s = maxOf(l.start, layout.getLineStart(line))
                        val e = minOf(l.end, layout.getLineVisibleEnd(line))
                        if (e <= s) continue
                        selPath.reset()
                        layout.getSelectionPath(s, e, selPath)
                        linkPath.addPath(selPath)
                        segmentX(s, e)?.let { x ->
                            if ((underlineCount + 1) * 4 > underlines.size) underlines = underlines.copyOf(maxOf(64, underlines.size * 2))
                            val y = layout.getLineBaseline(line) + 2f
                            val o = underlineCount * 4
                            underlines[o] = x.first; underlines[o + 1] = y; underlines[o + 2] = x.second; underlines[o + 3] = y
                            underlineCount++
                        }
                    }
                }
                if (!linkPath.isEmpty && !checkedPath.isEmpty) linkPath.op(checkedPath, Path.Op.DIFFERENCE)
            }
        }
        hasDecoration = !checkedPath.isEmpty || !linkPath.isEmpty
        if (hasDecoration) {
            unionPath.set(checkedPath)
            unionPath.op(linkPath, Path.Op.UNION)
        }
    }

    /**
     * Draws the body text. Link runs are drawn in the link blue, checked checklist items dimmed to 40 % with a strike-through
     * across every line. Each region is drawn by clipping and drawing the layout again with another base colour; plain text
     * (no links, no checked items) is a single `layout.draw`.
     */
    private fun drawBodyText(canvas: Canvas, firstLine: Int, lastLine: Int, withLinks: Boolean) {
        ensureDecorations(withLinks)
        if (!hasDecoration) {
            layout.draw(canvas)
            return
        }
        var save = canvas.save()
        canvas.clipOutPath(unionPath)
        layout.draw(canvas)
        canvas.restoreToCount(save)
        if (!linkPath.isEmpty) {
            save = canvas.save()
            canvas.clipPath(linkPath)
            paint.color = colors.handle
            layout.draw(canvas)
            canvas.restoreToCount(save)
        }
        if (!checkedPath.isEmpty) {
            save = canvas.save()
            canvas.clipPath(checkedPath)
            paint.color = blend(colors.text, colors.page, BodyTextMetrics.CHECKED_ALPHA)
            layout.draw(canvas)
            canvas.restoreToCount(save)
            drawStrikes(canvas, checkedLineList, firstLine, lastLine)
        }
        paint.color = colors.text
    }

    private fun drawStrikes(canvas: Canvas, lines: List<Int>, firstLine: Int, lastLine: Int) {
        tmpPaint.style = Paint.Style.STROKE
        tmpPaint.color = blend(colors.text, colors.page, BodyTextMetrics.CHECKED_STRIKE_ALPHA)
        tmpPaint.strokeWidth = paint.textSize * BodyTextMetrics.CHECKBOX_STROKE_EM * 0.92f
        val y = paint.textSize * 0.30f
        for (line in lines) {
            if (line < firstLine) continue
            if (line > lastLine) break
            val start = layout.getLineStart(line)
            val end = layout.getLineVisibleEnd(line)
            // Skip the list marker: the strike starts at the text.
            val x = (if (end > start) segmentX(start, end) else null) ?: continue
            val yy = layout.getLineBaseline(line) - y
            canvas.drawLine(x.first, yy, x.second, yy, tmpPaint)
        }
        tmpPaint.style = Paint.Style.FILL
    }

    /** [fg] over [bg] at [alpha], opaque. */
    private fun blend(fg: Int, bg: Int, alpha: Float): Int {
        fun ch(f: Int, b: Int) = (f * alpha + b * (1f - alpha)).toInt().coerceIn(0, 255)
        return Color.rgb(
            ch(Color.red(fg), Color.red(bg)), ch(Color.green(fg), Color.green(bg)), ch(Color.blue(fg), Color.blue(bg)),
        )
    }

    private fun drawLinks(canvas: Canvas) {
        if (underlineCount == 0) return
        tmpPaint.style = Paint.Style.STROKE
        tmpPaint.color = colors.handle
        tmpPaint.strokeWidth = 1.5f
        canvas.drawLines(underlines, 0, underlineCount * 4, tmpPaint)
        tmpPaint.style = Paint.Style.FILL
    }

    /** Draws every page intersecting [visible] (document space; null = all) on a document-space canvas. */
    fun drawDocument(canvas: Canvas, decorations: TextDecorations = TextDecorations(), visible: PuRect? = null) {
        for (i in pagination().slices.indices) {
            val r = geometry.pageRect(i)
            if (visible != null && (r.right < visible.left || r.left > visible.right || r.bottom < visible.top || r.top > visible.bottom)) continue
            drawPage(canvas, i, decorations, inPageSpace = false)
        }
    }

    private var hintLayout: StaticLayout? = null
    private var hintText: String? = null

    private fun drawHint(canvas: Canvas, hint: String) {
        if (hintText != hint || hintLayout?.width != layout.width) {
            val p = TextPaint(paint)
            hintLayout = StaticLayout.Builder.obtain(hint, 0, hint.length, p, layout.width)
                .setLineSpacing(0f, BodyTextMetrics.LINE_SPACING_MULT)
                .setIncludePad(false)
                .build()
            hintText = hint
        }
        hintLayout!!.paint.color = colors.hint
        hintLayout!!.draw(canvas)
    }
}

private val DEFAULT_PARAGRAPH = ParagraphStyle()
