package org.samesame.notes.core.ink.tools

import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput
import kotlin.math.hypot
import kotlin.math.max

/**
 * Straight-line highlighter / marker: a finished stroke is replaced by the straight segment from its first to its last
 * input (the brush, tool type and timing are kept; pressure is interpolated). The reference app's tool does the same
 * ("straight" tool action); axis snapping is not applied because the reference behaviour is not known.
 */
object StraightLine {
    /** Points along (x0,y0)..(x1,y1), at most [stepPu] apart, always including both ends. */
    fun samples(x0: Float, y0: Float, x1: Float, y1: Float, stepPu: Float = 8f): List<Pair<Float, Float>> {
        val n = max(1, kotlin.math.ceil(hypot(x1 - x0, y1 - y0) / stepPu.coerceAtLeast(0.01f)).toInt())
        return (0..n).map { i -> val t = i / n.toFloat(); (x0 + (x1 - x0) * t) to (y0 + (y1 - y0) * t) }
    }

    fun snap(stroke: Stroke): Stroke {
        val src = stroke.inputs
        if (src.size < 2) return stroke
        val a = StrokeInput(); val b = StrokeInput()
        src.populate(0, a); src.populate(src.size - 1, b)
        return toSegment(stroke, a.x, a.y, b.x, b.y)
    }

    /** [stroke] redrawn as the straight segment (x0,y0)..(x1,y1), keeping its brush, tool type, timing and pressure ends. */
    fun toSegment(stroke: Stroke, x0: Float, y0: Float, x1: Float, y1: Float): Stroke {
        val src = stroke.inputs
        if (src.size < 2) return stroke
        val a = StrokeInput(); val b = StrokeInput()
        src.populate(0, a); src.populate(src.size - 1, b)
        val pts = samples(x0, y0, x1, y1)
        val batch = MutableStrokeInputBatch()
        val q = StrokeInput()
        pts.forEachIndexed { i, (x, y) ->
            val t = i / (pts.size - 1).toFloat()
            val pressure = if (a.hasPressure && b.hasPressure) a.pressure + (b.pressure - a.pressure) * t else StrokeInput.NO_PRESSURE
            q.update(
                x, y, (a.elapsedTimeMillis + ((b.elapsedTimeMillis - a.elapsedTimeMillis) * t).toLong()), a.toolType,
                a.strokeUnitLengthCm, pressure, StrokeInput.NO_TILT, StrokeInput.NO_ORIENTATION,
            )
            batch.add(q)
        }
        return Stroke(stroke.brush, batch)
    }
}
