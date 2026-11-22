package org.samesame.notes.core.richtext.spans

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Build
import android.text.Spannable
import android.text.Spanned
import android.text.TextPaint
import android.text.style.ReplacementSpan
import kotlin.math.ceil

/**
 * Draws one arrow character (→ ← ↔ ⇒ ⇔ …, see [ArrowGlyphs]) a little larger and bold, centred on the surrounding
 * text's x-height, because in the body fonts these glyphs look small and thin next to the letters. Display only:
 * the character in the text is untouched, the span is never read into the model ([RichTextBridge] reads
 * [CharStyleSpan]s only), and `BodyText` keeps the spans in step with the text ([ArrowGlyphs.refresh]).
 *
 * A [ReplacementSpan] rather than a plain metric span because the platform applies every other metric span
 * (the run's own [CharStyleSpan] size, bold, italic, colour) to the paint before measuring or drawing a
 * replacement, whatever order the spans were added in, so the boost is always relative to the run's own size.
 * The line metrics are left as the run's ([getSize] doesn't touch `fm`), so a line with an arrow is no taller.
 */
class ArrowGlyphSpan : ReplacementSpan() {

    override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        if (text == null || end <= start) return 0
        val p = arrowPaint(paint)
        return ceil(p.measureText(text, start, end)).toInt()
    }

    override fun draw(canvas: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
        if (text == null || end <= start) return
        val p = arrowPaint(paint)
        val width = p.measureText(text, start, end)
        (paint as? TextPaint)?.bgColor?.takeIf { it != 0 }?.let { bg ->
            val fill = scratchFill.get()!!
            fill.set(paint)
            fill.style = Paint.Style.FILL
            fill.color = bg
            canvas.drawRect(x, top.toFloat(), x + ceil(width), bottom.toFloat(), fill)
        }
        val bounds = scratchRect.get()!!
        // Centre of the run's x-height (negative: above the baseline) and of the enlarged arrow glyph.
        paint.getTextBounds(X_HEIGHT_PROBE, 0, 1, bounds)
        val textMiddle = bounds.exactCenterY()
        val glyph = text.subSequence(start, end).toString() // getTextBounds(CharSequence) needs API 29
        p.getTextBounds(glyph, 0, glyph.length, bounds)
        val arrowMiddle = if (bounds.isEmpty) textMiddle else bounds.exactCenterY()
        canvas.drawText(text, start, end, x, y + (textMiddle - arrowMiddle), p)
        drawDecorations(canvas, paint, x, x + width, y.toFloat())
    }

    /** Underline / strike-through of the run (a replacement isn't decorated by the platform), at the run's own baseline. */
    private fun drawDecorations(canvas: Canvas, paint: Paint, left: Float, right: Float, baseline: Float) {
        if (!paint.isUnderlineText && !paint.isStrikeThruText) return
        val fill = scratchFill.get()!!
        fill.set(paint)
        fill.style = Paint.Style.FILL
        val size = paint.textSize
        val thickness: Float
        val underline: Float
        val strike: Float
        if (Build.VERSION.SDK_INT >= 29) {
            thickness = paint.underlineThickness.takeIf { it > 0f } ?: (size / 18f)
            underline = paint.underlinePosition
            strike = paint.strikeThruPosition
        } else {
            thickness = size / 18f
            underline = size * 0.111f
            strike = -size * 0.28f
        }
        if (paint.isUnderlineText) canvas.drawRect(left, baseline + underline, right, baseline + underline + thickness, fill)
        if (paint.isStrikeThruText) canvas.drawRect(left, baseline + strike - thickness / 2f, right, baseline + strike + thickness / 2f, fill)
    }

    override fun toString() = "ArrowGlyphSpan"

    companion object {
        /** Arrow size relative to the run's text size (the owner asked for "a little bigger"). */
        const val SCALE = 1.18f

        private const val X_HEIGHT_PROBE = "x"

        // Layout may be shaped off the main thread (BodyTextEngine.prepare), so scratch objects are per thread.
        private val scratchPaint = ThreadLocal.withInitial { TextPaint() }
        private val scratchFill = ThreadLocal.withInitial { Paint() }
        private val scratchRect = ThreadLocal.withInitial { Rect() }

        /** [base] (already styled by the run's spans) enlarged by [SCALE] and bold unless the run is bold already. */
        internal fun arrowPaint(base: Paint): TextPaint {
            val p = scratchPaint.get()!!
            p.set(base)
            p.isUnderlineText = false
            p.isStrikeThruText = false
            p.textSize = base.textSize * SCALE
            val tf = base.typeface ?: Typeface.DEFAULT
            if (!base.isFakeBoldText && !tf.isBold) {
                val bold = Typeface.create(tf, if (tf.isItalic) Typeface.BOLD_ITALIC else Typeface.BOLD)
                p.typeface = bold
                // Fonts without a bold face (and the symbol fallbacks most arrows come from) get a synthetic one.
                if (!bold.isBold) p.isFakeBoldText = true
            }
            return p
        }
    }
}

/** Which characters get an [ArrowGlyphSpan], and keeping those spans in step with a text. */
object ArrowGlyphs {

    /** Emoji presentation selector: `↔️` is an emoji and keeps its own rendering. */
    private const val EMOJI_PRESENTATION = '️'

    /**
     * The arrows the "replace arrows" shortcut produces (→ ← ↔ ⇒ ⇔) and their siblings when typed or pasted
     * directly: U+2190–U+2199 (← ↑ → ↓ ↔ ↕ ↖ ↗ ↘ ↙) and U+21D0–U+21D5 (⇐ ⇑ ⇒ ⇓ ⇔ ⇕). All BMP, so a surrogate half
     * of an emoji can never match.
     */
    fun isArrow(c: Char): Boolean = c in '←'..'↙' || c in '⇐'..'⇕'

    /** True when the character at [index] of [text] is drawn with an [ArrowGlyphSpan]. */
    fun isStyledArrowAt(text: CharSequence, index: Int): Boolean =
        index in 0 until text.length && isArrow(text[index]) &&
            (index + 1 >= text.length || text[index + 1] != EMOJI_PRESENTATION)

    /**
     * Makes the [ArrowGlyphSpan]s of `[start, end)` match the text: one single-character span on each styled arrow
     * and none elsewhere. Callers pass the changed range widened by one character on each side (a following U+FE0F
     * changes whether an arrow is styled).
     */
    fun refresh(text: Spannable, start: Int, end: Int) {
        val s = start.coerceIn(0, text.length)
        val e = end.coerceIn(s, text.length)
        for (sp in text.getSpans(s, e, ArrowGlyphSpan::class.java)) {
            val ss = text.getSpanStart(sp)
            val se = text.getSpanEnd(sp)
            if (se - ss != 1 || !isStyledArrowAt(text, ss)) text.removeSpan(sp)
        }
        for (i in s until e) {
            if (!isStyledArrowAt(text, i)) continue
            val has = text.getSpans(i, i + 1, ArrowGlyphSpan::class.java).any { text.getSpanStart(it) == i }
            if (!has) text.setSpan(ArrowGlyphSpan(), i, i + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
}
