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
    fun puPerPt(widthPt: Float): Float = Limits.PAGE_WIDTH_PU / widthPt
    fun ptPerPu(widthPt: Float): Float = widthPt / Limits.PAGE_WIDTH_PU

    fun ptToPagePu(ptX: Float, ptY: Float, widthPt: Float): Pair<Float, Float> {
        val k = puPerPt(widthPt)
        return ptX * k to ptY * k
    }

    fun pagePuToPt(puX: Float, puY: Float, widthPt: Float): Pair<Float, Float> {
        val k = ptPerPu(widthPt)
        return puX * k to puY * k
    }

    fun ptToPagePu(rect: RectPt, widthPt: Float): RectPt {
        val k = puPerPt(widthPt)
        return RectPt(rect.left * k, rect.top * k, rect.right * k, rect.bottom * k)
    }

    fun pagePuToPt(rect: RectPt, widthPt: Float): RectPt {
        val k = ptPerPu(widthPt)
        return RectPt(rect.left * k, rect.top * k, rect.right * k, rect.bottom * k)
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
    ): Pair<Float, Float> {
        val (docX, docY) = viewToDoc(viewX, viewY, originX, originY, scale)
        val (puX, puY) = docToPagePu(docX, docY, pageOriginX, pageOriginY)
        return pagePuToPt(puX, puY, widthPt)
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
    ): Pair<Float, Float> {
        val (puX, puY) = ptToPagePu(ptX, ptY, widthPt)
        val (docX, docY) = pagePuToDoc(puX, puY, pageOriginX, pageOriginY)
        return docToView(docX, docY, originX, originY, scale)
    }

    fun tileMatrix(
        col: Int,
        row: Int,
        scale: Float,
        widthPt: Float,
        tileSize: Int = Limits.PDF_TILE_SIZE_PX,
    ): Matrix {
        val s = scale * puPerPt(widthPt)
        return Matrix().apply {
            setScale(s, s)
            postTranslate(-col * tileSize.toFloat(), -row * tileSize.toFloat())
        }
    }
}
