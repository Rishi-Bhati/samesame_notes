package org.samesame.notes.core.richtext.spans

import android.graphics.Paint
import android.text.style.LineHeightSpan
import org.samesame.notes.core.richtext.TemplateLineAlignment

/**
 * Forces every line it covers to a whole number of template rules tall (see [TemplateLineAlignment]). The extra
 * space is put above the baseline, so the glyphs keep their position in the line and the caller can align the
 * page's first baseline to the first rule. Added by `BodyText.setLinePitch` and never persisted.
 */
class LinePitchSpan(@Volatile var pitch: Float) : LineHeightSpan {

    override fun chooseHeight(
        text: CharSequence,
        start: Int,
        end: Int,
        spanstartv: Int,
        lineTop: Int,
        fm: Paint.FontMetricsInt,
    ) {
        val p = pitch
        if (p <= 0f) return
        val natural = (fm.descent - fm.ascent).toFloat()
        if (natural <= 0f) return
        val target = TemplateLineAlignment.lineHeight(natural, p)
        val extra = (target - natural).toInt()
        if (extra <= 0) return
        fm.ascent -= extra
        fm.top -= extra
    }
}
