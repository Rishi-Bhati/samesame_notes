package org.samesame.notes.feature.composer.session

import org.samesame.notes.core.model.document.FloatingObject
import org.samesame.notes.core.model.document.ImageObject
import org.samesame.notes.core.model.document.NoteObject
import org.samesame.notes.core.model.document.ObjectLimits
import org.samesame.notes.core.model.document.PaintingObject
import org.samesame.notes.core.model.document.RectPu
import org.samesame.notes.core.model.document.VoiceObject
import org.samesame.notes.core.model.richtext.OBJECT_CHAR
import org.samesame.notes.core.model.richtext.RichText
import kotlin.math.ceil

/**
 * Anchor to text / Remove anchor (object context menu): a floating object becomes an inline object of the body (U+FFFC
 * with its id in `NoteDocument.inlineObjects`, the same representation `.sdocx` object spans import to) and back. The
 * reference (`TaskAnchor`) puts the object at the text position under its top-left corner, first adding line feeds when
 * that is below the end of the text, and turns an inline object back into a floating one on its page where it was
 * drawn. No format change: both forms already exist in the model. Pure parts, unit-tested.
 */
object AnchorToText {
    /** Types the reference can anchor (`isObjectSpanSupportedType`: image, drawing, voice). */
    fun supports(obj: NoteObject): Boolean = obj is ImageObject || obj is PaintingObject || obj is VoiceObject

    /** Line feeds to append so a line starts at [targetTop] when the text ends at [textBottom] (doc space). */
    fun lineFeedsNeeded(textBottom: Float, targetTop: Float, lineHeight: Float): Int =
        if (targetTop <= textBottom || lineHeight <= 0f) 0 else ceil((targetTop - textBottom) / lineHeight).toInt()

    /** The inline form of [f]: an image keeps the size it was shown at (inline objects have no frame). */
    fun inlineCopy(f: FloatingObject): NoteObject = when (val o = f.obj) {
        is ImageObject -> o.copy(widthPu = f.rect.width, heightPu = f.rect.height)
        else -> o
    }

    /** Size an inline object takes in the text before the column scales it down. */
    fun inlineSize(obj: NoteObject): Pair<Float, Float> = when (obj) {
        is ImageObject -> obj.widthPu to obj.heightPu
        is PaintingObject -> obj.content.width to obj.content.height
        is VoiceObject -> ObjectLimits.VOICE_WIDTH_PU to ObjectLimits.VOICE_HEIGHT_PU
        else -> 0f to 0f
    }

    /**
     * The floating form of an inline [obj] drawn [shownWidth] wide with its top-left at ([left], [top]) (page space):
     * the height follows the object's own ratio (the text may have scaled it down to the column).
     */
    fun floatingFor(obj: NoteObject, left: Float, top: Float, shownWidth: Float): FloatingObject {
        val (w0, h0) = inlineSize(obj)
        val w = if (shownWidth > 0f) shownWidth else w0
        val h = if (w0 > 0f) w * h0 / w0 else h0
        return FloatingObject(obj, RectPu(left, top, left + w, top + h))
    }

    /** Offsets (absolute, from [start]) and ids of the inline objects in the slice [rt]. */
    fun objectOffsets(rt: RichText, start: Int): List<Pair<Int, String>> {
        val out = ArrayList<Pair<Int, String>>()
        var pos = 0
        for (run in rt.runs) {
            val id = run.style.objectId
            if (id != null) for (i in pos until pos + run.length) if (rt.text.getOrNull(i) == OBJECT_CHAR) out += (start + i) to id
            pos += run.length
        }
        return out
    }

    /** True when the slice holds inline objects and nothing else (the reference's Remove anchor condition). */
    fun onlyObjects(rt: RichText): Boolean = rt.length > 0 && objectOffsets(rt, 0).size == rt.length

    /**
     * The body offset of the inline object under a touch, or null. [hit] is the text hit test of the touch (a caret
     * offset: the left half of an object character gives the offset before it, the right half the one after), so the
     * characters on both sides of it are tried; [isObject] says whether a character is an object, [contains] whether
     * that character's drawn bounds hold the touch (so a tap on the text beside an image is not taken for the image).
     */
    fun objectOffsetAt(hit: Int, length: Int, isObject: (Int) -> Boolean, contains: (Int) -> Boolean): Int? {
        if (length <= 0) return null
        val h = hit.coerceIn(0, length)
        for (o in intArrayOf(h, h - 1)) {
            if (o < 0 || o >= length || !isObject(o)) continue
            if (contains(o)) return o
        }
        return null
    }

    /** Extra rows of the body's floating selection menu. */
    enum class SelectionMenu { None, ReadOnly, InlineObjects }

    /**
     * Which extra rows a body selection [slice] gets: the view state's read-only rows (Share, Web search), Remove anchor
     * + Delete when an editable selection holds inline objects (alone or among text), or none.
     */
    fun selectionMenu(viewState: Boolean, textMode: Boolean, slice: RichText): SelectionMenu = when {
        slice.length == 0 -> SelectionMenu.None
        viewState -> SelectionMenu.ReadOnly
        textMode && objectOffsets(slice, 0).isNotEmpty() -> SelectionMenu.InlineObjects
        else -> SelectionMenu.None
    }
}
