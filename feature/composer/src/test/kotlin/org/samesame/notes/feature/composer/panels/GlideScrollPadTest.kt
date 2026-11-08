package org.samesame.notes.feature.composer.panels

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GlideScrollPadTest {
    // Size list viewport 1160..1400 px (240 px tall), rows 44 px, 60 rows; content 2640 px -> max scroll 2400.
    private val listTop = 1160f
    private val listBottom = 1400f
    private val rowHeight = 44f
    private val count = 60
    private val maxScroll = 2400f

    @Test fun highlightedRowFollowsTheScroll() {
        assertThat(GlideScrollPad.listIndexAt(1160f, listTop, listBottom, 0f, rowHeight, count)).isEqualTo(0)
        assertThat(GlideScrollPad.listIndexAt(1204f, listTop, listBottom, 0f, rowHeight, count)).isEqualTo(1)
        assertThat(GlideScrollPad.listIndexAt(1160f, listTop, listBottom, 440f, rowHeight, count)).isEqualTo(10)
        assertThat(GlideScrollPad.listIndexAt(1399f, listTop, listBottom, maxScroll, rowHeight, count)).isEqualTo(59)
        // Outside the viewport or past the last row.
        assertThat(GlideScrollPad.listIndexAt(1150f, listTop, listBottom, 0f, rowHeight, count)).isEqualTo(-1)
        assertThat(GlideScrollPad.listIndexAt(1400f, listTop, listBottom, 0f, rowHeight, count)).isEqualTo(-1)
        assertThat(GlideScrollPad.listIndexAt(1399f, listTop, listBottom, maxScroll, rowHeight, 50)).isEqualTo(-1)
    }

    /**
     * E3: the mapping is a pure function of the finger position and the scroll value. There is no velocity or history
     * input any more, so the same finger height always gives the same row (and therefore the same size).
     */
    @Test fun sameFingerPositionAndScrollAlwaysGiveTheSameRow() {
        val cases = listOf(1160f to 0f, 1204f to 0f, 1250f to 440f, 1399f to maxScroll, 1160f to 88f)
        for ((y, scroll) in cases) {
            val first = GlideScrollPad.listIndexAt(y, listTop, listBottom, scroll, rowHeight, count)
            repeat(5) {
                assertThat(GlideScrollPad.listIndexAt(y, listTop, listBottom, scroll, rowHeight, count)).isEqualTo(first)
            }
        }
    }

    @Test fun onlyTheTopAndBottomEdgeZonesScroll() {
        // Edge zone = 15 % of 240 px = 36 px.
        assertThat(GlideScrollPad.edgeBias(listTop, listTop, listBottom)).isEqualTo(-1f)
        assertThat(GlideScrollPad.edgeBias(listTop + 18f, listTop, listBottom)).isEqualTo(-0.5f)
        assertThat(GlideScrollPad.edgeBias(1280f, listTop, listBottom)).isEqualTo(0f)
        assertThat(GlideScrollPad.edgeBias(listBottom - 18f, listTop, listBottom)).isEqualTo(0.5f)
        assertThat(GlideScrollPad.edgeBias(listBottom, listTop, listBottom)).isEqualTo(1f)
        // Outside the list viewport (over the headings, or a finger that has not glided in yet) nothing scrolls.
        assertThat(GlideScrollPad.edgeBias(1100f, listTop, listBottom)).isEqualTo(0f)
        assertThat(GlideScrollPad.edgeBias(1450f, listTop, listBottom)).isEqualTo(0f)
        assertThat(GlideScrollPad.edgeScrollStep(1280f, listTop, listBottom, 16f, 900f)).isEqualTo(0f)
    }

    @Test fun edgeScrollIsProportionalToDepthAndCappedByTheFrameTime() {
        // At the very edge: the cap. Half the zone: half the cap. The step scales with the frame time.
        assertThat(GlideScrollPad.edgeScrollStep(listTop, listTop, listBottom, 1000f, 900f)).isEqualTo(-900f)
        assertThat(GlideScrollPad.edgeScrollStep(listTop + 18f, listTop, listBottom, 1000f, 900f)).isEqualTo(-450f)
        assertThat(GlideScrollPad.edgeScrollStep(listTop, listTop, listBottom, 16f, 900f)).isWithin(0.001f).of(-14.4f)
        assertThat(GlideScrollPad.edgeScrollStep(listBottom, listTop, listBottom, 16f, 900f)).isWithin(0.001f).of(14.4f)
    }

    @Test fun oneGlideFromTopToBottomReachesEverySize() {
        // A single glide: the finger starts on the first visible row and slides down to the bottom edge, where it is
        // held; the highlight is collected on every frame and must cover every row of the list exactly once over.
        var scroll = 0f
        val seen = mutableSetOf<Int>()
        val steps = 30
        for (i in 0..steps) {
            val y = listTop + (listBottom - 1f - listTop) * i / steps
            val index = GlideScrollPad.listIndexAt(y, listTop, listBottom, scroll, rowHeight, count)
            if (index >= 0) seen += index
            scroll = (scroll + GlideScrollPad.edgeScrollStep(y, listTop, listBottom, 16f, 900f))
                .coerceIn(0f, maxScroll)
        }
        var frames = 0
        while (scroll < maxScroll && frames < 1000) {
            val index = GlideScrollPad.listIndexAt(listBottom - 1f, listTop, listBottom, scroll, rowHeight, count)
            if (index >= 0) seen += index
            scroll = (scroll + GlideScrollPad.edgeScrollStep(listBottom, listTop, listBottom, 16f, 900f))
                .coerceIn(0f, maxScroll)
            frames++
        }
        assertThat(scroll).isEqualTo(maxScroll)
        assertThat(seen).containsExactlyElementsIn((0 until count).toSet())
        assertThat(frames).isLessThan(1000)
        // Back up with the finger at the top edge.
        while (scroll > 0f) {
            scroll = (scroll + GlideScrollPad.edgeScrollStep(listTop, listTop, listBottom, 16f, 900f))
                .coerceIn(0f, maxScroll)
        }
        assertThat(scroll).isEqualTo(0f)
    }
}
