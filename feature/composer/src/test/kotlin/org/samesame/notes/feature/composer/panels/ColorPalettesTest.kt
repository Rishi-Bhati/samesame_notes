package org.samesame.notes.feature.composer.panels

import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ColorPalettesTest {
    @Test
    fun everySetHasEightColoursInBothThemes() {
        for (id in 1..ColorPalettes.SET_COUNT) {
            assertThat(ColorPalettes.colors(id, dark = false)).hasSize(8)
            assertThat(ColorPalettes.colors(id, dark = true)).hasSize(8)
        }
    }

    @Test
    fun darkFirstSetMatchesMeasuredReference() {
        // #29 / #39-#42 strip measured in dark mode.
        assertThat(ColorPalettes.colors(1, dark = true)).containsExactly(
            Color(0xFFC90000), Color(0xFFFFCB30), Color(0xFFF7EB00), Color(0xFF2FEBD2),
            Color(0xFF3396FF), Color(0xFF1447FF), Color(0xFF9933FF), Color(0xFFDADADA),
        ).inOrder()
        assertThat(ColorPalettes.colors(1, dark = false)[0]).isEqualTo(Color(0xFFFF3636))
    }

    @Test
    fun swatchOrderCoversAllSetsOnce() {
        assertThat(ColorPalettes.SWATCH_ORDER.sorted()).containsExactlyElementsIn(1..21).inOrder()
        assertThat(ColorPalettes.DEFAULT_SELECTED).containsExactly(1, 4, 21, 5).inOrder()
    }

    @Test
    fun selectionNormalisesAndToggles() {
        assertThat(ColorPalettes.normalize(listOf(2, 2, 40, 3, 4, 5, 6))).containsExactly(2, 3, 4, 5).inOrder()
        assertThat(ColorPalettes.normalize(emptyList())).isEqualTo(ColorPalettes.DEFAULT_SELECTED)
        val four = listOf(1, 4, 21, 5)
        assertThat(togglePaletteSet(four, 9)).isEqualTo(four) // full: ignored
        assertThat(togglePaletteSet(four, 4)).containsExactly(1, 21, 5).inOrder()
        assertThat(togglePaletteSet(listOf(1), 9)).containsExactly(1, 9).inOrder()
        assertThat(togglePaletteSet(listOf(1), 1)).containsExactly(1) // never empty
    }
}
