package org.samesame.notes.feature.composer.drawing

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.Stroke
import androidx.ink.geometry.AffineTransform
import org.samesame.notes.core.ink.brush.GrainTextureStore
import org.samesame.notes.core.ink.page.InkElement
import org.samesame.notes.core.ink.page.PageContent
import org.samesame.notes.core.ink.page.PageElement
import org.samesame.notes.core.ink.page.PageLayerCodec
import org.samesame.notes.core.model.document.RectPu
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** The two files a drawing stores in the bundle: stroke payload (`.drw`) and PNG thumbnail. */
internal object DrawingFiles {
    /** The strokes in sheet space, in the page-layer format. */
    fun encodeInk(elements: List<PageElement>): ByteArray =
        ByteArrayOutputStream().also { PageLayerCodec.encode(PageContent(DrawingGeometry.SHEET_PAGE_ID, elements.filterIsInstance<InkElement>(), 0), it) }.toByteArray()

    fun decodeInk(bytes: ByteArray): List<PageElement> = ByteArrayInputStream(bytes).use { PageLayerCodec.decode(it) }

    /** [strokes] drawn over a transparent bitmap of [content] (1 pixel per page unit), as PNG. */
    fun renderThumbnail(strokes: List<Stroke>, content: RectPu): ByteArray {
        val bmp = Bitmap.createBitmap(content.width.toInt().coerceAtLeast(1), content.height.toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.translate(-content.left, -content.top)
        val renderer = CanvasStrokeRenderer.create(GrainTextureStore)
        for (s in strokes) renderer.draw(canvas, s, AffineTransform.IDENTITY)
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray().also { bmp.recycle() }
    }
}
