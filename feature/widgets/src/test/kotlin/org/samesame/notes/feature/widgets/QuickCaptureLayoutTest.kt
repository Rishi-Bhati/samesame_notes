package org.samesame.notes.feature.widgets

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.sqrt

class QuickCaptureLayoutTest {
    private fun shape(w: Int, h: Int) = QuickCaptureLayout.forSize(DpSize(w.dp, h.dp))

    @Test fun `low widgets keep the bar`() {
        assertThat(shape(320, 60)).isEqualTo(CaptureShape.Bar)
        assertThat(shape(180, 100)).isEqualTo(CaptureShape.Bar)
        // Square but too small for five 48 dp targets: the bar.
        assertThat(shape(110, 110)).isEqualTo(CaptureShape.Bar)
    }

    @Test fun `tall or wide non square widgets get tiles`() {
        assertThat(shape(320, 140)).isEqualTo(CaptureShape.Tiles)
        assertThat(shape(320, 200)).isEqualTo(CaptureShape.Tiles)
        assertThat(shape(130, 200)).isEqualTo(CaptureShape.Tiles)
        // Square shaped but under the square threshold.
        assertThat(shape(130, 130)).isEqualTo(CaptureShape.Tiles)
    }

    @Test fun `roughly square widgets get the corner layout`() {
        assertThat(shape(140, 140)).isEqualTo(CaptureShape.Square)
        assertThat(shape(180, 190)).isEqualTo(CaptureShape.Square)
        assertThat(shape(400, 400)).isEqualTo(CaptureShape.Square)
    }

    @Test fun `aspect edges of the square layout`() {
        // 0.75 and 1.33 are inside, a little beyond is not.
        assertThat(shape(150, 200)).isEqualTo(CaptureShape.Square)
        assertThat(shape(200, 150)).isEqualTo(CaptureShape.Square)
        assertThat(shape(140, 200)).isEqualTo(CaptureShape.Tiles)
        assertThat(shape(200, 140)).isEqualTo(CaptureShape.Tiles)
        // One side under the threshold.
        assertThat(shape(139, 150)).isEqualTo(CaptureShape.Tiles)
        assertThat(QuickCaptureLayout.tiles(DpSize(180.dp, 180.dp))).isFalse()
    }

    private val squareSizes = listOf(140 to 140, 150 to 200, 200 to 150, 170 to 180, 180 to 180, 303 to 233, 300 to 300, 600 to 600)

    @Test fun `square layout keeps 48 dp targets inside the card`() {
        squareSizes.forEach { (w, h) ->
            val g = QuickCaptureLayout.squareGeometry(DpSize(w.dp, h.dp))
            assertThat(g.cornerTarget.value).isAtLeast(48f)
            assertThat(g.centreTarget.value).isAtLeast(48f)
            assertThat(g.edgeX.value).isAtLeast(0f)
            assertThat(g.edgeY.value).isAtLeast(0f)
            // Corner targets don't overlap each other.
            assertThat(g.pitch.value * 2).isAtLeast(g.cornerTarget.value)
        }
    }

    @Test fun `main button is modestly bigger than the corners, never a giant disc`() {
        squareSizes.forEach { (w, h) ->
            val g = QuickCaptureLayout.squareGeometry(DpSize(w.dp, h.dp))
            val ratio = g.centre.value / g.corner.value
            assertThat(ratio).isAtLeast(1.15f)
            assertThat(ratio).isAtMost(1.4f)
            assertThat(g.centre.value).isAtMost(72f)
            assertThat(g.centreIcon.value).isIn(com.google.common.collect.Range.closed(24f, 28f))
            assertThat(g.cornerIcon.value).isIn(com.google.common.collect.Range.closed(20f, 24f))
        }
    }

    @Test fun `visible shapes keep clear of each other and of the card edge`() {
        squareSizes.forEach { (w, h) ->
            val g = QuickCaptureLayout.squareGeometry(DpSize(w.dp, h.dp))
            // Corner circle to the main rounded square, along the diagonal.
            val halfDiagonal = g.centre.value / 2 * sqrt(2.0) - g.centreRadius.value * (sqrt(2.0) - 1)
            val gap = g.pitch.value * sqrt(2.0) - g.corner.value / 2 - halfDiagonal
            assertThat(gap).isAtLeast(8.0)
            // Visible corner circle to the card edge, on both axes.
            assertThat(w / 2f - g.pitch.value - g.corner.value / 2).isAtLeast(g.inset.value - 0.01f)
            assertThat(h / 2f - g.pitch.value - g.corner.value / 2).isAtLeast(g.inset.value - 0.01f)
            assertThat(g.inset.value).isIn(com.google.common.collect.Range.closed(10f, 20f))
        }
    }

    @Test fun `the cluster stays square and stops spreading on a big widget`() {
        val oblong = QuickCaptureLayout.squareGeometry(DpSize(303.dp, 233.dp))
        // Same pitch on both axes: the wider side gets the extra margin.
        assertThat(oblong.edgeX.value).isGreaterThan(oblong.edgeY.value)
        val big = QuickCaptureLayout.squareGeometry(DpSize(600.dp, 600.dp))
        assertThat(big.corner).isEqualTo(QuickCaptureLayout.CORNER_MAX)
        assertThat(big.pitch.value).isWithin(0.01f).of(QuickCaptureLayout.CORNER_MAX.value * QuickCaptureLayout.MAX_PITCH_RATIO)
    }

    @Test fun `bar height is 48 dp and shrinks only on a very low widget`() {
        assertThat(QuickCaptureLayout.barHeight(100.dp)).isEqualTo(48.dp)
        assertThat(QuickCaptureLayout.barHeight(64.dp)).isEqualTo(48.dp)
        assertThat(QuickCaptureLayout.barHeight(52.dp)).isEqualTo(36.dp)
        assertThat(QuickCaptureLayout.barHeight(40.dp)).isEqualTo(32.dp)
    }

    @Test fun `tiles stay one row on a wide widget and two columns on a tall one`() {
        assertThat(QuickCaptureLayout.tileColumns(DpSize(350.dp, 150.dp), 4)).isEqualTo(4)
        assertThat(QuickCaptureLayout.tileColumns(DpSize(220.dp, 300.dp), 4)).isEqualTo(2)
        assertThat(QuickCaptureLayout.tileColumns(DpSize(220.dp, 300.dp), 3)).isEqualTo(2)
        assertThat(QuickCaptureLayout.tileColumns(DpSize(220.dp, 300.dp), 2)).isEqualTo(2)
        assertThat(QuickCaptureLayout.tileColumns(DpSize(220.dp, 300.dp), 1)).isEqualTo(1)
    }
}
