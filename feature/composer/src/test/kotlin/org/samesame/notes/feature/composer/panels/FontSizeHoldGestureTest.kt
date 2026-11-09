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

    @Test fun releaseOnAHighlightedRowApplies() {
        assertThat(FontSizeHoldGesture.release(highlighted = 5, insidePopup = true, enteredPopup = true, overButton = false))
            .isEqualTo(Release.Apply)
    }

    /** The other rc1 failure: a stationary hold opened the popup and the release on the button closed it again. */
    @Test fun releaseOnTheButtonWithoutGlidingKeepsThePopupOpen() {
        assertThat(FontSizeHoldGesture.release(highlighted = -1, insidePopup = false, enteredPopup = false, overButton = true))
            .isEqualTo(Release.KeepOpen)
    }

    @Test fun releaseInsideThePopupBetweenRowsKeepsItOpen() {
        assertThat(FontSizeHoldGesture.release(highlighted = -1, insidePopup = true, enteredPopup = true, overButton = false))
            .isEqualTo(Release.KeepOpen)
    }

    @Test fun releaseOutsideCancels() {
        assertThat(FontSizeHoldGesture.release(highlighted = -1, insidePopup = false, enteredPopup = true, overButton = false))
            .isEqualTo(Release.Cancel)
        assertThat(FontSizeHoldGesture.release(highlighted = -1, insidePopup = false, enteredPopup = false, overButton = false))
            .isEqualTo(Release.Cancel)
        // Glided into the popup and back onto the button: that is a change of mind, not a tap.
        assertThat(FontSizeHoldGesture.release(highlighted = -1, insidePopup = false, enteredPopup = true, overButton = true))
            .isEqualTo(Release.Cancel)
    }

    /** The hold state maps a finger over the reported rows to the row under it, and only inside the popup. */
    @Test fun holdStateHighlightsTheRowUnderTheFingerDeterministically() {
        val hold = FontSizeHoldState()
        assertThat(hold.popupShown).isFalse()
        // Popup 1180..1484 x 900..1800; Heading 1..3 and Body rows 88 px tall from y = 916.
        hold.reportPopup(1180f, 1484f, 900f, 1800f)
        for (i in 0..FontSizeRows.BODY_INDEX) hold.reportRow(RowBounds(i, 916f + i * 88f, 916f + (i + 1) * 88f))
        assertThat(hold.popupShown).isTrue()

        // Fast or slow, the same end point gives the same row: there is no path or velocity input.
        val slowPath = (0..22).map { 1700f - it * 20f } // ends at y = 1260, inside the Body row (1180..1268)
        slowPath.forEach { hold.moveTo(1300f, it) }
        val slow = hold.highlighted
        hold.moveTo(1300f, 1700f)
        hold.moveTo(1300f, slowPath.last())
        assertThat(hold.highlighted).isEqualTo(slow)
        assertThat(slow).isEqualTo(FontSizeRows.BODY_INDEX)

        // Each row in turn.
        hold.moveTo(1300f, 920f); assertThat(hold.highlighted).isEqualTo(0)
        hold.moveTo(1300f, 1010f); assertThat(hold.highlighted).isEqualTo(1)
        hold.moveTo(1300f, 1250f); assertThat(hold.highlighted).isEqualTo(FontSizeRows.BODY_INDEX)
        assertThat(FontSizeRows.pick(hold.highlighted, TextSizeCatalog.SIZES)).isEqualTo(FontSizePick.Body)
        // Inside the popup but above the first row (its top padding): no row.
        hold.moveTo(1300f, 905f); assertThat(hold.highlighted).isEqualTo(-1)

        // Outside the popup (left of it, over the toolbar button): nothing highlighted, so a release there cancels.
        hold.moveTo(560f, 1250f); assertThat(hold.highlighted).isEqualTo(-1)
        assertThat(hold.insidePopup(560f, 1250f)).isFalse()
    }
}
