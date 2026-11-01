package org.samesame.notes.core.export.render

import org.samesame.notes.core.model.Limits
import org.samesame.notes.core.model.document.PageSize
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Page-box maths shared by PDF export: note-space <-> original-PDF-points, page boxes and raster sizes. */
internal object PdfPageGeometry {
    data class Transform(val scale: Float, val dx: Float, val dy: Float)

    /** The importer fits the original page (centred) into a note page of [notePageW] x [notePageH]. This is that fit. */
    fun fit(srcW: Float, srcH: Float, notePageW: Float, notePageH: Float): Transform {
        val scale = min(notePageW / srcW, notePageH / srcH)
        return Transform(scale, (notePageW - srcW * scale) / 2f, (notePageH - srcH * scale) / 2f)
    }

    /** Inverse of [fit]: a note-space point maps to `note / scale - offset / scale` in original points. */
    fun toPdfX(noteX: Float, t: Transform): Float = noteX / t.scale - t.dx / t.scale
    fun toPdfY(noteY: Float, t: Transform): Float = noteY / t.scale - t.dy / t.scale

    /** Pixels for [widthPt] x [heightPt] at [dpi], never zero, scaled down uniformly to at most [maxPixels]. */
    fun rasterSize(widthPt: Float, heightPt: Float, dpi: Float, maxPixels: Long = Long.MAX_VALUE): Pair<Int, Int> {
        var w = widthPt / 72f * dpi
        var h = heightPt / 72f * dpi
        val area = w.toDouble() * h
        if (area > maxPixels) {
            val k = sqrt(maxPixels / area).toFloat()
            w *= k
            h *= k
        }
        return max(1, w.roundToInt()) to max(1, h.roundToInt())
    }

    /** Page-info size in points, rounded to whole units and never zero. */
    fun pageBox(widthPt: Float, heightPt: Float): Pair<Int, Int> =
        max(1, widthPt.roundToInt()) to max(1, heightPt.roundToInt())

    /**
     * Point size of a page that has no original PDF behind it. A page with its own size (v6) is written at that size;
     * otherwise A4 portrait width (595 pt), or A4 landscape width (842 pt) when the page is wider than tall, with the
     * height following the page's shape ([heightOverWidth]).
     */
    fun nativePageBox(size: PageSize?, heightOverWidth: Float): Pair<Int, Int> {
        if (size != null && size.isValid) return pageBox(size.widthPt, size.widthPt * size.heightOverWidth)
        val widthPt = if (heightOverWidth < 1f) PageSize.A4_HEIGHT_PT else PageSize.A4_WIDTH_PT
        return pageBox(widthPt, widthPt * heightOverWidth)
    }

    /** Points per page unit for a page box [widthPt] wide (pages are [Limits.PAGE_WIDTH_PU] wide). */
    fun pointsPerPu(widthPt: Int): Float = widthPt / Limits.PAGE_WIDTH_PU
}
