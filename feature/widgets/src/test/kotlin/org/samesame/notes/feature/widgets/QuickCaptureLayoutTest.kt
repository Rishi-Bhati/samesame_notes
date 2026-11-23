package org.samesame.notes.feature.widgets

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

class QuickCaptureLayoutTest {
    private val defaults = CaptureButtons()
    private fun spec(w: Int, h: Int, buttons: CaptureButtons = defaults) = QuickCaptureLayout.spec(w.toFloat(), h.toFloat(), buttons)
    private fun shape(w: Int, h: Int, buttons: CaptureButtons = defaults) = spec(w, h, buttons).shape
    private fun actions(s: CaptureSpec) = s.slices.count { !it.main }

    // Phone portrait cells are about 95 x 115 dp, tablet landscape cells about 150 x 115 dp (seen on the emulator).

    @Test fun `phone portrait cell sizes`() {
        assertThat(shape(90, 115)).isEqualTo(CaptureShape.Single) // 1 x 1
        assertThat(shape(190, 115)).isEqualTo(CaptureShape.Row) // 2 x 1
        assertThat(shape(380, 115)).isEqualTo(CaptureShape.Row) // 4 x 1
        assertThat(shape(90, 240)).isEqualTo(CaptureShape.Column) // 1 x 2
        assertThat(shape(90, 360)).isEqualTo(CaptureShape.Column) // 1 x 3
        assertThat(shape(190, 240)).isEqualTo(CaptureShape.Pad) // 2 x 2
        assertThat(shape(285, 360)).isEqualTo(CaptureShape.Pad) // 3 x 3
        assertThat(shape(285, 240)).isEqualTo(CaptureShape.Pad) // 3 x 2
        assertThat(shape(380, 240)).isEqualTo(CaptureShape.Bento) // 4 x 2
        assertThat(shape(190, 360)).isEqualTo(CaptureShape.Bento) // 2 x 3
        assertThat(shape(190, 480)).isEqualTo(CaptureShape.Bento) // 2 x 4
    }

    @Test fun `tablet landscape cell sizes`() {
        assertThat(shape(150, 115)).isEqualTo(CaptureShape.Single) // 1 x 1
        assertThat(shape(303, 115)).isEqualTo(CaptureShape.Row) // 2 x 1
        assertThat(shape(607, 107)).isEqualTo(CaptureShape.Row) // 4 x 1
        assertThat(shape(150, 233)).isEqualTo(CaptureShape.Column) // 1 x 2
        assertThat(shape(150, 350)).isEqualTo(CaptureShape.Column) // 1 x 3
        assertThat(shape(303, 233)).isEqualTo(CaptureShape.Pad) // 2 x 2
        assertThat(shape(455, 350)).isEqualTo(CaptureShape.Pad) // 3 x 3
        assertThat(shape(455, 233)).isEqualTo(CaptureShape.Bento) // 3 x 2
        assertThat(shape(607, 233)).isEqualTo(CaptureShape.Bento) // 4 x 2
    }

    @Test fun `bar shows as many actions as fit and the label when there is room`() {
        val phone2x1 = spec(190, 115)
        assertThat(actions(phone2x1)).isEqualTo(2)
        assertThat(phone2x1.slices.first { it.main }.label).isNull()
        val phone4x1 = spec(380, 115)
        assertThat(actions(phone4x1)).isEqualTo(4)
        assertThat(phone4x1.slices.first { it.main }.label).isNotNull()
        val tablet4x1 = spec(607, 107)
        assertThat(actions(tablet4x1)).isEqualTo(4)
        assertThat(tablet4x1.slices.first { it.main }.w).isAtMost(QuickCaptureLayout.MAIN_MAX)
        // The bar is 64 dp high on a normal cell and stays centred.
        assertThat(tablet4x1.slices.all { it.h == QuickCaptureLayout.BAR }).isTrue()
        assertThat(QuickCaptureLayout.barHeight(115f)).isEqualTo(64f)
        assertThat(QuickCaptureLayout.barHeight(52f)).isEqualTo(40f)
        assertThat(QuickCaptureLayout.barHeight(40f)).isEqualTo(40f)
    }

    @Test fun `column stacks the actions that fit under the main button`() {
        assertThat(actions(spec(90, 360))).isEqualTo(4)
        assertThat(actions(spec(90, 240))).isEqualTo(3)
        assertThat(spec(90, 240).slices.first().main).isTrue()
    }

    @Test fun `small or empty layouts fall back to the single tile`() {
        assertThat(shape(40, 40)).isEqualTo(CaptureShape.Single)
        assertThat(shape(57, 102)).isEqualTo(CaptureShape.Single)
        assertThat(shape(119, 130)).isEqualTo(CaptureShape.Single)
        assertThat(shape(120, 120)).isEqualTo(CaptureShape.Pad)
        val none = CaptureButtons(corners = List(CaptureButtons.SLOTS) { CaptureButton.None })
        assertThat(shape(190, 190, none)).isEqualTo(CaptureShape.Single)
        assertThat(shape(90, 360, none)).isEqualTo(CaptureShape.Single)
        assertThat(shape(190, 80, none)).isEqualTo(CaptureShape.Row) // a labelled "Take a note" pill
        assertThat(shape(120, 60, none)).isEqualTo(CaptureShape.Single)
        // The bento without actions is one large main tile.
        assertThat(spec(380, 240, none).slices).hasSize(1)
    }

    @Test fun `aspect edges of the pad`() {
        assertThat(shape(200, 300)).isEqualTo(CaptureShape.Pad)
        assertThat(shape(300, 200)).isEqualTo(CaptureShape.Pad)
        assertThat(shape(190, 300)).isEqualTo(CaptureShape.Bento)
        assertThat(shape(300, 190)).isEqualTo(CaptureShape.Bento)
        assertThat(QuickCaptureLayout.forSize(DpSize(180.dp, 180.dp))).isEqualTo(CaptureShape.Pad)
    }

    @Test fun `an empty corner leaves its quadrant out and keeps the others in place`() {
        val three = defaults.withCorner(CaptureButtons.TOP_RIGHT, CaptureButton.None)
        val full = spec(190, 190)
        val missing = spec(190, 190, three)
        assertThat(missing.slices.map { it.slot }).containsExactly(0, 2, 3)
        assertThat(missing.slices).containsExactlyElementsIn(full.slices.filter { it.slot != CaptureButtons.TOP_RIGHT })
    }

    @Test fun `pad is a square of the shorter side, capped, around the coin`() {
        listOf(120 to 120, 190 to 240, 303 to 233, 455 to 350, 600 to 600).forEach { (w, h) ->
            val s = spec(w, h)
            val side = minOf(w.toFloat(), h.toFloat(), QuickCaptureLayout.PAD_MAX)
            val left = s.slices.minOf { it.x }
            val right = s.slices.maxOf { it.x + it.w }
            assertThat(right - left).isWithin(0.01f).of(side)
            val coin = s.coin!!
            assertThat(coin.cx).isWithin(0.01f).of(w / 2f)
            assertThat(coin.cy).isWithin(0.01f).of(h / 2f)
            // The coin with its moat is a big enough target, and each quadrant's icon stays clear of the moat.
            assertThat((coin.r + coin.moat) * 2).isAtLeast(QuickCaptureLayout.TARGET)
            s.slices.forEach { q ->
                val gx = q.x + q.glyph.cx
                val gy = q.y + q.glyph.cy
                val near = hypot(gx - coin.cx, gy - coin.cy) - q.glyph.size / 2 * 1.42f
                assertThat(near).isAtLeast(coin.r + coin.moat)
            }
        }
    }

    @Test fun `every size from 40 dp up fits inside the widget, without overlaps, with readable icons`() {
        val shapes = mutableSetOf<CaptureShape>()
        for (w in 40..640 step 10) for (h in 40..640 step 10) {
            val s = spec(w, h)
            shapes += s.shape
            s.slices.forEach { sl ->
                assertThat(sl.x).isAtLeast(-0.01f)
                assertThat(sl.y).isAtLeast(-0.01f)
                assertThat(sl.x + sl.w).isAtMost(w + 0.01f)
                assertThat(sl.y + sl.h).isAtMost(h + 0.01f)
                // Radii fit the slice: no two corners on one edge add up to more than the edge.
                val c = sl.corners
                assertThat(max(c.tl + c.tr, c.bl + c.br)).isAtMost(sl.w + 0.01f)
                assertThat(max(c.tl + c.bl, c.tr + c.br)).isAtMost(sl.h + 0.01f)
                // The icon sits inside its slice.
                val g = sl.glyph
                assertThat(g.cx - g.size / 2).isAtLeast(0f)
                assertThat(g.cy - g.size / 2).isAtLeast(0f)
                assertThat(g.cx + g.size / 2).isAtMost(sl.w + 0.01f)
                assertThat(g.cy + g.size / 2).isAtMost(sl.h + 0.01f)
                assertThat(g.size).isAtLeast(18f)
                sl.label?.let { b ->
                    assertThat(b.x).isAtLeast(0f)
                    assertThat(b.x + b.w).isAtMost(sl.w + 0.01f)
                    assertThat(b.y + b.h).isAtMost(sl.h + 0.01f)
                    // A label never covers the icon: the bar's starts after it, the others are under it.
                    if (b.start) assertThat(b.x).isAtLeast(g.cx + g.size / 2) else assertThat(b.y).isAtLeast(g.cy + g.size / 2 - 0.01f)
                }
                // Tap targets: 48 dp where the widget has room for it.
                if (min(w, h) >= 64) com.google.common.truth.Truth.assertWithMessage("$w x $h $sl").that(min(sl.w, sl.h)).isAtLeast(QuickCaptureLayout.TARGET)
            }
            // Slices never overlap: at least the gap apart on one axis.
            for (i in s.slices.indices) for (j in i + 1 until s.slices.size) {
                val a = s.slices[i]
                val b = s.slices[j]
                val apartX = max(b.x - (a.x + a.w), a.x - (b.x + b.w))
                val apartY = max(b.y - (a.y + a.h), a.y - (b.y + b.h))
                assertThat(max(apartX, apartY)).isAtLeast(QuickCaptureLayout.GAP - 0.01f)
            }
        }
        assertThat(shapes).containsExactlyElementsIn(CaptureShape.entries)
    }

    @Test fun `bento rounds only the outer corners of the whole shape`() {
        val s = QuickCaptureLayout.spec(380f, 240f, defaults, outerRadius = 28f)
        val main = s.slices.first { it.main }
        assertThat(main.corners).isEqualTo(Corners(28f, QuickCaptureLayout.TILE_INNER, QuickCaptureLayout.TILE_INNER, 28f))
        val topRight = s.slices.first { it.slot == CaptureButtons.TOP_RIGHT }
        assertThat(topRight.corners.tr).isEqualTo(28f)
        assertThat(topRight.corners.smallest).isEqualTo(QuickCaptureLayout.TILE_INNER)
        assertThat(s.slices.filter { !it.main }.all { it.label != null }).isTrue()
        // Three actions: the last one spans the row.
        val three = QuickCaptureLayout.spec(380f, 240f, defaults.withCorner(CaptureButtons.BOTTOM_RIGHT, CaptureButton.None))
        val last = three.slices.last()
        assertThat(last.w).isGreaterThan(three.slices[1].w * 1.9f)
    }
}
