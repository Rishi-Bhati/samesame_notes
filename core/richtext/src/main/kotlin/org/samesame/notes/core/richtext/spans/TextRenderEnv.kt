package org.samesame.notes.core.richtext.spans

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import org.samesame.notes.core.richtext.BodyTextMetrics

/** Size of an inline object in pu. */
data class ObjectSize(val width: Float, val height: Float)

/**
 * Supplies inline objects (U+FFFC with an `objectId`): their size for layout and how to draw them.
 * Images/text boxes are later work; until then [PlaceholderObjectProvider] draws a box.
 */
interface InlineObjectProvider {
    fun size(objectId: String): ObjectSize
    fun draw(canvas: Canvas, objectId: String, rect: RectF)
}

object PlaceholderObjectProvider : InlineObjectProvider {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f; color = Color.GRAY }
    override fun size(objectId: String) = ObjectSize(BodyTextMetrics.PLACEHOLDER_OBJECT_SIZE, BodyTextMetrics.PLACEHOLDER_OBJECT_SIZE)
    override fun draw(canvas: Canvas, objectId: String, rect: RectF) {
        canvas.drawRoundRect(rect, 12f, 12f, paint)
        canvas.drawLine(rect.left, rect.top, rect.right, rect.bottom, paint)
        canvas.drawLine(rect.right, rect.top, rect.left, rect.bottom, paint)
    }
}

/**
 * Rendering environment shared by all spans of one body. Colours here don't affect metrics, so
 * changing them only needs a redraw; changing [objectProvider] sizes needs `BodyText.invalidateObjects`.
 */
class TextRenderEnv {
    /** Applied to explicit text/highlight colours (WP-J passes DarkModeInk.invert on a dark page). */
    var colorTransform: (Int) -> Int = { it }

    /** Fill colour inside a checked checkbox's tick (the page colour). */
    var pageColor: Int = Color.WHITE

    var objectProvider: InlineObjectProvider = PlaceholderObjectProvider

    var typeface: Typeface = Typeface.DEFAULT

    /** Inline objects wider than this are scaled down (the text column width). */
    var maxObjectWidth: Float = BodyTextMetrics.COLUMN_WIDTH

    private val typefaces = arrayOfNulls<Typeface>(4)
    private var typefaceBase: Typeface? = null

    internal fun typeface(bold: Boolean, italic: Boolean): Typeface {
        if (typefaceBase !== typeface) {
            typefaces.fill(null)
            typefaceBase = typeface
        }
        val style = when {
            bold && italic -> Typeface.BOLD_ITALIC
            bold -> Typeface.BOLD
            italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        return typefaces[style] ?: Typeface.create(typeface, style).also { typefaces[style] = it }
    }
}
