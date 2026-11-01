package org.samesame.notes.feature.composer.panels

import androidx.compose.ui.geometry.Offset
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.samesame.notes.feature.composer.toolbar.ColorTarget

class EyedropperOverlayTest {
    @Test
    fun widgetStaysInsideTheArea() {
        assertThat(clampSpuitOffset(-5f, -9f, 1000f, 2000f, 300f, 100f)).isEqualTo(Offset(0f, 0f))
        assertThat(clampSpuitOffset(900f, 1990f, 1000f, 2000f, 300f, 100f)).isEqualTo(Offset(700f, 1900f))
        assertThat(clampSpuitOffset(10f, 20f, 1000f, 2000f, 300f, 100f)).isEqualTo(Offset(10f, 20f))
        assertThat(clampSpuitOffset(10f, 20f, 100f, 50f, 300f, 100f)).isEqualTo(Offset(0f, 0f)) // area smaller than the widget
    }

    @Test
    fun penAndHighlighterTakeSamplesLive() {
        assertThat(spuitLiveTarget(ColorTarget.Pen)).isTrue()
        assertThat(spuitLiveTarget(ColorTarget.Highlighter)).isTrue()
        assertThat(spuitLiveTarget(ColorTarget.FontColor)).isFalse()
        assertThat(spuitLiveTarget(ColorTarget.Highlight)).isFalse()
    }

    @Test
    fun changedPenColourIsAppliedAndRecorded() {
        val red = 0xFFE01E1EL
        val blue = 0xFF002CCCL
        assertThat(spuitCommit(ColorTarget.Pen, red, blue)).isEqualTo(SpuitCommit(setTarget = true, addRecent = true))
    }

    @Test
    fun penSampledBackToStartStillAppliesButIsNotRecorded() {
        // Samples move the live pen away from the start colour; leaving on the start colour must restore it.
        val red = 0xFFE01E1EL
        assertThat(spuitCommit(ColorTarget.Pen, red, red)).isEqualTo(SpuitCommit(setTarget = true, addRecent = false))
    }

    @Test
    fun textColourUnchangedDoesNothing() {
        val grey = 0xFFDADADAL
        assertThat(spuitCommit(ColorTarget.FontColor, grey, grey)).isEqualTo(SpuitCommit(setTarget = false, addRecent = false))
    }

    @Test
    fun textColourChangedIsAppliedOnLeaving() {
        assertThat(spuitCommit(ColorTarget.FontColor, 0xFFDADADAL, 0xFF002CCCL)).isEqualTo(SpuitCommit(setTarget = true, addRecent = true))
    }
}
