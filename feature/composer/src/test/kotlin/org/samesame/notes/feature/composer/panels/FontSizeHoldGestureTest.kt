package org.samesame.notes.feature.composer.panels

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.samesame.notes.feature.composer.panels.FontSizeHoldGesture.Release
import org.samesame.notes.feature.composer.panels.FontSizeHoldGesture.Start

/** How a press on the font-size button starts and what lifting the finger does (direct-pointing glide). */
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

    @Test fun releaseOnARowAppliesIt() {
        assertThat(FontSizeHoldGesture.release(highlighted = 5, moved = true, outside = false)).isEqualTo(Release.Apply)
        assertThat(FontSizeHoldGesture.release(highlighted = 20, moved = true, outside = false)).isEqualTo(Release.Apply)
    }

    /** A hold without moving (or a wobble that stays on the button) leaves the popup open. */
    @Test fun releaseWithoutMovingKeepsThePopupOpen() {
        assertThat(FontSizeHoldGesture.release(highlighted = 13, moved = false, outside = false)).isEqualTo(Release.KeepOpen)
        assertThat(FontSizeHoldGesture.release(highlighted = -1, moved = false, outside = false)).isEqualTo(Release.KeepOpen)
    }

    /** Glided off the popup sideways: a change of mind, whatever is highlighted. */
    @Test fun releaseOutsideThePopupCancels() {
        assertThat(FontSizeHoldGesture.release(highlighted = 13, moved = true, outside = true)).isEqualTo(Release.Cancel)
        assertThat(FontSizeHoldGesture.release(highlighted = -1, moved = true, outside = true)).isEqualTo(Release.Cancel)
        // Nothing to apply (the popup never reported its rows).
        assertThat(FontSizeHoldGesture.release(highlighted = -1, moved = true, outside = false)).isEqualTo(Release.Cancel)
    }

    @Test fun sidewaysTravelCountsAsOutsideOnlyPastTheSlack() {
        val slack = 24f
        assertThat(FontSizeHoldGesture.outside(x = 500f, left = 400f, right = 700f, slackPx = slack)).isFalse()
        assertThat(FontSizeHoldGesture.outside(x = 400f, left = 400f, right = 700f, slackPx = slack)).isFalse()
        assertThat(FontSizeHoldGesture.outside(x = 700f, left = 400f, right = 700f, slackPx = slack)).isFalse()
        assertThat(FontSizeHoldGesture.outside(x = 400f - slack, left = 400f, right = 700f, slackPx = slack)).isFalse()
        assertThat(FontSizeHoldGesture.outside(x = 400f - slack - 1f, left = 400f, right = 700f, slackPx = slack)).isTrue()
        assertThat(FontSizeHoldGesture.outside(x = 700f + slack + 1f, left = 400f, right = 700f, slackPx = slack)).isTrue()
    }

    /** Vertical travel never cancels: the edge bands and the toolbar below the bottom band are part of the control. */
    @Test fun verticalTravelStaysInside() {
        assertThat(FontSizeHoldGesture.outside(x = 550f, left = 400f, right = 700f, slackPx = 24f)).isFalse()
    }

    /** The hold state starts on the anchor row, then highlights the row physically under the finger. */
    @Test fun holdStateFollowsTheFingerAndClears() {
        val hold = FontSizeHoldState()
        val sizes = TextSizeCatalog.SIZES
        val rowPx = 36f
        val anchor = FontSizeRows.SIZE_START + 3
        assertThat(hold.highlighted).isEqualTo(-1)
        hold.startGlide(
            sizes = sizes, anchor = anchor, fingerY = 1000f, minTop = 100f, maxBottom = 1400f,
            rowPx = rowPx, topPad = 8f, bottomPad = 8f, pxPerDp = 2.625f,
        )
        assertThat(hold.gliding).isTrue()
        assertThat(hold.highlighted).isEqualTo(anchor)
        assertThat(hold.moved).isFalse()
        val listTop = hold.placement!!.listTop(8f)
        val y = 1000f - rowPx * 3f
        hold.glideTo(y)
        assertThat(hold.moved).isTrue()
        val expected = ((y - listTop) / rowPx).toInt().coerceIn(0, FontSizeRows.count(sizes) - 1)
        assertThat(hold.highlighted).isEqualTo(expected)
        assertThat(hold.highlightedPick).isEqualTo(FontSizeRows.pick(expected, sizes))
        // Far off the popup sideways: no highlight, the note shows no preview.
        hold.glideTo(y, suppressed = true)
        assertThat(hold.highlighted).isEqualTo(-1)
        hold.glideTo(y)
        assertThat(hold.highlighted).isEqualTo(expected)
        hold.endGlide()
        assertThat(hold.gliding).isFalse()
        assertThat(hold.highlighted).isEqualTo(-1)
        assertThat(hold.highlightedPick).isNull()
    }
}
