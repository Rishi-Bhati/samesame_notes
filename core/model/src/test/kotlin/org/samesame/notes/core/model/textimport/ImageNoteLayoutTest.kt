package org.samesame.notes.core.model.textimport

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ImageNoteLayoutTest {
    @Test fun singleImageIsCentredAtSeventyPercent() {
        val p = ImageNoteLayout.layout(listOf(1000 to 500), 1527f).single()
        assertThat(p.page).isEqualTo(0)
        assertThat(p.rect.width).isWithin(0.01f).of(756f)
        assertThat(p.rect.height).isWithin(0.01f).of(378f)
        assertThat(p.rect.left).isWithin(0.01f).of(162f)
    }

    @Test fun severalStackOnOnePageThenOverflowToNext() {
        val ps = ImageNoteLayout.layout(List(4) { 1000 to 500 }, 1527f)
        assertThat(ps.map { it.page }).containsExactly(0, 0, 0, 1).inOrder()
        assertThat(ps[1].rect.top).isGreaterThan(ps[0].rect.bottom)
        assertThat(ps[3].rect.top).isEqualTo(ImageNoteLayout.MARGIN_PU)
    }

    @Test fun tallImageIsScaledToOnePage() {
        val p = ImageNoteLayout.layout(listOf(100 to 4000), 1527f).single()
        assertThat(p.rect.height).isAtMost(1527f - 2 * ImageNoteLayout.MARGIN_PU + 0.01f)
    }

    @Test fun infinitePageNeverBreaks() {
        val ps = ImageNoteLayout.layout(List(10) { 1000 to 1000 }, null)
        assertThat(ps.all { it.page == 0 }).isTrue()
    }
}
