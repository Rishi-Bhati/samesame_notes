package org.samesame.notes.feature.composer.panels

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.random.Random

/** E3 (rel 0.1.2 owner feedback): the glide mapping is positional and relative, with no time-based scrolling. */
class GlideScrollPadTest {
    // Pixel 6 Pro: 28 dp = 73.5 px. Popup: 3 headings + Body + 26 sizes = 30 rows; anchor on "17" (flat row 13).
    private val step = 73.5f
    private val sizes = TextSizeCatalog.SIZES
    private val count = FontSizeRows.count(sizes)
    private val anchor = FontSizeRows.anchorRow(null, 17, sizes)
    private val anchorY = 1650f

    private fun index(y: Float) = GlideScrollPad.glideIndex(anchor, anchorY, y, step, count)

    @Test fun theGlideStartsOnTheAppliedRow() {
        assertThat(anchor).isEqualTo(FontSizeRows.SIZE_START + sizes.indexOf(17))
        assertThat(index(anchorY)).isEqualTo(anchor)
        // Less than half a step either way: still on the applied row.
        assertThat(index(anchorY - step * 0.49f)).isEqualTo(anchor)
        assertThat(index(anchorY + step * 0.49f)).isEqualTo(anchor)
    }

    @Test fun oneRowPerStepFingerUpIsUpTheList() {
        assertThat(index(anchorY - step)).isEqualTo(anchor - 1)
        assertThat(index(anchorY - 3 * step)).isEqualTo(anchor - 3)
        assertThat(index(anchorY + step)).isEqualTo(anchor + 1)
        assertThat(FontSizeRows.pick(index(anchorY - step), sizes)).isEqualTo(FontSizePick.Size(16))
        assertThat(FontSizeRows.pick(index(anchorY + 2 * step), sizes)).isEqualTo(FontSizePick.Size(20))
    }

    /**
     * The owner's complaint: the result depended on timing. The highlight is a function of the end position only, so
     * any path (fast with 2 events, slow with hundreds, overshooting and coming back) ends on the same row.
     */
    @Test fun sameDisplacementGivesTheSameRowWhateverThePath() {
        val end = anchorY - 4.2f * step
        val direct = index(end)
        val rnd = Random(7)
        repeat(50) {
            val events = 1 + rnd.nextInt(300)
            var last = -1
            for (i in 1..events) {
                val wobble = if (i < events) (rnd.nextFloat() - 0.5f) * 6 * step else 0f
                last = index(anchorY + (end - anchorY) * i / events + wobble)
            }
            assertThat(last).isEqualTo(direct)
        }
        assertThat(direct).isEqualTo(anchor - 4)
    }

    @Test fun theMappingIsIndependentOfWhereTheGlideStarted() {
        // A popup or button elsewhere on screen: the same displacement from the press gives the same row.
        for (start in listOf(300f, 1100f, 2100f)) {
            assertThat(GlideScrollPad.glideIndex(anchor, start, start - 2 * step, step, count)).isEqualTo(anchor - 2)
        }
    }

    @Test fun theHighlightIsClampedToTheList() {
        assertThat(index(anchorY - 100 * step)).isEqualTo(0)
        assertThat(index(anchorY + 100 * step)).isEqualTo(count - 1)
        assertThat(GlideScrollPad.glideIndex(-1, anchorY, anchorY, step, count)).isEqualTo(-1)
        assertThat(GlideScrollPad.glideIndex(0, anchorY, anchorY, step, 0)).isEqualTo(-1)
    }

    @Test fun everyRowIsReachableFromEveryAnchorOneStepAtATime() {
        for (a in 0 until count) {
            val seen = (-count..count).map { GlideScrollPad.glideIndex(a, anchorY, anchorY + it * step, step, count) }.toSet()
            assertThat(seen).containsExactlyElementsIn((0 until count).toSet())
            // Neighbouring steps never skip a row.
            for (s in -count until count) {
                val d = GlideScrollPad.glideIndex(a, anchorY, anchorY + (s + 1) * step, step, count) -
                    GlideScrollPad.glideIndex(a, anchorY, anchorY + s * step, step, count)
                assertThat(d).isAtMost(1)
                assertThat(d).isAtLeast(0)
            }
        }
        // The whole list fits a phone's height at the step size: 29 steps of 28 dp = 812 dp < 891 dp (Pixel 6 Pro).
        assertThat((count - 1) * GlideScrollPad.STEP_DP).isLessThan(891f)
    }

    @Test fun scrollTargetCentresAndKeepsTheHighlightVisible() {
        val row = 115.5f // 44 dp
        val viewport = 585f
        val maxScroll = sizes.size * row - viewport
        for (i in sizes.indices) {
            val scroll = GlideScrollPad.centredScroll(i, row, viewport, maxScroll)
            assertThat(scroll).isAtLeast(0f)
            assertThat(scroll).isAtMost(maxScroll)
            val top = i * row - scroll
            assertThat(top).isAtLeast(0f)
            assertThat(top + row).isAtMost(viewport + 0.01f)
        }
        // Mid list: exactly centred.
        assertThat(GlideScrollPad.centredScroll(10, row, viewport, maxScroll)).isEqualTo(10 * row + row / 2 - viewport / 2)
        // Ends clamp.
        assertThat(GlideScrollPad.centredScroll(0, row, viewport, maxScroll)).isEqualTo(0f)
        assertThat(GlideScrollPad.centredScroll(sizes.size - 1, row, viewport, maxScroll)).isEqualTo(maxScroll)
    }
}
