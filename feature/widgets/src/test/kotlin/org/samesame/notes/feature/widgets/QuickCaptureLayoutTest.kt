package org.samesame.notes.feature.widgets

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import org.junit.Test

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

    @Test fun `square buttons keep 48 dp targets and a bigger main button that clears the corners`() {
        listOf(140 to 140, 150 to 200, 200 to 150, 180 to 180, 300 to 300).forEach { (w, h) ->
            val size = DpSize(w.dp, h.dp)
            val corner = QuickCaptureLayout.corner(size)
            val centre = QuickCaptureLayout.centre(size)
            assertThat(corner.value).isAtLeast(48f)
            assertThat(centre.value).isGreaterThan(corner.value)
            // Corner centres on the diagonals: the main button's edge stays off the corner buttons.
            val dx = (w - 12 - corner.value) / 2
            val dy = (h - 12 - corner.value) / 2
            val gap = Math.sqrt((dx * dx + dy * dy).toDouble()) - corner.value / 2 - centre.value / 2
            assertThat(gap).isAtLeast(1.0)
        }
        assertThat(QuickCaptureLayout.centre(DpSize(1000.dp, 1000.dp))).isEqualTo(QuickCaptureLayout.CENTRE_MAX)
        assertThat(QuickCaptureLayout.corner(DpSize(1000.dp, 1000.dp))).isEqualTo(QuickCaptureLayout.CORNER_MAX)
    }
}
