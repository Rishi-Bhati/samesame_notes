package org.samesame.notes.core.ink.tools

import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.samesame.notes.core.ink.PenBrushes
import org.samesame.notes.core.ink.brush.PenCatalog

/** Needs the native ink library, so it only runs on a device. */
@RunWith(AndroidJUnit4::class)
class StraightLineInstrumentedTest {
    @Test
    fun snapKeepsEndsAndBrushAndDropsTheWobble() {
        val brush = PenCatalog.highlighterFor(2).brush(50, 0xFFFFEB3B)
        val b = MutableStrokeInputBatch()
        val ys = floatArrayOf(0f, 9f, -7f, 12f, 3f)
        ys.forEachIndexed { i, y -> b.add(InputToolType.STYLUS, x = i * 25f, y = y, elapsedTimeMillis = i * 10L, pressure = 0.5f) }
        val snapped = StraightLine.snap(Stroke(brush, b))
        val first = StrokeInput(); val last = StrokeInput()
        snapped.inputs.populate(0, first); snapped.inputs.populate(snapped.inputs.size - 1, last)
        assertThat(first.x).isEqualTo(0f); assertThat(first.y).isEqualTo(0f)
        assertThat(last.x).isEqualTo(100f); assertThat(last.y).isEqualTo(3f)
        val p = StrokeInput()
        for (i in 0 until snapped.inputs.size) {
            snapped.inputs.populate(i, p)
            assertThat(p.y).isWithin(0.01f).of(p.x * 3f / 100f)
        }
        assertThat(snapped.brush.family.developerComment).isEqualTo(PenBrushes.ROUND_MARKER_KEY)
    }

    @Test
    fun roundMarkerFamilySurvivesTheKeyRoundTrip() {
        val family = PenBrushes.familyForKey(PenBrushes.ROUND_MARKER_KEY)
        assertThat(family.developerComment).isEqualTo(PenBrushes.ROUND_MARKER_KEY)
    }
}
