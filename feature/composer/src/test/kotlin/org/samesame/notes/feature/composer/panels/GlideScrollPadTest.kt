package org.samesame.notes.feature.composer.panels

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The glide through [FontSizeHoldState]: frames from the frame clock drive the joystick ([GlideJoystick]) around the
 * popup's centre; the list follows the fractional position ([GlideScrollPad.centredScroll]).
 */
class GlideScrollPadTest {
    private val dp = 2.625f // Pixel 6 Pro
    private val sizes = TextSizeCatalog.SIZES
    private val count = FontSizeRows.count(sizes)
    private val centreY = 1000f
    private val buttonY = centreY + 240f * dp
    private val frameNanos = 16_666_667L
    private var now = 0L

    private fun FontSizeHoldState.frames(n: Int) = repeat(n) { now += frameNanos; frame(now) }

    /** Opens the popup state on [anchor], starts a glide on the button and brings the finger to the centre. */
    private fun FontSizeHoldState.glideToCentre(anchor: Int, listCount: Int = sizes.size) {
        this.listCount = listCount
        selectedRow = anchor
        neutralY = centreY
        startGlide(buttonY, dp)
        frames(1)
        // On the way up from the button nothing moves.
        for (y in 0..10) { glideTo(buttonY - (buttonY - centreY) * y / 10f); frames(2); assertThat(highlighted).isEqualTo(anchor) }
    }

    /** Pushes the finger [dir] just past the dead zone until the highlight changes, then back to the centre to settle. */
    private fun FontSizeHoldState.oneStep(dir: Int): Int {
        val from = highlighted
        glideTo(centreY + dir * (GlideJoystick.DEAD_ZONE_DP + GlideJoystick.RAMP_DP + 4f) * dp)
        var guard = 0
        while (highlighted == from && guard++ < 600) frames(1)
        glideTo(centreY)
        frames(30)
        return highlighted
    }

    @Test fun theGlideStartsOnTheAppliedRowAndWaitsForTheCentre() {
        val hold = FontSizeHoldState()
        assertThat(hold.highlighted).isEqualTo(-1)
        val anchor = FontSizeRows.anchorRow(null, 17, sizes)
        hold.glideToCentre(anchor)
        hold.frames(120)
        assertThat(hold.highlighted).isEqualTo(anchor)
        assertThat(hold.position).isEqualTo(anchor.toFloat())
    }

    @Test fun theAnchorArrivingLateStillStartsThere() {
        // The popup reports its rows a frame or two after the glide started.
        val hold = FontSizeHoldState()
        hold.startGlide(buttonY, dp)
        hold.frames(3)
        assertThat(hold.highlighted).isEqualTo(-1)
        hold.listCount = sizes.size
        hold.selectedRow = 5
        hold.neutralY = centreY
        hold.frames(1)
        assertThat(hold.highlighted).isEqualTo(5)
    }

    @Test fun fingerAboveTheCentreScrollsUpBelowScrollsDown() {
        val hold = FontSizeHoldState()
        val anchor = FontSizeRows.anchorRow(null, 17, sizes)
        hold.glideToCentre(anchor)
        hold.glideTo(centreY - 60f * dp)
        hold.frames(20)
        assertThat(hold.highlighted).isLessThan(anchor)
        hold.glideTo(centreY + 60f * dp)
        hold.frames(80)
        assertThat(hold.highlighted).isGreaterThan(anchor)
    }

    @Test fun backOverTheToolbarHidesTheHighlightAndStopsTheTravel() {
        val hold = FontSizeHoldState()
        hold.glideToCentre(10)
        hold.glideTo(centreY + 300f * dp, suppressed = true)
        hold.frames(60)
        assertThat(hold.highlighted).isEqualTo(-1)
        assertThat(hold.position).isEqualTo(10f)
        // Back into the popup: visible again, travel resumes.
        hold.glideTo(centreY)
        hold.frames(1)
        assertThat(hold.highlighted).isEqualTo(10)
        hold.endGlide()
        assertThat(hold.highlighted).isEqualTo(-1)
        assertThat(hold.gliding).isFalse()
    }

    @Test fun framesAfterTheGlideEndedDoNothing() {
        val hold = FontSizeHoldState()
        hold.glideToCentre(10)
        hold.endGlide()
        hold.glideTo(centreY + 200f * dp)
        hold.frames(60)
        assertThat(hold.position).isEqualTo(-1f)
    }

    @Test fun scrollTargetCentresAndKeepsTheHighlightVisible() {
        val row = 115.5f // 44 dp
        val viewport = 585f
        val maxScroll = sizes.size * row - viewport
        var i = 0f
        var last = 0f
        while (i <= sizes.size - 1) {
            val scroll = GlideScrollPad.centredScroll(i, row, viewport, maxScroll)
            assertThat(scroll).isAtLeast(0f)
            assertThat(scroll).isAtMost(maxScroll)
            // Continuous and monotonic: the list slides with the position.
            assertThat(scroll).isAtLeast(last)
            assertThat(scroll - last).isAtMost(row * 0.05f + 0.01f)
            last = scroll
            val top = Math.round(i) * row - scroll
            assertThat(top).isAtLeast(-row / 2 - 0.01f)
            assertThat(top + row).isAtMost(viewport + row / 2 + 0.01f)
            i += 0.05f
        }
        assertThat(GlideScrollPad.centredScroll(10f, row, viewport, maxScroll)).isEqualTo(10 * row + row / 2 - viewport / 2)
        assertThat(GlideScrollPad.centredScroll(-3f, row, viewport, maxScroll)).isEqualTo(0f)
        assertThat(GlideScrollPad.centredScroll(sizes.size - 1f, row, viewport, maxScroll)).isEqualTo(maxScroll)
    }

    /** The popup's visible order, top to bottom, for the real catalog. */
    private val visibleOrder: List<FontSizePick> =
        TextSizeCatalog.Heading.entries.map { FontSizePick.Heading(it) } + FontSizePick.Body + sizes.map { FontSizePick.Size(it) }

    @Test fun oneGlideStepsThroughEveryVisibleRowInOrderDownAndBack() {
        val hold = FontSizeHoldState()
        hold.glideToCentre(0)
        val down = (1 until count).map { FontSizeRows.pick(hold.oneStep(+1), sizes) }
        assertThat(down).containsExactlyElementsIn(visibleOrder.drop(1)).inOrder()
        val up = (1 until count).map { FontSizeRows.pick(hold.oneStep(-1), sizes) }
        assertThat(up).containsExactlyElementsIn(visibleOrder.reversed().drop(1)).inOrder()
    }

    /**
     * RC4 finding: one-step glides down from H3 gave Body, then 18 (Body and "17" apply the same style and the next
     * glide started on the "17" row). Walking the real catalog with one-step glides, each applying its pick, must
     * visit every visible row in order, from H1 to the last size and back.
     */
    @Test fun oneStepGlidesWalkEveryVisibleRowFromH1ToTheLastSizeAndBack() {
        val hold = FontSizeHoldState()
        // The applied style, as the composer stores it: size + bold (a heading is its size in bold).
        var size = 32f
        var bold = true
        fun glide(dir: Int): FontSizePick {
            val menuSizes = TextSizeCatalog.menuSizes(size)
            val anchor = FontSizeRows.anchorRow(
                TextSizeCatalog.headingFor(size, bold), TextSizeCatalog.selectedSize(size), menuSizes, hold.lastPickBody,
            )
            hold.glideToCentre(anchor, menuSizes.size)
            val pick = FontSizeRows.pick(hold.oneStep(dir), menuSizes)!!
            hold.endGlide()
            hold.notePick(pick)
            when (pick) {
                is FontSizePick.Heading -> { size = pick.heading.size.toFloat(); bold = true }
                FontSizePick.Body -> { size = TextSizeCatalog.BODY_SIZE.toFloat(); bold = false }
                is FontSizePick.Size -> { size = pick.pt.toFloat(); bold = false }
            }
            return pick
        }
        val down = (1 until count).map { glide(+1) }
        assertThat(down).containsExactlyElementsIn(visibleOrder.drop(1)).inOrder()
        val up = (1 until count).map { glide(-1) }
        assertThat(up).containsExactlyElementsIn(visibleOrder.reversed().drop(1)).inOrder()
        val fresh = FontSizeHoldState()
        assertThat(FontSizeRows.anchorRow(null, 17, sizes, fresh.lastPickBody)).isEqualTo(FontSizeRows.SIZE_START + sizes.indexOf(17))
    }
}
