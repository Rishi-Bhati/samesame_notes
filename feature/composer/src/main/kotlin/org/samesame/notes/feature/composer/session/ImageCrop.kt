package org.samesame.notes.feature.composer.session

import org.samesame.notes.core.model.document.FloatingObject
import org.samesame.notes.core.model.document.FrameHandle
import org.samesame.notes.core.model.document.ImageObject
import org.samesame.notes.core.model.document.PaintingObject
import org.samesame.notes.core.model.document.RectPu
import org.samesame.notes.core.model.document.frame

/**
 * A crop as fractions of the image (0..1 on both axes, in the object's own unrotated axes): left, top, right, bottom.
 */
data class CropFraction(val left: Float = 0f, val top: Float = 0f, val right: Float = 1f, val bottom: Float = 1f) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val isFull: Boolean get() = left <= 0f && top <= 0f && right >= 1f && bottom >= 1f

    /** The crop in bitmap pixels of a [w] x [h] image, at least 1 px each way and inside it. */
    fun pixels(w: Int, h: Int): IntArray {
        val l = (left * w).toInt().coerceIn(0, w - 1)
        val t = (top * h).toInt().coerceIn(0, h - 1)
        val r = Math.round(right * w).coerceIn(l + 1, w)
        val b = Math.round(bottom * h).coerceIn(t + 1, h)
        return intArrayOf(l, t, r - l, b - t)
    }
}

/**
 * Image / drawing crop (the reference's object context menu "Crop"): the crop frame's handles move edges of a [CropFraction];
 * Done writes the cropped pixels as a new bundle image and the object keeps the cropped part where it was on the page
 * (same scale and rotation). Pure geometry, unit-tested.
 */
object ImageCrop {

    /**
     * Drags [handle] (or the whole crop for null) of [crop] by ([dx], [dy]) in fractions of the image. Edges stay inside
     * the image and at least [minW] / [minH] apart; a moved crop keeps its size.
     */
    fun drag(crop: CropFraction, handle: FrameHandle?, dx: Float, dy: Float, minW: Float, minH: Float): CropFraction {
        if (handle == null) {
            val mx = dx.coerceIn(-crop.left, 1f - crop.right)
            val my = dy.coerceIn(-crop.top, 1f - crop.bottom)
            return CropFraction(crop.left + mx, crop.top + my, crop.right + mx, crop.bottom + my)
        }
        var l = crop.left; var t = crop.top; var r = crop.right; var b = crop.bottom
        when (handle.nx) {
            -1 -> l = (l + dx).coerceIn(0f, r - minW)
            1 -> r = (r + dx).coerceIn(l + minW, 1f)
        }
        when (handle.ny) {
            -1 -> t = (t + dy).coerceIn(0f, b - minH)
            1 -> b = (b + dy).coerceIn(t + minH, 1f)
        }
        return CropFraction(l, t, r, b)
    }

    /**
     * The object after cropping to [crop] with the cropped pixels stored as [media]: the frame shrinks around the kept part
     * (its centre moves along the rotated axes), and the default size scales with it so "Resize to default" keeps the
     * image's own pixel scale.
     */
    fun apply(f: FloatingObject, crop: CropFraction, media: String): FloatingObject {
        val obj = when (val o = f.obj) {
            is ImageObject -> o.copy(media = media, widthPu = o.widthPu * crop.width, heightPu = o.heightPu * crop.height, crop = null)
            // A drawing keeps its strokes; its thumbnail now covers only the kept part of the sheet region, so a re-edit
            // (which re-renders the thumbnail from all strokes) brings the whole drawing back, like the reference clearing
            // the painting's crop rect on re-edit.
            is PaintingObject -> o.copy(media = media, content = cropContent(o.content, crop))
            else -> return f
        }
        val fr = f.frame
        val w = fr.w * crop.width
        val h = fr.h * crop.height
        val c = fr.toWorld(((crop.left + crop.right) / 2f - 0.5f) * fr.w, ((crop.top + crop.bottom) / 2f - 0.5f) * fr.h)
        return f.copy(obj = obj, rect = RectPu(c.x - w / 2f, c.y - h / 2f, c.x + w / 2f, c.y + h / 2f))
    }

    /** The part of a drawing's sheet region [content] that [crop] keeps. */
    fun cropContent(content: RectPu, crop: CropFraction): RectPu = RectPu(
        content.left + content.width * crop.left, content.top + content.height * crop.top,
        content.left + content.width * crop.right, content.top + content.height * crop.bottom,
    )
}

/**
 * Edit image (object menu): an outside editor wrote new pixels ([pixelW] x [pixelH]) for the image, stored as [media].
 * The object keeps its centre, width and rotation; its height follows the new picture's ratio (an editor may have
 * cropped or rotated it), and the default size keeps its width. Pure, unit-tested.
 */
object ImageReplace {
    fun apply(f: FloatingObject, media: String, pixelW: Int, pixelH: Int): FloatingObject {
        val img = f.obj as? ImageObject ?: return f
        if (pixelW <= 0 || pixelH <= 0) return f.copy(obj = img.copy(media = media, crop = null))
        val ratio = pixelH.toFloat() / pixelW
        val cx = (f.rect.left + f.rect.right) / 2f
        val cy = (f.rect.top + f.rect.bottom) / 2f
        val w = f.rect.width
        val h = w * ratio
        return f.copy(
            obj = img.copy(media = media, heightPu = img.widthPu * ratio, crop = null),
            rect = RectPu(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f),
        )
    }
}
