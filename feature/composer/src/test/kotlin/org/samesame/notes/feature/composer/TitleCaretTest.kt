package org.samesame.notes.feature.composer

import org.junit.Test
import com.google.common.truth.Truth.assertThat

class TitleCaretTest {
    private val charAt = { x: Float -> (x / 10f).toInt() } // 10 px per character

    @Test fun tapInsideTextPlacesCaretAtCharacter() = assertThat(titleCaretForTap(35f, 80f, 8, charAt)).isEqualTo(3)
    @Test fun tapAtEndGoesToEnd() = assertThat(titleCaretForTap(80f, 80f, 8, charAt)).isEqualTo(8)
    @Test fun tapInTrailingRoomGoesToEnd() = assertThat(titleCaretForTap(100f, 80f, 8, charAt)).isEqualTo(8)
    @Test fun emptyTitleCaretIsZero() = assertThat(titleCaretForTap(10f, 0f, 0, charAt)).isEqualTo(0)
    @Test fun offsetIsClamped() = assertThat(titleCaretForTap(79f, 80f, 8) { 99 }).isEqualTo(8)
}
