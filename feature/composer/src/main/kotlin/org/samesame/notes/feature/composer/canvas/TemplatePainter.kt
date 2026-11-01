package org.samesame.notes.feature.composer.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import org.samesame.notes.core.model.PageTemplate
import kotlin.math.ceil
import kotlin.math.floor

/** Template geometry in page units. NOTE(WP-G): eyeballed, not measured (doc 2.7 says to take it from #55). */
object TemplateMetrics {
    const val LINE_NARROW = 42f
    const val LINE_MEDIUM = 56f
    const val LINE_WIDE = 76f
    const val GRID_NARROW = 36f
    const val GRID_MEDIUM = 54f
    const val GRID_WIDE = 78f
    const val DOT_NARROW = 36f
    const val DOT_MEDIUM = 54f
    const val DOT_WIDE = 78f
    const val LINE_WIDTH = 1.5f
    const val DOT_RADIUS = 2.2f
    const val TOP_MARGIN = 32f
    const val CORNELL_CUE_WIDTH = 300f
    const val CORNELL_SUMMARY_HEIGHT = 260f
    const val STAFF_GAP = 14f
    const val STAFF_LINES = 5
    const val STAFF_PITCH = 200f
    const val TODO_ROW = 70f
    const val TODO_BOX = 30f
}

/**
 * Draws page templates procedurally in page units. The picker thumbnails use the same code, so picker and
 * page agree. [phaseY] shifts repeating patterns so they stay continuous across infinite segments (pass the
 * segment's document-space top).
 */
object TemplatePainter {
    fun spacing(t: PageTemplate): Float? = when (t) {
        PageTemplate.LinesNarrow -> TemplateMetrics.LINE_NARROW
        PageTemplate.LinesMedium -> TemplateMetrics.LINE_MEDIUM
        PageTemplate.LinesWide -> TemplateMetrics.LINE_WIDE
        PageTemplate.GridNarrow -> TemplateMetrics.GRID_NARROW
        PageTemplate.GridMedium -> TemplateMetrics.GRID_MEDIUM
        PageTemplate.GridWide -> TemplateMetrics.GRID_WIDE
        PageTemplate.DotsNarrow -> TemplateMetrics.DOT_NARROW
        PageTemplate.DotsMedium -> TemplateMetrics.DOT_MEDIUM
        PageTemplate.DotsWide -> TemplateMetrics.DOT_WIDE
        else -> null
    }

    /** Draws into the current origin (0,0 = page top-left) for a page of [size] pu. */
    fun draw(scope: DrawScope, template: PageTemplate, size: Size, lineColor: Color, phaseY: Float = 0f) = with(scope) {
        val w = TemplateMetrics.LINE_WIDTH
        when (template) {
            PageTemplate.Blank -> Unit
            PageTemplate.LinesNarrow, PageTemplate.LinesMedium, PageTemplate.LinesWide -> {
                val s = spacing(template)!!
                horizontals(s, phaseY, size, lineColor, w)
            }
            PageTemplate.GridNarrow, PageTemplate.GridMedium, PageTemplate.GridWide -> {
                val s = spacing(template)!!
                horizontals(s, phaseY, size, lineColor, w)
                var x = s
                while (x < size.width) { drawLine(lineColor, Offset(x, 0f), Offset(x, size.height), w); x += s }
            }
            PageTemplate.DotsNarrow, PageTemplate.DotsMedium, PageTemplate.DotsWide -> {
                val s = spacing(template)!!
                var y = firstLine(s, phaseY)
                while (y < size.height) {
                    var x = s
                    while (x < size.width) { drawCircle(lineColor, TemplateMetrics.DOT_RADIUS, Offset(x, y)); x += s }
                    y += s
                }
            }
            PageTemplate.Cornell -> {
                val cue = TemplateMetrics.CORNELL_CUE_WIDTH
                val sum = size.height - TemplateMetrics.CORNELL_SUMMARY_HEIGHT
                drawLine(lineColor, Offset(cue, 0f), Offset(cue, sum), w * 2)
                drawLine(lineColor, Offset(0f, sum), Offset(size.width, sum), w * 2)
                var y = TemplateMetrics.TOP_MARGIN + TemplateMetrics.LINE_MEDIUM
                while (y < sum) { drawLine(lineColor, Offset(cue, y), Offset(size.width, y), w); y += TemplateMetrics.LINE_MEDIUM }
            }
            PageTemplate.Music -> {
                var top = TemplateMetrics.TOP_MARGIN + 80f
                while (top + TemplateMetrics.STAFF_GAP * (TemplateMetrics.STAFF_LINES - 1) < size.height) {
                    repeat(TemplateMetrics.STAFF_LINES) { i ->
                        val y = top + i * TemplateMetrics.STAFF_GAP
                        drawLine(lineColor, Offset(60f, y), Offset(size.width - 60f, y), w)
                    }
                    top += TemplateMetrics.STAFF_PITCH
                }
            }
            PageTemplate.TodoList -> {
                var y = TemplateMetrics.TOP_MARGIN + TemplateMetrics.TODO_ROW
                while (y < size.height) {
                    drawRect(lineColor, Offset(60f, y - TemplateMetrics.TODO_BOX - 14f), Size(TemplateMetrics.TODO_BOX, TemplateMetrics.TODO_BOX), style = Stroke(w))
                    drawLine(lineColor, Offset(60f + TemplateMetrics.TODO_BOX + 24f, y), Offset(size.width - 60f, y), w)
                    y += TemplateMetrics.TODO_ROW
                }
            }
            PageTemplate.Weekly -> {
                val rows = 7
                val rowH = size.height / rows
                for (i in 0..rows) drawLine(lineColor, Offset(0f, i * rowH), Offset(size.width, i * rowH), w)
                drawLine(lineColor, Offset(220f, 0f), Offset(220f, size.height), w)
            }
            PageTemplate.Monthly -> {
                val cols = 7
                val rows = 6
                val head = 90f
                val colW = size.width / cols
                val rowH = (size.height - head) / rows
                for (i in 0..cols) drawLine(lineColor, Offset(i * colW, head), Offset(i * colW, size.height), w)
                for (i in 0..rows) drawLine(lineColor, Offset(0f, head + i * rowH), Offset(size.width, head + i * rowH), w)
            }
        }
    }

    /** First pattern line at or below the top margin, aligned to the global phase. */
    private fun firstLine(spacing: Float, phaseY: Float): Float {
        val minY = if (phaseY > 0f) 0f else TemplateMetrics.TOP_MARGIN // later infinite segments continue the pattern
        val k = ceil((minY + phaseY) / spacing)
        return k * spacing - phaseY
    }

    private fun DrawScope.horizontals(s: Float, phaseY: Float, size: Size, color: Color, w: Float) {
        var y = firstLine(s, phaseY)
        // NOTE(WP-G): in infinite mode phaseY continues the pattern; with phase 0 the first line is at k*s.
        while (y < size.height) { drawLine(color, Offset(0f, y), Offset(size.width, y), w); y += s }
    }

    /** Key of everything the drawing depends on, for layer caching. */
    fun key(template: PageTemplate, phaseY: Float, lineColor: Color): Long =
        (template.ordinal.toLong() shl 40) xor (floor(phaseY * 4).toLong() shl 8) xor lineColor.value.toLong()
}
