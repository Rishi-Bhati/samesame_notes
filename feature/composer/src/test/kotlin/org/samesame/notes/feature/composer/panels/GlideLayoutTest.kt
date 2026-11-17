package org.samesame.notes.feature.composer.panels

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Placement of the glide popup: the current row starts under the finger, the viewport shows as many rows as fit, the
 * popup is clamped to the screen, and the one scrolling list is pre-scrolled minimally.
 */
class GlideLayoutTest {

    private val rowPx = 100f
    private val topPad = 8f
    private val bottomPad = 8f

    @Test fun anchorRowIsUnderTheFingerWhenItFits() {
        val p = GlideLayout.place(
            count = 30, anchor = 20, rowPx = rowPx, topPad = topPad, bottomPad = bottomPad,
            fingerY = 1050f, minTop = 100f, maxBottom = 1100f,
        )
        assertThat(p.visible).isEqualTo(9)
        assertThat(p.scroll).isEqualTo(12)
        val centre = p.listTop(topPad) + (20 - p.scroll + 0.5f) * rowPx
        assertThat(centre).isEqualTo(1050f)
        // The viewport and the popup stay on screen; only the bottom padding may overhang.
        assertThat(p.top).isAtLeast(100f)
        assertThat(p.listTop(topPad) + p.visible * rowPx).isAtMost(1100f)
        assertThat(GlideLayout.height(p.visible, rowPx, topPad, bottomPad))
            .isEqualTo(p.top + topPad + p.visible * rowPx + bottomPad)
    }

    /** Finger at the bottom of the screen: the anchor row is the bottom-most visible row (the viewport ends there). */
    @Test fun anchorIsTheBottomRowWithTheFingerAtTheBottom() {
        val p = GlideLayout.place(
            count = 30, anchor = 20, rowPx = rowPx, topPad = topPad, bottomPad = bottomPad,
            fingerY = 1000f, minTop = 0f, maxBottom = 1000f,
        )
        assertThat(p.visible).isEqualTo(9)
        assertThat(20 - p.scroll).isEqualTo(p.visible - 1)
        val listTop = p.listTop(topPad)
        assertThat(listTop + p.visible * rowPx).isEqualTo(1000f)
        assertThat(p.top).isAtMost(1000f - p.visible * rowPx - topPad)
    }

    /** A current size near the start cannot be under a finger at the bottom: the popup is clamped, the anchor is above. */
    @Test fun anchorNearTheStartIsClampedAboveTheFinger() {
        val p = GlideLayout.place(
            count = 30, anchor = 1, rowPx = rowPx, topPad = topPad, bottomPad = bottomPad,
            fingerY = 1000f, minTop = 0f, maxBottom = 1000f,
        )
        assertThat(p.scroll).isEqualTo(0)
        assertThat(p.top).isEqualTo(1000f - p.visible * rowPx - topPad)
        val centre = p.listTop(topPad) + (1 + 0.5f) * rowPx
        assertThat(centre).isLessThan(1000f)
    }

    @Test fun visibleRowsComeFromTheSpaceAndNeverExceedTheList() {
        val many = GlideLayout.place(
            count = 50, anchor = 10, rowPx = rowPx, topPad = topPad, bottomPad = bottomPad,
            fingerY = 500f, minTop = 0f, maxBottom = 1000f,
        )
        assertThat(many.visible).isEqualTo(9)
        val few = GlideLayout.place(
            count = 3, anchor = 1, rowPx = rowPx, topPad = topPad, bottomPad = bottomPad,
            fingerY = 500f, minTop = 0f, maxBottom = 1000f,
        )
        assertThat(few.visible).isEqualTo(3)
        val tight = GlideLayout.place(
            count = 50, anchor = 1, rowPx = rowPx, topPad = topPad, bottomPad = bottomPad,
            fingerY = 150f, minTop = 0f, maxBottom = 150f,
        )
        assertThat(tight.visible).isEqualTo(1)
    }

    @Test fun scrollAndFingerRowStayInsideTheList() {
        for (count in listOf(1, 4, 30)) {
            for (anchor in 0 until count) {
                for (fingerY in listOf(0f, 200f, 700f, 1000f)) {
                    val p = GlideLayout.place(
                        count = count, anchor = anchor, rowPx = rowPx, topPad = topPad, bottomPad = bottomPad,
                        fingerY = fingerY, minTop = 0f, maxBottom = 1000f,
                    )
                    assertThat(p.visible).isInRange(1, count)
                    assertThat(p.scroll).isInRange(0, count - p.visible)
                    // The anchor stays inside the viewport.
                    assertThat(anchor - p.scroll).isInRange(0, p.visible - 1)
                    assertThat(p.top).isAtLeast(0f)
                    assertThat(p.top).isAtMost(1000f - p.visible * rowPx - topPad)
                }
            }
        }
    }

    /** The top margin (status bar + 8 dp) is a hard limit: the popup never starts above it. */
    @Test fun thePopupNeverStartsAboveTheTopLimit() {
        val p = GlideLayout.place(
            count = 30, anchor = 0, rowPx = rowPx, topPad = topPad, bottomPad = bottomPad,
            fingerY = 120f, minTop = 100f, maxBottom = 1000f,
        )
        assertThat(p.top).isAtLeast(100f)
        assertThat(p.scroll).isEqualTo(0)
    }
}
