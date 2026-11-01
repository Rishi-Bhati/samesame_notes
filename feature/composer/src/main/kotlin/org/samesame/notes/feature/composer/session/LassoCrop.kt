package org.samesame.notes.feature.composer.session

import org.samesame.notes.core.model.document.Pt
import kotlin.math.abs

/**
 * Lasso crop of an image (the reference's object context menu "Lasso crop", images only): the finger draws a free
 * outline over the picture; Done keeps the part inside it with everything outside transparent. Points are fractions of
 * the image (0..1 in the object's own unrotated axes). The kept file is the outline's bounding box (a new PNG, like
 * the rectangular crop writes a new file) and the object shrinks to it in place ([ImageCrop.apply]). Pure, unit-tested.
 */
object LassoCrop {
    /** Points closer than this (fraction of the image) to the previous one are dropped while drawing. */
    const val MIN_STEP = 0.004f
    /** Smallest kept box side (fraction of the image). */
    const val MIN_SIDE = 0.02f

    /** A lasso point under the finger, kept inside the image. */
    fun clamp(p: Pt): Pt = Pt(p.x.coerceIn(0f, 1f), p.y.coerceIn(0f, 1f))

    /** True when [next] is far enough from [last] to be added. */
    fun accepts(last: Pt?, next: Pt): Boolean = last == null || abs(next.x - last.x) + abs(next.y - last.y) >= MIN_STEP

    /** Area enclosed by the closed outline (shoelace), in image fractions squared. */
    fun area(points: List<Pt>): Float {
        if (points.size < 3) return 0f
        var a = 0f
        for (i in points.indices) {
            val p = points[i]; val q = points[(i + 1) % points.size]
            a += p.x * q.y - q.x * p.y
        }
        return abs(a) / 2f
    }

    /**
     * The crop box of a finished outline, or null when it encloses (almost) nothing: fewer than 3 points, a box under
     * [minW] x [minH], or an area under a quarter of that box minimum.
     */
    fun bounds(points: List<Pt>, minW: Float, minH: Float): CropFraction? {
        if (points.size < 3) return null
        val l = points.minOf { it.x }.coerceIn(0f, 1f); val r = points.maxOf { it.x }.coerceIn(0f, 1f)
        val t = points.minOf { it.y }.coerceIn(0f, 1f); val b = points.maxOf { it.y }.coerceIn(0f, 1f)
        if (r - l < minW || b - t < minH || area(points) < minW * minH / 4f) return null
        return CropFraction(l, t, r, b)
    }

    /** The outline in fractions of [crop] (0..1 across the kept box), for the mask drawn into the new bitmap. */
    fun inCrop(points: List<Pt>, crop: CropFraction): List<Pt> = points.map {
        Pt(
            if (crop.width > 0f) (it.x - crop.left) / crop.width else 0f,
            if (crop.height > 0f) (it.y - crop.top) / crop.height else 0f,
        )
    }
}
