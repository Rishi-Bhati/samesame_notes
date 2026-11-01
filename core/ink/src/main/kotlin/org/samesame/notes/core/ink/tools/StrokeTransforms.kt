package org.samesame.notes.core.ink.tools

import androidx.ink.brush.Brush
import androidx.ink.geometry.AffineTransform
import androidx.ink.geometry.ImmutableAffineTransform
import androidx.ink.geometry.ImmutableBox
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput
import org.samesame.notes.core.ink.PenBrushes
import org.samesame.notes.core.ink.page.InkElement
import org.samesame.notes.core.ink.page.ObjectElement
import org.samesame.notes.core.ink.page.PageElement
import org.samesame.notes.core.ink.page.ElementId
import org.samesame.notes.core.ink.page.floating
import org.samesame.notes.core.ink.page.withFloating
import org.samesame.notes.core.model.document.Affine2
import org.samesame.notes.core.model.document.transformedBy
import org.samesame.notes.core.ink.page.box
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Bakes transforms and style changes into strokes (docs 4.3). Matrices map x' = m00 x + m10 y + m20,
 * y' = m01 x + m11 y + m21 (Ink's [AffineTransform] layout). Everything returns new immutable values.
 */
object StrokeTransforms {
    fun translate(dx: Float, dy: Float): ImmutableAffineTransform = ImmutableAffineTransform.translate(dx, dy)

    /** [first] applied before [second]. */
    fun compose(first: AffineTransform, second: AffineTransform): ImmutableAffineTransform = ImmutableAffineTransform(
        second.m00 * first.m00 + second.m10 * first.m01,
        second.m00 * first.m10 + second.m10 * first.m11,
        second.m00 * first.m20 + second.m10 * first.m21 + second.m20,
        second.m01 * first.m00 + second.m11 * first.m01,
        second.m01 * first.m10 + second.m11 * first.m11,
        second.m01 * first.m20 + second.m11 * first.m21 + second.m21,
    )

    fun scaleAbout(sx: Float, sy: Float, px: Float, py: Float): ImmutableAffineTransform =
        ImmutableAffineTransform(sx, 0f, px - sx * px, 0f, sy, py - sy * py)

    fun rotateAbout(degrees: Float, px: Float, py: Float): ImmutableAffineTransform {
        val r = Math.toRadians(degrees.toDouble()); val c = Math.cos(r).toFloat(); val s = Math.sin(r).toFloat()
        return ImmutableAffineTransform(c, -s, px - c * px + s * py, s, c, py - s * px - c * py)
    }

    /** Brush size factor under [m]: sqrt(|det|) (unverified against the reference, docs 4.3). */
    fun sizeFactor(m: AffineTransform): Float = sqrt(abs(m.m00 * m.m11 - m.m10 * m.m01))

    fun transformBounds(b: ImmutableBox, m: AffineTransform): ImmutableBox {
        var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
        for (cx in floatArrayOf(b.xMin, b.xMax)) for (cy in floatArrayOf(b.yMin, b.yMax)) {
            val x = m.m00 * cx + m.m10 * cy + m.m20; val y = m.m01 * cx + m.m11 * cy + m.m21
            x0 = min(x0, x); x1 = max(x1, x); y0 = min(y0, y); y1 = max(y1, y)
        }
        return box(x0, y0, x1, y1)
    }

    /** New stroke with every input mapped through [m] and the brush size scaled by [sizeFactor]. */
    fun apply(stroke: Stroke, m: AffineTransform): Stroke {
        val src = stroke.inputs
        val batch = MutableStrokeInputBatch()
        val rot = atan2(m.m01, m.m00) // pen orientation follows rotation
        val p = StrokeInput(); val q = StrokeInput()
        for (i in 0 until src.size) {
            src.populate(i, p)
            q.update(
                m.m00 * p.x + m.m10 * p.y + m.m20, m.m01 * p.x + m.m11 * p.y + m.m21,
                p.elapsedTimeMillis, p.toolType, p.strokeUnitLengthCm, p.pressure, p.tiltRadians,
                if (p.hasOrientation) p.orientationRadians + rot else StrokeInput.NO_ORIENTATION,
            )
            batch.add(q)
        }
        val f = sizeFactor(m)
        val b = stroke.brush
        val brush = if (f == 1f) b else Brush.createWithColorIntArgb(b.family, b.colorIntArgb, b.size * f, b.epsilon)
        return Stroke(brush, batch)
    }

    /**
     * Applies [m] to the elements whose id is in [ids]; others are returned untouched (same instances).
     * Ink keeps its id. Object elements get transformed bounds and their JSON rect rewritten ([transformObject]).
     */
    fun apply(elements: List<PageElement>, ids: Set<ElementId>, m: AffineTransform): List<PageElement> =
        elements.map { e ->
            when {
                e.id !in ids -> e
                e is InkElement -> InkElement(e.id, apply(e.stroke, m))
                e is ObjectElement -> transformObject(e, m)
                else -> e
            }
        }

    /**
     * Moves/resizes/rotates a floating object: its frame (rect + rotation) and bounds follow [m] (no minimum is
     * enforced here, the gesture does that). Unreadable JSON keeps the JSON and only moves the bounds.
     */
    fun transformObject(e: ObjectElement, m: AffineTransform): ObjectElement {
        val f = e.floating() ?: return ObjectElement(e.id, e.json, transformBounds(e.bounds, m))
        return e.withFloating(f.transformedBy(Affine2(m.m00, m.m10, m.m20, m.m01, m.m11, m.m21)))
    }

    /**
     * Change style: keeps the inputs, replaces colour / size / pen. Null leaves a property as is.
     * [familyKey] is a `PenBrushes` key (samesame/<Type>/<pressure|fixed>); the caller picks alpha in [colorArgb].
     */
    fun restyle(stroke: Stroke, colorArgb: Int? = null, sizePu: Float? = null, familyKey: String? = null): Stroke {
        val b = stroke.brush
        val family = familyKey?.let(PenBrushes::familyForKey) ?: b.family
        val brush = Brush.createWithColorIntArgb(family, colorArgb ?: b.colorIntArgb, sizePu ?: b.size, b.epsilon)
        return Stroke(brush, stroke.inputs)
    }

    fun restyle(elements: List<PageElement>, ids: Set<ElementId>, colorArgb: Int? = null, sizePu: Float? = null, familyKey: String? = null): List<PageElement> =
        elements.map { e -> if (e is InkElement && e.id in ids) InkElement(e.id, restyle(e.stroke, colorArgb, sizePu, familyKey)) else e }
}
