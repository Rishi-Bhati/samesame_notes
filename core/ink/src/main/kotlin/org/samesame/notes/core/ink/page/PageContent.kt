package org.samesame.notes.core.ink.page

import androidx.ink.geometry.ImmutableBox
import androidx.ink.geometry.ImmutableVec
import androidx.ink.strokes.Stroke
import org.samesame.notes.core.model.document.rotatedBounds

@JvmInline
value class ElementId(val value: Long)

/** One entry of a page layer; list order is z-order (first = bottom). */
sealed interface PageElement {
    val id: ElementId
    val bounds: ImmutableBox
}

class InkElement(override val id: ElementId, val stroke: Stroke) : PageElement {
    override val bounds: ImmutableBox = stroke.shape.computeBoundingBox()?.toImmutable() ?: box(0f, 0f, 0f, 0f)
}

/** A floating object. [json] is the serialized FloatingObject, opaque to this module. */
class ObjectElement(override val id: ElementId, val json: String, override val bounds: ImmutableBox) : PageElement

/** The decoded floating object, null when the JSON is unreadable (kept as-is by every edit). */
fun ObjectElement.floating(): org.samesame.notes.core.model.document.FloatingObject? =
    runCatching { org.samesame.notes.core.model.document.FloatingObject.fromJson(json) }.getOrNull()

/** New element with the same id for [f]; bounds follow its (rotated) frame. */
fun ObjectElement.withFloating(f: org.samesame.notes.core.model.document.FloatingObject): ObjectElement =
    newObjectElement(id, f)

fun newObjectElement(id: ElementId, f: org.samesame.notes.core.model.document.FloatingObject): ObjectElement {
    val b = f.rotatedBounds // axis-aligned bounds of the (possibly rotated) frame
    return ObjectElement(id, f.toJson(), box(b.left, b.top, b.right, b.bottom))
}

/** Immutable snapshot of a page layer; copy the list on edit. */
class PageContent(val pageId: String, val elements: List<PageElement>, val version: Long)

/** The ImmutableBox constructor is internal in Ink; build through the factory. */
fun box(xMin: Float, yMin: Float, xMax: Float, yMax: Float): ImmutableBox =
    ImmutableBox.fromTwoPoints(ImmutableVec(xMin, yMin), ImmutableVec(xMax, yMax))
