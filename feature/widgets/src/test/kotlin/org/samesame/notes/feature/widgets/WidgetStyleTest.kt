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

    @Test fun `a solid widget ignores the wallpaper`() {
        assertThat(WidgetStyle(WidgetBackground.White, 100).isDark(false, wallpaperDarkText = false)).isFalse()
        assertThat(WidgetStyle(WidgetBackground.Black, 50).isDark(false, wallpaperDarkText = true)).isTrue()
    }

    @Test fun `a see-through widget takes its content colour from the wallpaper`() {
        // Mostly transparent white widget on a dark wallpaper: light text.
        assertThat(WidgetStyle(WidgetBackground.White, 40).isDark(false, wallpaperDarkText = false)).isTrue()
        // Mostly transparent black widget on a light wallpaper: dark text.
        assertThat(WidgetStyle(WidgetBackground.Black, 0).isDark(true, wallpaperDarkText = true)).isFalse()
        // Unknown wallpaper: the background decides.
        assertThat(WidgetStyle(WidgetBackground.Black, 0).isDark(false, wallpaperDarkText = null)).isTrue()
    }

    @Test fun `opacity is clamped, stepped and applied to the surface only`() {
        assertThat(WidgetStyle.clampOpacity(150)).isEqualTo(100)
        assertThat(WidgetStyle.clampOpacity(-5)).isEqualTo(0)
        assertThat(WidgetStyle.stepOpacity(44f)).isEqualTo(40)
        assertThat(WidgetStyle.stepOpacity(46f)).isEqualTo(50)
        assertThat(WidgetStyle.stepOpacity(130f)).isEqualTo(100)
        val roles = WidgetTheme.roles(WidgetStyle(WidgetBackground.White, opacity = 50))
        assertThat(roles.surface.day.alpha).isWithin(0.01f).of(0.5f)
        assertThat(roles.text.day.alpha).isEqualTo(1f)
    }

    @Test fun `tiles stay readable on a see-through widget`() {
        assertThat(WidgetTheme.tileAlpha(100)).isWithin(0.001f).of(1f)
        assertThat(WidgetTheme.tileAlpha(0)).isWithin(0.001f).of(WidgetTheme.MIN_TILE_ALPHA)
        val roles = WidgetTheme.roles(WidgetStyle(WidgetBackground.White, opacity = 0))
        assertThat(roles.tile.day.alpha).isAtLeast(WidgetTheme.MIN_TILE_ALPHA - 0.001f)
    }

    @Test fun `fixed backgrounds use the same colours in day and night`() {
        val white = WidgetTheme.roles(WidgetStyle(WidgetBackground.White))
        assertThat(white.surface.day).isEqualTo(white.surface.night)
        assertThat(white.text.day).isEqualTo(white.text.night)
        val black = WidgetTheme.roles(WidgetStyle(WidgetBackground.Black))
        assertThat(black.surface.night).isEqualTo(Color.Black)
        assertThat(black.text.day).isNotEqualTo(white.text.day)
    }

    @Test fun `match dark mode swaps surface and text between day and night`() {
        val r = WidgetTheme.roles(WidgetStyle(WidgetBackground.MatchDark))
        assertThat(r.surface.day).isNotEqualTo(r.surface.night)
        assertThat(r.text.day).isNotEqualTo(r.text.night)
        assertThat(r.accent.day).isEqualTo(WidgetTokens.accentLight)
        assertThat(r.accent.night).isEqualTo(WidgetTokens.accentDark)
    }

    @Test fun `start action keys are stable`() {
        assertThat(StartAction.entries.map { it.key })
            .containsExactly("keypad", "pen", "brush", "image", "voice", "camera", "markdown").inOrder()
        assertThat(StartAction.fromKey("scan")).isNull()
    }
}
