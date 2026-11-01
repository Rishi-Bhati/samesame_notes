package org.samesame.notes.feature.composer.easypad

import androidx.compose.ui.geometry.Offset
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EasyPadMathTest {
    private val slot = PadSlot(pageIndex = 0, x = 100f, lineTop = 200f, lineHeight = 70f, contentLeft = 80f, contentRight = 1000f)

    @Test
    fun scaleMakesTheBandFillTheArea() {
        val s = EasyPadMath.scale(378f, 70f)
        assertThat(s * 70f * EasyPadMath.BAND).isWithin(0.01f).of(378f)
        assertThat(s).isWithin(0.1f).of(3.857f)
    }

    @Test
    fun baselineIsAtEightTenthsOfTheLine() {
        val area = 378f
        val s = EasyPadMath.scale(area, slot.lineHeight)
        val base = EasyPadMath.toPage(0f, EasyPadMath.baselineY(area), 0f, slot, s)
        assertThat(base.y).isWithin(0.01f).of(slot.lineTop + 0.8f * slot.lineHeight)
    }

    @Test
    fun groupLeftEdgeLandsOnTheSlot() {
        val s = 4f
        assertThat(EasyPadMath.toPage(250f, 56f, 250f, slot, s).x).isEqualTo(slot.x)
        assertThat(EasyPadMath.toPage(290f, 56f, 250f, slot, s)).isEqualTo(Offset(110f, 200f - 14f + 14f))
    }

    @Test
    fun advanceAddsWidthAndGap() {
        assertThat(EasyPadMath.advance(slot, 60f)).isWithin(0.01f).of(100f + 60f + 0.25f * 70f)
        assertThat(EasyPadMath.spaceWidth(slot)).isWithin(0.01f).of(21f)
    }

    @Test
    fun enterGoesToTheEndOfTheCaretsParagraph() {
        val text = "Line 1\nLine 2"
        // Caret at the start of "Line 1" (where the pad's handwriting sat): the newline goes after it, not before.
        assertThat(EasyPadMath.enterOffset(text, 0)).isEqualTo(6)
        assertThat(EasyPadMath.enterOffset(text, 3)).isEqualTo(6)
        assertThat(EasyPadMath.enterOffset(text, 6)).isEqualTo(6) // caret on the newline itself
        assertThat(EasyPadMath.enterOffset(text, 7)).isEqualTo(text.length) // last paragraph
        assertThat(EasyPadMath.enterOffset("", 0)).isEqualTo(0)
        assertThat(EasyPadMath.enterOffset("abc", 99)).isEqualTo(3)
    }

    @Test
    fun wrapsWhenLessThanTwoLinesRemain() {
        assertThat(EasyPadMath.needsWrap(850f, slot)).isFalse()
        assertThat(EasyPadMath.needsWrap(861f, slot)).isTrue()
    }
}
