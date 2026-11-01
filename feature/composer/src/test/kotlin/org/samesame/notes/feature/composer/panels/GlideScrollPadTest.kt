package org.samesame.notes.feature.composer.panels

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GlideScrollPadTest {
    // Popup window 1000..1400 px (400 px tall); list content 2640 px in a 240 px viewport -> max scroll 2400.
    private val top = 1000f
    private val bottom = 1400f
    private val max = 2400f

    @Test fun windowHeightMapsLinearlyToTheWholeList() {
        assertThat(GlideScrollPad.absoluteTarget(top, top, bottom, max)).isEqualTo(0f)
        assertThat(GlideScrollPad.absoluteTarget(bottom, top, bottom, max)).isEqualTo(max)
        assertThat(GlideScrollPad.absoluteTarget(1100f, top, bottom, max)).isEqualTo(600f)
        assertThat(GlideScrollPad.absoluteTarget(1200f, top, bottom, max)).isEqualTo(1200f)
        // Clamped outside the window; nothing to scroll -> 0.
        assertThat(GlideScrollPad.absoluteTarget(900f, top, bottom, max)).isEqualTo(0f)
        assertThat(GlideScrollPad.absoluteTarget(1500f, top, bottom, max)).isEqualTo(max)
        assertThat(GlideScrollPad.absoluteTarget(1200f, top, bottom, 0f)).isEqualTo(0f)
    }

    @Test fun highlightedRowFollowsTheScroll() {
        // Viewport 1160..1400, rows 44 px, 60 rows.
        assertThat(GlideScrollPad.listIndexAt(1160f, 1160f, 1400f, 0f, 44f, 60)).isEqualTo(0)
        assertThat(GlideScrollPad.listIndexAt(1204f, 1160f, 1400f, 0f, 44f, 60)).isEqualTo(1)
        assertThat(GlideScrollPad.listIndexAt(1160f, 1160f, 1400f, 440f, 44f, 60)).isEqualTo(10)
        assertThat(GlideScrollPad.listIndexAt(1399f, 1160f, 1400f, 2400f, 44f, 60)).isEqualTo(59)
        // Outside the viewport or past the last row.
        assertThat(GlideScrollPad.listIndexAt(1150f, 1160f, 1400f, 0f, 44f, 60)).isEqualTo(-1)
        assertThat(GlideScrollPad.listIndexAt(1400f, 1160f, 1400f, 0f, 44f, 60)).isEqualTo(-1)
        assertThat(GlideScrollPad.listIndexAt(1399f, 1160f, 1400f, 2400f, 44f, 50)).isEqualTo(-1)
    }

    @Test fun smoothingMovesTowardTheTargetWithoutJumping() {
        val a = GlideScrollPad.smoothStep(0f, 1000f, 16f)
        assertThat(a).isGreaterThan(0f)
        assertThat(a).isLessThan(1000f)
        var v = 0f
        repeat(30) { v = GlideScrollPad.smoothStep(v, 1000f, 16f) } // ~0.5 s
        assertThat(v).isEqualTo(1000f)
        assertThat(GlideScrollPad.smoothStep(999.8f, 1000f, 16f)).isEqualTo(1000f)
        assertThat(GlideScrollPad.smoothStep(500f, 500f, 16f)).isEqualTo(500f)
    }

    @Test fun shareAndEdgeFade() {
        assertThat(GlideScrollPad.absoluteShare(50f, 100f, 500f)).isEqualTo(0f)
        assertThat(GlideScrollPad.absoluteShare(300f, 100f, 500f)).isEqualTo(0.5f)
        assertThat(GlideScrollPad.absoluteShare(900f, 100f, 500f)).isEqualTo(1f)
        assertThat(GlideScrollPad.edgeFactor(top, top, bottom)).isEqualTo(0f)
        assertThat(GlideScrollPad.edgeFactor(1020f, top, bottom)).isEqualTo(0.5f)
        assertThat(GlideScrollPad.edgeFactor(1200f, top, bottom)).isEqualTo(1f)
        assertThat(GlideScrollPad.edgeFactor(bottom, top, bottom)).isEqualTo(0f)
    }

    @Test fun enteringTheMiddleDoesNotJumpTheList() {
        val t = GlideScrollPadTracker(100f, 500f)
        assertThat(t.target(1200f, 0L, top, bottom, max, currentScroll = 300f)).isEqualTo(300f)
    }

    @Test fun fastSlidesFollowTheAbsoluteMapping() {
        val t = GlideScrollPadTracker(100f, 500f)
        t.target(1200f, 0L, top, bottom, max, 1200f) // starts on the mapping
        // 50 px per 16 ms = 3125 px/s: fast.
        var y = 1200f; var ms = 0L; var target = 0f
        repeat(3) { y += 50f; ms += 16; target = t.target(y, ms, top, bottom, max, 0f) }
        assertThat(target).isWithin(60f).of(GlideScrollPad.absoluteTarget(y, top, bottom, max))
    }

    @Test fun slowSmallMovesGiveFineControl() {
        val t = GlideScrollPadTracker(100f, 500f)
        val start = t.target(1200f, 0L, top, bottom, max, 1200f)
        // 1 px per 16 ms = 62 px/s: slow, the list stays put (the highlight moves row by row instead).
        var y = 1200f; var ms = 0L; var target = start
        repeat(20) { y += 1f; ms += 16; target = t.target(y, ms, top, bottom, max, 0f) }
        assertThat(target).isWithin(1f).of(start)
    }

    @Test fun theEdgesAlwaysReachTheEnds() {
        val t = GlideScrollPadTracker(100f, 500f)
        t.target(1200f, 0L, top, bottom, max, 300f) // offset -900 kept from the entry
        var y = 1200f; var ms = 0L; var target = 0f
        while (y < bottom) { y = minOf(bottom, y + 2f); ms += 16; target = t.target(y, ms, top, bottom, max, 0f) }
        assertThat(target).isEqualTo(max)
        while (y > top) { y = maxOf(top, y - 2f); ms += 16; target = t.target(y, ms, top, bottom, max, 0f) }
        assertThat(target).isEqualTo(0f)
    }
}
