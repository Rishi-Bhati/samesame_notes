package org.samesame.notes.feature.widgets

import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WidgetStyleTest {
    @Test fun `match dark mode follows the system`() {
        val s = WidgetStyle(WidgetBackground.MatchDark)
        assertThat(s.isDark(systemNight = true)).isTrue()
        assertThat(s.isDark(systemNight = false)).isFalse()
    }

    @Test fun `white and black ignore the system`() {
        assertThat(WidgetStyle(WidgetBackground.White).isDark(true)).isFalse()
        assertThat(WidgetStyle(WidgetBackground.Black).isDark(false)).isTrue()
    }

    @Test fun `opacity is clamped and applied to the surface only`() {
        assertThat(WidgetStyle.clampOpacity(150)).isEqualTo(100)
        assertThat(WidgetStyle.clampOpacity(-5)).isEqualTo(0)
        val roles = WidgetColorRules.roles(WidgetStyle(WidgetBackground.White, opacity = 50))
        assertThat(roles.surface.day.alpha).isWithin(0.01f).of(0.5f)
        assertThat(roles.text.day.alpha).isEqualTo(1f)
    }

    @Test fun `fixed backgrounds use the same colours in day and night`() {
        val white = WidgetColorRules.roles(WidgetStyle(WidgetBackground.White))
        assertThat(white.surface.day).isEqualTo(white.surface.night)
        assertThat(white.text.day).isEqualTo(white.text.night)
        val black = WidgetColorRules.roles(WidgetStyle(WidgetBackground.Black))
        assertThat(black.surface.night).isEqualTo(Color.Black)
        assertThat(black.text.day).isNotEqualTo(white.text.day)
    }

    @Test fun `match dark mode swaps surface and text between day and night`() {
        val r = WidgetColorRules.roles(WidgetStyle(WidgetBackground.MatchDark))
        assertThat(r.surface.day).isNotEqualTo(r.surface.night)
        assertThat(r.text.day).isNotEqualTo(r.text.night)
    }

    @Test fun `creator keys are keypad pen brush image voice`() {
        assertThat(StartAction.entries.map { it.key }).containsExactly("keypad", "pen", "brush", "image", "voice").inOrder()
        assertThat(StartAction.fromKey("camera")).isNull()
    }
}
