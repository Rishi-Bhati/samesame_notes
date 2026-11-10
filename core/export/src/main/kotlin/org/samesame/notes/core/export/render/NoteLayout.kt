package org.samesame.notes.core.export.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.AbsoluteSizeSpan
import android.text.style.AlignmentSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.ReplacementSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.graphics.Typeface
import android.os.Build
import org.samesame.notes.core.export.text.toParagraphs
import org.samesame.notes.core.model.Limits
import org.samesame.notes.core.model.PageMode
import org.samesame.notes.core.model.document.ImageObject
import org.samesame.notes.core.model.document.NoteDocument
import org.samesame.notes.core.model.document.pageHeightOverWidth
import org.samesame.notes.core.model.richtext.ListKind
import org.samesame.notes.core.model.richtext.OBJECT_CHAR
import org.samesame.notes.core.model.richtext.RichText
import org.samesame.notes.core.model.richtext.TextAlign
import kotlin.math.ceil
import kotlin.math.min

internal const val MARGIN_X = 47f
internal const val MARGIN_Y = 32f
internal const val MARGIN_BOTTOM = 96f
internal const val A4_RATIO = 1.4142857f

/** Same glyph metrics as the composer's body text (core/richtext `BodyTextMetrics`). */
private const val GLYPH_SCALE = 1.14f
private const val LINE_SPACING_MULT = 1.16f
private val BODY_TEXT_SIZE_PU = Limits.DEFAULT_FONT_SIZE * Limits.FONT_PU_PER_POINT * GLYPH_SCALE
private const val INDENT_PU = 48
private const val LIST_GAP = 80

/** Builds the Android spannable for rich text; sizes are in page units (1 sp of the reference = 2.625 pu). */
/** Link runs (file links, URLs) print blue and underlined; PDF link annotations come with the later PDF engine. */
private const val LINK_COLOR: Int = 0xFF0563C1.toInt()

internal fun buildSpannable(
    rt: RichText,
    media: (String) -> Bitmap?,
    objects: Map<String, ImageObject>,
    maxImageWidth: Float,
    colorTransform: (Int) -> Int = { it },
): SpannableStringBuilder {
    val out = SpannableStringBuilder(rt.text)
    var pos = 0
    for (run in rt.runs) {
        val s = run.style
        val end = (pos + run.length).coerceAtMost(rt.text.length)
        if (end > pos) {
            val f = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            out.setSpan(ForegroundColorSpan(colorTransform(if (s.link != null) LINK_COLOR else s.color?.toInt() ?: Color.BLACK)), pos, end, f)
            s.highlight?.let { out.setSpan(BackgroundColorSpan(colorTransform(it.toInt())), pos, end, f) }
            val face = when {
                s.bold && s.italic -> Typeface.BOLD_ITALIC
                s.bold -> Typeface.BOLD
                s.italic -> Typeface.ITALIC
                else -> Typeface.NORMAL
            }
            if (face != Typeface.NORMAL) out.setSpan(StyleSpan(face), pos, end, f)
            if (s.underline || s.link != null) out.setSpan(UnderlineSpan(), pos, end, f)
            if (s.strikethrough) out.setSpan(StrikethroughSpan(), pos, end, f)
            s.fontSize?.let { out.setSpan(AbsoluteSizeSpan((it * Limits.FONT_PU_PER_POINT * GLYPH_SCALE).toInt().coerceAtLeast(1), false), pos, end, f) }
            if (s.objectId != null && end - pos == 1 && rt.text[pos] == OBJECT_CHAR) {
                val img = objects[s.objectId]
                val bmp = img?.let { media(it.media) }
                out.setSpan(InlineImageSpan(bmp, img?.widthPu ?: 0f, img?.heightPu ?: 0f, maxImageWidth), pos, end, f)
            }
        }
        pos = end
    }
    // paragraph spans
    var start = 0
    for (p in rt.toParagraphs()) {
        val nl = rt.text.indexOf('\n', start)
        val end = if (nl < 0) rt.text.length else nl + 1
        if (end > start) {
            val align = when (p.style.align) {
                TextAlign.Left -> Layout.Alignment.ALIGN_NORMAL
                TextAlign.Center -> Layout.Alignment.ALIGN_CENTER
                TextAlign.Right -> Layout.Alignment.ALIGN_OPPOSITE
            }
            val f = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            out.setSpan(AlignmentSpan.Standard(align), start, end, f)
            val marker = when (p.style.list) {
                ListKind.None -> null
                ListKind.Bullet -> Marker.Bullet
                ListKind.Numbered -> Marker.Number("${p.number ?: 1}.")
                ListKind.Checklist -> if (p.style.checked) Marker.Checked else Marker.Unchecked
            }
            out.setSpan(MarkerSpan(p.style.indent * INDENT_PU, marker, colorTransform), start, end, f)
            if (p.style.list == ListKind.Checklist && p.style.checked) {
                // Checked item: text dimmed to 40 % and struck through (no newline in the span).
                val textEnd = if (end > start && out[end - 1] == '\n') end - 1 else end
                if (textEnd > start) out.setSpan(CheckedTextSpan(colorTransform(Color.WHITE)), start, textEnd, f)
            }
        }
        start = end
    }
    return out
}

private const val CHECKED_ALPHA = 0.4f

private fun dim(color: Int, page: Int): Int {
    fun ch(f: Int, b: Int) = (f * CHECKED_ALPHA + b * (1f - CHECKED_ALPHA)).toInt().coerceIn(0, 255)
    return Color.argb(255, ch(Color.red(color), Color.red(page)), ch(Color.green(color), Color.green(page)), ch(Color.blue(color), Color.blue(page)))
}

/** Dims the text colour towards the page colour and strikes it through. */
private class CheckedTextSpan(private val pageColor: Int) : android.text.style.CharacterStyle() {
    override fun updateDrawState(tp: android.text.TextPaint) {
        tp.color = dim(tp.color, pageColor)
        tp.isStrikeThruText = true
    }
}

internal sealed interface Marker {
    data object Bullet : Marker
    data class Number(val label: String) : Marker
    data object Unchecked : Marker
    data object Checked : Marker
}

private class MarkerSpan(
    private val indent: Int,
    private val marker: Marker?,
    private val colorTransform: (Int) -> Int = { it },
) : LeadingMarginSpan {
    override fun getLeadingMargin(first: Boolean): Int = indent + if (marker != null) LIST_GAP else 0

    override fun drawLeadingMargin(
        c: Canvas, p: Paint, x: Int, dir: Int, top: Int, baseline: Int, bottom: Int,
        text: CharSequence, start: Int, end: Int, first: Boolean, layout: Layout,
    ) {
        if (!first || marker == null) return
        val left = x + indent.toFloat()
        val size = p.textSize
        val old = Paint(p)
        p.color = colorTransform(Color.BLACK).let { if (marker == Marker.Checked) dim(it, colorTransform(Color.WHITE)) else it }
        p.style = Paint.Style.FILL
        p.isFakeBoldText = false
        when (marker) {
            Marker.Bullet -> c.drawCircle(left + 14f, baseline - size * 0.33f, size * 0.12f, p)
            is Marker.Number -> { p.textSize = size; c.drawText(marker.label, left, baseline.toFloat(), p) }
            Marker.Unchecked, Marker.Checked -> {
                val box = size * 0.62f
                val r = RectF(left + 4f, baseline - box, left + 4f + box, baseline.toFloat())
                p.style = Paint.Style.STROKE
                p.strokeWidth = size * 0.07f
                c.drawRoundRect(r, 4f, 4f, p)
                if (marker == Marker.Checked) {
                    val path = Path().apply {
                        moveTo(r.left + box * 0.2f, r.top + box * 0.55f)
                        lineTo(r.left + box * 0.42f, r.top + box * 0.78f)
                        lineTo(r.left + box * 0.82f, r.top + box * 0.22f)
                    }
                    c.drawPath(path, p)
                }
            }
        }
        p.set(old)
    }
}

/** Draws a bitmap in the text flow; nothing for a missing bitmap (width 0 for non-image objects). */
private class InlineImageSpan(
    private val bitmap: Bitmap?, widthPu: Float, heightPu: Float, maxWidth: Float,
) : ReplacementSpan() {
    private val scale = if (widthPu > 0f) min(1f, maxWidth / widthPu) else 1f
    private val w = if (bitmap != null) widthPu * scale else 0f
    private val h = if (bitmap != null) heightPu * scale else 0f

    override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        if (fm != null && h > 0f) {
            fm.ascent = -h.toInt()
            fm.top = fm.ascent
            fm.descent = 0
            fm.bottom = 0
        }
        return ceil(w).toInt()
    }

    override fun draw(canvas: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
        val b = bitmap ?: return
        canvas.drawBitmap(b, null, RectF(x, y - h, x + w, y.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))
    }
}

internal fun layoutText(
    rt: RichText,
    widthPu: Int,
    media: (String) -> Bitmap?,
    objects: Map<String, ImageObject>,
    colorTransform: (Int) -> Int = { it },
): StaticLayout {
    val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = BODY_TEXT_SIZE_PU
        isSubpixelText = true
        @Suppress("DEPRECATION")
        isLinearText = true // same glyph advances as the composer's body layout
        color = colorTransform(Color.BLACK)
    }
    val sp = buildSpannable(rt, media, objects, widthPu.toFloat(), colorTransform)
    val builder = StaticLayout.Builder.obtain(sp, 0, sp.length, paint, widthPu)
        .setLineSpacing(0f, LINE_SPACING_MULT)
        .setIncludePad(false)
    if (Build.VERSION.SDK_INT >= 28) builder.setUseLineSpacingFromFallbacks(true)
    return builder.build()
}

/** One page's share of the body text. */
internal class TextSlice(val startLine: Int, val endLine: Int, val srcTop: Float, val srcBottom: Float)

/**
 * The note body laid out once at the page width (1080 pu minus margins) and cut into pages:
 * paged notes break between lines; infinite notes are sliced at exact segment boundaries.
 */
internal class NoteLayout(
    val doc: NoteDocument,
    media: (String) -> Bitmap?,
    private val colorTransform: (Int) -> Int = { it },
) {
    val infinite = doc.layout.mode == PageMode.Infinite
    val pageW = Limits.PAGE_WIDTH_PU
    val ratio = if (infinite) A4_RATIO else doc.layout.ratio.heightOverWidth
    val pageH = pageW * ratio

    /** Height of page [i] in pu: its own size (v6) in paged notes, the uniform segment height in infinite notes. */
    fun pageHeight(i: Int): Float = if (infinite) pageH else pageW * doc.pageHeightOverWidth(i)
    private val textWidth = (pageW - 2 * MARGIN_X).toInt()
    val layout: StaticLayout? = if (doc.body.text.isEmpty()) null
    else layoutText(doc.body, textWidth, media, doc.inlineObjects.filterIsInstance<ImageObject>().associateBy { it.id }, colorTransform)

    val slices: List<TextSlice> = buildSlices()

    private fun buildSlices(): List<TextSlice> {
        val l = layout ?: return emptyList()
        val result = ArrayList<TextSlice>()
        if (infinite) {
            val total = MARGIN_Y + l.height
            val n = ceil(total / pageH).toInt().coerceAtLeast(1)
            for (i in 0 until n) {
                val top = (i * pageH - MARGIN_Y).coerceAtLeast(0f)
                val bottom = ((i + 1) * pageH - MARGIN_Y).coerceAtMost(l.height.toFloat())
                result += TextSlice(l.getLineForVertical(top.toInt()), l.getLineForVertical((bottom - 1f).coerceAtLeast(0f).toInt()) + 1, top, bottom)
            }
        } else {
            var startLine = 0
            for (line in 0 until l.lineCount) {
                val used = l.getLineBottom(line) - l.getLineTop(startLine)
                val contentH = pageHeight(result.size) - MARGIN_Y - MARGIN_BOTTOM
                if (used > contentH && line > startLine) {
                    result += TextSlice(startLine, line, l.getLineTop(startLine).toFloat(), l.getLineTop(line).toFloat())
                    startLine = line
                }
            }
            result += TextSlice(startLine, l.lineCount, l.getLineTop(startLine).toFloat(), l.height.toFloat())
        }
        return result
    }

    val textPageCount: Int get() = slices.size

    fun drawText(canvas: Canvas, pageIndex: Int) {
        val l = layout ?: return
        val s = slices.getOrNull(pageIndex) ?: return
        canvas.save()
        if (infinite) {
            canvas.clipRect(0f, 0f, pageW, pageH)
            canvas.translate(MARGIN_X, MARGIN_Y - pageIndex * pageH)
        } else {
            canvas.clipRect(0f, MARGIN_Y, pageW, MARGIN_Y + (s.srcBottom - s.srcTop))
            canvas.translate(MARGIN_X, MARGIN_Y - s.srcTop)
        }
        l.draw(canvas)
        canvas.restore()
    }

    fun pageText(pageIndex: Int): String {
        val l = layout ?: return ""
        val s = slices.getOrNull(pageIndex) ?: return ""
        if (s.endLine <= s.startLine) return ""
        val from = l.getLineStart(s.startLine)
        val to = l.getLineEnd(s.endLine - 1)
        return l.text.subSequence(from, to).toString().replace(OBJECT_CHAR.toString(), "").trimEnd('\n')
    }

    fun hasText(pageIndex: Int): Boolean = pageText(pageIndex).isNotBlank() || (slices.getOrNull(pageIndex)?.let { s ->
        layout != null && s.endLine > s.startLine && layout.text.subSequence(layout.getLineStart(s.startLine), layout.getLineEnd(s.endLine - 1)).contains(OBJECT_CHAR)
    } ?: false)
}
