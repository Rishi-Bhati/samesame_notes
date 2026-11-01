package org.samesame.notes.feature.composer.voice

import com.google.common.truth.Truth.assertThat
import org.junit.Test

private const val EPS = 1e-4f

class Skip10GlyphTest {
    private val half = Skip10Glyph.STROKE / 2f

    @Test fun glyphFitsItsNineteenBySixteenBoxIncludingTheStroke() {
        for (forward in listOf(false, true)) {
            val pts = Skip10Glyph.outline(forward).flatten()
            assertThat(pts.minOf { it.x } - half).isAtLeast(-EPS)
            assertThat(pts.maxOf { it.x } + half).isAtMost(Skip10Glyph.WIDTH + EPS)
            assertThat(pts.minOf { it.y } - half).isAtLeast(-EPS)
            assertThat(pts.maxOf { it.y } + half).isAtMost(Skip10Glyph.HEIGHT + EPS)
        }
    }

    @Test fun backArrowPointsLeftAndForwardArrowPointsRight() {
        val (_, backHead) = Skip10Glyph.outline(forward = false)
        val (_, fwdHead) = Skip10Glyph.outline(forward = true)
        // The tip (middle point) is the extreme point on the skip side; the wings trail behind it.
        assertThat(backHead[1].x).isLessThan(backHead[0].x)
        assertThat(backHead[1].x).isLessThan(backHead[2].x)
        assertThat(fwdHead[1].x).isGreaterThan(fwdHead[0].x)
        assertThat(fwdHead[1].x).isGreaterThan(fwdHead[2].x)
    }

    @Test fun shaftEndsExactlyAtTheTipSoTheStrokesJoinWithoutOverlap() {
        val (box, head) = Skip10Glyph.outline(forward = false)
        assertThat(box.last()).isEqualTo(head[1])
        // The box's open side starts clear of the lower wing (stroke widths included): no double stroke at the corner.
        val lowerWingY = head.maxOf { it.y }
        assertThat(box.first().y - half).isGreaterThan(lowerWingY + half)
        assertThat(box.first().y).isEqualTo(Skip10Glyph.GAP_END_Y)
    }

    @Test fun forwardIsTheMirrorImageOfBack() {
        val back = Skip10Glyph.outline(forward = false).flatten()
        val fwd = Skip10Glyph.outline(forward = true).flatten()
        back.zip(fwd).forEach { (b, f) ->
            assertThat(f.x).isWithin(1e-4f).of(Skip10Glyph.WIDTH - b.x)
            assertThat(f.y).isEqualTo(b.y)
        }
    }

    @Test fun shaftRunsAlongTheTopEdgeStraight() {
        val (box, _) = Skip10Glyph.outline(forward = false)
        val shaft = box.takeLast(2)
        assertThat(shaft[0].y).isWithin(1e-4f).of(shaft[1].y)
    }
}
