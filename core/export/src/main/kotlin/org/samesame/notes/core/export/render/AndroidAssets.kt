package org.samesame.notes.core.export.render

import android.graphics.Bitmap
import org.samesame.notes.core.export.ExportAssets
import org.samesame.notes.core.export.RenderedImage
import org.samesame.notes.core.model.richtext.RichText
import java.io.ByteArrayOutputStream

/** [ExportAssets] backed by the real renderer. */
internal class AndroidAssets(private val renderer: NoteRenderer) : ExportAssets {
    override val pageCount: Int get() = renderer.pageCount
    override val heightOverWidth: Float get() = renderer.layout.ratio
    override fun media(path: String): ByteArray? = renderer.media(path)

    override fun inkLayer(pageIndex: Int): RenderedImage? {
        if (!renderer.hasInkLayer(pageIndex)) return null
        return png(renderer.renderBitmap(pageIndex, 1f, Layers(background = false, template = false, text = false, textBoxes = false)))
    }

    override fun fullPage(pageIndex: Int): RenderedImage = png(renderer.renderBitmap(pageIndex, 1.5f, Layers()))
    override fun floatingTexts(pageIndex: Int): List<RichText> = renderer.floatingTexts(pageIndex)
    override fun pageText(pageIndex: Int): String = renderer.pageText(pageIndex)

    private fun png(b: Bitmap): RenderedImage {
        val bytes = ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        return RenderedImage(bytes, b.width, b.height).also { b.recycle() }
    }
}
