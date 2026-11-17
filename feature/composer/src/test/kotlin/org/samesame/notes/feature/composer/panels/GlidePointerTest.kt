package org.samesame.notes.feature.composer.panels

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Direct pointing on the font-size glide: the highlighted row is the one under the finger (with a little hysteresis),
 * the edge bands auto-scroll only once the finger has armed, and the scroll integrates per frame and clamps to the list.
 * Distances are px; [pxPerDp] = 1 keeps dp constants equal to px so the arithmetic stays readable.
 */
class GlidePointerTest {

    private val rowPx = 36f
    private val visible = 9
    private val count = 30

    /** A pointer whose press is the centre of [anchor], in the middle of the viewport (not in an edge band). */
    private fun middlePointer(anchor: Int = 4) = GlidePointer(
        count = count, visible = visible, scroll = 0, rowPx = rowPx, listTop = 0f,
        anchor = anchor, pressY = (anchor + 0.5f) * rowPx, pxPerDp = 1f,
    )

    /** An idle hold keeps the anchor highlighted, arms nothing and never scrolls. */
    @Test fun idleHoldKeepsTheAnchorAndDoesNotScroll() {
        val p = middlePointer()
        assertThat(p.highlighted).isEqualTo(4)
        assertThat(p.armed).isFalse()
        assertThat(p.velocity()).isEqualTo(0f)
        // A wobble smaller than the arming distance is still a stationary hold.
        p.pointer(4.5f * rowPx + GlidePointer.ARM_DP - 1f)
        assertThat(p.armed).isFalse()
        assertThat(p.highlighted).isEqualTo(4)
        assertThat(p.advance(0.05f)).isFalse()
        assertThat(p.scroll).isEqualTo(0f)
    }

    /** Once the finger travels the arming distance the highlight is the row physically under it (1:1). */
    @Test fun afterArmingTheHighlightFollowsTheFinger() {
        val p = middlePointer()
        p.pointer(4.5f * rowPx)
        assertThat(p.armed).isFalse()
        val y = 5.5f * rowPx
        p.pointer(y)
        assertThat(p.armed).isTrue()
        assertThat(p.highlighted).isEqualTo(5)
        assertThat(p.highlighted).isEqualTo(p.rowAt(y))
        val y2 = 7.5f * rowPx
        p.pointer(y2)
        assertThat(p.highlighted).isEqualTo(7)
        assertThat(p.highlighted).isEqualTo(p.rowAt(y2))
    }

    /** A few dp of hysteresis at a border stop the highlight flickering while the finger rests on the seam. */
    @Test fun hysteresisAtARowBorder() {
        val p = middlePointer()
        val border = 5f * rowPx // seam between rows 4 and 5
        val hystPx = GlidePointer.HYSTERESIS_DP
        p.pointer(border + 1f)
        assertThat(p.highlighted).isEqualTo(4)
        p.pointer(border + hystPx + 1f)
        assertThat(p.highlighted).isEqualTo(5)
        // Coming back, the highlight does not drop until the finger is past the border the other way.
        p.pointer(border + 1f)
        assertThat(p.highlighted).isEqualTo(5)
        p.pointer(border - hystPx - 1f)
        assertThat(p.highlighted).isEqualTo(4)
    }

    /** Pressing inside the bottom band: no scroll at the press, then a speed that grows with depth up to MAX_SPEED. */
    @Test fun bottomBandSpeedGrowsWithDepthAndCaps() {
        val pressY = 8.5f * rowPx // row 8, inside the bottom band (viewport ends at row 9)
        val p = GlidePointer(
            count = count, visible = visible, scroll = 0, rowPx = rowPx, listTop = 0f,
            anchor = 8, pressY = pressY, pxPerDp = 1f,
        )
        assertThat(p.velocity()).isEqualTo(0f) // not armed yet
        p.pointer(pressY + GlidePointer.ARM_DP + 1f)
        assertThat(p.armed).isTrue()
        val shallow = p.velocity()
        assertThat(shallow).isGreaterThan(0f)
        p.pointer(pressY + rowPx / 2f)
        val deeper = p.velocity()
        assertThat(deeper).isGreaterThan(shallow)
        p.pointer(pressY + rowPx * 3f) // past the band's outer edge
        assertThat(p.velocity()).isEqualTo(GlidePointer.MAX_SPEED)
    }

    /** Symmetrically, the top band scrolls towards the start and clamps at scroll 0. */
    @Test fun topBandScrollsTowardsTheStartAndClamps() {
        val p = GlidePointer(
            count = count, visible = visible, scroll = 0, rowPx = rowPx, listTop = 0f,
            anchor = 0, pressY = 0.5f * rowPx, pxPerDp = 1f,
        )
        assertThat(p.velocity()).isEqualTo(0f)
        p.pointer(0.5f * rowPx - GlidePointer.ARM_DP - 1f)
        assertThat(p.armed).isTrue()
        assertThat(p.velocity()).isLessThan(0f)
        p.pointer(-rowPx * 2f)
        assertThat(p.velocity()).isEqualTo(-GlidePointer.MAX_SPEED)
        assertThat(p.advance(GlidePointer.MAX_FRAME_S)).isFalse() // already at scroll 0
        assertThat(p.scroll).isEqualTo(0f)
    }

    /** The frame loop integrates the band speed (frame gaps clamped) and stops at the end of the list. */
    @Test fun advanceIntegratesAndClampsAtTheListEnd() {
        val pressY = 8.5f * rowPx
        val p = GlidePointer(
            count = count, visible = visible, scroll = 0, rowPx = rowPx, listTop = 0f,
            anchor = 8, pressY = pressY, pxPerDp = 1f,
        )
        p.pointer(pressY + rowPx * 3f) // armed, full speed
        p.advance(0.05f)
        assertThat(p.scroll).isWithin(0.001f).of(GlidePointer.MAX_SPEED * GlidePointer.MAX_FRAME_S)
        // A long stall is integrated as at most MAX_FRAME_S, so the list never leaps.
        p.advance(5f)
        assertThat(p.scroll).isWithin(0.001f).of(2f * GlidePointer.MAX_SPEED * GlidePointer.MAX_FRAME_S)
        repeat(100) { p.advance(0.05f) }
        assertThat(p.scroll).isEqualTo(p.maxScroll)
        assertThat(p.advance(0.05f)).isFalse()
    }

    /** Leaving a band stops the scroll at once and the row under the finger is highlighted again. */
    @Test fun leavingTheBandStopsTheScrollAndRealigns() {
        val pressY = 8.5f * rowPx
        val p = GlidePointer(
            count = count, visible = visible, scroll = 0, rowPx = rowPx, listTop = 0f,
            anchor = 8, pressY = pressY, pxPerDp = 1f,
        )
        p.pointer(pressY + rowPx * 3f)
        assertThat(p.velocity()).isGreaterThan(0f)
        val scrolled = p.scroll
        p.advance(0.05f)
        assertThat(p.scroll).isGreaterThan(scrolled)
        // Back to the middle of the viewport: out of the band, pointing at row 4 again.
        val y = 4.5f * rowPx
        p.pointer(y)
        assertThat(p.velocity()).isEqualTo(0f)
        assertThat(p.highlighted).isEqualTo(p.rowAt(y))
        assertThat(p.advance(0.05f)).isFalse()
    }

    /** Suppressing the pointer (finger far off the popup sideways) hides the highlight and freezes the scroll. */
    @Test fun suppressedHidesTheHighlightAndComesBack() {
        val p = middlePointer()
        p.pointer(4.5f * rowPx)
        assertThat(p.highlighted).isEqualTo(4)
        p.pointer(4.5f * rowPx, suppressed = true)
        assertThat(p.suppressed).isTrue()
        assertThat(p.highlighted).isEqualTo(-1)
        assertThat(p.advance(0.05f)).isFalse()
        assertThat(p.rowCentreY(0)).isEqualTo(0.5f * rowPx)
        p.pointer(4.5f * rowPx)
        assertThat(p.highlighted).isEqualTo(4)
    }

    /** Basic row geometry over the whole list, used by the popup for the highlight and the sample chip. */
    @Test fun rowGeometry() {
        val p = middlePointer()
        assertThat(p.maxScroll).isEqualTo((count - visible).toFloat())
        assertThat(p.rowAt(0f)).isEqualTo(0)
        assertThat(p.rowAt(rowPx)).isEqualTo(1)
        assertThat(p.rowCentreY(0)).isEqualTo(0.5f * rowPx)
        assertThat(p.rowCentreY(1)).isEqualTo(1.5f * rowPx)
        // Out-of-range y is clamped to the list ends.
        assertThat(p.rowAt(-rowPx)).isEqualTo(0)
        assertThat(p.rowAt(rowPx * count)).isEqualTo(count - 1)
    }
}
