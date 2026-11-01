package org.samesame.notes.feature.composer.toolbar

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TextToolbarHighlightTest {
    @Test
    fun textOptionsStaysPlainWhileItsPanelIsOpen() {
        val model = TextToolbarModel(openPanel = TextToolbarButton.TextOptions)
        assertThat(model.isHighlighted(TextToolbarButton.TextOptions)).isFalse()
    }

    @Test
    fun otherButtonsHighlightWhileTheirPanelIsOpen() {
        val colour = TextToolbarModel(openPanel = TextToolbarButton.FontColor)
        assertThat(colour.isHighlighted(TextToolbarButton.FontColor)).isTrue()
        assertThat(colour.isHighlighted(TextToolbarButton.Highlight)).isFalse()
        val size = TextToolbarModel(openPanel = TextToolbarButton.FontSize)
        assertThat(size.isHighlighted(TextToolbarButton.FontSize)).isTrue()
    }

    @Test
    fun nothingHighlightedWithoutAnOpenPanel() {
        for (b in TextToolbarButton.entries) assertThat(TextToolbarModel().isHighlighted(b)).isFalse()
    }
}
