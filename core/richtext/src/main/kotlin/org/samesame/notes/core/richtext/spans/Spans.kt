package org.samesame.notes.core.richtext.spans

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.Spanned
import android.text.TextPaint
import android.text.style.AlignmentSpan
import android.text.style.LeadingMarginSpan
import android.text.style.MetricAffectingSpan
import android.text.style.ReplacementSpan
import org.samesame.notes.core.model.Limits
import org.samesame.notes.core.model.richtext.CharStyle
import org.samesame.notes.core.model.richtext.ListKind
import org.samesame.notes.core.model.richtext.ParagraphStyle
import org.samesame.notes.core.model.richtext.TextAlign
import org.samesame.notes.core.richtext.BodyTextMetrics

/** Text size in pu for a model font size (null = note default); scaled to the reference's glyph metrics. */
fun fontSizePu(fontSize: Float?): Float =
    (fontSize ?: Limits.DEFAULT_FONT_SIZE) * Limits.FONT_PU_PER_POINT * BodyTextMetrics.GLYPH_SCALE

/**
 * One run's [CharStyle]: the only character-style span in a body, so the Spannable maps 1:1 onto
 * model runs. Characters without one have the default style.
 */
class CharStyleSpan(val style: CharStyle, private val env: TextRenderEnv) : MetricAffectingSpan() {
    override fun updateMeasureState(tp: TextPaint) = applyMetrics(tp)

    override fun updateDrawState(tp: TextPaint) {
        applyMetrics(tp)
        tp.isUnderlineText = style.underline
        tp.isStrikeThruText = style.strikethrough
        style.color?.let { tp.color = env.colorTransform(it.toInt()) }
        style.highlight?.let { tp.bgColor = env.colorTransform(it.toInt()) }
    }

    private fun applyMetrics(tp: TextPaint) {
        if (style.fontSize != null) tp.textSize = fontSizePu(style.fontSize)
        if (style.bold || style.italic) {
            val tf = env.typeface(style.bold, style.italic)
            tp.typeface = tf
            // Fall back to synthetic styles when the font has no real bold/italic face.
            val missing = ((if (style.bold) 1 else 0) or (if (style.italic) 2 else 0)) and tf.style.inv()
            if (missing and 1 != 0) tp.isFakeBoldText = true
            if (missing and 2 != 0) tp.textSkewX = -0.25f
        }
    }

    override fun toString() = "CharStyleSpan($style)"
}

/**
 * An inline object (U+FFFC). Sits on the baseline; scaled down to the column width when wider.
 */
class InlineObjectSpan(val objectId: String, private val env: TextRenderEnv) : ReplacementSpan() {

    private fun size(): ObjectSize {
        val s = env.objectProvider.size(objectId)
        if (s.width <= env.maxObjectWidth || s.width <= 0f) return s
        val k = env.maxObjectWidth / s.width
        return ObjectSize(s.width * k, s.height * k)
    }

    override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        val s = size()
        if (fm != null) {
            val pfm = paint.fontMetricsInt
            fm.ascent = -s.height.toInt()
            fm.top = fm.ascent
            fm.descent = pfm.descent
            fm.bottom = pfm.bottom
        }
        return s.width.toInt()
    }

    override fun draw(canvas: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
        val s = size()
        env.objectProvider.draw(canvas, objectId, RectF(x, y - s.height, x + s.width, y.toFloat()))
    }
}

/**
 * One paragraph's [ParagraphStyle]: indent and list marker as a hanging leading margin, alignment,
 * and the marker itself (drawn by [ListMarkerSpan] / [ChecklistSpan]). Covers the paragraph
 * including its `\n`. Default-styled paragraphs have no span.
 *
 * NOTE(WP-F): the doc lists ListMarkerSpan and ChecklistSpan as spans; Android sums the margins of
 * all LeadingMarginSpans in an unspecified order, so one span owns the margin and delegates the
 * drawing to those two marker renderers instead.
 */
class ParagraphSpan(val style: ParagraphStyle, private val env: TextRenderEnv) : LeadingMarginSpan, AlignmentSpan {
    /** 1-based number for Numbered paragraphs, kept current by BodyText. */
    var number: Int = 0

    override fun getLeadingMargin(first: Boolean): Int = leadingMargin(style).toInt()

    override fun getAlignment(): Layout.Alignment = alignmentOf(style.align)

    override fun drawLeadingMargin(
        c: Canvas, p: Paint, x: Int, dir: Int, top: Int, baseline: Int, bottom: Int,
        text: CharSequence, start: Int, end: Int, first: Boolean, layout: Layout,
    ) {
        if (!first || style.list == ListKind.None) return
        // Marker size follows the first character of the paragraph.
        val size = (text as? Spanned)?.getSpans(start, minOf(start + 1, end), CharStyleSpan::class.java)
            ?.firstOrNull { text.getSpanStart(it) <= start }?.style?.fontSize
        val left = x + dir * style.indent * BodyTextMetrics.INDENT_STEP
        drawMarker(c, p, left, baseline.toFloat(), fontSizePu(size), style, number, env)
    }

    companion object {
        fun leadingMargin(style: ParagraphStyle): Float =
            style.indent * BodyTextMetrics.INDENT_STEP + if (style.list != ListKind.None) BodyTextMetrics.MARKER_WIDTH else 0f

        fun alignmentOf(align: TextAlign): Layout.Alignment = when (align) {
            TextAlign.Left -> Layout.Alignment.ALIGN_NORMAL
            TextAlign.Center -> Layout.Alignment.ALIGN_CENTER
            TextAlign.Right -> Layout.Alignment.ALIGN_OPPOSITE
        }

        /** Draws the marker of [style] whose box starts at [left]; used for spans and for the empty last paragraph. */
        fun drawMarker(c: Canvas, p: Paint, left: Float, baseline: Float, em: Float, style: ParagraphStyle, number: Int, env: TextRenderEnv) {
            when (style.list) {
                ListKind.None -> Unit
                ListKind.Bullet -> ListMarkerSpan.drawBullet(c, p, left, baseline, em)
                ListKind.Numbered -> ListMarkerSpan.drawNumber(c, p, left, baseline, em, number)
                ListKind.Checklist -> ChecklistSpan.draw(c, p, left, baseline, em, style.checked, env.pageColor)
            }
        }
    }
}

/** Bullet and number markers. NOTE(WP-F): not captured yet; drawn in the checkbox's box. */
object ListMarkerSpan {
    private val tmp = Paint(Paint.ANTI_ALIAS_FLAG)

    fun drawBullet(c: Canvas, p: Paint, left: Float, baseline: Float, em: Float) {
        val m = BodyTextMetrics
        val cx = left + m.MARKER_INSET + em * m.CHECKBOX_SIZE_EM / 2f
        val cy = baseline - em * m.MARKER_CENTER_ABOVE_BASELINE_EM
        tmp.color = p.color
        tmp.style = Paint.Style.FILL
        c.drawCircle(cx, cy, em * m.BULLET_RADIUS_EM, tmp)
    }

    fun drawNumber(c: Canvas, p: Paint, left: Float, baseline: Float, em: Float, number: Int) {
        tmp.set(p)
        tmp.textSize = em
        tmp.textAlign = Paint.Align.RIGHT
        // Right-aligned so the text column stays put as numbers grow.
        c.drawText("$number.", left + BodyTextMetrics.MARKER_WIDTH - em * 0.35f, baseline, tmp)
    }
}

/** Checkbox marker (#74): rounded square, ~1 em, centred on the cap height. */
object ChecklistSpan {
    private val tmp = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()
    private val path = android.graphics.Path()

    fun boxRect(left: Float, baseline: Float, em: Float, out: RectF = RectF()): RectF {
        val m = BodyTextMetrics
        val size = em * m.CHECKBOX_SIZE_EM
        val cy = baseline - em * m.MARKER_CENTER_ABOVE_BASELINE_EM
        out.set(left + m.MARKER_INSET, cy - size / 2f, left + m.MARKER_INSET + size, cy + size / 2f)
        return out
    }

    fun draw(c: Canvas, p: Paint, left: Float, baseline: Float, em: Float, checked: Boolean, pageColor: Int) {
        val m = BodyTextMetrics
        boxRect(left, baseline, em, box)
        val stroke = em * m.CHECKBOX_STROKE_EM
        val r = em * m.CHECKBOX_CORNER_EM
        tmp.reset()
        tmp.isAntiAlias = true
        tmp.color = p.color
        // Same outline either way; a checked box keeps its empty inside and gets a tick (the dimming comes from [p]'s colour).
        tmp.style = Paint.Style.STROKE
        tmp.strokeWidth = stroke
        box.inset(stroke / 2f, stroke / 2f)
        c.drawRoundRect(box, r, r, tmp)
        if (checked) {
            tmp.strokeWidth = stroke * 1.2f
            tmp.strokeCap = Paint.Cap.ROUND
            tmp.strokeJoin = Paint.Join.ROUND
            val w = box.width()
            path.reset()
            path.moveTo(box.left + w * 0.24f, box.top + w * 0.52f)
            path.lineTo(box.left + w * 0.43f, box.top + w * 0.70f)
            path.lineTo(box.left + w * 0.77f, box.top + w * 0.33f)
            c.drawPath(path, tmp)
        }
    }
}
