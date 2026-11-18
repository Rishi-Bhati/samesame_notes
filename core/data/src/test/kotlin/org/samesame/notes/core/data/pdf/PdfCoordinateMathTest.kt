package org.samesame.notes.core.data.pdf

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.samesame.notes.core.model.Limits
import org.samesame.notes.core.model.pdf.RectPt

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PdfCoordinateMathTest {

    private val widthPt = 595f // standard A4 pt
    private val heightPt = 842f

    @Test
    fun ptAndPagePuConversionsRoundTrip() {
        val ptX = 120f
        val ptY = 340f

        val (puX, puY) = PdfCoordinates.ptToPagePu(ptX, ptY, widthPt)
        assertThat(puX).isWithin(0.001f).of(ptX * (Limits.PAGE_WIDTH_PU / widthPt))
        assertThat(puY).isWithin(0.001f).of(ptY * (Limits.PAGE_WIDTH_PU / widthPt))

        val (backPtX, backPtY) = PdfCoordinates.pagePuToPt(puX, puY, widthPt)
        assertThat(backPtX).isWithin(0.001f).of(ptX)
        assertThat(backPtY).isWithin(0.001f).of(ptY)
    }

    @Test
    fun rectPtConversionsRoundTrip() {
        val rect = RectPt(10f, 20f, 150f, 250f)
        val puRect = PdfCoordinates.ptToPagePu(rect, widthPt)
        val backRect = PdfCoordinates.pagePuToPt(puRect, widthPt)

        assertThat(backRect.left).isWithin(0.001f).of(rect.left)
        assertThat(backRect.top).isWithin(0.001f).of(rect.top)
        assertThat(backRect.right).isWithin(0.001f).of(rect.right)
        assertThat(backRect.bottom).isWithin(0.001f).of(rect.bottom)
    }

    @Test
    fun pagePuAndDocConversionsRoundTrip() {
        val pageOriginX = 0f
        val pageOriginY = 1500f
        val puX = 200f
        val puY = 300f

        val (docX, docY) = PdfCoordinates.pagePuToDoc(puX, puY, pageOriginX, pageOriginY)
        assertThat(docX).isEqualTo(200f)
        assertThat(docY).isEqualTo(1800f)

        val (backPuX, backPuY) = PdfCoordinates.docToPagePu(docX, docY, pageOriginX, pageOriginY)
        assertThat(backPuX).isEqualTo(puX)
        assertThat(backPuY).isEqualTo(puY)
    }

    @Test
    fun docAndViewConversionsRoundTrip() {
        val originX = 50f
        val originY = -100f
        val scale = 1.5f
        val docX = 300f
        val docY = 400f

        val (viewX, viewY) = PdfCoordinates.docToView(docX, docY, originX, originY, scale)
        assertThat(viewX).isEqualTo(originX + docX * scale)
        assertThat(viewY).isEqualTo(originY + docY * scale)

        val (backDocX, backDocY) = PdfCoordinates.viewToDoc(viewX, viewY, originX, originY, scale)
        assertThat(backDocX).isWithin(0.001f).of(docX)
        assertThat(backDocY).isWithin(0.001f).of(docY)
    }

    @Test
    fun endToEndViewAndPtConversionsRoundTrip() {
        val originX = 64f
        val originY = 128f
        val scale = 2.0f
        val pageOriginX = 0f
        val pageOriginY = 1080f

        val origPtX = 72f // 1 inch from left
        val origPtY = 144f // 2 inches from top

        val (viewX, viewY) = PdfCoordinates.ptToView(
            ptX = origPtX,
            ptY = origPtY,
            widthPt = widthPt,
            pageOriginX = pageOriginX,
            pageOriginY = pageOriginY,
            originX = originX,
            originY = originY,
            scale = scale,
        )

        val (backPtX, backPtY) = PdfCoordinates.viewToPt(
            viewX = viewX,
            viewY = viewY,
            originX = originX,
            originY = originY,
            scale = scale,
            pageOriginX = pageOriginX,
            pageOriginY = pageOriginY,
            widthPt = widthPt,
        )

        assertThat(backPtX).isWithin(0.01f).of(origPtX)
        assertThat(backPtY).isWithin(0.01f).of(origPtY)
    }

    @Test
    fun tileMatrixMapsCorrectTileRegion() {
        val col = 2
        val row = 3
        val tileSize = 512
        val scale = 1.5f
        val s = scale * (Limits.PAGE_WIDTH_PU / widthPt)

        val matrix = PdfCoordinates.tileMatrix(col, row, scale, widthPt, tileSize)

        // Point corresponding to the top-left of tile (col, row) in pt
        val tileLeftPx = col * tileSize.toFloat()
        val tileTopPx = row * tileSize.toFloat()
        val ptX = tileLeftPx / s
        val ptY = tileTopPx / s

        val srcPoints = floatArrayOf(ptX, ptY)
        val dstPoints = FloatArray(2)
        matrix.mapPoints(dstPoints, srcPoints)

        // Top-left of the tile should map to (0, 0) on the tile bitmap
        assertThat(dstPoints[0]).isWithin(0.001f).of(0f)
        assertThat(dstPoints[1]).isWithin(0.001f).of(0f)

        // A point 512px to the right and down should map to (512, 512)
        val ptRightPx = (col + 1) * tileSize.toFloat()
        val ptBottomPx = (row + 1) * tileSize.toFloat()
        val srcEndPoints = floatArrayOf(ptRightPx / s, ptBottomPx / s)
        val dstEndPoints = FloatArray(2)
        matrix.mapPoints(dstEndPoints, srcEndPoints)

        assertThat(dstEndPoints[0]).isWithin(0.001f).of(512f)
        assertThat(dstEndPoints[1]).isWithin(0.001f).of(512f)
    }
    @Test fun legacyLandscapeFitMapsSelectionMarkupAndTileGrid() {
        val fit = PdfCoordinates.fit(800f, 400f, 1600f)
        assertThat(fit.scale).isWithin(0.001f).of(1.35f)
        assertThat(fit.dy).isWithin(0.001f).of(530f)
        val rect = RectPt(20f, 40f, 100f, 80f)
        val pu = PdfCoordinates.ptToPagePu(rect, 800f, fit)
        assertThat(pu.top).isWithin(0.001f).of(584f)
        val back = PdfCoordinates.pagePuToPt(pu, 800f, fit)
        assertThat(back.left).isWithin(0.001f).of(rect.left)
        assertThat(back.top).isWithin(0.001f).of(rect.top)
        val view = PdfCoordinates.ptToView(rect.left, rect.top, 800f, 10f, 200f, 50f, -30f, 2f, fit)
        val pt = PdfCoordinates.viewToPt(view.first, view.second, 50f, -30f, 2f, 10f, 200f, 800f, fit)
        assertThat(pt.first).isWithin(0.001f).of(rect.left)
        assertThat(pt.second).isWithin(0.001f).of(rect.top)
        val point = floatArrayOf(rect.left, rect.top)
        PdfCoordinates.tileMatrix(0, 2, 2f, 800f, 512, fit).mapPoints(point)
        assertThat(point[0]).isWithin(0.001f).of(pu.left * 2f)
        assertThat(point[1]).isWithin(0.001f).of(pu.top * 2f - 1024f)
    }

}
