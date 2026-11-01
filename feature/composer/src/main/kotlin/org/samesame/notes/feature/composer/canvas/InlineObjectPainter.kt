package org.samesame.notes.feature.composer.canvas

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import org.samesame.notes.core.model.document.ImageObject
import org.samesame.notes.core.model.document.NoteObject
import org.samesame.notes.core.model.document.ObjectLimits
import org.samesame.notes.core.model.document.PaintingObject
import org.samesame.notes.core.model.document.VoiceObject
import org.samesame.notes.core.richtext.spans.InlineObjectProvider
import org.samesame.notes.core.richtext.spans.ObjectSize
import org.samesame.notes.core.richtext.spans.PlaceholderObjectProvider

/**
 * Draws the body's inline objects (objects anchored to the text, `NoteDocument.inlineObjects`): images and drawings
 * from the shared bitmap cache, voice objects as their pill; anything else keeps the placeholder box. Sizes come
 * from the object (an image's size, a drawing's sheet region, the fixed voice pill); the text layout scales wide ones
 * down to the column. Main thread (layout / draw phase).
 */
internal class InlineObjectPainter(
    private val lookup: (String) -> NoteObject?,
    private val image: (String) -> ImageBitmap?,
    private val painters: ObjectPainters,
    private val dark: () -> Boolean,
) : InlineObjectProvider {
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val loadingPaint = Paint().apply { color = 0x22808080 }

    override fun size(objectId: String): ObjectSize = when (val o = lookup(objectId)) {
        is ImageObject -> ObjectSize(o.widthPu, o.heightPu)
        is PaintingObject -> ObjectSize(o.content.width, o.content.height)
        is VoiceObject -> ObjectSize(ObjectLimits.VOICE_WIDTH_PU, ObjectLimits.VOICE_HEIGHT_PU)
        else -> PlaceholderObjectProvider.size(objectId)
    }

    override fun draw(canvas: Canvas, objectId: String, rect: RectF) {
        when (val o = lookup(objectId)) {
            is ImageObject -> drawBitmap(canvas, o.media, rect)
            is PaintingObject -> drawBitmap(canvas, o.media, rect)
            is VoiceObject -> painters.drawVoice(canvas, RectPu2(rect.left, rect.top, rect.width(), rect.height()), o, dark())
            else -> PlaceholderObjectProvider.draw(canvas, objectId, rect)
        }
    }

    private fun drawBitmap(canvas: Canvas, media: String, rect: RectF) {
        val b = image(media)
        if (b == null) canvas.drawRect(rect, loadingPaint) else canvas.drawBitmap(b.asAndroidBitmap(), null, rect, bitmapPaint)
    }
}
