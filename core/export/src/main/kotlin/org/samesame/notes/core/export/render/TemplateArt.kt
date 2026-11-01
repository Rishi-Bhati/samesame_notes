package org.samesame.notes.core.export.render

import android.graphics.Canvas
import android.graphics.Paint
import org.samesame.notes.core.model.PageTemplate

/** Simple own-drawn page templates (lines, grid, dots, ...) in page units. */
internal object TemplateArt {
    private const val INK = 0xFFC9CED6.toInt()
    private const val STRONG = 0xFF9AA1AD.toInt()

    private fun stroke(transform: (Int) -> Int, argb: Int = INK, width: Float = 1.5f) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = transform(argb); style = Paint.Style.STROKE; strokeWidth = width
    }

    private fun spacing(t: PageTemplate) = when (t) {
        PageTemplate.LinesNarrow, PageTemplate.GridNarrow, PageTemplate.DotsNarrow -> 40f
        PageTemplate.LinesMedium, PageTemplate.GridMedium, PageTemplate.DotsMedium -> 60f
        PageTemplate.LinesWide, PageTemplate.GridWide, PageTemplate.DotsWide -> 84f
        else -> 60f
    }

    fun draw(c: Canvas, t: PageTemplate, w: Float, h: Float, transform: (Int) -> Int = { it }) {
        val p = stroke(transform)
        when (t) {
            PageTemplate.Blank -> Unit
            PageTemplate.LinesNarrow, PageTemplate.LinesMedium, PageTemplate.LinesWide -> {
                val s = spacing(t)
                var y = MARGIN_Y + s
                while (y < h - MARGIN_Y) { c.drawLine(MARGIN_X, y, w - MARGIN_X, y, p); y += s }
            }
            PageTemplate.GridNarrow, PageTemplate.GridMedium, PageTemplate.GridWide -> {
                val s = spacing(t)
                var x = s
                while (x < w) { c.drawLine(x, 0f, x, h, p); x += s }
                var y = s
                while (y < h) { c.drawLine(0f, y, w, y, p); y += s }
            }
            PageTemplate.DotsNarrow, PageTemplate.DotsMedium, PageTemplate.DotsWide -> {
                val s = spacing(t)
                val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = transform(STRONG) }
                var y = s
                while (y < h) {
                    var x = s
                    while (x < w) { c.drawCircle(x, y, 2.4f, dot); x += s }
                    y += s
                }
            }
            PageTemplate.Cornell -> {
                val strong = stroke(transform, STRONG, 2f)
                val cue = w * 0.3f
                val summary = h * 0.82f
                c.drawLine(cue, MARGIN_Y, cue, summary, strong)
                c.drawLine(MARGIN_X, summary, w - MARGIN_X, summary, strong)
                var y = MARGIN_Y + 60f
                while (y < summary) { c.drawLine(MARGIN_X, y, w - MARGIN_X, y, p); y += 60f }
            }
            PageTemplate.Music -> {
                var top = MARGIN_Y + 90f
                while (top + 4 * 18f < h - MARGIN_Y) {
                    for (i in 0..4) c.drawLine(MARGIN_X, top + i * 18f, w - MARGIN_X, top + i * 18f, stroke(transform, STRONG, 1.5f))
                    top += 4 * 18f + 90f
                }
            }
            PageTemplate.TodoList -> {
                var y = MARGIN_Y + 90f
                while (y < h - MARGIN_Y) {
                    c.drawRoundRect(MARGIN_X, y - 34f, MARGIN_X + 34f, y, 6f, 6f, stroke(transform, STRONG, 2f))
                    c.drawLine(MARGIN_X + 60f, y, w - MARGIN_X, y, p)
                    y += 80f
                }
            }
            PageTemplate.Weekly -> {
                val strong = stroke(transform, STRONG, 2f)
                val top = MARGIN_Y + 60f
                val rowH = (h - top - MARGIN_Y) / 4f
                val colW = (w - 2 * MARGIN_X) / 2f
                for (r in 0..4) c.drawLine(MARGIN_X, top + r * rowH, w - MARGIN_X, top + r * rowH, strong)
                for (cIdx in 0..2) c.drawLine(MARGIN_X + cIdx * colW, top, MARGIN_X + cIdx * colW, top + 4 * rowH, strong)
            }
            PageTemplate.Monthly -> {
                val strong = stroke(transform, STRONG, 2f)
                val top = MARGIN_Y + 60f
                val colW = (w - 2 * MARGIN_X) / 7f
                val rowH = (h - top - MARGIN_Y) / 6f
                for (r in 0..6) c.drawLine(MARGIN_X, top + r * rowH, w - MARGIN_X, top + r * rowH, strong)
                for (k in 0..7) c.drawLine(MARGIN_X + k * colW, top, MARGIN_X + k * colW, top + 6 * rowH, strong)
            }
        }
    }
}
