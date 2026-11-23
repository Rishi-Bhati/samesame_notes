package org.samesame.notes.feature.widgets

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.sqrt

class QuickCaptureLayoutTest {
    private fun shape(w: Int, h: Int) = QuickCaptureLayout.forSize(DpSize(w.dp, h.dp))

    // Phone portrait cells are about 95 x 115 dp, tablet landscape cells about 150 x 115 dp (seen on the emulator).

    @Test fun `phone portrait cell sizes`() {
        assertThat(shape(90, 115)).isEqualTo(CaptureShape.Single) // 1 x 1
        assertThat(shape(190, 115)).isEqualTo(CaptureShape.Bar) // 2 x 1
        assertThat(shape(380, 115)).isEqualTo(CaptureShape.Bar) // 4 x 1
        assertThat(shape(90, 240)).isEqualTo(CaptureShape.Column) // 1 x 2
        assertThat(shape(90, 360)).isEqualTo(CaptureShape.Column) // 1 x 3
        assertThat(shape(190, 240)).isEqualTo(CaptureShape.Square) // 2 x 2
        assertThat(shape(285, 360)).isEqualTo(CaptureShape.Square) // 3 x 3
        assertThat(shape(285, 240)).isEqualTo(CaptureShape.Square) // 3 x 2
        assertThat(shape(380, 240)).isEqualTo(CaptureShape.Tiles) // 4 x 2
        assertThat(shape(190, 360)).isEqualTo(CaptureShape.Tiles) // 2 x 3
        assertThat(shape(190, 480)).isEqualTo(CaptureShape.Tiles) // 2 x 4
    }

    @Test fun `tablet landscape cell sizes`() {
        assertThat(shape(150, 115)).isEqualTo(CaptureShape.Single) // 1 x 1
        assertThat(shape(303, 115)).isEqualTo(CaptureShape.Bar) // 2 x 1
        assertThat(shape(607, 107)).isEqualTo(CaptureShape.Bar) // 4 x 1
        assertThat(shape(150, 233)).isEqualTo(CaptureShape.Column) // 1 x 2
        assertThat(shape(150, 350)).isEqualTo(CaptureShape.Column) // 1 x 3
        assertThat(shape(303, 233)).isEqualTo(CaptureShape.Square) // 2 x 2
        assertThat(shape(455, 350)).isEqualTo(CaptureShape.Square) // 3 x 3
        assertThat(shape(455, 233)).isEqualTo(CaptureShape.Tiles) // 3 x 2
        assertThat(shape(607, 233)).isEqualTo(CaptureShape.Tiles) // 4 x 2
    }

    @Test fun `narrow low widgets show icons, too small ones the single button`() {
        assertThat(shape(150, 80)).isEqualTo(CaptureShape.Icons)
        assertThat(QuickCaptureLayout.rowActions(DpSize(150.dp, 80.dp))).isEqualTo(1)
        assertThat(shape(110, 40)).isEqualTo(CaptureShape.Icons)
        assertThat(shape(40, 110)).isEqualTo(CaptureShape.Column)
        assertThat(shape(40, 40)).isEqualTo(CaptureShape.Single)
        // Android's sizing-table 1 x 1 (57 x 102): no room for an action under the main button.
        assertThat(shape(57, 102)).isEqualTo(CaptureShape.Single)
        // A row or column without any action set is the single button.
        assertThat(QuickCaptureLayout.forSize(DpSize(150.dp, 80.dp), actions = 0)).isEqualTo(CaptureShape.Single)
        assertThat(QuickCaptureLayout.forSize(DpSize(90.dp, 240.dp), actions = 0)).isEqualTo(CaptureShape.Single)
    }

    @Test fun `aspect edges of the square layout`() {
        // 2:3 and 3:2 are inside, a little beyond is not.
        assertThat(shape(200, 300)).isEqualTo(CaptureShape.Square)
        assertThat(shape(300, 200)).isEqualTo(CaptureShape.Square)
        assertThat(shape(190, 300)).isEqualTo(CaptureShape.Tiles)
        assertThat(shape(300, 190)).isEqualTo(CaptureShape.Tiles)
        // About square but under the square threshold: the single button.
        assertThat(shape(119, 130)).isEqualTo(CaptureShape.Single)
        assertThat(shape(120, 120)).isEqualTo(CaptureShape.Square)
        assertThat(QuickCaptureLayout.tiles(DpSize(180.dp, 180.dp))).isFalse()
    }

    @Test fun `every size from 40 dp up gets a layout whose buttons fit inside it`() {
        val inset = WidgetDims.Inset.value
        val gap = QuickCaptureLayout.BUTTON_GAP.value
        for (w in 40..640 step 10) for (h in 40..640 step 10) {
            val size = DpSize(w.dp, h.dp)
            when (QuickCaptureLayout.forSize(size)) {
                CaptureShape.Single -> {
                    val g = QuickCaptureLayout.singleGeometry(size)
                    assertThat(g.target.value).isAtMost(g.side.value)
                    assertThat(g.side.value - g.button.value).isAtLeast(8f)
                }
                CaptureShape.Icons -> {
                    val b = QuickCaptureLayout.barHeight(h.dp).value
                    val n = QuickCaptureLayout.rowActions(size)
                    assertThat(n).isAtLeast(1)
                    assertThat((n + 1) * b + n * gap).isAtMost(w - 2 * inset)
                    assertThat(b).isAtMost(h - 8f)
                }
                CaptureShape.Column -> {
                    val b = QuickCaptureLayout.columnButton(w.dp).value
                    val n = QuickCaptureLayout.columnActions(size)
                    assertThat(n).isAtLeast(1)
                    assertThat((n + 1) * b + n * gap).isAtMost(h - 2 * inset)
                    assertThat(b).isAtMost(w - 8f)
                }
                CaptureShape.Bar -> assertThat(w.toFloat()).isAtLeast(QuickCaptureLayout.BAR_LABEL_FROM.value)
                CaptureShape.Square -> assertThat(QuickCaptureLayout.squareGeometry(size).edge.value).isAtLeast(0f)
                CaptureShape.Tiles -> assertThat(minOf(w, h)).isAtLeast(120)
            }
        }
    }

    private val squareSizes = listOf(120 to 120, 120 to 180, 140 to 140, 150 to 200, 200 to 150, 170 to 180, 180 to 180, 303 to 233, 300 to 300, 600 to 600)

    @Test fun `square layout keeps 48 dp targets inside the card`() {
        squareSizes.forEach { (w, h) ->
            val g = QuickCaptureLayout.squareGeometry(DpSize(w.dp, h.dp))
            assertThat(g.cornerTarget.value).isAtLeast(48f)
            assertThat(g.centreTarget.value).isAtLeast(48f)
            assertThat(g.side.value).isEqualTo(minOf(w, h).toFloat())
            assertThat(g.edge.value).isAtLeast(0f)
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
            assertThat(g.cornerIcon.value).isIn(com.google.common.collect.Range.closed(18f, 24f))
        }
    }

    @Test fun `visible shapes keep clear of each other and of the card edge`() {
        squareSizes.forEach { (w, h) ->
            val g = QuickCaptureLayout.squareGeometry(DpSize(w.dp, h.dp))
            // Corner circle to the main rounded square, along the diagonal.
            val halfDiagonal = g.centre.value / 2 * sqrt(2.0) - g.centreRadius.value * (sqrt(2.0) - 1)
            val gap = g.pitch.value * sqrt(2.0) - g.corner.value / 2 - halfDiagonal
            assertThat(gap).isAtLeast(8.0)
            // Visible corner circle to the square card's edge.
            assertThat(g.side.value / 2 - g.pitch.value - g.corner.value / 2).isAtLeast(g.inset.value - 0.01f)
            assertThat(g.inset.value).isIn(com.google.common.collect.Range.closed(8f, 20f))
        }
    }

    @Test fun `the card is a square of the shorter side and the cluster stops spreading on a big widget`() {
        // A 4:3 widget gets a square card of its shorter side, with the same geometry as a square widget of that side.
        val oblong = QuickCaptureLayout.squareGeometry(DpSize(303.dp, 233.dp))
        assertThat(oblong.side).isEqualTo(233.dp)
        assertThat(oblong).isEqualTo(QuickCaptureLayout.squareGeometry(DpSize(233.dp, 233.dp)))
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
