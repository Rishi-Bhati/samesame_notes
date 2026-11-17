package org.samesame.notes.core.model.textimport

import org.samesame.notes.core.model.Limits
import org.samesame.notes.core.model.document.RectPu

/**
 * Where the pictures of a note made from received photos go: each is 70% of the page width (like an inserted picture),
 * centred, stacked down the page with a gap; a picture that does not fit the rest of a page starts the next page.
 * [pageHeightPu] null = an infinite page (everything stacks on page 0). A very tall picture is scaled down to fit one page.
 */
object ImageNoteLayout {
    data class Placement(val page: Int, val rect: RectPu)

    const val MARGIN_PU = 96f
    const val GAP_PU = 48f
    const val WIDTH_FRACTION = 0.7f

    fun layout(sizesPx: List<Pair<Int, Int>>, pageHeightPu: Float?): List<Placement> {
        val pageW = Limits.PAGE_WIDTH_PU
        var page = 0
        var y = MARGIN_PU
        return sizesPx.map { (wPx, hPx) ->
            val aspect = if (wPx > 0 && hPx > 0) wPx.toFloat() / hPx else 1f
            var w = pageW * WIDTH_FRACTION
            var h = w / aspect
            if (pageHeightPu != null) {
                val maxH = (pageHeightPu - 2 * MARGIN_PU).coerceAtLeast(1f)
                if (h > maxH) { h = maxH; w = h * aspect }
                if (y + h > pageHeightPu - MARGIN_PU && y > MARGIN_PU) { page++; y = MARGIN_PU }
            }
            val left = (pageW - w) / 2f
            val p = Placement(page, RectPu(left, y, left + w, y + h))
            y += h + GAP_PU
            p
        }
    }
}
