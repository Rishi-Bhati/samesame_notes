package org.samesame.notes.core.data.pdf

import android.graphics.Matrix
import org.samesame.notes.core.model.Limits
import org.samesame.notes.core.model.pdf.RectPt

/**
 * Pure coordinate conversions across View px, Document pu, Page pu, and PDF pt.
 *
 * Coordinate spaces:
 * 1. Screen View px: Canvas pixels with viewport origin O and scale sv.
 * 2. Document Space pu: Continuous canvas where page i has origin Di = (pageOriginX, pageOriginY).
 * 3. Page Unit Space pu: Page coordinates where width is 1080 pu (Limits.PAGE_WIDTH_PU) and height is proportional.
 * 4. PDF Points pt: Native 72 dpi PDF points with top-left origin.
 */
object PdfCoordinates {
    data class PageTransform(val scale: Float, val dx: Float = 0f, val dy: Float = 0f)

    /** Legacy pages fit the displayed source into the note's existing aspect, with centred letterboxing. */
    fun fit(widthPt: Float, heightPt: Float, pageHeightPu: Float): PageTransform {
        val scale = minOf(Limits.PAGE_WIDTH_PU / widthPt, pageHeightPu / heightPt)
        return PageTransform(scale, (Limits.PAGE_WIDTH_PU - widthPt * scale) / 2f, (pageHeightPu - heightPt * scale) / 2f)
    }

    fun puPerPt(widthPt: Float): Float = Limits.PAGE_WIDTH_PU / widthPt
    fun ptPerPu(widthPt: Float): Float = widthPt / Limits.PAGE_WIDTH_PU

    fun ptToPagePu(ptX: Float, ptY: Float, widthPt: Float, transform: PageTransform = PageTransform(puPerPt(widthPt))): Pair<Float, Float> {
        return ptX * transform.scale + transform.dx to ptY * transform.scale + transform.dy
    }

    fun pagePuToPt(puX: Float, puY: Float, widthPt: Float, transform: PageTransform = PageTransform(puPerPt(widthPt))): Pair<Float, Float> {
        return (puX - transform.dx) / transform.scale to (puY - transform.dy) / transform.scale
    }

    fun ptToPagePu(rect: RectPt, widthPt: Float, transform: PageTransform = PageTransform(puPerPt(widthPt))): RectPt {
        val (left, top) = ptToPagePu(rect.left, rect.top, widthPt, transform)
        val (right, bottom) = ptToPagePu(rect.right, rect.bottom, widthPt, transform)
        return RectPt(left, top, right, bottom)
    }

    fun pagePuToPt(rect: RectPt, widthPt: Float, transform: PageTransform = PageTransform(puPerPt(widthPt))): RectPt {
        val (left, top) = pagePuToPt(rect.left, rect.top, widthPt, transform)
        val (right, bottom) = pagePuToPt(rect.right, rect.bottom, widthPt, transform)
        return RectPt(left, top, right, bottom)
    }

    fun pagePuToDoc(puX: Float, puY: Float, pageOriginX: Float, pageOriginY: Float): Pair<Float, Float> =
        (pageOriginX + puX) to (pageOriginY + puY)

    fun docToPagePu(docX: Float, docY: Float, pageOriginX: Float, pageOriginY: Float): Pair<Float, Float> =
        (docX - pageOriginX) to (docY - pageOriginY)

    fun docToView(docX: Float, docY: Float, originX: Float, originY: Float, scale: Float): Pair<Float, Float> =
        (originX + docX * scale) to (originY + docY * scale)

    fun viewToDoc(viewX: Float, viewY: Float, originX: Float, originY: Float, scale: Float): Pair<Float, Float> =
        ((viewX - originX) / scale) to ((viewY - originY) / scale)

    fun viewToPt(
        viewX: Float,
        viewY: Float,
        originX: Float,
        originY: Float,
        scale: Float,
        pageOriginX: Float,
        pageOriginY: Float,
        widthPt: Float,
        transform: PageTransform = PageTransform(puPerPt(widthPt)),
    ): Pair<Float, Float> {
        val (docX, docY) = viewToDoc(viewX, viewY, originX, originY, scale)
        val (puX, puY) = docToPagePu(docX, docY, pageOriginX, pageOriginY)
        return pagePuToPt(puX, puY, widthPt, transform)
    }

    fun ptToView(
        ptX: Float,
        ptY: Float,
        widthPt: Float,
        pageOriginX: Float,
        pageOriginY: Float,
        originX: Float,
        originY: Float,
        scale: Float,
        transform: PageTransform = PageTransform(puPerPt(widthPt)),
    ): Pair<Float, Float> {
        val (puX, puY) = ptToPagePu(ptX, ptY, widthPt, transform)
        val (docX, docY) = pagePuToDoc(puX, puY, pageOriginX, pageOriginY)
        return docToView(docX, docY, originX, originY, scale)
    }

    fun tileMatrix(
        col: Int,
        row: Int,
        scale: Float,
        widthPt: Float,
        tileSize: Int = Limits.PDF_TILE_SIZE_PX,
        transform: PageTransform = PageTransform(puPerPt(widthPt)),
    ): Matrix {
        val s = scale * transform.scale
        return Matrix().apply {
            setScale(s, s)
            postTranslate(transform.dx * scale - col * tileSize, transform.dy * scale - row * tileSize)
        }
    }
}
