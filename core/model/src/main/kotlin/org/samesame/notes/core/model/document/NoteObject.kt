package org.samesame.notes.core.model.document

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.samesame.notes.core.model.richtext.RichText
import kotlin.math.abs

/** Rectangle in page units. */
@Serializable
data class RectPu(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

@Serializable
data class SizePu(val width: Float, val height: Float)

@Serializable
enum class TextBoxBorder { None, Solid }

@Serializable
enum class TextWrap { InFront, Behind }

@Serializable
sealed interface NoteObject {
    val id: String
}

@Serializable @SerialName("image")
data class ImageObject(
    override val id: String,
    /** `media/<sha256>.<ext>` */
    val media: String,
    val widthPu: Float,
    val heightPu: Float,
    val crop: RectPu? = null,
) : NoteObject

@Serializable @SerialName("textbox")
data class TextBoxObject(
    override val id: String,
    val text: RichText,
    val border: TextBoxBorder = TextBoxBorder.None,
) : NoteObject

@Serializable @SerialName("voice")
data class VoiceObject(
    override val id: String,
    val media: String,
    val durationMs: Long,
) : NoteObject

/**
 * A re-editable drawing (format v5, Insert > Drawing). The strokes live in the bundle ([ink] = `media/<sha256>.drw`, a
 * `PageLayerCodec` payload in sheet space) and [media] is the rendered thumbnail (PNG with alpha) the page, previews and
 * exports draw. [content] is the region of the drawing sheet the thumbnail covers (the ink bounds plus a margin), so a
 * re-edit can put the strokes back where they were.
 */
@Serializable @SerialName("painting")
data class PaintingObject(
    override val id: String,
    val media: String,
    val ink: String,
    val content: RectPu,
) : NoteObject

/** An object stored in a page layer (see PageLayerCodec). */
@Serializable
data class FloatingObject(
    val obj: NoteObject,
    val rect: RectPu,
    val rotationDeg: Float = 0f,
    val wrap: TextWrap = TextWrap.InFront,
) {
    fun toJson(): String = NoteJson.encodeToString(serializer(), this)

    companion object {
        fun fromJson(json: String): FloatingObject = NoteJson.decodeFromString(serializer(), json)
    }
}

// ------------------------------------------------------------------------------------------------ object geometry

/** Defaults and limits of floating objects (page units). NOTE: sizes are our own choices, not measured. */
object ObjectLimits {
    /** An object can't be resized below this on either side. */
    const val MIN_SIZE_PU = 48f
    const val DEFAULT_TEXT_BOX_WIDTH_PU = 540f
    const val DEFAULT_TEXT_BOX_HEIGHT_PU = 200f
    /** A new text box sits this far from the page's top and left edges. */
    const val TEXT_BOX_MARGIN_PU = 42f
    /** Inner padding of a text box. */
    const val TEXT_BOX_PADDING_PU = 24f
    /** A voice object is a fixed pill. */
    const val VOICE_WIDTH_PU = 480f
    const val VOICE_HEIGHT_PU = 120f
    /** The drawing sheet is the page width wide and 1.5x that tall. */
    const val DRAWING_SHEET_HEIGHT_OVER_WIDTH = 1.5f
    /** Space kept around the ink when the thumbnail is cropped to it. */
    const val DRAWING_THUMB_MARGIN_PU = 24f
    /** A new drawing is inserted at most this fraction of the page width wide (our choice; the tablet scale is 90% in the reference). */
    const val DRAWING_MAX_WIDTH_FRACTION = 0.9f
}

val NoteObject.isImage: Boolean get() = this is ImageObject
val NoteObject.isTextBox: Boolean get() = this is TextBoxObject

/** Axis-aligned bounds of this rect mapped by x' = m00 x + m10 y + m20, y' = m01 x + m11 y + m21. */
fun RectPu.mapped(m00: Float, m10: Float, m20: Float, m01: Float, m11: Float, m21: Float): RectPu {
    var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
    for (cx in floatArrayOf(left, right)) for (cy in floatArrayOf(top, bottom)) {
        val x = m00 * cx + m10 * cy + m20; val y = m01 * cx + m11 * cy + m21
        x0 = minOf(x0, x); x1 = maxOf(x1, x); y0 = minOf(y0, y); y1 = maxOf(y1, y)
    }
    return RectPu(x0, y0, x1, y1)
}

/** The size "Resize to default" restores; null when the object has none (text box: [ObjectLimits] defaults). */
fun FloatingObject.defaultSize(): SizePu = when (val o = obj) {
    is ImageObject -> SizePu(o.widthPu, o.heightPu)
    is TextBoxObject -> SizePu(ObjectLimits.DEFAULT_TEXT_BOX_WIDTH_PU, ObjectLimits.DEFAULT_TEXT_BOX_HEIGHT_PU)
    is VoiceObject -> SizePu(ObjectLimits.VOICE_WIDTH_PU, ObjectLimits.VOICE_HEIGHT_PU)
    is PaintingObject -> SizePu(o.content.width, o.content.height)
}

/** The bitmap an object is drawn from (`media/<name>`): an image itself, a drawing's thumbnail; null for the rest. */
val NoteObject.bitmapMedia: String? get() = when (this) {
    is ImageObject -> media
    is PaintingObject -> media
    else -> null
}

/** Voice pills keep a fixed look: no resize, no wrap. */
val FloatingObject.isResizable: Boolean get() = obj !is VoiceObject

/** Images and text boxes can wrap text (behind or around); voice pills can't. */
val FloatingObject.supportsWrap: Boolean get() = obj !is VoiceObject

/** True when the object already spans the page from the left edge to [pageWidth] (what "Fit to page width" makes). */
fun FloatingObject.isFittedToWidth(pageWidth: Float): Boolean =
    abs(rect.left) < 0.5f && abs(rect.width - pageWidth) < 0.5f

/** Back to the default size, keeping the centre. */
fun FloatingObject.resizedToDefault(): FloatingObject {
    val s = defaultSize()
    val cx = (rect.left + rect.right) / 2f; val cy = (rect.top + rect.bottom) / 2f
    return copy(rect = RectPu(cx - s.width / 2f, cy - s.height / 2f, cx + s.width / 2f, cy + s.height / 2f))
}

/** Full page width (left edge at 0), height following the current aspect, top edge kept. */
fun FloatingObject.fittedToWidth(pageWidth: Float): FloatingObject {
    val w = rect.width.coerceAtLeast(1f); val h = rect.height
    val newH = h * pageWidth / w
    return copy(rect = RectPu(0f, rect.top, pageWidth, rect.top + newH))
}

fun FloatingObject.withWrap(wrap: TextWrap): FloatingObject = copy(wrap = wrap)

/** The same object moved/resized to [r] (inner object untouched). */
fun FloatingObject.withRect(r: RectPu): FloatingObject = copy(rect = r)
