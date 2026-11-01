package org.samesame.notes.feature.composer.canvas

import androidx.compose.ui.geometry.Offset
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput

/** A finished live stroke attributed to a page, in that page's space. */
class PlacedStroke(val pageIndex: Int, val stroke: Stroke)

/**
 * Hand-off from live ink (document space) to page space (docs 2.5): the page containing the first input
 * owns the stroke, whose input batch is rebuilt translated into that page's space. O(points).
 * Needs the native Ink library, so it is exercised by instrumented tests only; [pageFor] is pure.
 */
object StrokePlacement {
    /** Page for a stroke starting at [firstInput]; a start in a gap goes to the nearest page. */
    fun pageFor(firstInput: Offset, layout: DocumentLayout): Int? = layout.pageAt(firstInput) ?: layout.nearestPage(firstInput)

    fun toPage(stroke: Stroke, layout: DocumentLayout): PlacedStroke? {
        val inputs = stroke.inputs
        if (inputs.size == 0) return null
        val first = StrokeInput().also { inputs.populate(0, it) }
        val index = pageFor(Offset(first.x, first.y), layout) ?: return null
        val origin = layout.pageOrigin(index)
        val batch = MutableStrokeInputBatch()
        val tmp = StrokeInput()
        for (i in 0 until inputs.size) {
            inputs.populate(i, tmp)
            batch.add(
                tmp.toolType, tmp.x - origin.x, tmp.y - origin.y, tmp.elapsedTimeMillis,
                tmp.strokeUnitLengthCm, tmp.pressure, tmp.tiltRadians, tmp.orientationRadians,
            )
        }
        return PlacedStroke(index, Stroke(stroke.brush, batch))
    }
}
