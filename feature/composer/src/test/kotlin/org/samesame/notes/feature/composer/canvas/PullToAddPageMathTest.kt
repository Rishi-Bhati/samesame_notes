package org.samesame.notes.feature.composer.canvas

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PullToAddPageMathTest {
    private val max = 240f
    private val threshold = 88f

    @Test fun rubberBandStartsWithTheResistanceSlopeAndNeverPassesTheMaximum() {
        assertThat(PullToAddPageMath.visible(0f, max)).isEqualTo(0f)
        assertThat(PullToAddPageMath.visible(-5f, max)).isEqualTo(0f)
        // Small pulls move at ~RESISTANCE of the finger.
        assertThat(PullToAddPageMath.visible(1f, max)).isWithin(0.01f).of(PullToAddPageMath.RESISTANCE)
        val a = PullToAddPageMath.visible(100f, max)
        val b = PullToAddPageMath.visible(200f, max)
        assertThat(b).isGreaterThan(a)
        assertThat(b - a).isLessThan(a) // friction grows with the distance
        assertThat(PullToAddPageMath.visible(1e6f, max)).isLessThan(max)
    }

    @Test fun inverseRoundTrips() {
        for (raw in listOf(10f, 100f, 300f, 800f)) {
            val v = PullToAddPageMath.visible(raw, max)
            assertThat(PullToAddPageMath.rawFor(v, max)).isWithin(0.5f).of(raw)
        }
    }

    @Test fun thresholdNeedsAboutTwoHundredDpOfFinger() {
        val raw = PullToAddPageMath.rawFor(threshold, max)
        assertThat(raw).isWithin(5f).of(198f)
        assertThat(PullToAddPageMath.commits(PullToAddPageMath.visible(raw + 1f, max), threshold)).isTrue()
        assertThat(PullToAddPageMath.commits(PullToAddPageMath.visible(raw - 5f, max), threshold)).isFalse()
        assertThat(PullToAddPageMath.progress(44f, threshold)).isWithin(1e-4f).of(0.5f)
        assertThat(PullToAddPageMath.progress(500f, threshold)).isEqualTo(1f)
    }

    @Test fun comingBackUndoesThePullBeforeScrolling() {
        // Finger moves back 30 px with 20 px pulled: 20 px close the pull, 10 px scroll the content.
        assertThat(PullToAddPageMath.takeBack(30f, 20f)).isEqualTo(-20f to 10f)
        assertThat(PullToAddPageMath.takeBack(5f, 20f)).isEqualTo(-5f to 0f)
        // Towards the end, or nothing pulled: everything goes to the scroll.
        assertThat(PullToAddPageMath.takeBack(-30f, 20f)).isEqualTo(0f to -30f)
        assertThat(PullToAddPageMath.takeBack(30f, 0f)).isEqualTo(0f to 30f)
    }

    @Test fun committedReleaseFadesTheLabelOutWhileTheSheetRises() {
        // The label is fully visible at the released distance and gone once the sheet covers the viewport.
        assertThat(PullToAddPageMath.labelAlpha(visible = 120f, from = 120f, full = 1200f)).isEqualTo(1f)
        assertThat(PullToAddPageMath.labelAlpha(visible = 1200f, from = 120f, full = 1200f)).isEqualTo(0f)
        assertThat(PullToAddPageMath.labelAlpha(visible = 660f, from = 120f, full = 1200f)).isWithin(1e-4f).of(0.5f)
        // Monotonic, never out of range.
        assertThat(PullToAddPageMath.labelAlpha(visible = 1000f, from = 120f, full = 1200f))
            .isLessThan(PullToAddPageMath.labelAlpha(visible = 500f, from = 120f, full = 1200f))
        assertThat(PullToAddPageMath.labelAlpha(visible = 0f, from = 120f, full = 1200f)).isEqualTo(1f)
        assertThat(PullToAddPageMath.labelAlpha(visible = 5000f, from = 120f, full = 1200f)).isEqualTo(0f)
        // Degenerate geometry (no distance to travel) must not divide by zero.
        assertThat(PullToAddPageMath.labelAlpha(visible = 120f, from = 120f, full = 120f)).isEqualTo(0f)
    }
}
