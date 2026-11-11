package org.samesame.notes.feature.composer.panels

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.samesame.notes.feature.composer.panels.FontSizeHoldGesture.Release
import org.samesame.notes.feature.composer.panels.FontSizeHoldGesture.Start

/** E3 (rc1 round 5): how a press on the font-size button starts and what lifting the finger does. */
class FontSizeHoldGestureTest {

    private val slop = 16f

    @Test fun liftBeforeTheTimeoutIsATap() {
        assertThat(FontSizeHoldGesture.start(pressed = false, distance = 0f, touchSlop = slop, timedOut = false)).isEqualTo(Start.Tap)
        assertThat(FontSizeHoldGesture.start(pressed = false, distance = 10f, touchSlop = slop, timedOut = false)).isEqualTo(Start.Tap)
    }

    @Test fun stillDownWithinTheSlopIsUndecided() {
        assertThat(FontSizeHoldGesture.start(pressed = true, distance = 0f, touchSlop = slop, timedOut = false)).isNull()
        assertThat(FontSizeHoldGesture.start(pressed = true, distance = slop, touchSlop = slop, timedOut = false)).isNull()
    }

    @Test fun holdingToTheTimeoutStartsAGlide() {
        assertThat(FontSizeHoldGesture.start(pressed = true, distance = 0f, touchSlop = slop, timedOut = true)).isEqualTo(Start.Glide)
    }

    /**
     * The rc1 failure: a glide that starts moving at once leaves the 32 dp button before the long-press timeout. It
     * must start the glide instead of being dropped.
     */
    @Test fun movingPastTheSlopBeforeTheTimeoutStartsAGlide() {
        assertThat(FontSizeHoldGesture.start(pressed = true, distance = slop + 1f, touchSlop = slop, timedOut = false)).isEqualTo(Start.Glide)
        assertThat(FontSizeHoldGesture.start(pressed = true, distance = 400f, touchSlop = slop, timedOut = false)).isEqualTo(Start.Glide)
    }

    @Test fun releaseAwayFromTheToolbarAppliesTheHighlight() {
        assertThat(FontSizeHoldGesture.release(highlighted = 5, leftButton = true, overToolbar = false)).isEqualTo(Release.Apply)
        // Below the toolbar (over the keyboard) too: larger sizes are below the applied one.
        assertThat(FontSizeHoldGesture.release(highlighted = 20, leftButton = true, overToolbar = false)).isEqualTo(Release.Apply)
    }

    /** A hold without moving (or a wobble that stays on the button) leaves the popup open. */
    @Test fun releaseWithoutLeavingTheButtonKeepsThePopupOpen() {
        assertThat(FontSizeHoldGesture.release(highlighted = 13, leftButton = false, overToolbar = true)).isEqualTo(Release.KeepOpen)
        assertThat(FontSizeHoldGesture.release(highlighted = -1, leftButton = false, overToolbar = true)).isEqualTo(Release.KeepOpen)
    }

    /** Glided away and back onto the toolbar (the button included): a change of mind. */
    @Test fun releaseBackOverTheToolbarCancels() {
        assertThat(FontSizeHoldGesture.release(highlighted = -1, leftButton = true, overToolbar = true)).isEqualTo(Release.Cancel)
        assertThat(FontSizeHoldGesture.release(highlighted = 13, leftButton = true, overToolbar = true)).isEqualTo(Release.Cancel)
        // Nothing to apply (the popup never reported its rows).
        assertThat(FontSizeHoldGesture.release(highlighted = -1, leftButton = true, overToolbar = false)).isEqualTo(Release.Cancel)
    }

    /** The hold state derives the highlight from the glide alone and hides it while the finger is back over the toolbar. */
    @Test fun holdStateHighlightsFromTheAppliedRow() {
        val hold = FontSizeHoldState()
        var t = 0L
        fun frame() { t += 16_666_667L; hold.frame(t) }
        assertThat(hold.highlighted).isEqualTo(-1)
        // The glide starts before the popup has reported: nothing yet, then the applied row once it has.
        hold.startGlide(1650f, 2.625f)
        frame()
        assertThat(hold.highlighted).isEqualTo(-1)
        hold.listCount = TextSizeCatalog.SIZES.size
        hold.selectedRow = FontSizeRows.anchorRow(TextSizeCatalog.Heading.H2, 24, TextSizeCatalog.SIZES)
        hold.neutralY = 1000f
        frame()
        assertThat(hold.highlighted).isEqualTo(1)
        hold.glideTo(1650f, suppressed = true); frame(); assertThat(hold.highlighted).isEqualTo(-1)
        hold.glideTo(1650f); frame(); assertThat(hold.highlighted).isEqualTo(1)
        assertThat(FontSizeRows.pick(hold.highlighted, TextSizeCatalog.SIZES)).isEqualTo(FontSizePick.Heading(TextSizeCatalog.Heading.H2))
        hold.endGlide()
        assertThat(hold.highlighted).isEqualTo(-1)
        hold.clear()
        hold.startGlide(0f, 2.625f)
        frame()
        assertThat(hold.highlighted).isEqualTo(-1)
    }
}
