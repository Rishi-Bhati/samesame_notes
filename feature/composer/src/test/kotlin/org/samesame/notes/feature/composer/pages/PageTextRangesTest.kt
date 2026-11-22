package org.samesame.notes.feature.composer.pages

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PageTextRangesTest {
    private val text = "one\ntwo\nthree" // page 1 = 0..3, page 2 = 4..7, page 3 = 8..12

    @Test fun middlePageKeepsItsBoundaries() {
        assertThat(PageTextRanges.normalise(listOf(4..7), text)).containsExactly(4..7)
    }

    @Test fun lastPageAlsoTakesTheLineBreakBeforeIt() {
        assertThat(PageTextRanges.normalise(listOf(8..12), text)).containsExactly(7..12)
    }

    @Test fun neighbouringPagesMergeAndComeLastFirst() {
        assertThat(PageTextRanges.normalise(listOf(0..3, 8..12), text)).containsExactly(7..12, 0..3).inOrder()
        assertThat(PageTextRanges.normalise(listOf(8..12, 4..7), text)).containsExactly(3..12)
    }

    @Test fun rangesAreClampedAndEmptyOnesDropped() {
        assertThat(PageTextRanges.normalise(listOf(10..40), text)).containsExactly(10..12)
        assertThat(PageTextRanges.normalise(listOf(20..30), text)).isEmpty()
        assertThat(PageTextRanges.normalise(emptyList(), text)).isEmpty()
    }
}
